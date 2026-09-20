package com.saasinvestigator.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.saasinvestigator.systemconfig.SystemConfigDocument;
import com.saasinvestigator.systemconfig.SystemConfigService;
import com.saasinvestigator.user.Role;
import com.saasinvestigator.user.User;
import io.jsonwebtoken.Claims;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link JwtService}.
 *
 * <p>The negative cases matter more than the positive one. A token issued under a different secret, or an
 * expired token, must not verify - and it must fail by returning empty rather than by throwing, because the
 * filter treats "no verified claims" as "unauthenticated" and anything that escapes as a 500.
 */
@ExtendWith(MockitoExtension.class)
class JwtServiceTest {

    @Mock
    private SystemConfigService systemConfig;

    @Test
    void issuedTokenCarriesTheClaimsTheFilterNeeds() {
        JwtService jwtService = serviceWithSecret("a-test-signing-secret", 12);
        User user = user();

        Claims claims = jwtService.verify(jwtService.issue(user)).orElseThrow();

        assertThat(claims.getSubject()).isEqualTo("dana");
        assertThat(claims.get(JwtService.CLAIM_ROLE, String.class)).isEqualTo("READ_ONLY");
        assertThat(claims.get(JwtService.CLAIM_USER_ID, String.class)).isEqualTo("user-1");
        assertThat(claims.get(JwtService.CLAIM_DISPLAY_NAME, String.class)).isEqualTo("Dana Scully");
    }

    @Test
    void doesNotVerifyATokenSignedWithADifferentSecret() {
        String token = serviceWithSecret("the-original-secret", 12).issue(user());

        // This is exactly what happens after an admin rotates the JWT secret: every issued token stops
        // verifying, which is how "sign everyone out" is implemented without server-side sessions.
        assertThat(serviceWithSecret("a-rotated-secret", 12).verify(token)).isEmpty();
    }

    @Test
    void doesNotVerifyAnExpiredToken() {
        // A negative expiry issues a token that expired an hour before it was created. Cleaner than
        // sleeping, and it exercises the same jjwt expiry check a genuinely stale token hits.
        JwtService jwtService = serviceWithSecret("a-test-signing-secret", -1);

        assertThat(jwtService.verify(jwtService.issue(user()))).isEmpty();
    }

    @Test
    void doesNotVerifyGarbage() {
        JwtService jwtService = serviceWithSecret("a-test-signing-secret", 12);

        assertThat(jwtService.verify("not-a-jwt")).isEmpty();
        assertThat(jwtService.verify("")).isEmpty();
        assertThat(jwtService.verify("a.b.c")).isEmpty();
    }

    @Test
    void doesNotVerifyAnUnsignedTokenForgedWithTheAlgNoneTrick() {
        JwtService jwtService = serviceWithSecret("a-test-signing-secret", 12);
        // {"alg":"none"} . {"sub":"admin","role":"ADMIN"} . (empty signature)
        String forged = "eyJhbGciOiJub25lIn0"
                + ".eyJpc3MiOiJzYWFzLXByb2R1Y3QtaW52ZXN0aWdhdG9yIiwic3ViIjoiYWRtaW4iLCJyb2xlIjoiQURNSU4ifQ"
                + ".";

        assertThat(jwtService.verify(forged)).isEmpty();
    }

    private JwtService serviceWithSecret(String secret, long expirationHours) {
        when(systemConfig.getSecret(SystemConfigDocument.KEY_JWT_SIGNING_SECRET_OVERRIDE))
                .thenReturn(Optional.empty());
        return new JwtService(new JwtSecretResolver(systemConfig, secret), expirationHours);
    }

    private static User user() {
        User user = new User("Dana", "Scully", "dana", "dana@example.com", "hash", Role.READ_ONLY);
        user.setId("user-1");
        return user;
    }
}
