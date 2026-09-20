package com.saasinvestigator.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code PUT /api/users/{id}/password}.
 *
 * <p>There is no {@code currentPassword} field, because an admin resetting someone else's password does not
 * know it - that is the whole reason this endpoint exists rather than a "change my password" one.
 *
 * @param newPassword the replacement password, hashed on arrival and never stored or logged in plaintext
 */
public record ResetPasswordRequest(
        @NotBlank(message = "newPassword is required")
        @Size(min = UserService.MIN_PASSWORD_LENGTH,
                message = "newPassword must be at least " + UserService.MIN_PASSWORD_LENGTH + " characters")
        String newPassword) {
}
