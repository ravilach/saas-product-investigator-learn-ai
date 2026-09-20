package com.saasinvestigator.testsupport;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import com.saasinvestigator.security.AuthenticatedUser;
import com.saasinvestigator.user.Role;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Authenticates MockMvc requests as this application's own {@link AuthenticatedUser} principal.
 *
 * <p>Spring Security's {@code @WithMockUser} is not usable here. It supplies a {@code UserDetails} principal,
 * whereas {@code CurrentUser} - which controllers read to answer questions like "is this the caller's own account?"
 * - expects an {@link AuthenticatedUser}. Authenticating explicitly gives both the right authority and the right
 * principal type; using {@code @WithMockUser} instead produces a confusing {@code ClassCastException} deep inside a
 * handler rather than the 403 or 400 the test is about.
 */
public final class TestPrincipals {

    private TestPrincipals() {
    }

    /**
     * @return an ADMIN caller with id {@code id-admin}
     */
    public static RequestPostProcessor admin() {
        return principal("admin", Role.ADMIN, "id-admin");
    }

    /**
     * @return a READ_ONLY caller with id {@code id-reader}
     */
    public static RequestPostProcessor readOnly() {
        return principal("reader", Role.READ_ONLY, "id-reader");
    }

    /**
     * @param username the username to authenticate as
     * @param role the caller's role, which becomes their single granted authority
     * @param userId the caller's user id, as {@code CurrentUser} will see it
     * @return a post-processor that authenticates the request as that user
     */
    public static RequestPostProcessor principal(String username, Role role, String userId) {
        AuthenticatedUser user = new AuthenticatedUser(userId, username, username, role);
        return authentication(new UsernamePasswordAuthenticationToken(
                user, null, List.of(new SimpleGrantedAuthority(role.authority()))));
    }
}
