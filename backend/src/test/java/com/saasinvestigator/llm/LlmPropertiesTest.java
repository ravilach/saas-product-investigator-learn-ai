package com.saasinvestigator.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Tests the configuration this class validates at startup rather than at first use.
 *
 * <p>All of it is startup validation, which is the only reason it needs tests: the whole point of rejecting a bad
 * value in the constructor is that nobody finds out about it from an SDK stack trace twenty minutes into a NUCLEAR
 * run, so a regression here would not show up as a failing run - it would show up as a much later, much less
 * legible failure.
 *
 * <p>The base-URL pair gets the most attention because it is the one knob that can silently redirect every LLM call
 * this application makes. "Blank means the provider's own endpoint" therefore has to be a tested guarantee and not
 * an implementation detail: the difference between an empty override and an override of {@code ""} is the difference
 * between talking to Anthropic and failing every run.
 */
class LlmPropertiesTest {

    private static LlmProperties withBaseUrls(String anthropic, String openAi) {
        return new LlmProperties("claude-sonnet-5", "gpt-6-astra", anthropic, openAi, 600_000);
    }

    @Test
    void anUnsetBaseUrlIsAbsentRatherThanBlank() {
        LlmProperties properties = withBaseUrls("", "");

        // Absent, not present-and-empty. A provider that passed "" to its SDK's baseUrl would break every call.
        assertThat(properties.anthropicBaseUrl()).isEmpty();
        assertThat(properties.openAiBaseUrl()).isEmpty();
    }

    @Test
    void aBaseUrlOfOnlyWhitespaceIsTreatedAsUnset() {
        // Easier to hit than it looks: a trailing space in a .env line, or an env var set to "" by a shell script.
        LlmProperties properties = withBaseUrls("   ", "\t");

        assertThat(properties.anthropicBaseUrl()).isEmpty();
        assertThat(properties.openAiBaseUrl()).isEmpty();
    }

    @Test
    void aConfiguredBaseUrlIsExposedTrimmed() {
        LlmProperties properties = withBaseUrls("  http://localhost:8081  ", "https://gateway.internal/v1");

        assertThat(properties.anthropicBaseUrl()).contains("http://localhost:8081");
        assertThat(properties.openAiBaseUrl()).contains("https://gateway.internal/v1");
    }

    @Test
    void eachProviderGetsOnlyItsOwnOverride() {
        // The failure this guards against is a copy-paste one, and it fails in the worst possible way: every
        // Anthropic call quietly going to an OpenAI-compatible gateway that answers with a different shape.
        LlmProperties properties = withBaseUrls("http://localhost:8081", "");

        assertThat(properties.anthropicBaseUrl()).contains("http://localhost:8081");
        assertThat(properties.openAiBaseUrl()).isEmpty();
    }

    @Test
    void aBaseUrlWithoutASchemeIsRejectedAtStartup() {
        // URI accepts "gateway:8080" - as scheme "gateway" - so this is a real trap rather than a defensive check.
        assertThatThrownBy(() -> withBaseUrls("gateway:8080", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.llm.anthropic.base-url")
                .hasMessageContaining("absolute URL");
    }

    @Test
    void aMalformedBaseUrlIsRejectedAtStartupAndNamesTheProperty() {
        assertThatThrownBy(() -> withBaseUrls("", "http://has a space/v1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.llm.openai.base-url");
    }

    @Test
    void aBlankModelNameIsRejectedAtStartup() {
        assertThatThrownBy(() -> new LlmProperties(" ", "gpt-6-astra", "", "", 600_000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.llm.anthropic.model");
    }

    @Test
    void aPromptBudgetBelowTheFloorIsClampedRatherThanRejected() {
        // Clamped, not thrown: a too-small budget has a safe interpretation, unlike a missing model name.
        LlmProperties properties = new LlmProperties("claude-sonnet-5", "gpt-6-astra", "", "", 1_000);

        assertThat(properties.maxPromptChars()).isEqualTo(LlmProperties.MIN_PROMPT_CHARS);
    }

    @Test
    void aPromptBudgetAtOrAboveTheFloorIsKept() {
        assertThatCode(() -> assertThat(
                new LlmProperties("claude-sonnet-5", "gpt-6-astra", "", "", LlmProperties.MIN_PROMPT_CHARS)
                        .maxPromptChars())
                .isEqualTo(LlmProperties.MIN_PROMPT_CHARS))
                .doesNotThrowAnyException();
    }
}
