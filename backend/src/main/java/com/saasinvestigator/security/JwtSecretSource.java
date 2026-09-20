package com.saasinvestigator.security;

/**
 * Where the JWT signing secret currently in use came from.
 *
 * <p>Reported by {@code GET /api/admin/jwt-secret} so an admin can tell which channel is winning
 * without the value itself ever being returned. Unlike an API key there is no {@code last4} here: a
 * masked hint of a signing secret helps nobody and only widens what an attacker knows.
 */
public enum JwtSecretSource {

    /** An admin set an explicit override from the Admin Console; stored encrypted in {@code system_config}. */
    ADMIN_OVERRIDE,

    /** Supplied externally as {@code JWT_SECRET} - env var, properties file, K8s Secret, or ECS secret. */
    ENV_VAR,

    /** Nobody supplied one, so the app generated a random 256-bit secret on first boot and persisted it. */
    AUTO_GENERATED
}
