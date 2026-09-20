package com.saasinvestigator.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saasinvestigator.crypto.CryptoService;
import com.saasinvestigator.llm.LlmProviderType;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests the personal BYOK credential store.
 *
 * <p>A real {@link CryptoService} is used rather than a mock. The single most important property of this class -
 * that what reaches the repository is not the key the user typed - is only meaningfully asserted against real
 * encryption; a stubbed {@code encrypt} would make the test agree with itself.
 */
class UserCredentialServiceTest {

    private static final String USER = "user-1";
    private static final String KEY = "sk-ant-api03-personal-key-0000wxyz";

    private final CryptoService crypto = new CryptoService("test-encryption-key-please-do-not-reuse");
    private UserLlmCredentialRepository repository;
    private UserCredentialService service;

    @BeforeEach
    void setUp() {
        repository = mock(UserLlmCredentialRepository.class);
        when(repository.findByUserIdAndProvider(any(), any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new UserCredentialService(repository, crypto);
    }

    private UserLlmCredential stored(LlmProviderType provider, String plaintext) {
        UserLlmCredential credential = new UserLlmCredential(USER, provider, crypto.encrypt(plaintext));
        credential.setId("cred-" + provider);
        return credential;
    }

    @Test
    void storesTheKeyEncryptedAndReturnsOnlyItsTail() {
        CredentialStatus status = service.store(USER, LlmProviderType.ANTHROPIC, KEY);

        ArgumentCaptor<UserLlmCredential> saved = ArgumentCaptor.forClass(UserLlmCredential.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getApiKeyEncrypted()).isNotEqualTo(KEY).doesNotContain(KEY);
        assertThat(crypto.decrypt(saved.getValue().getApiKeyEncrypted())).isEqualTo(KEY);
        assertThat(saved.getValue().getUserId()).isEqualTo(USER);
        assertThat(saved.getValue().getCreatedAt()).isNotNull();

        assertThat(status.configured()).isTrue();
        assertThat(status.last4()).isEqualTo("wxyz");
    }

    @Test
    void replacingAKeyUpdatesTheExistingRowRatherThanAddingASecond() {
        // The unique index on (userId, provider) would reject a second row, so a find-then-save is not an
        // optimisation here - getting it wrong turns "paste a new key" into a 500.
        UserLlmCredential existing = stored(LlmProviderType.ANTHROPIC, "sk-ant-the-old-key");
        when(repository.findByUserIdAndProvider(USER, LlmProviderType.ANTHROPIC))
                .thenReturn(Optional.of(existing));

        service.store(USER, LlmProviderType.ANTHROPIC, KEY);

        ArgumentCaptor<UserLlmCredential> saved = ArgumentCaptor.forClass(UserLlmCredential.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo("cred-ANTHROPIC");
        assertThat(saved.getValue().getUpdatedAt()).isNotNull();
        assertThat(crypto.decrypt(saved.getValue().getApiKeyEncrypted())).isEqualTo(KEY);
    }

    @Test
    void listsOneStatusPerStoredKeyAndNeverTheKeyItself() {
        when(repository.findByUserId(USER)).thenReturn(List.of(
                stored(LlmProviderType.OPENAI, "sk-openai-key-abcd"),
                stored(LlmProviderType.ANTHROPIC, KEY)));

        List<CredentialStatus> statuses = service.list(USER);

        assertThat(statuses).extracting(CredentialStatus::provider)
                .containsExactly(LlmProviderType.ANTHROPIC, LlmProviderType.OPENAI);
        assertThat(statuses).extracting(CredentialStatus::last4).containsExactly("wxyz", "abcd");
        assertThat(statuses).allSatisfy(status -> assertThat(status.configured()).isTrue());
    }

    @Test
    void anUndecryptableKeyStillListsRatherThanBreakingTheWholeAccountPage() {
        // This is what a rotated CREDENTIAL_ENCRYPTION_KEY looks like from the inside. The user needs to be
        // able to reach the page in order to re-enter the key, so the row reports as masked rather than
        // failing the request.
        UserLlmCredential corrupt = new UserLlmCredential(USER, LlmProviderType.ANTHROPIC, "not-ciphertext");
        when(repository.findByUserId(USER)).thenReturn(List.of(corrupt));

        assertThat(service.list(USER)).singleElement()
                .satisfies(status -> assertThat(status.last4()).isEqualTo("****"));
    }

    @Test
    void resolvesToAPlaintextCredentialTaggedAsPersonal() {
        when(repository.findByUserIdAndProvider(USER, LlmProviderType.ANTHROPIC))
                .thenReturn(Optional.of(stored(LlmProviderType.ANTHROPIC, KEY)));

        Optional<ResolvedCredential> resolved = service.resolve(USER, LlmProviderType.ANTHROPIC);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().apiKey()).isEqualTo(KEY);
        assertThat(resolved.get().source()).isEqualTo(CredentialSource.PERSONAL);
    }

    @Test
    void resolvedCredentialsToStringNeverContainsTheKey() {
        // A ResolvedCredential can end up inside a log line, an exception message, or a debugger's variable
        // pane. A record's generated toString would print every component, including this one.
        ResolvedCredential credential =
                new ResolvedCredential(LlmProviderType.ANTHROPIC, KEY, CredentialSource.PERSONAL);

        assertThat(credential.toString()).doesNotContain(KEY).contains("apiKey=***");
    }

    @Test
    void resolvesToEmptyWhenTheKeyCannotBeDecrypted() {
        UserLlmCredential corrupt = new UserLlmCredential(USER, LlmProviderType.ANTHROPIC, "not-ciphertext");
        when(repository.findByUserIdAndProvider(USER, LlmProviderType.ANTHROPIC))
                .thenReturn(Optional.of(corrupt));

        // Empty, not an exception: the caller then falls through to the system-wide key, which is a better
        // outcome for the user than a failed run.
        assertThat(service.resolve(USER, LlmProviderType.ANTHROPIC)).isEmpty();
    }

    @Test
    void resolvesToEmptyWhenNothingIsStored() {
        assertThat(service.resolve(USER, LlmProviderType.OPENAI)).isEmpty();
        assertThat(service.has(USER, LlmProviderType.OPENAI)).isFalse();
    }

    @Test
    void removeReportsWhetherItActuallyRemovedSomething() {
        when(repository.deleteByUserIdAndProvider(USER, LlmProviderType.ANTHROPIC)).thenReturn(1L);
        when(repository.deleteByUserIdAndProvider(USER, LlmProviderType.OPENAI)).thenReturn(0L);

        // The distinction is what keeps the audit log a record of changes rather than of button presses.
        assertThat(service.remove(USER, LlmProviderType.ANTHROPIC)).isTrue();
        assertThat(service.remove(USER, LlmProviderType.OPENAI)).isFalse();
    }

    @Test
    void forgettingAUserDiscardsEveryKeyTheyHad() {
        when(repository.deleteByUserId(USER)).thenReturn(2L);

        assertThat(service.forgetAll(USER)).isEqualTo(2L);
        verify(repository).deleteByUserId(USER);
    }
}
