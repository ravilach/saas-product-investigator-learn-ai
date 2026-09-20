package com.saasinvestigator.credential;

import com.saasinvestigator.llm.LlmProviderType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Access to {@code user_llm_credentials}.
 *
 * <p>Every method is scoped by {@code userId}. There is intentionally no "find all credentials" or
 * "find by provider" across users: no feature needs one, and the only thing such a method could be used
 * for is enumerating other people's keys.
 */
public interface UserLlmCredentialRepository extends MongoRepository<UserLlmCredential, String> {

    /**
     * @param userId the owning user
     * @param provider the provider
     * @return that user's credential for that provider, if stored
     */
    Optional<UserLlmCredential> findByUserIdAndProvider(String userId, LlmProviderType provider);

    /**
     * @param userId the owning user
     * @return every credential that user has stored, at most one per provider
     */
    List<UserLlmCredential> findByUserId(String userId);

    /**
     * Removes one credential.
     *
     * @param userId the owning user
     * @param provider the provider to forget
     * @return how many documents were deleted - {@code 0} if there was nothing stored, which lets the
     *     service distinguish "removed" from "there was nothing to remove" without a prior read
     */
    long deleteByUserIdAndProvider(String userId, LlmProviderType provider);

    /**
     * Removes every credential belonging to a user, for use when the account is deleted.
     *
     * @param userId the owning user
     * @return how many documents were deleted
     */
    long deleteByUserId(String userId);
}
