package com.saasinvestigator.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saasinvestigator.error.BadRequestException;
import com.saasinvestigator.error.ConflictException;
import com.saasinvestigator.error.NotFoundException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Unit tests for {@link UserService}.
 *
 * <p>The repository is mocked but the password encoder is real: the thing most worth asserting here is that a
 * plaintext password never reaches the saved document, and a stubbed encoder returning a fixed string would
 * make that assertion vacuous.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, passwordEncoder);
    }

    @Test
    void createHashesThePasswordAndNeverStoresThePlaintext() {
        when(userRepository.existsByUsername("dana")).thenReturn(false);
        when(userRepository.existsByEmail("dana@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        userService.create("Dana", "Scully", "dana", "dana@example.com", "trustno1", Role.READ_ONLY);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getPasswordHash())
                .isNotEqualTo("trustno1")
                .startsWith("$2");
        assertThat(passwordEncoder.matches("trustno1", saved.getValue().getPasswordHash())).isTrue();
    }

    @Test
    void createRejectsADuplicateUsername() {
        when(userRepository.existsByUsername("admin")).thenReturn(true);

        assertThatThrownBy(() -> userService.create("A", "B", "admin", "a@b.com", "password", Role.ADMIN))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("admin");
        verify(userRepository, never()).save(any());
    }

    @Test
    void createRejectsADuplicateEmail() {
        when(userRepository.existsByUsername("new")).thenReturn(false);
        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.create("A", "B", "new", "taken@example.com", "password",
                Role.ADMIN))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("taken@example.com");
    }

    @Test
    void createRejectsAShortPassword() {
        assertThatThrownBy(() -> userService.create("A", "B", "new", "a@b.com", "x", Role.ADMIN))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("at least");
        verify(userRepository, never()).save(any());
    }

    @Test
    void resetPasswordReplacesTheHash() {
        User user = user("dana", Role.READ_ONLY);
        user.setPasswordHash(passwordEncoder.encode("old-password"));
        when(userRepository.findById("id-1")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User updated = userService.resetPassword("id-1", "new-password");

        assertThat(passwordEncoder.matches("new-password", updated.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches("old-password", updated.getPasswordHash())).isFalse();
    }

    @Test
    void resetPasswordFailsForAnUnknownUser() {
        when(userRepository.findById("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.resetPassword("nope", "new-password"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void deleteRefusesToRemoveTheLastAdmin() {
        User onlyAdmin = user("admin", Role.ADMIN);
        when(userRepository.findById("id-1")).thenReturn(Optional.of(onlyAdmin));
        when(userRepository.findAll()).thenReturn(List.of(onlyAdmin));

        assertThatThrownBy(() -> userService.delete("id-1"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("only remaining ADMIN");
        verify(userRepository, never()).delete(any());
    }

    @Test
    void deleteAllowsRemovingAnAdminWhenAnotherRemains() {
        User first = user("admin", Role.ADMIN);
        User second = user("root", Role.ADMIN);
        when(userRepository.findById("id-1")).thenReturn(Optional.of(first));
        when(userRepository.findAll()).thenReturn(List.of(first, second));

        userService.delete("id-1");

        verify(userRepository).delete(first);
    }

    @Test
    void passwordMatchesComparesAgainstTheStoredHash() {
        User user = user("dana", Role.READ_ONLY);
        user.setPasswordHash(passwordEncoder.encode("correct"));

        assertThat(userService.passwordMatches(user, "correct")).isTrue();
        assertThat(userService.passwordMatches(user, "incorrect")).isFalse();
    }

    private static User user(String username, Role role) {
        return new User("First", "Last", username, username + "@example.com", "unset", role);
    }
}
