package com.saasinvestigator.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CryptoService}.
 *
 * <p>These cover the properties the rest of the application depends on rather than the algorithm itself:
 * that a value survives a round trip, that two encryptions of the same value are not equal, that a wrong key
 * fails loudly instead of returning garbage, and that {@code last4} never over-reveals.
 */
class CryptoServiceTest {

    private static final String KEY = "test-encryption-key-please-do-not-reuse";

    private final CryptoService crypto = new CryptoService(KEY);

    @Test
    void roundTripsAValue() {
        String plaintext = "sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789";

        assertThat(crypto.decrypt(crypto.encrypt(plaintext))).isEqualTo(plaintext);
    }

    @Test
    void roundTripsUnicodeAndEmptyValues() {
        assertThat(crypto.decrypt(crypto.encrypt(""))).isEmpty();
        assertThat(crypto.decrypt(crypto.encrypt("naïve — 日本語 🔐"))).isEqualTo("naïve — 日本語 🔐");
    }

    @Test
    void producesDifferentCiphertextForTheSamePlaintext() {
        // The random per-encryption IV is what guarantees this. Without it, two users storing the same API
        // key would produce identical ciphertext, and anyone with read access to Mongo could tell.
        String plaintext = "the-same-secret";

        assertThat(crypto.encrypt(plaintext)).isNotEqualTo(crypto.encrypt(plaintext));
    }

    @Test
    void failsLoudlyWhenDecryptingWithADifferentKey() {
        String ciphertext = crypto.encrypt("secret");
        CryptoService otherKey = new CryptoService("a-completely-different-encryption-key");

        assertThatThrownBy(() -> otherKey.decrypt(ciphertext))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CREDENTIAL_ENCRYPTION_KEY");
    }

    @Test
    void failsLoudlyWhenCiphertextHasBeenTampered() {
        // GCM authenticates the ciphertext, so a flipped bit is detected rather than silently decrypting to
        // different bytes. This is the reason GCM was chosen over CBC.
        String ciphertext = crypto.encrypt("secret");
        char[] chars = ciphertext.toCharArray();
        chars[chars.length - 2] = chars[chars.length - 2] == 'A' ? 'B' : 'A';

        assertThatThrownBy(() -> crypto.decrypt(new String(chars)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesToStartWithoutAKey() {
        assertThatThrownBy(() -> new CryptoService("  "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CREDENTIAL_ENCRYPTION_KEY is not set");
    }

    @Test
    void masksShortValuesEntirelyRatherThanPartially() {
        assertThat(crypto.last4(null)).isEqualTo("****");
        assertThat(crypto.last4("")).isEqualTo("****");
        assertThat(crypto.last4("abc")).isEqualTo("****");
        assertThat(crypto.last4("1234567")).isEqualTo("****");
    }

    @Test
    void revealsOnlyTheLastFourCharactersOfALongValue() {
        assertThat(crypto.last4("sk-ant-api03-WXYZ")).isEqualTo("WXYZ");
    }
}
