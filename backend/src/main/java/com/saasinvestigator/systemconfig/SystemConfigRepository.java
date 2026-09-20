package com.saasinvestigator.systemconfig;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Spring Data repository over the {@code system_config} collection. */
public interface SystemConfigRepository extends MongoRepository<SystemConfigDocument, String> {

    /**
     * Looks up one config entry.
     *
     * @param key the config key, e.g. {@code JWT_SIGNING_SECRET}
     * @return the entry, or empty if the key has never been written
     */
    Optional<SystemConfigDocument> findByKey(String key);

    /**
     * Removes one config entry, used when an admin clears an override.
     *
     * @param key the config key to delete
     */
    void deleteByKey(String key);
}
