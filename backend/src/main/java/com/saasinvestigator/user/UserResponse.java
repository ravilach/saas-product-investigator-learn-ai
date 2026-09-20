package com.saasinvestigator.user;

import com.saasinvestigator.llm.LlmProviderType;
import java.time.Instant;

/**
 * A user as returned by the API.
 *
 * <p>This type exists specifically so that {@code passwordHash} cannot leak. Returning {@link User}
 * directly would serialise every field it has, and every future field it gains, which is exactly the
 * mistake that puts a hash in a JSON response. Mapping through a record makes the response shape an
 * explicit decision instead of a side effect of the persistence model.
 *
 * @param id the user id
 * @param firstName given name
 * @param lastName family name
 * @param username the login identifier
 * @param email contact address
 * @param role the assigned role
 * @param preferredLlmProvider the chosen provider, or {@code null}
 * @param createdAt when the account was created
 */
public record UserResponse(
        String id,
        String firstName,
        String lastName,
        String username,
        String email,
        Role role,
        LlmProviderType preferredLlmProvider,
        Instant createdAt) {

    /**
     * Maps a persisted user to its API representation.
     *
     * @param user the stored user
     * @return the safe-to-return view of it
     */
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                user.getUsername(),
                user.getEmail(),
                user.getRole(),
                user.getPreferredLlmProvider(),
                user.getCreatedAt());
    }
}
