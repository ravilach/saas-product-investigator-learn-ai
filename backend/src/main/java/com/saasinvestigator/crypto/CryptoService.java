package com.saasinvestigator.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Encrypts and decrypts secrets at rest using AES-256-GCM under {@code CREDENTIAL_ENCRYPTION_KEY}.
 *
 * <p>Design notes worth knowing before changing anything here:
 *
 * <ul>
 *   <li><strong>GCM, not CBC.</strong> GCM is authenticated, so a tampered or truncated ciphertext
 *       fails to decrypt rather than silently producing garbage plaintext. For values that get fed
 *       to an HTTP client as an API key, failing loudly is the behaviour you want.
 *   <li><strong>Random 12-byte IV per encryption, prefixed to the ciphertext.</strong> Reusing an IV
 *       under GCM is catastrophic (it leaks the authentication key), so a fresh one is generated for
 *       every call and stored alongside the output. That also means encrypting the same plaintext
 *       twice yields different ciphertext - correct, but it does mean you cannot compare ciphertexts
 *       for equality to test whether two secrets match.
 *   <li><strong>The configured key is SHA-256'd into 32 bytes.</strong> This accepts any key length
 *       a person or {@code entrypoint.sh} supplies (base64, hex, a passphrase) without demanding
 *       exactly 32 bytes of input. It is a key-derivation shortcut, not a password KDF - the input
 *       is expected to be high-entropy already, which is why there is no salt or iteration count.
 * </ul>
 *
 * <p>This key is the root of trust for everything else the app stores, which is why it can never be
 * set through the Admin Console: a secret cannot be stored encrypted in Mongo using itself as the
 * encryption key. See {@code /docs/ARCHITECTURE.md} and the {@code rotate-secrets} skill - rotating
 * this key requires decrypt-with-old then re-encrypt-with-new, not a simple swap.
 */
@Service
public class CryptoService {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param configuredKey the raw value of {@code CREDENTIAL_ENCRYPTION_KEY}
     * @throws IllegalStateException if the key is absent, with a message explaining how to supply it
     *     - this is a fail-fast on purpose, because starting without it would mean every credential
     *     write fails later at a much more confusing moment
     */
    public CryptoService(@Value("${app.credential-encryption-key:}") String configuredKey) {
        if (configuredKey == null || configuredKey.isBlank()) {
            throw new IllegalStateException("""
                    CREDENTIAL_ENCRYPTION_KEY is not set.

                    This is the one secret that must be supplied externally: it encrypts everything \
                    else this app stores (LLM API keys, MCP auth tokens, the JWT signing secret), so \
                    it cannot itself be stored encrypted in the database.

                    Local development: export CREDENTIAL_ENCRYPTION_KEY="$(head -c 32 /dev/urandom | base64)"
                    Docker: leave it unset and entrypoint.sh generates one into /data/credential-key.

                    See docs/SETUP.md.""");
        }
        this.key = new SecretKeySpec(sha256(configuredKey), "AES");
    }

    /**
     * Encrypts a plaintext secret.
     *
     * @param plaintext the secret to protect; must not be {@code null}
     * @return base64 of {@code IV || ciphertext || GCM tag}, safe to persist as a string
     * @throws IllegalStateException if the JCE provider rejects the operation, which in practice
     *     means a broken JVM crypto configuration rather than bad input
     */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // IV is prepended rather than stored in a separate field: it is not secret, and keeping
            // it with the ciphertext makes a stored value self-contained and impossible to mismatch.
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt a secret value.", e);
        }
    }

    /**
     * Decrypts a value previously produced by {@link #encrypt(String)}.
     *
     * @param encrypted base64 of {@code IV || ciphertext || tag}
     * @return the original plaintext
     * @throws IllegalStateException if the value is malformed, truncated, or was encrypted under a
     *     different key - all of which are indistinguishable from tampering, and all of which mean
     *     the same thing operationally: this value is not recoverable with the current key
     */
    public String decrypt(String encrypted) {
        try {
            byte[] combined = Base64.getDecoder().decode(encrypted);
            if (combined.length <= IV_LENGTH_BYTES) {
                throw new IllegalStateException("Stored secret is too short to be valid ciphertext.");
            }
            byte[] iv = new byte[IV_LENGTH_BYTES];
            byte[] ciphertext = new byte[combined.length - IV_LENGTH_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH_BYTES);
            System.arraycopy(combined, IV_LENGTH_BYTES, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Failed to decrypt a stored secret. The most likely cause is that "
                            + "CREDENTIAL_ENCRYPTION_KEY changed since the value was written - see the "
                            + "rotate-secrets skill.",
                    e);
        }
    }

    /**
     * Returns the last four characters of a secret, for the masked {@code last4} display pattern.
     *
     * <p>Short values are masked entirely rather than partially revealed: showing "ab" of a
     * three-character token gives away proportionally far more than showing 4 of 40.
     *
     * @param plaintext the secret, or {@code null}
     * @return the last four characters, or {@code "****"} if the value is absent or shorter than 8
     */
    public String last4(String plaintext) {
        if (plaintext == null || plaintext.length() < 8) {
            return "****";
        }
        return plaintext.substring(plaintext.length() - 4);
    }

    private static byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is unavailable in this JVM.", e);
        }
    }
}
