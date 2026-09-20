package com.saasinvestigator.credential;

import com.saasinvestigator.llm.LlmProviderType;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Access to {@code system_llm_credentials} - at most one document per provider.
 */
public interface SystemLlmCredentialRepository extends MongoRepository<SystemLlmCredential, String> {

    /**
     * @param provider the provider
     * @return the system-wide override for that provider, if an admin has set one
     */
    Optional<SystemLlmCredential> findByProvider(LlmProviderType provider);

    /**
     * Clears the override for a provider.
     *
     * @param provider the provider to revert to env-var/host-mount resolution
     * @return how many documents were deleted - {@code 0} when no override was set
     */
    long deleteByProvider(LlmProviderType provider);
}
