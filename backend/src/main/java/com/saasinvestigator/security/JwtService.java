package com.saasinvestigator.security;

import com.saasinvestigator.user.Role;
import com.saasinvestigator.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies the application's JWTs.
 *
 * <p>Tokens carry only what the API needs to authorise a request - subject (username), {@code role},
 * {@code userId}, and the display name - and deliberately nothing sensitive. A JWT's payload is signed,
 * not encrypted: anyone holding one can read every claim in it, so a claim is public information by
 * definition.
 */
@Service
public class JwtService {

    /** Claim holding the user's {@link Role} name, used to build the Spring Security authority. */
    public static final String CLAIM_ROLE = "role";

    /** Claim holding the Mongo user id, so services can attribute work without another lookup. */
    public static final String CLAIM_USER_ID = "userId";

    /** Claim holding the user's full name, purely so the UI can greet them without a second call. */
    public static final String CLAIM_DISPLAY_NAME = "name";

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final String ISSUER = "saas-product-investigator";

    private final JwtSecretResolver secretResolver;
    private final Duration expiration;

    /**
     * @param secretResolver supplies the current signing key
     * @param expirationHours how long an issued token stays valid
     */
    public JwtService(JwtSecretResolver secretResolver,
                      @Value("${app.jwt.expiration-hours:12}") long expirationHours) {
        this.secretResolver = secretResolver;
        this.expiration = Duration.ofHours(expirationHours);
    }

    /**
     * Issues a token for a freshly authenticated user.
     *
     * @param user the authenticated user
     * @return a signed compact JWT
     */
    public String issue(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(user.getUsername())
                .claim(CLAIM_ROLE, user.getRole().name())
                .claim(CLAIM_USER_ID, user.getId())
                .claim(CLAIM_DISPLAY_NAME, user.displayName())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expiration)))
                .signWith(secretResolver.activeKey())
                .compact();
    }

    /**
     * Verifies a token's signature and expiry and returns its claims.
     *
     * <p>Every failure mode - bad signature, expired, malformed, wrong issuer - collapses to an empty
     * result on purpose. The filter's only decision is "authenticate or don't", and distinguishing the
     * reasons in the response would tell an attacker which part of a forged token to fix next. The
     * reason is logged at DEBUG for whoever is debugging a real client.
     *
     * @param token the compact JWT from the {@code Authorization} header
     * @return the verified claims, or empty if the token is not valid right now
     */
    public Optional<Claims> verify(String token) {
        try {
            return Optional.of(Jwts.parser()
                    .verifyWith(secretResolver.activeKey())
                    .requireIssuer(ISSUER)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload());
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Rejected a JWT: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * @return how long issued tokens remain valid, so the login response can tell the UI when to
     *     re-authenticate instead of the UI having to decode the token
     */
    public Duration expiration() {
        return expiration;
    }
}
