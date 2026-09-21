package com.saasinvestigator.llm;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Model names and prompt-size limits for the two providers.
 *
 * <p>Deliberately does <b>not</b> hold API keys. Keys resolve through {@code credential/} - personal override, then
 * admin override, then env var, then host-mounted file - and are read per call so that pasting a new one takes
 * effect immediately. A key cached in a singleton here would reintroduce exactly the "works on the second attempt"
 * behaviour ADR 0006 argues against, and would give two places to look for the same secret.
 *
 * <p>Model names, by contrast, belong here: they are configuration rather than secrets, they are the same for
 * everyone on the instance, and they are the thing most likely to need changing without a code edit. Providers
 * rename and retire models on their own schedule, so both are plain strings passed straight through to the SDK
 * rather than enum constants - the SDKs accept a raw model string precisely because a pinned dependency should not
 * be able to prevent a newer model from being used.
 */
@Component
public class LlmProperties {

    private static final Logger log = LoggerFactory.getLogger(LlmProperties.class);

    /**
     * Floor for {@link #maxPromptChars()}. A budget below this cannot hold two states of even one small page, so a
     * misconfiguration would silently turn every comparison into a comparison of two truncation markers.
     */
    static final int MIN_PROMPT_CHARS = 20_000;

    private final String anthropicModel;
    private final String openAiModel;
    private final String anthropicBaseUrl;
    private final String openAiBaseUrl;
    private final int maxPromptChars;

    /**
     * @param anthropicModel model id for Anthropic calls, from {@code ANTHROPIC_MODEL}
     * @param openAiModel model id for OpenAI calls, from {@code OPENAI_MODEL}
     * @param anthropicBaseUrl optional endpoint override for Anthropic calls, from {@code ANTHROPIC_BASE_URL}
     * @param openAiBaseUrl optional endpoint override for OpenAI calls, from {@code OPENAI_BASE_URL}
     * @param maxPromptChars total source-text budget for one prompt, across every block in it
     */
    public LlmProperties(@Value("${app.llm.anthropic.model}") String anthropicModel,
                         @Value("${app.llm.openai.model}") String openAiModel,
                         @Value("${app.llm.anthropic.base-url:}") String anthropicBaseUrl,
                         @Value("${app.llm.openai.base-url:}") String openAiBaseUrl,
                         @Value("${app.llm.max-prompt-chars:600000}") int maxPromptChars) {
        this.anthropicModel = require(anthropicModel, "app.llm.anthropic.model");
        this.openAiModel = require(openAiModel, "app.llm.openai.model");
        this.anthropicBaseUrl = optionalUrl(anthropicBaseUrl, "app.llm.anthropic.base-url");
        this.openAiBaseUrl = optionalUrl(openAiBaseUrl, "app.llm.openai.base-url");
        if (maxPromptChars < MIN_PROMPT_CHARS) {
            log.warn("app.llm.max-prompt-chars was {}, which is below the {} floor; using the floor instead.",
                    maxPromptChars, MIN_PROMPT_CHARS);
            this.maxPromptChars = MIN_PROMPT_CHARS;
        } else {
            this.maxPromptChars = maxPromptChars;
        }
    }

    /**
     * @return the Anthropic model id
     */
    public String anthropicModel() {
        return anthropicModel;
    }

    /**
     * @return the OpenAI model id
     */
    public String openAiModel() {
        return openAiModel;
    }

    /**
     * Endpoint override for Anthropic calls, or empty to use the SDK's own default.
     *
     * <p>Empty is the normal case and the reason this returns an {@link Optional} rather than a nullable string: a
     * caller has to decide what to do about absence, and the only correct thing to do is leave the SDK's default
     * alone rather than pass it a blank URL.
     *
     * @return the override, or {@link Optional#empty()} if calls should go to Anthropic directly
     */
    public Optional<String> anthropicBaseUrl() {
        return anthropicBaseUrl.isEmpty() ? Optional.empty() : Optional.of(anthropicBaseUrl);
    }

    /**
     * Endpoint override for OpenAI calls, or empty to use the SDK's own default.
     *
     * @return the override, or {@link Optional#empty()} if calls should go to OpenAI directly
     * @see #anthropicBaseUrl()
     */
    public Optional<String> openAiBaseUrl() {
        return openAiBaseUrl.isEmpty() ? Optional.empty() : Optional.of(openAiBaseUrl);
    }

    /**
     * Total character budget for the source text in one prompt, shared across every block in it.
     *
     * <p>This is a second, coarser limit on top of the crawler's per-source cap, and it exists because the two caps
     * answer different questions. The crawler's {@code max-chars-per-source} stops one runaway site from producing
     * an unbounded snapshot. This one stops a product with <em>many</em> reasonable sources from producing an
     * unusable prompt: eight sources at the crawler's 200k ceiling, each with a prior state to compare against, is
     * over three million characters, which no current model would accept and no useful analysis needs.
     *
     * <p>The default is set well inside today's large context windows rather than at their edge, on the grounds that
     * the useful signal in a changelog is nowhere near a million characters and paying for the rest of the window
     * to be filled with navigation chrome is not analysis. Raise it with {@code app.llm.max-prompt-chars} if a
     * genuinely large product needs it.
     *
     * @return the budget in characters, never below {@link #MIN_PROMPT_CHARS}
     */
    public int maxPromptChars() {
        return maxPromptChars;
    }

    /**
     * Validates an optional endpoint override, returning {@code ""} for "not set".
     *
     * <p>Fails at startup on a malformed URL for the same reason {@link #require} does on a blank model name: the
     * alternative is an SDK-level failure on someone's first run, minutes in, pointing at a stack frame rather than
     * at the env var that caused it. A scheme is required because a host-only value like {@code gateway:8080} is
     * accepted by {@link URI} and then resolved as a scheme, which fails much later and much less clearly.
     */
    private static String optionalUrl(String value, String property) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        try {
            URI uri = new URI(trimmed);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalStateException(
                        property + " must be an absolute URL including scheme and host, e.g. "
                                + "http://localhost:8081 - was: " + trimmed);
            }
        } catch (URISyntaxException e) {
            throw new IllegalStateException(property + " is not a valid URL: " + trimmed, e);
        }
        // Logged at startup because an instance silently talking to something other than the provider's own API is
        // the kind of thing an operator should be able to confirm from the logs rather than by reading env vars.
        log.info("{} is set; LLM calls for this provider will go to {} instead of the provider's default endpoint.",
                property, trimmed);
        return trimmed;
    }

    private static String require(String value, String property) {
        if (value == null || value.isBlank()) {
            // Fails at startup rather than on the first run: a blank model name produces a provider error from
            // deep inside an SDK call, minutes into someone's first attempt to use the app.
            throw new IllegalStateException(property + " must be set to a model id.");
        }
        return value.trim();
    }
}
