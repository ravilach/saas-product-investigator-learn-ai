package com.saasinvestigator.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.saasinvestigator.user.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.DispatcherType;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Tests the filter that turns a bearer token into a security context.
 *
 * <p>The async-dispatch test is the one with a history. When an {@code SseEmitter} completes, the container
 * dispatches the request back through the filter chain to finish the response, and
 * {@link org.springframework.web.filter.OncePerRequestFilter} skips that dispatch by default - while Spring
 * Security's authorization filter does not. So a run's own event stream was denied as anonymous at the very
 * moment it finished, and the container then tried to write an error page onto an already-committed response.
 * Every event still arrived, so the UI looked correct; the stream simply never terminated, and {@code curl}
 * exited 18 on a run that had succeeded.
 *
 * <p>That is why the assertion here is about the dispatch type rather than about a status code: the visible
 * symptom was in the framing of a response whose body was complete.
 */
class JwtAuthenticationFilterTest {

    private static final String TOKEN = "a.valid.token";

    private final JwtService jwtService = mock(JwtService.class);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static Claims claims(Role role) {
        return Jwts.claims()
                .subject("dana")
                .add(JwtService.CLAIM_ROLE, role.name())
                .add(JwtService.CLAIM_USER_ID, "user-1")
                .add(JwtService.CLAIM_DISPLAY_NAME, "Dana Example")
                .build();
    }

    private static MockHttpServletRequest request(DispatcherType type, String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/saas-products/p1/runs/r1/events");
        request.setDispatcherType(type);
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }

    private Authentication runFilter(MockHttpServletRequest request) throws Exception {
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        return SecurityContextHolder.getContext().getAuthentication();
    }

    // ---------------------------------------------------------------------
    // The ordinary path
    // ---------------------------------------------------------------------

    @Test
    void aValidTokenPopulatesThePrincipalAndTheAuthority() throws Exception {
        when(jwtService.verify(TOKEN)).thenReturn(Optional.of(claims(Role.ADMIN)));

        Authentication authentication = runFilter(request(DispatcherType.REQUEST, "Bearer " + TOKEN));

        assertThat(authentication).isNotNull();
        assertThat(authentication.getPrincipal())
                .isEqualTo(new AuthenticatedUser("user-1", "dana", "Dana Example", Role.ADMIN));
        assertThat(authentication.getAuthorities()).extracting(Object::toString)
                .containsExactly(Role.ADMIN.authority());
    }

    @Test
    void anInvalidTokenLeavesTheContextEmptyRatherThanRejectingTheRequestHere() throws Exception {
        when(jwtService.verify(TOKEN)).thenReturn(Optional.empty());

        // The chain's own rules decide: that is what lets /api/auth/login and /actuator through without this
        // filter knowing anything about them, and keeps every 401 in one shape.
        assertThat(runFilter(request(DispatcherType.REQUEST, "Bearer " + TOKEN))).isNull();
    }

    @Test
    void aRequestWithNoBearerHeaderIsNotEvenOfferedToTheVerifier() throws Exception {
        assertThat(runFilter(request(DispatcherType.REQUEST, null))).isNull();
        assertThat(runFilter(request(DispatcherType.REQUEST, "Basic dXNlcjpwdw=="))).isNull();
        assertThat(runFilter(request(DispatcherType.REQUEST, "Bearer    "))).isNull();
        verifyNoInteractions(jwtService);
    }

    @Test
    void aTokenNamingARoleThisBuildDoesNotKnowIsIgnoredRatherThanGuessed() throws Exception {
        Claims unknownRole = Jwts.claims().subject("dana")
                .add(JwtService.CLAIM_ROLE, "SUPER_ADMIN")
                .add(JwtService.CLAIM_USER_ID, "user-1")
                .build();
        when(jwtService.verify(anyString())).thenReturn(Optional.of(unknownRole));

        assertThat(runFilter(request(DispatcherType.REQUEST, "Bearer " + TOKEN))).isNull();
    }

    // ---------------------------------------------------------------------
    // The async dispatch, which the streaming endpoints depend on
    // ---------------------------------------------------------------------

    @Test
    void theFilterAlsoRunsOnTheAsyncDispatchSoAFinishedStreamIsNotDeniedAsAnonymous() throws Exception {
        when(jwtService.verify(TOKEN)).thenReturn(Optional.of(claims(Role.READ_ONLY)));

        Authentication authentication = runFilter(request(DispatcherType.ASYNC, "Bearer " + TOKEN));

        // Without this the context is empty on the dispatch that completes an SseEmitter, the authorization
        // filter denies the request, and the response is truncated instead of terminated.
        assertThat(authentication).isNotNull();
        assertThat(authentication.getPrincipal())
                .isEqualTo(new AuthenticatedUser("user-1", "dana", "Dana Example", Role.READ_ONLY));
    }

    @Test
    void theAsyncDispatchIsNotOptedOutOf() {
        // Asserted directly as well as through behaviour, because this single boolean is the whole fix and it
        // is the kind of override that gets "tidied away" by someone who has not seen what it prevents.
        assertThat(filter.shouldNotFilterAsyncDispatch()).isFalse();
    }
}
