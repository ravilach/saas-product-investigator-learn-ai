package com.saasinvestigator.credential;

import com.saasinvestigator.crypto.CryptoService;
import com.saasinvestigator.llm.LlmProviderType;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The system-wide API key for each provider, and the three-channel chain that resolves it.
 *
 * <p>Resolution order, highest priority first:
 *
 * <ol>
 *   <li><b>{@link CredentialSource#OVERRIDE}</b> - an admin-set key in {@code system_llm_credentials},
 *       encrypted at rest.
 *   <li><b>{@link CredentialSource#ENV_VAR}</b> - {@code ANTHROPIC_API_KEY} / {@code OPENAI_API_KEY},
 *       however they arrive: env var, properties file, Kubernetes {@code Secret}, ECS task-definition secret.
 *   <li><b>{@link CredentialSource#HOST_MOUNT}</b> - the bind-mounted file, Anthropic only. See
 *       {@link HostCredentialLoader}.
 * </ol>
 *
 * <p>This is one instance of the application-wide secrets pattern - <em>Admin Console override &gt; explicit
 * external config &gt; automatic fallback</em> - which also governs the JWT signing secret
 * ({@code JwtSecretResolver}) and per-source MCP auth tokens. The ordering puts the UI on top for a specific
 * reason: the operator who most needs to fix a key is the one whose container was deployed with a wrong or
 * rotated one, and they are precisely the person who cannot conveniently change its environment.
 *
 * <p><b>Nothing here is cached.</b> Unlike the JWT secret - which is read on every authenticated request and
 * therefore has a short TTL cache - a provider key is read once per run or ask, which is already an
 * operation measured in seconds of LLM latency. One Mongo read against a unique index is not worth the
 * staleness: a key pasted into the Secrets tab takes effect on the very next run, on every replica, with no
 * invalidation to get wrong.
 */
@Service
public class SystemCredentialService {

    private static final Logger log = LoggerFactory.getLogger(SystemCredentialService.class);

    private final SystemLlmCredentialRepository repository;
    private final CryptoService cryptoService;
    private final HostCredentialLoader hostCredentials;
    private final Map<LlmProviderType, String> envKeys;

    /**
     * @param repository the {@code system_llm_credentials} repository
     * @param cryptoService encrypts overrides at rest and produces {@code last4} for display
     * @param hostCredentials the quick-boot host-mounted Anthropic key, if any
     * @param anthropicEnvKey {@code ANTHROPIC_API_KEY}, blank when unset
     * @param openAiEnvKey {@code OPENAI_API_KEY}, blank when unset
     */
    public SystemCredentialService(SystemLlmCredentialRepository repository,
                                   CryptoService cryptoService,
                                   HostCredentialLoader hostCredentials,
                                   @Value("${app.llm.anthropic.api-key:}") String anthropicEnvKey,
                                   @Value("${app.llm.openai.api-key:}") String openAiEnvKey) {
        this.repository = repository;
        this.cryptoService = cryptoService;
        this.hostCredentials = hostCredentials;
        this.envKeys = Map.of(
                LlmProviderType.ANTHROPIC, trimmed(anthropicEnvKey),
                LlmProviderType.OPENAI, trimmed(openAiEnvKey));
    }

    /**
     * Resolves the system-wide key for a provider by walking the chain.
     *
     * @param provider which provider
     * @return the winning credential, or empty if no channel supplied one
     */
    public Optional<ResolvedCredential> resolve(LlmProviderType provider) {
        Optional<String> override = storedOverride(provider);
        if (override.isPresent()) {
            return override.map(key -> new ResolvedCredential(provider, key, CredentialSource.OVERRIDE));
        }
        String fromEnv = envKeys.getOrDefault(provider, "");
        if (!fromEnv.isEmpty()) {
            return Optional.of(new ResolvedCredential(provider, fromEnv, CredentialSource.ENV_VAR));
        }
        if (provider == LlmProviderType.ANTHROPIC) {
            return hostCredentials.anthropicKey()
                    .map(key -> new ResolvedCredential(provider, key, CredentialSource.HOST_MOUNT));
        }
        return Optional.empty();
    }

    /**
     * Reports what is configured for every provider, for the Admin Console's Secrets tab.
     *
     * <p>Every provider gets a row, including ones with nothing set, so the tab shows a complete picture
     * rather than only the good news.
     *
     * @return one status per {@link LlmProviderType}, in declaration order
     */
    public List<SystemCredentialStatus> statuses() {
        return Arrays.stream(LlmProviderType.values())
                .map(provider -> resolve(provider)
                        .map(resolved -> new SystemCredentialStatus(provider, true,
                                cryptoService.last4(resolved.apiKey()), resolved.source()))
                        .orElseGet(() -> SystemCredentialStatus.none(provider)))
                .toList();
    }

    /**
     * Sets or replaces the admin override for a provider.
     *
     * @param provider which provider
     * @param plaintextApiKey the key, encrypted here and then discarded
     * @param actorUsername the admin setting it, recorded on the document alongside the audit entry
     * @return the resulting status, which will report {@link CredentialSource#OVERRIDE}
     */
    public SystemCredentialStatus setOverride(LlmProviderType provider, String plaintextApiKey,
                                              String actorUsername) {
        SystemLlmCredential credential = repository.findByProvider(provider)
                .orElseGet(() -> new SystemLlmCredential(provider, null, actorUsername));
        credential.setApiKeyEncrypted(cryptoService.encrypt(plaintextApiKey));
        credential.setUpdatedBy(actorUsername);
        credential.setUpdatedAt(Instant.now());
        repository.save(credential);
        log.info("The system-wide {} API key was set from the Admin Console by {}. It now takes precedence "
                + "over the environment variable and the host-mounted file.", provider, actorUsername);
        return new SystemCredentialStatus(provider, true, cryptoService.last4(plaintextApiKey),
                CredentialSource.OVERRIDE);
    }

    /**
     * Clears the admin override for a provider, reverting to env-var then host-mount resolution.
     *
     * @param provider which provider
     * @return {@code true} if an override was actually removed. The caller responds {@code 204} either way -
     *     clearing something already clear is the state the client asked for - but only audit-logs a real
     *     change.
     */
    public boolean clearOverride(LlmProviderType provider) {
        boolean removed = repository.deleteByProvider(provider) > 0;
        if (removed) {
            log.info("The system-wide {} API key override was cleared. Resolution falls back to the "
                    + "environment variable, then the host-mounted file.", provider);
        }
        return removed;
    }

    /**
     * @param provider which provider
     * @return {@code true} if an admin override document exists, without decrypting it
     */
    public boolean hasOverride(LlmProviderType provider) {
        return repository.findByProvider(provider).isPresent();
    }

    private Optional<String> storedOverride(LlmProviderType provider) {
        return repository.findByProvider(provider).flatMap(credential -> {
            try {
                return Optional.of(cryptoService.decrypt(credential.getApiKeyEncrypted()));
            } catch (IllegalStateException e) {
                // Falls through to the next channel rather than failing: an override that cannot be read
                // is not an override, and an env var that still works should keep the app working.
                log.error("The system-wide {} API key override could not be decrypted and is being "
                        + "skipped. The most likely cause is that CREDENTIAL_ENCRYPTION_KEY changed; "
                        + "re-enter the key in Admin Console -> Secrets.", provider, e);
                return Optional.empty();
            }
        });
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
