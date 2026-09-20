package com.saasinvestigator.security;

import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Reads the authenticated caller out of the security context.
 *
 * <p>A static helper rather than a bean because it holds no state and is needed by services, not just
 * controllers - audit logging in particular runs deep in service code that has no business taking an
 * {@code AuthenticatedUser} parameter through five layers just to record who acted.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /**
     * @return the authenticated caller, or empty on an unauthenticated request
     */
    public static Optional<AuthenticatedUser> find() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            return Optional.empty();
        }
        return Optional.of(user);
    }

    /**
     * @return the authenticated caller
     * @throws IllegalStateException if the request is unauthenticated - which can only happen if an
     *     endpoint was left out of the security rules, so it is a bug rather than a client error
     */
    public static AuthenticatedUser require() {
        return find().orElseThrow(() -> new IllegalStateException(
                "No authenticated user in the security context. An endpoint that needs one is reachable "
                        + "without authentication - check SecurityConfig."));
    }
}
