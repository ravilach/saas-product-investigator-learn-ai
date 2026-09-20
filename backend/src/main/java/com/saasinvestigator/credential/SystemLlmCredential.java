package com.saasinvestigator.credential;

import com.saasinvestigator.llm.LlmProviderType;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The admin-set system-wide API key override for one provider.
 *
 * <p>One document per provider (unique index on {@code provider}), which is what makes this an
 * <em>override</em> rather than a list: there is exactly one system-wide Anthropic key in effect at a time,
 * and setting a new one replaces it.
 *
 * <p>This is the top of the system-wide resolution chain - it wins over the {@code ANTHROPIC_API_KEY} env
 * var and over the host-mounted file. That ordering is the point of the feature: an operator whose
 * container was deployed with a key that has since been rotated can fix it from the UI without a redeploy.
 * The inverse ordering would make the Admin Console field silently ineffective on exactly the deployments
 * that most need it.
 *
 * <p>{@code updatedBy} records the acting admin's username so "who changed the system key" is answerable
 * from the document as well as from the audit log. The key itself is ciphertext, same contract as
 * {@link UserLlmCredential}.
 */
@Document(collection = "system_llm_credentials")
public class SystemLlmCredential {

    @Id
    private String id;

    private LlmProviderType provider;

    /** Base64 AES-256-GCM ciphertext of the API key. Never plaintext, never returned to a client. */
    private String apiKeyEncrypted;

    private Instant updatedAt;

    /** Username of the admin who last set this override. Not a secret, and useful next to the audit entry. */
    private String updatedBy;

    /** Required by Spring Data's mapping layer. */
    public SystemLlmCredential() {}

    /**
     * @param provider which provider this key is for
     * @param apiKeyEncrypted the already-encrypted key
     * @param updatedBy username of the admin setting it
     */
    public SystemLlmCredential(LlmProviderType provider, String apiKeyEncrypted, String updatedBy) {
        this.provider = provider;
        this.apiKeyEncrypted = apiKeyEncrypted;
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
    }

    /** @return the Mongo document id */
    public String getId() {
        return id;
    }

    /** @param id the Mongo document id */
    public void setId(String id) {
        this.id = id;
    }

    /** @return which provider this key is for */
    public LlmProviderType getProvider() {
        return provider;
    }

    /** @param provider which provider this key is for */
    public void setProvider(LlmProviderType provider) {
        this.provider = provider;
    }

    /** @return the encrypted key. Ciphertext - not safe to return to a client as-is. */
    public String getApiKeyEncrypted() {
        return apiKeyEncrypted;
    }

    /** @param apiKeyEncrypted an already-encrypted key. Never a plaintext one. */
    public void setApiKeyEncrypted(String apiKeyEncrypted) {
        this.apiKeyEncrypted = apiKeyEncrypted;
    }

    /** @return when this override was last set */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** @param updatedAt when this override was last set */
    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    /** @return username of the admin who last set this override */
    public String getUpdatedBy() {
        return updatedBy;
    }

    /** @param updatedBy username of the admin who last set this override */
    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }
}
