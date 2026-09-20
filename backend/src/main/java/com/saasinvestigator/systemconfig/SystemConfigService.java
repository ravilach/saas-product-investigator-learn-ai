package com.saasinvestigator.systemconfig;

import com.saasinvestigator.crypto.CryptoService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Read/write access to the {@code system_config} keyed store, with encryption handled transparently
 * for secret keys.
 *
 * <p>Callers deal in plaintext and never see ciphertext; this service decides which keys are secret.
 * That keeps the "is this encrypted?" decision in one place instead of spread across the JWT
 * resolver, the settings controller, and the Data Explorer.
 */
@Service
public class SystemConfigService {

    private static final Logger log = LoggerFactory.getLogger(SystemConfigService.class);

    private final SystemConfigRepository repository;
    private final CryptoService cryptoService;

    /**
     * @param repository the {@code system_config} repository
     * @param cryptoService used for the encrypted keys
     */
    public SystemConfigService(SystemConfigRepository repository, CryptoService cryptoService) {
        this.repository = repository;
        this.cryptoService = cryptoService;
    }

    /**
     * Reads and decrypts a secret config value.
     *
     * @param key the config key
     * @return the plaintext value, or empty if the key is unset or its ciphertext is unreadable
     *     under the current {@code CREDENTIAL_ENCRYPTION_KEY}
     */
    public Optional<String> getSecret(String key) {
        return repository.findByKey(key)
                .map(SystemConfigDocument::getValueEncrypted)
                .flatMap(ciphertext -> {
                    try {
                        return Optional.of(cryptoService.decrypt(ciphertext));
                    } catch (IllegalStateException e) {
                        // Treated as "unset" rather than fatal: a stale value encrypted under a
                        // previous key must not stop the app booting. The JWT resolver will simply
                        // generate a fresh secret, which logs everyone out once - noisy but
                        // recoverable, unlike a container that refuses to start.
                        log.error("Config key {} could not be decrypted and is being ignored. "
                                + "This usually means CREDENTIAL_ENCRYPTION_KEY changed.", key, e);
                        return Optional.empty();
                    }
                });
    }

    /**
     * Encrypts and stores a secret config value, creating or updating the entry.
     *
     * @param key the config key
     * @param plaintextValue the value to protect
     */
    public void putSecret(String key, String plaintextValue) {
        SystemConfigDocument doc = repository.findByKey(key).orElseGet(() -> new SystemConfigDocument(key));
        doc.setValueEncrypted(cryptoService.encrypt(plaintextValue));
        doc.setValue(null);
        if (doc.getCreatedAt() == null) {
            doc.setCreatedAt(Instant.now());
        } else {
            doc.setUpdatedAt(Instant.now());
        }
        repository.save(doc);
    }

    /**
     * Stores a secret only if the key is currently unset, and returns whichever value ends up in
     * effect.
     *
     * <p>This exists for the auto-generated JWT signing secret, where several replicas can boot
     * against the same empty database at once. The unique index on {@code key} makes the second
     * writer fail rather than create a duplicate document; catching that and re-reading means every
     * replica converges on the same secret instead of each trusting the one it generated locally and
     * rejecting the others' tokens.
     *
     * @param key the config key
     * @param plaintextValue the value to store if nothing is stored yet
     * @return the value now in effect - either the one just written, or the one another writer won with
     */
    public String putSecretIfAbsent(String key, String plaintextValue) {
        Optional<String> existing = getSecret(key);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            putSecret(key, plaintextValue);
            return plaintextValue;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            log.info("Config key {} was written concurrently by another instance; using that value.", key);
            return getSecret(key).orElseThrow(() -> new IllegalStateException(
                    "Config key " + key + " exists but could not be read back", e));
        }
    }

    /**
     * Reads a non-secret structured config value.
     *
     * @param key the config key
     * @return the stored map, or empty if unset
     */
    public Optional<Map<String, Object>> getValue(String key) {
        return repository.findByKey(key).map(SystemConfigDocument::getValue);
    }

    /**
     * Stores a non-secret structured config value.
     *
     * @param key the config key
     * @param value the value to store; must contain nothing secret, since it is stored in the clear
     *     and is readable through the Admin Console's Data Explorer
     */
    public void putValue(String key, Map<String, Object> value) {
        SystemConfigDocument doc = repository.findByKey(key).orElseGet(() -> new SystemConfigDocument(key));
        doc.setValue(new LinkedHashMap<>(value));
        doc.setValueEncrypted(null);
        if (doc.getCreatedAt() == null) {
            doc.setCreatedAt(Instant.now());
        } else {
            doc.setUpdatedAt(Instant.now());
        }
        repository.save(doc);
    }

    /**
     * Deletes a config entry, reverting whatever reads it to its next fallback.
     *
     * @param key the config key to remove
     */
    public void delete(String key) {
        repository.deleteByKey(key);
    }

    /**
     * Tests whether a key currently exists, without decrypting it.
     *
     * @param key the config key
     * @return {@code true} if a document for the key exists
     */
    public boolean exists(String key) {
        return repository.findByKey(key).isPresent();
    }
}
