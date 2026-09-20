package com.saasinvestigator.security;

import jakarta.validation.constraints.NotBlank;

/**
 * The body of {@code POST /api/auth/login}.
 *
 * <p>Login is by username, not email - see USERS &amp; ROLES in the build prompt.
 *
 * @param username the login identifier
 * @param password the plaintext password, used for one BCrypt comparison and then discarded. It is never
 *     stored, echoed, or logged - not even at DEBUG, and not in an audit-log {@code details} field.
 */
public record LoginRequest(
        @NotBlank(message = "username is required") String username,
        @NotBlank(message = "password is required") String password) {
}
