package com.saasinvestigator.security;

import com.saasinvestigator.user.Role;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Populates the security context from a {@code Authorization: Bearer <jwt>} header.
 *
 * <p>The filter never rejects a request itself. A missing or invalid token simply leaves the context
 * unauthenticated and lets the filter chain's own rules decide - which is what makes {@code /actuator/**}
 * and {@code /api/auth/login} work without special-casing them here, and what makes the 401/403 come from
 * one place with a consistent body shape.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;

    /**
     * @param jwtService verifies presented tokens
     */
    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        bearerToken(request)
                .flatMap(jwtService::verify)
                .ifPresent(this::authenticate);
        filterChain.doFilter(request, response);
    }

    /**
     * Extracts the bearer token, if any.
     *
     * <p>Only the header is read. A {@code ?access_token=} query parameter is deliberately not
     * supported, even though it is the usual workaround for the browser {@code EventSource} API being
     * unable to send headers: URLs end up in access logs, proxy logs, and browser history in a way
     * headers do not. The SSE endpoints are consumed with {@code fetch} plus a streamed response body
     * instead, which carries the header normally - and is required anyway, since {@code /ask} is a POST
     * and {@code EventSource} can only issue GETs.
     */
    private Optional<String> bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String token = header.substring(PREFIX.length()).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }

    private void authenticate(Claims claims) {
        Role role;
        try {
            role = Role.valueOf(claims.get(JwtService.CLAIM_ROLE, String.class));
        } catch (IllegalArgumentException | NullPointerException e) {
            // A validly signed token carrying a role this build no longer knows (e.g. a role was
            // renamed while tokens were in flight). Treat as unauthenticated rather than guessing.
            log.warn("Token for subject {} carries an unrecognised role claim; ignoring the token.",
                    claims.getSubject());
            return;
        }
        AuthenticatedUser principal = new AuthenticatedUser(
                claims.get(JwtService.CLAIM_USER_ID, String.class),
                claims.getSubject(),
                claims.get(JwtService.CLAIM_DISPLAY_NAME, String.class),
                role);
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority(role.authority())));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
