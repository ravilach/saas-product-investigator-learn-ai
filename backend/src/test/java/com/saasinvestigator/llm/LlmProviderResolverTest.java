package com.saasinvestigator.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saasinvestigator.credential.CredentialSource;
import com.saasinvestigator.credential.ResolvedCredential;
import com.saasinvestigator.credential.SystemCredentialService;
import com.saasinvestigator.credential.UserCredentialService;
import com.saasinvestigator.error.ProviderUnavailableException;
import com.saasinvestigator.user.User;
import com.saasinvestigator.user.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests which provider a run actually uses, and where its key came from.
 *
 * <p>This is the class that decides whose money a run spends, so the precedence is worth pinning down. It is also the
 * class that produces the message a user sees when nothing is configured, and that message is the only thing standing
 * between "no API key" and a support question - so its content is asserted too, not just its type.
 */
class LlmProviderResolverTest {

    private static final String USER_ID = "user-1";

    private UserRepository users;
    private UserCredentialService userCredentials;
    private SystemCredentialService systemCredentials;
    private LlmProviderResolver resolver;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        userCredentials = mock(UserCredentialService.class);
        systemCredentials = mock(SystemCredentialService.class);
        LlmProperties properties = new LlmProperties("claude-sonnet-5", "gpt-6-astra", "", "", 600_000);
        PromptBuilder promptBuilder = new PromptBuilder(properties);
        resolver = new LlmProviderResolver(users, userCredentials, systemCredentials, properties, promptBuilder,
                new ChangeReportJsonParser(promptBuilder));

        lenient().when(users.findById(USER_ID)).thenReturn(Optional.empty());
        lenient().when(userCredentials.resolve(eq(USER_ID), any())).thenReturn(Optional.empty());
        lenient().when(systemCredentials.resolve(any())).thenReturn(Optional.empty());
    }

    private static User userPreferring(LlmProviderType preference) {
        User user = new User();
        user.setId(USER_ID);
        user.setUsername("dana");
        user.setPreferredLlmProvider(preference);
        return user;
    }

    private static ResolvedCredential credential(LlmProviderType provider, CredentialSource source) {
        return new ResolvedCredential(provider, "sk-test-key", source);
    }

    // ---------------------------------------------------------------------
    // Precedence
    // ---------------------------------------------------------------------

    @Test
    void aUsersOwnKeyForTheirChosenProviderWins() {
        when(users.findById(USER_ID)).thenReturn(Optional.of(userPreferring(LlmProviderType.OPENAI)));
        when(userCredentials.resolve(USER_ID, LlmProviderType.OPENAI))
                .thenReturn(Optional.of(credential(LlmProviderType.OPENAI, CredentialSource.PERSONAL)));

        LlmProviderResolver.ResolvedProvider resolved = resolver.resolve(USER_ID);

        assertThat(resolved.type()).isEqualTo(LlmProviderType.OPENAI);
        assertThat(resolved.credentialSource()).isEqualTo(CredentialSource.PERSONAL);
        // The system key is not even consulted: a user who brought their own key is spending their own budget, and
        // falling back to the shared one would silently move the cost.
        verify(systemCredentials, never()).resolve(any());
    }

    @Test
    void aUserWithNoPreferenceGetsTheSystemProvider() {
        when(systemCredentials.resolve(LlmProviderResolver.SYSTEM_DEFAULT))
                .thenReturn(Optional.of(credential(LlmProviderResolver.SYSTEM_DEFAULT, CredentialSource.ENV_VAR)));

        LlmProviderResolver.ResolvedProvider resolved = resolver.resolve(USER_ID);

        assertThat(resolved.type()).isEqualTo(LlmProviderResolver.SYSTEM_DEFAULT);
        assertThat(resolved.credentialSource()).isEqualTo(CredentialSource.ENV_VAR);
        verify(userCredentials, never()).resolve(any(), any());
    }

    @Test
    void aPreferenceWithNoKeyBehindItFallsBackToTheSystemProviderRatherThanFailing() {
        when(users.findById(USER_ID)).thenReturn(Optional.of(userPreferring(LlmProviderType.OPENAI)));
        when(userCredentials.resolve(USER_ID, LlmProviderType.OPENAI)).thenReturn(Optional.empty());
        when(systemCredentials.resolve(LlmProviderResolver.SYSTEM_DEFAULT))
                .thenReturn(Optional.of(credential(LlmProviderResolver.SYSTEM_DEFAULT, CredentialSource.OVERRIDE)));

        LlmProviderResolver.ResolvedProvider resolved = resolver.resolve(USER_ID);

        // A stale preference - set, then the key deleted - should not stop someone running a report when a working
        // system key exists. The fallback is logged, so it is discoverable rather than mysterious.
        assertThat(resolved.type()).isEqualTo(LlmProviderResolver.SYSTEM_DEFAULT);
        assertThat(resolved.credentialSource()).isEqualTo(CredentialSource.OVERRIDE);
    }

    @Test
    void theCredentialSourceIsCarriedThroughSoAFailingKeyCanBeTracedToWhereItLives() {
        when(systemCredentials.resolve(LlmProviderResolver.SYSTEM_DEFAULT))
                .thenReturn(Optional.of(credential(LlmProviderResolver.SYSTEM_DEFAULT, CredentialSource.HOST_MOUNT)));

        // Four channels can supply a system key, and "the provider rejected our key" is unanswerable without knowing
        // which of the four it came from.
        assertThat(resolver.resolve(USER_ID).credentialSource()).isEqualTo(CredentialSource.HOST_MOUNT);
    }

    // ---------------------------------------------------------------------
    // Nothing configured
    // ---------------------------------------------------------------------

    @Test
    void withNoKeyAnywhereTheErrorNamesBothPlacesTheUserCanFixIt() {
        assertThatThrownBy(() -> resolver.resolve(USER_ID))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessageContaining("Account Settings")
                .hasMessageContaining("Admin Console");
    }

    @Test
    void theErrorMentionsTheUsersOwnUnusablePreferenceFirstBecauseThatIsWhatTheyMeantToFix() {
        when(users.findById(USER_ID)).thenReturn(Optional.of(userPreferring(LlmProviderType.OPENAI)));

        assertThatThrownBy(() -> resolver.resolve(USER_ID))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessageStartingWith("You have chosen OPENAI")
                .hasMessageContaining("no OPENAI key stored");
    }

    @Test
    void theErrorSaysNothingAboutEncryptionOrResolutionOrder() {
        // Written for the person reading it on a screen. Everything about how keys are stored and in what order they
        // are consulted is true, internal, and useless to them.
        assertThatThrownBy(() -> resolver.resolve(USER_ID))
                .hasMessageNotContaining("encrypt")
                .hasMessageNotContaining("CREDENTIAL_ENCRYPTION_KEY")
                .hasMessageNotContaining("system_config");
    }

    // ---------------------------------------------------------------------
    // What gets built
    // ---------------------------------------------------------------------

    @Test
    void eachProviderTypeBuildsItsOwnImplementation() {
        when(users.findById(USER_ID)).thenReturn(Optional.of(userPreferring(LlmProviderType.ANTHROPIC)));
        when(userCredentials.resolve(USER_ID, LlmProviderType.ANTHROPIC))
                .thenReturn(Optional.of(credential(LlmProviderType.ANTHROPIC, CredentialSource.PERSONAL)));
        assertThat(resolver.resolve(USER_ID).provider()).isInstanceOf(AnthropicLlmProvider.class);

        when(users.findById(USER_ID)).thenReturn(Optional.of(userPreferring(LlmProviderType.OPENAI)));
        when(userCredentials.resolve(USER_ID, LlmProviderType.OPENAI))
                .thenReturn(Optional.of(credential(LlmProviderType.OPENAI, CredentialSource.PERSONAL)));
        assertThat(resolver.resolve(USER_ID).provider()).isInstanceOf(OpenAiLlmProvider.class);
    }

    @Test
    void aProviderIsBuiltFreshForEachResolutionRatherThanCachedWithItsKey() {
        when(systemCredentials.resolve(LlmProviderResolver.SYSTEM_DEFAULT))
                .thenReturn(Optional.of(credential(LlmProviderResolver.SYSTEM_DEFAULT, CredentialSource.ENV_VAR)));

        LlmProvider first = resolver.resolve(USER_ID).provider();
        LlmProvider second = resolver.resolve(USER_ID).provider();

        // Two reasons, and both matter. A cached provider holds a plaintext key in a long-lived object for the life
        // of the process; and a cached provider keeps working after an admin has rotated or revoked the key it was
        // built with.
        assertThat(first).isNotSameAs(second);
    }
}
