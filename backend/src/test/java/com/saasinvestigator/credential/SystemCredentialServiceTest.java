package com.saasinvestigator.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saasinvestigator.crypto.CryptoService;
import com.saasinvestigator.llm.LlmProviderType;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests the system-wide credential resolution chain: admin override, then the environment variable, then the
 * host-mounted file.
 *
 * <p>The order is the whole point of the class, so most of these tests set up two or three channels at once and
 * assert which one wins. Each channel in isolation would pass with the order reversed.
 */
class SystemCredentialServiceTest {

    private static final String OVERRIDE_KEY = "sk-ant-override-key-0000ovrd";
    private static final String ENV_KEY = "sk-ant-env-var-key-000000benv";
    private static final String HOST_KEY = "sk-ant-host-mount-key-00host";

    private final CryptoService crypto = new CryptoService("test-encryption-key-please-do-not-reuse");
    private SystemLlmCredentialRepository repository;
    private HostCredentialLoader hostLoader;

    @BeforeEach
    void setUp() {
        repository = mock(SystemLlmCredentialRepository.class);
        when(repository.findByProvider(any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        hostLoader = mock(HostCredentialLoader.class);
        when(hostLoader.anthropicKey()).thenReturn(Optional.empty());
    }

    private SystemCredentialService service(String anthropicEnvKey, String openAiEnvKey) {
        return new SystemCredentialService(repository, crypto, hostLoader, anthropicEnvKey, openAiEnvKey);
    }

    private void storedOverride(LlmProviderType provider, String plaintext) {
        when(repository.findByProvider(provider)).thenReturn(Optional.of(
                new SystemLlmCredential(provider, crypto.encrypt(plaintext), "admin")));
    }

    @Test
    void theAdminOverrideBeatsBothOtherChannels() {
        // The reason this order exists: an operator whose container was deployed with a key that has since
        // been rotated can fix it from the UI, without a redeploy and without shell access.
        storedOverride(LlmProviderType.ANTHROPIC, OVERRIDE_KEY);
        when(hostLoader.anthropicKey()).thenReturn(Optional.of(HOST_KEY));

        Optional<ResolvedCredential> resolved = service(ENV_KEY, "").resolve(LlmProviderType.ANTHROPIC);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().apiKey()).isEqualTo(OVERRIDE_KEY);
        assertThat(resolved.get().source()).isEqualTo(CredentialSource.OVERRIDE);
    }

    @Test
    void theEnvironmentVariableBeatsTheHostMount() {
        when(hostLoader.anthropicKey()).thenReturn(Optional.of(HOST_KEY));

        Optional<ResolvedCredential> resolved = service(ENV_KEY, "").resolve(LlmProviderType.ANTHROPIC);

        assertThat(resolved.orElseThrow().apiKey()).isEqualTo(ENV_KEY);
        assertThat(resolved.orElseThrow().source()).isEqualTo(CredentialSource.ENV_VAR);
    }

    @Test
    void theHostMountIsUsedWhenNothingElseIsConfigured() {
        when(hostLoader.anthropicKey()).thenReturn(Optional.of(HOST_KEY));

        Optional<ResolvedCredential> resolved = service("", "").resolve(LlmProviderType.ANTHROPIC);

        assertThat(resolved.orElseThrow().apiKey()).isEqualTo(HOST_KEY);
        assertThat(resolved.orElseThrow().source()).isEqualTo(CredentialSource.HOST_MOUNT);
    }

    @Test
    void theHostMountIsAnthropicOnly() {
        // The mount is a single well-known path holding an Anthropic key, because Anthropic is the system-wide
        // default provider. An OpenAI key arriving from that file would be a silent mix-up.
        when(hostLoader.anthropicKey()).thenReturn(Optional.of(HOST_KEY));

        assertThat(service("", "").resolve(LlmProviderType.OPENAI)).isEmpty();
    }

    @Test
    void resolvesToEmptyWhenNoChannelHasAKey() {
        // Empty rather than an exception: the caller turns this into an actionable message naming the three
        // places a key can be put, which is more useful than a stack trace.
        assertThat(service("", "").resolve(LlmProviderType.ANTHROPIC)).isEmpty();
        assertThat(service("   ", "").resolve(LlmProviderType.ANTHROPIC)).isEmpty();
    }

    @Test
    void anUnreadableOverrideFallsThroughToTheNextChannel() {
        // An override that cannot be decrypted is not an override. Treating it as one would strand an
        // operator who rotated CREDENTIAL_ENCRYPTION_KEY with a system that refuses to use its own env var.
        when(repository.findByProvider(LlmProviderType.ANTHROPIC)).thenReturn(Optional.of(
                new SystemLlmCredential(LlmProviderType.ANTHROPIC, "not-ciphertext", "admin")));

        Optional<ResolvedCredential> resolved = service(ENV_KEY, "").resolve(LlmProviderType.ANTHROPIC);

        assertThat(resolved.orElseThrow().source()).isEqualTo(CredentialSource.ENV_VAR);
    }

    @Test
    void reportsOneStatusPerProviderWithItsWinningSource() {
        storedOverride(LlmProviderType.ANTHROPIC, OVERRIDE_KEY);

        var statuses = service("", "sk-openai-env-key-000000abcd").statuses();

        assertThat(statuses).extracting(SystemCredentialStatus::provider)
                .containsExactly(LlmProviderType.ANTHROPIC, LlmProviderType.OPENAI);
        assertThat(statuses.get(0).source()).isEqualTo(CredentialSource.OVERRIDE);
        assertThat(statuses.get(0).last4()).isEqualTo("ovrd");
        assertThat(statuses.get(1).source()).isEqualTo(CredentialSource.ENV_VAR);
        assertThat(statuses.get(1).last4()).isEqualTo("abcd");
    }

    @Test
    void reportsAnUnconfiguredProviderAsSuch() {
        var statuses = service("", "").statuses();

        assertThat(statuses).allSatisfy(status -> {
            assertThat(status.configured()).isFalse();
            assertThat(status.source()).isEqualTo(CredentialSource.NONE);
        });
    }

    @Test
    void noStatusEverReportsAPersonalKey() {
        // PERSONAL is a member of CredentialSource so that run metadata has one vocabulary for provenance,
        // but this endpoint is the admin's view of system-wide keys - another user's key must not appear in it
        // under any source.
        storedOverride(LlmProviderType.ANTHROPIC, OVERRIDE_KEY);

        assertThat(service(ENV_KEY, ENV_KEY).statuses())
                .noneMatch(status -> status.source() == CredentialSource.PERSONAL);
    }

    @Test
    void settingAnOverrideEncryptsItAndRecordsWhoDidIt() {
        SystemCredentialStatus status =
                service("", "").setOverride(LlmProviderType.ANTHROPIC, OVERRIDE_KEY, "admin");

        ArgumentCaptor<SystemLlmCredential> saved = ArgumentCaptor.forClass(SystemLlmCredential.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getApiKeyEncrypted()).doesNotContain(OVERRIDE_KEY);
        assertThat(crypto.decrypt(saved.getValue().getApiKeyEncrypted())).isEqualTo(OVERRIDE_KEY);
        assertThat(saved.getValue().getUpdatedBy()).isEqualTo("admin");
        assertThat(saved.getValue().getUpdatedAt()).isNotNull();

        assertThat(status.source()).isEqualTo(CredentialSource.OVERRIDE);
        assertThat(status.last4()).isEqualTo("ovrd");
    }

    @Test
    void settingAnOverrideTwiceUpdatesTheSameRow() {
        storedOverride(LlmProviderType.ANTHROPIC, "sk-ant-the-previous-override");

        service("", "").setOverride(LlmProviderType.ANTHROPIC, OVERRIDE_KEY, "admin");

        ArgumentCaptor<SystemLlmCredential> saved = ArgumentCaptor.forClass(SystemLlmCredential.class);
        verify(repository).save(saved.capture());
        assertThat(crypto.decrypt(saved.getValue().getApiKeyEncrypted())).isEqualTo(OVERRIDE_KEY);
    }

    @Test
    void clearingAnOverrideReportsWhetherThereWasOne() {
        when(repository.deleteByProvider(LlmProviderType.ANTHROPIC)).thenReturn(1L);
        when(repository.deleteByProvider(LlmProviderType.OPENAI)).thenReturn(0L);
        SystemCredentialService service = service("", "");

        assertThat(service.clearOverride(LlmProviderType.ANTHROPIC)).isTrue();
        assertThat(service.clearOverride(LlmProviderType.OPENAI)).isFalse();
    }

    @Test
    void clearingAnOverrideRevealsTheChannelUnderneath() {
        // Nothing is cached, so the env var takes effect on the next resolve rather than after a restart.
        SystemCredentialService service = service(ENV_KEY, "");
        storedOverride(LlmProviderType.ANTHROPIC, OVERRIDE_KEY);
        assertThat(service.resolve(LlmProviderType.ANTHROPIC).orElseThrow().apiKey()).isEqualTo(OVERRIDE_KEY);

        when(repository.findByProvider(LlmProviderType.ANTHROPIC)).thenReturn(Optional.empty());

        assertThat(service.resolve(LlmProviderType.ANTHROPIC).orElseThrow().apiKey()).isEqualTo(ENV_KEY);
    }

    @Test
    void hasOverrideDoesNotDependOnBeingAbleToDecryptIt() {
        // "Is there an override row" and "can it be used" are different questions. The Admin Console needs the
        // first one to decide whether to offer a Clear button, and a corrupt row is exactly when clearing it
        // is the thing the operator needs to do.
        when(repository.findByProvider(LlmProviderType.ANTHROPIC)).thenReturn(Optional.of(
                new SystemLlmCredential(LlmProviderType.ANTHROPIC, "not-ciphertext", "admin")));

        assertThat(service("", "").hasOverride(LlmProviderType.ANTHROPIC)).isTrue();
    }
}
