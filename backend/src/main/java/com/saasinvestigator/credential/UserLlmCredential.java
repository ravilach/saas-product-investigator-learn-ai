package com.saasinvestigator.credential;

import com.saasinvestigator.llm.LlmProviderType;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One user's API key for one provider - the "bring your own key" store.
 *
 * <p>Unique on {@code userId} + {@code provider} (index declared in {@code MongoIndexInitializer}), so a
 * user has at most one key per provider and "store or update my key" is a find-then-save rather than an
 * insert that could duplicate.
 *
 * <p>{@code apiKeyEncrypted} holds AES-256-GCM ciphertext and nothing else. There is deliberately no
 * getter returning plaintext and no {@code toString} override that could include the field: the only code
 * that decrypts is {@link UserCredentialService}, which hands the plaintext straight to a provider SDK and
 * never returns it to a caller. Same reasoning as {@code SourceConfig.authTokenEncrypted} - a getter that
 * returns a plaintext secret is a getter that eventually ends up in a log line.
 *
 * <p>Keyed by {@code userId} rather than username, because a username is a display identifier a future
 * rename feature would change, and a renamed user losing their stored key would be a baffling bug.
 */
@Document(collection = "user_llm_credentials")
public class UserLlmCredential {

    @Id
    private String id;

    private String userId;

    private LlmProviderType provider;

    /** Base64 AES-256-GCM ciphertext of the API key. Never plaintext, never returned to a client. */
    private String apiKeyEncrypted;

    private Instant createdAt;
    private Instant updatedAt;

    /** Required by Spring Data's mapping layer. */
    public UserLlmCredential() {}

    /**
     * @param userId the owning user's id
     * @param provider which provider this key is for
     * @param apiKeyEncrypted the already-encrypted key
     */
    public UserLlmCredential(String userId, LlmProviderType provider, String apiKeyEncrypted) {
        this.userId = userId;
        this.provider = provider;
        this.apiKeyEncrypted = apiKeyEncrypted;
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

    /** @return the owning user's id */
    public String getUserId() {
        return userId;
    }

    /** @param userId the owning user's id */
    public void setUserId(String userId) {
        this.userId = userId;
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

    /** @return when the key was first stored */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** @param createdAt when the key was first stored */
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    /** @return when the key was last replaced, or {@code null} if never */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** @param updatedAt when the key was last replaced */
    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
