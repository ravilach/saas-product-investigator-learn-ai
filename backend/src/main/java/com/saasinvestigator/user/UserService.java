package com.saasinvestigator.user;

import com.saasinvestigator.error.BadRequestException;
import com.saasinvestigator.error.ConflictException;
import com.saasinvestigator.error.NotFoundException;
import com.saasinvestigator.llm.LlmProviderType;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * User lifecycle operations: create, list, delete, reset password, and set a provider preference.
 *
 * <p>Every path that accepts a plaintext password hashes it immediately and never retains, returns, or
 * logs the plaintext. Callers hand in the plaintext and get back nothing derived from it.
 */
@Service
public class UserService {

    /**
     * Minimum password length. Deliberately low rather than a full policy engine: this is a learning
     * project whose documented default credential is {@code admin}/{@code admin}, and a strict policy
     * here would only be theatre next to that. It exists to catch empty/one-character mistakes.
     */
    public static final int MIN_PASSWORD_LENGTH = 4;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * @param userRepository the {@code users} repository
     * @param passwordEncoder the BCrypt encoder configured in {@code SecurityConfig}
     */
    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Lists all users, oldest first, so the seeded admin stays at the top of the Admin Console table.
     *
     * @return every user; callers must map to a DTO that omits {@code passwordHash}
     */
    public List<User> findAll() {
        return userRepository.findAll(Sort.by(Sort.Direction.ASC, "createdAt"));
    }

    /**
     * Loads one user by id.
     *
     * @param id the user id
     * @return the user
     * @throws NotFoundException if no such user exists
     */
    public User findById(String id) {
        return userRepository.findById(id).orElseThrow(() -> NotFoundException.of("User", id));
    }

    /**
     * Loads one user by login identifier.
     *
     * @param username the login identifier
     * @return the user
     * @throws NotFoundException if no such user exists
     */
    public User findByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> NotFoundException.of("User", username));
    }

    /**
     * Creates a user with an admin-assigned role.
     *
     * @param firstName given name
     * @param lastName family name
     * @param username the login identifier; must not already exist
     * @param email contact address; must not already exist
     * @param plaintextPassword the initial password, hashed here and then discarded
     * @param role the role to assign
     * @return the saved user
     * @throws ConflictException if the username or email is taken
     * @throws BadRequestException if a required field is blank or the password is too short
     */
    public User create(String firstName, String lastName, String username, String email,
                       String plaintextPassword, Role role) {
        requireText(firstName, "firstName");
        requireText(lastName, "lastName");
        requireText(username, "username");
        requireText(email, "email");
        validatePassword(plaintextPassword);
        if (role == null) {
            throw new BadRequestException("role is required and must be ADMIN or READ_ONLY.");
        }
        if (userRepository.existsByUsername(username)) {
            throw new ConflictException("Username '" + username + "' is already taken.");
        }
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("Email '" + email + "' is already registered.");
        }
        User user = new User(firstName, lastName, username, email,
                passwordEncoder.encode(plaintextPassword), role);
        return userRepository.save(user);
    }

    /**
     * Deletes a user.
     *
     * <p>Refuses to remove the last remaining ADMIN. Without that check an admin can lock everyone out
     * of user management and the Admin Console with a single click, recoverable only by editing Mongo
     * by hand.
     *
     * @param id the user id
     * @return the deleted user, so the caller can audit-log who it was
     * @throws NotFoundException if no such user exists
     * @throws BadRequestException if this would delete the only admin
     */
    public User delete(String id) {
        User user = findById(id);
        if (user.getRole() == Role.ADMIN && countAdmins() <= 1) {
            throw new BadRequestException(
                    "Cannot delete the only remaining ADMIN user - promote another user to ADMIN first.");
        }
        userRepository.delete(user);
        return user;
    }

    /**
     * Resets a user's password to a new value.
     *
     * <p>This is the closest safe equivalent to "an admin can view the password": the old value cannot
     * be recovered from its BCrypt hash, so it is replaced rather than revealed.
     *
     * @param id the user id
     * @param newPlaintextPassword the replacement password, hashed here and then discarded
     * @return the updated user
     * @throws NotFoundException if no such user exists
     * @throws BadRequestException if the password is too short
     */
    public User resetPassword(String id, String newPlaintextPassword) {
        validatePassword(newPlaintextPassword);
        User user = findById(id);
        user.setPasswordHash(passwordEncoder.encode(newPlaintextPassword));
        return userRepository.save(user);
    }

    /**
     * Records which provider a user would rather have handle their runs.
     *
     * <p>Accepts {@code null} to clear the preference, which falls the user back to the system-wide
     * default. Note that setting a preference does not by itself change anything: the user must also
     * have stored a credential for that provider.
     *
     * @param username the login identifier of the user changing their own preference
     * @param provider the preferred provider, or {@code null} to clear
     * @return the updated user
     * @throws NotFoundException if no such user exists
     */
    public User updatePreferredProvider(String username, LlmProviderType provider) {
        User user = findByUsername(username);
        user.setPreferredLlmProvider(provider);
        return userRepository.save(user);
    }

    /**
     * Verifies a plaintext password against a user's stored hash.
     *
     * @param user the user to check against
     * @param plaintextPassword the candidate password
     * @return {@code true} if the password matches
     */
    public boolean passwordMatches(User user, String plaintextPassword) {
        return passwordEncoder.matches(plaintextPassword, user.getPasswordHash());
    }

    /**
     * @return how many ADMIN users exist
     */
    public long countAdmins() {
        return userRepository.findAll().stream().filter(u -> u.getRole() == Role.ADMIN).count();
    }

    /**
     * @return the total number of users, used by the Admin Console stats endpoint
     */
    public long count() {
        return userRepository.count();
    }

    private void validatePassword(String plaintextPassword) {
        if (plaintextPassword == null || plaintextPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new BadRequestException(
                    "Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(field + " is required.");
        }
    }
}
