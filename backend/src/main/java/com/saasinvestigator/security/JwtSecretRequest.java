package com.saasinvestigator.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code PUT /api/admin/jwt-secret}.
 *
 * <p>The field is {@code value} rather than {@code secret} to match the build prompt's
 * {@code { value }}, and the minimum length is a real constraint rather than a formality: HMAC-SHA256 keys
 * carry at most as much strength as the material behind them, and this value is what stands between an
 * attacker and a forged token for any user. {@code JwtSecretResolver} SHA-256s whatever arrives to the
 * required 32 bytes, so a short value would be <em>accepted</em> by the crypto and be weak - which is exactly
 * the case worth rejecting at the edge instead.
 *
 * @param value the new signing secret; 32 characters or more
 */
public record JwtSecretRequest(
        @NotBlank(message = "value is required")
        @Size(min = 32, message = "value must be at least 32 characters - this secret is what prevents "
                + "anyone from forging a token for any user")
        String value) {
}
