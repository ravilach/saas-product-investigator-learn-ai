package com.saasinvestigator.security;

import com.saasinvestigator.user.Role;

/**
 * The authenticated caller, as reconstructed from a verified JWT's claims.
 *
 * <p>Held as the Spring Security {@code Authentication} principal, so controllers and services can take
 * the acting user's id and username straight from it - which is what every audit-log entry needs - rather
 * than re-reading the {@code users} collection on every request.
 *
 * @param userId the Mongo id of the user
 * @param username the login identifier, also the token's subject
 * @param displayName the user's full name, for display only
 * @param role the user's role
 */
public record AuthenticatedUser(String userId, String username, String displayName, Role role) {

    /**
     * @return {@code true} if this caller is an admin; prefer {@code @PreAuthorize} for access control
     *     and use this only where a response's <em>content</em> differs by role
     */
    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
