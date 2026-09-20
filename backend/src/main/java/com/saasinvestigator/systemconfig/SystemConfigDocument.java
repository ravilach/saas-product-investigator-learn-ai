package com.saasinvestigator.systemconfig;

import java.time.Instant;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One key/value entry in the {@code system_config} collection.
 *
 * <p>Exactly one of {@link #getValueEncrypted()} and {@link #getValue()} is populated per document:
 * secrets use the encrypted field, non-secret structured config uses the plain one. Keeping them as
 * distinct fields rather than one polymorphic field is what lets the Data Explorer mask secrets
 * server-side by field name without having to understand what each key means.
 */
@Document(collection = "system_config")
public class SystemConfigDocument {

    /** Key for the auto-generated, persisted JWT signing secret (encrypted). */
    public static final String KEY_JWT_SIGNING_SECRET = "JWT_SIGNING_SECRET";

    /** Key for the admin-set JWT signing secret override (encrypted); wins over everything else. */
    public static final String KEY_JWT_SIGNING_SECRET_OVERRIDE = "JWT_SIGNING_SECRET_OVERRIDE";

    /** Key for the non-secret crawl defaults: {@code { maxDepth, maxPages }}. */
    public static final String KEY_CRAWL_DEFAULTS = "CRAWL_DEFAULTS";

    @Id
    private String id;

    @Indexed(unique = true)
    private String key;

    /** Base64 AES-GCM ciphertext, for secret values only. */
    private String valueEncrypted;

    /** Plain structured value, for non-secret config only. */
    private Map<String, Object> value;

    private Instant createdAt;
    private Instant updatedAt;

    /** Required by Spring Data's mapping layer. */
    public SystemConfigDocument() {}

    /**
     * @param key the config key
     */
    public SystemConfigDocument(String key) {
        this.key = key;
        this.createdAt = Instant.now();
    }

    /** @return the Mongo document id */
    public String getId() {
        return id;
    }

    /** @param id the Mongo document id */
    public void setId(String id) {
        this.id = id;
    }

    /** @return the config key */
    public String getKey() {
        return key;
    }

    /** @param key the config key */
    public void setKey(String key) {
        this.key = key;
    }

    /** @return base64 ciphertext for a secret value, or {@code null} for non-secret entries */
    public String getValueEncrypted() {
        return valueEncrypted;
    }

    /** @param valueEncrypted base64 ciphertext produced by {@code CryptoService.encrypt} */
    public void setValueEncrypted(String valueEncrypted) {
        this.valueEncrypted = valueEncrypted;
    }

    /** @return the plain structured value for non-secret entries, or {@code null} */
    public Map<String, Object> getValue() {
        return value;
    }

    /** @param value the plain structured value; must not contain anything secret */
    public void setValue(Map<String, Object> value) {
        this.value = value;
    }

    /** @return when this entry was first written */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** @param createdAt when this entry was first written */
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    /** @return when this entry was last changed, or {@code null} if never changed */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** @param updatedAt when this entry was last changed */
    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
