package com.saasinvestigator.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /api/users}.
 *
 * <p>The role is supplied by the creating admin rather than chosen by the new user - there is no
 * self-registration in this application, which is why there is no public signup endpoint for this to be
 * shared with.
 *
 * @param firstName given name
 * @param lastName family name
 * @param username the login identifier; letters, digits, dot, dash, and underscore only
 * @param email contact address
 * @param password the initial password, hashed on arrival and never stored or logged in plaintext
 * @param role the role to assign
 */
public record CreateUserRequest(
        @NotBlank(message = "firstName is required") String firstName,
        @NotBlank(message = "lastName is required") String lastName,

        // Constrained rather than free-form because a username containing a space or a slash is
        // needlessly awkward to type at a login prompt and to read in an audit log.
        @NotBlank(message = "username is required")
        @Pattern(regexp = "^[A-Za-z0-9._-]{2,64}$",
                message = "username must be 2-64 characters of letters, digits, '.', '_' or '-'")
        String username,

        @NotBlank(message = "email is required")
        @Email(message = "email must be a valid address")
        String email,

        @NotBlank(message = "password is required")
        @Size(min = UserService.MIN_PASSWORD_LENGTH,
                message = "password must be at least " + UserService.MIN_PASSWORD_LENGTH + " characters")
        String password,

        @NotNull(message = "role is required and must be ADMIN or READ_ONLY") Role role) {
}
