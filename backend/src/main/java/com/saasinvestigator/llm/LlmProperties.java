package com.saasinvestigator.llm;

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
    private final int maxPromptChars;

    /**
     * @param anthropicModel model id for Anthropic calls, from {@code ANTHROPIC_MODEL}
     * @param openAiModel model id for OpenAI calls, from {@code OPENAI_MODEL}
     * @param maxPromptChars total source-text budget for one prompt, across every block in it
     */
    public LlmProperties(@Value("${app.llm.anthropic.model}") String anthropicModel,
                         @Value("${app.llm.openai.model}") String openAiModel,
                         @Value("${app.llm.max-prompt-chars:600000}") int maxPromptChars) {
        this.anthropicModel = require(anthropicModel, "app.llm.anthropic.model");
        this.openAiModel = require(openAiModel, "app.llm.openai.model");
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

    private static String require(String value, String property) {
        if (value == null || value.isBlank()) {
            // Fails at startup rather than on the first run: a blank model name produces a provider error from
            // deep inside an SDK call, minutes into someone's first attempt to use the app.
            throw new IllegalStateException(property + " must be set to a model id.");
        }
        return value.trim();
    }
}
