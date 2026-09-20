package com.saasinvestigator.security;

import com.saasinvestigator.systemconfig.SystemConfigDocument;
import com.saasinvestigator.systemconfig.SystemConfigService;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves the HMAC key used to sign and verify JWTs.
 *
 * <p>Resolution order, highest priority first:
 *
 * <ol>
 *   <li><b>Admin override</b> - {@code system_config} key {@code JWT_SIGNING_SECRET_OVERRIDE},
 *       encrypted at rest. Set and cleared from the Admin Console's Secrets tab.
 *   <li><b>Explicit external config</b> - the {@code JWT_SECRET} env var / property / Kubernetes
 *       Secret / ECS task-definition secret, all of which arrive here as {@code app.jwt.secret}.
 *   <li><b>Auto-generated</b> - a random 256-bit value created on first boot, encrypted, and persisted
 *       as {@code system_config} key {@code JWT_SIGNING_SECRET}. Every later boot and every additional
 *       replica reads the same value back, so restarts don't sign everyone out and horizontal scaling
 *       works.
 * </ol>
 *
 * <p><b>Why the secret is not derived from the admin password.</b> The seeded admin password is the
 * publicly documented value {@code admin}. A secret derived from it would be computable by anyone, who
 * could then forge a valid token for <em>any</em> user - the whole auth system would hinge on a value
 * this project's own docs tell you. It would also couple password resets to session invalidation. Hence
 * the chain above instead.
 *
 * <p><b>Why the resolved key is cached for only {@value #CACHE_TTL_SECONDS} seconds.</b> Changing the
 * override has to invalidate every issued token, and that happens naturally because tokens signed with
 * the old key stop verifying. Locally we invalidate the cache immediately on change, but other replicas
 * have their own caches and no way to be notified; a short TTL bounds how long a replica can keep
 * honouring the old key to a few seconds, without turning every authenticated request into a Mongo read.
 */
@Component
public class JwtSecretResolver {

    /** How long a resolved key is reused before the chain is re-read. */
    static final long CACHE_TTL_SECONDS = 15;

    private static final Logger log = LoggerFactory.getLogger(JwtSecretResolver.class);
    private static final int GENERATED_SECRET_BYTES = 32;

    private final SystemConfigService systemConfig;
    private final String configuredSecret;
    private final SecureRandom secureRandom = new SecureRandom();

    /** The cached resolution, or {@code null} until the first resolve. Replaced atomically, never mutated. */
    private final AtomicReference<CachedSecret> cache = new AtomicReference<>();

    /**
     * @param systemConfig store for the override and the auto-generated secret
     * @param configuredSecret the value of {@code JWT_SECRET}, blank when not supplied
     */
    public JwtSecretResolver(SystemConfigService systemConfig,
                             @Value("${app.jwt.secret:}") String configuredSecret) {
        this.systemConfig = systemConfig;
        this.configuredSecret = configuredSecret == null ? "" : configuredSecret.trim();
    }

    /**
     * Returns the key to sign new tokens with and verify presented ones against.
     *
     * @return an HMAC-SHA256 key derived from whichever secret currently wins the resolution chain
     */
    public SecretKey activeKey() {
        return current().key();
    }

    /**
     * Reports which channel supplied the secret in use, for {@code GET /api/admin/jwt-secret}.
     *
     * @return the winning source
     */
    public JwtSecretSource activeSource() {
        return current().source();
    }

    /**
     * Drops the cached key so the next call re-reads the chain.
     *
     * <p>Called immediately after an admin sets or clears the override, so this instance stops
     * honouring old tokens at once rather than waiting out the TTL.
     */
    public void invalidateCache() {
        cache.set(null);
    }

    /**
     * Stores an admin override and stops honouring tokens signed with the previous secret.
     *
     * <p>Writing to {@code system_config} and invalidating the cache are one method rather than two calls a
     * controller makes in order, because the failure mode of doing them separately is invisible: the write
     * succeeds, the cache is not dropped, and this instance keeps accepting tokens it has just decided are
     * invalid for up to {@value #CACHE_TTL_SECONDS} seconds. Coupling them here means the only way to change
     * the secret is the way that also takes effect.
     *
     * <p><b>This signs everyone out</b>, including the admin who called it. Tokens are stateless and signed;
     * changing the key is what invalidation <em>is</em>, so there is no way to make this selective. The
     * frontend must say so and confirm before calling it.
     *
     * @param secret the new signing secret; must not be blank
     * @throws IllegalArgumentException if the secret is blank, which would otherwise silently fall through to
     *     the next channel in the chain and look like the override had not been stored at all
     */
    public void applyOverride(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("A JWT signing secret override cannot be blank.");
        }
        systemConfig.putSecret(SystemConfigDocument.KEY_JWT_SIGNING_SECRET_OVERRIDE, secret.trim());
        invalidateCache();
        log.warn("The JWT signing secret override was set. Every previously issued token is now invalid and "
                + "every session has been signed out, including the one that made this change.");
    }

    /**
     * Removes the admin override, reverting to {@code JWT_SECRET} or the auto-generated secret.
     *
     * <p>Also signs everyone out, for exactly the same reason: the key in use changes.
     *
     * @return {@code true} if an override existed and was removed
     */
    public boolean clearOverride() {
        boolean existed = systemConfig.exists(SystemConfigDocument.KEY_JWT_SIGNING_SECRET_OVERRIDE);
        systemConfig.delete(SystemConfigDocument.KEY_JWT_SIGNING_SECRET_OVERRIDE);
        invalidateCache();
        if (existed) {
            log.warn("The JWT signing secret override was cleared, reverting to {}. Every previously issued "
                    + "token is now invalid.", configuredSecret.isEmpty() ? "the auto-generated secret"
                            : "the externally configured JWT_SECRET");
        }
        return existed;
    }

    /**
     * @return {@code true} if an admin override is currently stored. Distinct from {@code activeSource() ==
     *     ADMIN_OVERRIDE} only in the pathological case of an override whose ciphertext cannot be decrypted,
     *     where a document exists but is not in effect.
     */
    public boolean hasOverride() {
        return systemConfig.exists(SystemConfigDocument.KEY_JWT_SIGNING_SECRET_OVERRIDE);
    }

    private CachedSecret current() {
        CachedSecret cached = cache.get();
        if (cached != null && !cached.isStale()) {
            return cached;
        }
        CachedSecret resolved = resolve();
        cache.set(resolved);
        return resolved;
    }

    private CachedSecret resolve() {
        String override = systemConfig
                .getSecret(SystemConfigDocument.KEY_JWT_SIGNING_SECRET_OVERRIDE)
                .orElse(null);
        if (override != null && !override.isBlank()) {
            return CachedSecret.of(override, JwtSecretSource.ADMIN_OVERRIDE);
        }
        if (!configuredSecret.isEmpty()) {
            return CachedSecret.of(configuredSecret, JwtSecretSource.ENV_VAR);
        }
        return CachedSecret.of(resolvePersistedOrGenerate(), JwtSecretSource.AUTO_GENERATED);
    }

    private String resolvePersistedOrGenerate() {
        return systemConfig.getSecret(SystemConfigDocument.KEY_JWT_SIGNING_SECRET)
                .orElseGet(() -> {
                    byte[] random = new byte[GENERATED_SECRET_BYTES];
                    secureRandom.nextBytes(random);
                    String generated = Base64.getEncoder().encodeToString(random);
                    String effective = systemConfig.putSecretIfAbsent(
                            SystemConfigDocument.KEY_JWT_SIGNING_SECRET, generated);
                    if (effective.equals(generated)) {
                        log.info("No JWT_SECRET was supplied, so a random 256-bit signing secret was "
                                + "generated and persisted to system_config. It will be reused on every "
                                + "restart and by every replica sharing this database.");
                    }
                    return effective;
                });
    }

    /**
     * Derives a fixed-length HMAC key from an arbitrary secret string.
     *
     * <p>HMAC-SHA256 needs at least 256 bits of key material and jjwt rejects anything shorter. Rather
     * than make a hand-set {@code JWT_SECRET} fail for being a reasonable-looking passphrase, the value
     * is SHA-256'd to exactly 32 bytes. This is a length normaliser, not a password KDF - there is no
     * salt or work factor because the input is expected to be a real secret, not a guessable password.
     */
    private static SecretKey toKey(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
            return Keys.hmacShaKeyFor(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM but was unavailable", e);
        }
    }

    /** An immutable resolution result plus the nanotime it was computed at. */
    private record CachedSecret(SecretKey key, JwtSecretSource source, long resolvedAtNanos) {

        static CachedSecret of(String secret, JwtSecretSource source) {
            return new CachedSecret(toKey(secret), source, System.nanoTime());
        }

        boolean isStale() {
            return System.nanoTime() - resolvedAtNanos > Duration.ofSeconds(CACHE_TTL_SECONDS).toNanos();
        }
    }
}
