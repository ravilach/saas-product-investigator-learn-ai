package com.saasinvestigator.credential;

import com.saasinvestigator.crypto.CryptoService;
import com.saasinvestigator.llm.LlmProviderType;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The personal bring-your-own-key store: a user's own API keys, one per provider.
 *
 * <p>This service is the encryption boundary. Callers hand it plaintext and get back either a masked
 * {@link CredentialStatus} or, for the provider call itself, a {@link ResolvedCredential}. Ciphertext never
 * leaves it and plaintext is never persisted.
 *
 * <p><b>Every method takes a {@code userId} and scopes its query by it.</b> That is not defensive
 * duplication of the {@code /api/users/me/...} routing - it is what makes it impossible for a future
 * endpoint that takes an id from a path variable to read somebody else's key by accident. There is no
 * "find any credential for provider X" method here at all.
 */
@Service
public class UserCredentialService {

    private static final Logger log = LoggerFactory.getLogger(UserCredentialService.class);

    private final UserLlmCredentialRepository repository;
    private final CryptoService cryptoService;

    /**
     * @param repository the {@code user_llm_credentials} repository
     * @param cryptoService encrypts on the way in, decrypts only for a provider call
     */
    public UserCredentialService(UserLlmCredentialRepository repository, CryptoService cryptoService) {
        this.repository = repository;
        this.cryptoService = cryptoService;
    }

    /**
     * Stores or replaces the caller's key for a provider.
     *
     * <p>Replacing rather than rejecting a second key for the same provider is the whole point of the unique
     * index on {@code (userId, provider)}: pasting a rotated key into the same field is the common action,
     * and making the user delete the old one first would be ceremony with no purpose.
     *
     * @param userId the owning user
     * @param provider which provider the key is for
     * @param plaintextApiKey the key, encrypted here and then discarded
     * @return the masked view of what is now stored
     */
    public CredentialStatus store(String userId, LlmProviderType provider, String plaintextApiKey) {
        UserLlmCredential credential = repository.findByUserIdAndProvider(userId, provider)
                .orElseGet(() -> new UserLlmCredential(userId, provider, null));
        credential.setApiKeyEncrypted(cryptoService.encrypt(plaintextApiKey));
        if (credential.getCreatedAt() == null) {
            credential.setCreatedAt(Instant.now());
        } else {
            credential.setUpdatedAt(Instant.now());
        }
        repository.save(credential);
        return new CredentialStatus(provider, true, cryptoService.last4(plaintextApiKey));
    }

    /**
     * Removes the caller's key for a provider.
     *
     * @param userId the owning user
     * @param provider which provider to forget
     * @return {@code true} if a credential was actually removed, {@code false} if there was none stored.
     *     The caller responds {@code 204} either way - a second click on Remove should not produce an error
     *     for an outcome the user already has - but only audit-logs a removal that happened.
     */
    public boolean remove(String userId, LlmProviderType provider) {
        return repository.deleteByUserIdAndProvider(userId, provider) > 0;
    }

    /**
     * Lists the providers this user has a key stored for.
     *
     * <p>Producing a {@code last4} requires decrypting, and a credential written under a since-changed
     * {@code CREDENTIAL_ENCRYPTION_KEY} cannot be decrypted. Rather than fail the whole listing, such an
     * entry is reported as configured with a masked {@code last4} - it genuinely is stored, it genuinely
     * cannot be read, and the fix is to re-paste the key, which this response leads the user to do.
     *
     * @param userId the owning user
     * @return one entry per stored credential, ordered by provider for a stable UI
     */
    public List<CredentialStatus> list(String userId) {
        return repository.findByUserId(userId).stream()
                .sorted(Comparator.comparing(UserLlmCredential::getProvider))
                .map(credential -> new CredentialStatus(
                        credential.getProvider(), true, last4OrMasked(credential.getApiKeyEncrypted())))
                .toList();
    }

    /**
     * @param userId the owning user
     * @param provider which provider
     * @return {@code true} if this user has a key stored for that provider, without decrypting it
     */
    public boolean has(String userId, LlmProviderType provider) {
        return repository.findByUserIdAndProvider(userId, provider).isPresent();
    }

    /**
     * Decrypts the caller's key for use in an actual provider call.
     *
     * <p>The only method here that produces plaintext. An undecryptable stored value resolves to empty
     * rather than throwing, so provider resolution falls through to the system-wide key instead of failing
     * the run outright - the user's run still works, and the ERROR line plus the masked {@code last4} in
     * Account Settings is what tells them to re-enter it.
     *
     * @param userId the owning user
     * @param provider which provider
     * @return the resolved credential, or empty if none is stored or it cannot be decrypted
     */
    public Optional<ResolvedCredential> resolve(String userId, LlmProviderType provider) {
        return repository.findByUserIdAndProvider(userId, provider)
                .flatMap(credential -> decrypt(credential.getApiKeyEncrypted(), provider, userId))
                .map(key -> new ResolvedCredential(provider, key, CredentialSource.PERSONAL));
    }

    /**
     * Removes every credential belonging to a user.
     *
     * <p>Called when the account is deleted. Without it, the encrypted keys of deleted users accumulate in
     * {@code user_llm_credentials} forever - unreachable by any endpoint, but still stored secrets that
     * nobody remembers exist and that no longer have an owner who could rotate them.
     *
     * @param userId the departing user
     * @return how many credentials were removed
     */
    public long forgetAll(String userId) {
        return repository.deleteByUserId(userId);
    }

    private String last4OrMasked(String ciphertext) {
        try {
            return cryptoService.last4(cryptoService.decrypt(ciphertext));
        } catch (IllegalStateException e) {
            return cryptoService.last4(null);
        }
    }

    private Optional<String> decrypt(String ciphertext, LlmProviderType provider, String userId) {
        try {
            return Optional.of(cryptoService.decrypt(ciphertext));
        } catch (IllegalStateException e) {
            // Logged with the user id and provider and nothing else - a decryption failure is worth an
            // ERROR, and the ciphertext that failed is not worth putting in a log file.
            log.error("The stored {} credential for user {} could not be decrypted and is being ignored. "
                    + "The most likely cause is that CREDENTIAL_ENCRYPTION_KEY changed; the user needs to "
                    + "re-enter their key.", provider, userId, e);
            return Optional.empty();
        }
    }
}
