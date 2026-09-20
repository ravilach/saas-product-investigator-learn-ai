package com.saasinvestigator.user;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Spring Data repository over the {@code users} collection. */
public interface UserRepository extends MongoRepository<User, String> {

    /**
     * Looks a user up by their login identifier. Backed by the unique {@code username} index.
     *
     * @param username the login identifier
     * @return the user, or empty if no such username exists
     */
    Optional<User> findByUsername(String username);

    /**
     * Looks a user up by email, used to reject duplicate registrations.
     *
     * @param email the email address
     * @return the user, or empty
     */
    Optional<User> findByEmail(String email);

    /**
     * @param username the login identifier
     * @return {@code true} if the username is taken
     */
    boolean existsByUsername(String username);

    /**
     * @param email the email address
     * @return {@code true} if the email is taken
     */
    boolean existsByEmail(String email);
}
