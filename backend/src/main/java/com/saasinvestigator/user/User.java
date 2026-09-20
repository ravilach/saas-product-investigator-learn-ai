package com.saasinvestigator.user;

import com.saasinvestigator.llm.LlmProviderType;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A user of the application, stored in the {@code users} collection.
 *
 * <p>Login is by {@code username} plus password, not by email - email is stored (and uniquely
 * indexed) for identification, but is not a credential.
 *
 * <p>{@code passwordHash} holds a BCrypt hash and nothing else; there is intentionally no field
 * anywhere that could hold a recoverable password.
 */
@Document(collection = "users")
public class User {

    @Id
    private String id;

    private String firstName;
    private String lastName;

    /**
     * The login identifier. Uniquely indexed - see {@code MongoIndexConfig}, where indexes are
     * declared explicitly rather than via auto-index-creation so they are visible in one place.
     */
    private String username;

    /** Uniquely indexed. Used for identification, never for login. */
    private String email;

    /** BCrypt hash. Never returned by any endpoint, and never logged. */
    private String passwordHash;

    private Role role;

    /**
     * Which provider this user would rather have handle their runs, or {@code null} until they pick
     * one. On its own it does nothing: it only takes effect once the user has also stored a personal
     * credential for that provider (see {@code LlmProviderResolver}).
     */
    private LlmProviderType preferredLlmProvider;

    private Instant createdAt;

    /** Required by Spring Data's mapping layer. */
    public User() {}

    /**
     * Creates a user with the mandatory fields populated and {@code createdAt} stamped.
     *
     * @param firstName given name
     * @param lastName family name
     * @param username the login identifier; must be unique
     * @param email contact address; must be unique
     * @param passwordHash a BCrypt hash, never a plaintext password
     * @param role the assigned role
     */
    public User(String firstName, String lastName, String username, String email,
                String passwordHash, Role role) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
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

    /** @return the user's given name */
    public String getFirstName() {
        return firstName;
    }

    /** @param firstName the user's given name */
    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    /** @return the user's family name */
    public String getLastName() {
        return lastName;
    }

    /** @param lastName the user's family name */
    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    /** @return the login identifier */
    public String getUsername() {
        return username;
    }

    /** @param username the login identifier */
    public void setUsername(String username) {
        this.username = username;
    }

    /** @return the contact email address */
    public String getEmail() {
        return email;
    }

    /** @param email the contact email address */
    public void setEmail(String email) {
        this.email = email;
    }

    /** @return the BCrypt password hash; do not serialise this into any response */
    public String getPasswordHash() {
        return passwordHash;
    }

    /** @param passwordHash a BCrypt hash, never a plaintext password */
    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    /** @return the assigned role */
    public Role getRole() {
        return role;
    }

    /** @param role the assigned role */
    public void setRole(Role role) {
        this.role = role;
    }

    /** @return the preferred provider, or {@code null} if the user has not chosen one */
    public LlmProviderType getPreferredLlmProvider() {
        return preferredLlmProvider;
    }

    /** @param preferredLlmProvider the preferred provider, or {@code null} to clear the preference */
    public void setPreferredLlmProvider(LlmProviderType preferredLlmProvider) {
        this.preferredLlmProvider = preferredLlmProvider;
    }

    /** @return when the account was created */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** @param createdAt when the account was created */
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * Full name for display.
     *
     * @return {@code firstName + " " + lastName}
     */
    public String displayName() {
        return firstName + " " + lastName;
    }
}
