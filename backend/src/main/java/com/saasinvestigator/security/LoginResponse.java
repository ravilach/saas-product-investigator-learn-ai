package com.saasinvestigator.security;

import com.saasinvestigator.user.UserResponse;
import java.time.Instant;

/**
 * The response to a successful login.
 *
 * <p>The user object is included so the frontend can render the header and decide which routes to show
 * without a second round trip, and without decoding the token itself - a client that parses JWT claims to
 * drive its UI tends to drift from what the server actually enforces.
 *
 * @param token the signed JWT to send as {@code Authorization: Bearer <token>}
 * @param tokenType always {@code Bearer}, stated explicitly so the client does not hardcode the prefix
 * @param expiresAt when the token stops being accepted, so the UI can prompt a re-login before a request
 *     fails rather than after
 * @param user the authenticated user, never including a password hash
 */
public record LoginResponse(String token, String tokenType, Instant expiresAt, UserResponse user) {

    /**
     * @param token the signed JWT
     * @param expiresAt the token's expiry
     * @param user the authenticated user
     * @return a response with {@code tokenType} filled in
     */
    public static LoginResponse of(String token, Instant expiresAt, UserResponse user) {
        return new LoginResponse(token, "Bearer", expiresAt, user);
    }
}
