package com.saasinvestigator.security;

/**
 * What {@code GET /api/admin/jwt-secret} returns.
 *
 * <p><b>There is no {@code last4} here, unlike every API key status in this application.</b> That asymmetry
 * is deliberate. A {@code last4} on an API key answers a real question - "is the key in effect the one I
 * pasted, or a stale one?" - because the operator holds the key and can compare. Nobody holds the JWT signing
 * secret: it is typically auto-generated and never displayed, so four characters of it could not be recognised
 * by anyone legitimate, while still narrowing the search space for anyone else.
 *
 * <p>{@code configured} is always {@code true} in practice, because the resolver generates a secret when no
 * channel supplies one - there is no state in which the app is running without a signing secret. It is
 * reported anyway so the field's absence never has to be interpreted, and so the UI can render one uniform
 * secrets table.
 *
 * @param configured whether a signing secret is in effect
 * @param source which channel supplied it
 */
public record JwtSecretStatus(boolean configured, JwtSecretSource source) {
}
