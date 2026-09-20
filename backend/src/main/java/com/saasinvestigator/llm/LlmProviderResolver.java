package com.saasinvestigator.llm;

import com.saasinvestigator.credential.CredentialSource;
import com.saasinvestigator.credential.ResolvedCredential;
import com.saasinvestigator.credential.SystemCredentialService;
import com.saasinvestigator.credential.UserCredentialService;
import com.saasinvestigator.error.ProviderUnavailableException;
import com.saasinvestigator.user.User;
import com.saasinvestigator.user.UserRepository;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Decides which provider serves a run, and with whose key.
 *
 * <p>Two steps, in this order:
 *
 * <ol>
 *   <li>If the initiating user has set a {@code preferredLlmProvider} <em>and</em> has a stored key for it, use
 *       their provider with their key. Both halves are required: a preference without a key is an intention, not a
 *       usable credential, and honouring it anyway would fail every run with "no API key" until they noticed.
 *   <li>Otherwise use the system-wide default - always Anthropic - with the system key, itself resolved admin
 *       override, then environment, then host-mounted file.
 * </ol>
 *
 * <p>The fallback is silent to the user by design but not to the log: someone who set OpenAI as their preference and
 * then had their run served by Anthropic should be able to find out why, and the run metadata carries which provider
 * actually ran so the UI can say so rather than implying the preference was honoured.
 *
 * <p>Returns a <em>constructed</em> provider rather than picking from a set of beans, because a provider instance is
 * bound to one plaintext API key and must not outlive the call that needed it. The key is fetched here, handed to a
 * new instance, and becomes garbage when the run ends; nothing caches it, which is also why a user who pastes a
 * corrected key does not have to restart anything for it to take effect.
 */
@Service
public class LlmProviderResolver {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderResolver.class);

    /**
     * The system-wide default provider.
     *
     * <p>Fixed rather than configurable, and Anthropic rather than "whichever key happens to be set". A default that
     * moved with configuration would mean two instances of this application producing differently-shaped reports
     * from the same sources with nothing in the UI explaining why, and a user with no preference has no way to ask
     * which one they got. If the system key for it is missing, the answer is an actionable error, not a quiet
     * substitution.
     */
    public static final LlmProviderType SYSTEM_DEFAULT = LlmProviderType.ANTHROPIC;

    private final UserRepository userRepository;
    private final UserCredentialService userCredentials;
    private final SystemCredentialService systemCredentials;
    private final LlmProperties properties;
    private final PromptBuilder promptBuilder;
    private final ChangeReportJsonParser parser;

    public LlmProviderResolver(UserRepository userRepository,
                               UserCredentialService userCredentials,
                               SystemCredentialService systemCredentials,
                               LlmProperties properties,
                               PromptBuilder promptBuilder,
                               ChangeReportJsonParser parser) {
        this.userRepository = userRepository;
        this.userCredentials = userCredentials;
        this.systemCredentials = systemCredentials;
        this.properties = properties;
        this.promptBuilder = promptBuilder;
        this.parser = parser;
    }

    /**
     * Resolves the provider to use on behalf of one user.
     *
     * @param userId the user who triggered the run, ask, or compare
     * @return a provider ready to call, plus where its key came from
     * @throws ProviderUnavailableException if no key is available from any channel. The message is written for the
     *     person reading it on screen - it names the provider, says which of the two places they can fix it, and
     *     does not mention encryption, resolution order, or anything else they cannot act on.
     */
    public ResolvedProvider resolve(String userId) {
        Optional<LlmProviderType> preference = userRepository.findById(userId).map(User::getPreferredLlmProvider);

        if (preference.isPresent()) {
            LlmProviderType preferred = preference.get();
            Optional<ResolvedCredential> personal = userCredentials.resolve(userId, preferred);
            if (personal.isPresent()) {
                return new ResolvedProvider(build(preferred, personal.get().apiKey()), CredentialSource.PERSONAL);
            }
            log.info("User {} prefers {} but has no stored key for it; falling back to the system default ({}).",
                    userId, preferred, SYSTEM_DEFAULT);
        }

        ResolvedCredential system = systemCredentials.resolve(SYSTEM_DEFAULT)
                .orElseThrow(() -> new ProviderUnavailableException(noKeyMessage(preference.orElse(null))));
        return new ResolvedProvider(build(SYSTEM_DEFAULT, system.apiKey()), system.source());
    }

    /**
     * Builds a provider bound to one key.
     *
     * <p>A {@code switch} over the enum rather than a registry, so that adding a provider is a compile error here
     * until it is wired - which is the whole point of the {@code add-llm-provider} checklist. A registry would let a
     * new provider be added and silently never selected.
     */
    private LlmProvider build(LlmProviderType type, String apiKey) {
        return switch (type) {
            case ANTHROPIC -> new AnthropicLlmProvider(apiKey, properties, promptBuilder, parser);
            case OPENAI -> new OpenAiLlmProvider(apiKey, properties, promptBuilder, parser);
        };
    }

    private static String noKeyMessage(LlmProviderType unusablePreference) {
        String base = "No " + SYSTEM_DEFAULT + " API key is configured, so this analysis cannot run. "
                + "Add your own key under Account Settings, or ask an administrator to set a system-wide key in "
                + "the Admin Console.";
        if (unusablePreference == null) {
            return base;
        }
        // Their preference is the more likely thing they meant to fix, so say so first.
        return "You have chosen " + unusablePreference + " as your provider but have no " + unusablePreference
                + " key stored, and no system-wide " + SYSTEM_DEFAULT + " key is configured either. "
                + "Add a key under Account Settings, or ask an administrator to set a system-wide key in the "
                + "Admin Console.";
    }

    /**
     * A provider ready to call, and where its key came from.
     *
     * @param provider the bound provider; valid only for the duration of the call that resolved it
     * @param credentialSource which channel supplied the key, for run metadata and for answering "which key was
     *     used?" when a provider rejects it
     */
    public record ResolvedProvider(LlmProvider provider, CredentialSource credentialSource) {

        /** @return which provider actually served the call, which is not always the user's preference */
        public LlmProviderType type() {
            return provider.type();
        }
    }
}
