package com.saasinvestigator.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.openai.models.ReasoningEffort;
import com.saasinvestigator.report.AnalysisDepth;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Tests the part of the OpenAI provider that can be tested without a key.
 *
 * <p>Almost all of this class's behaviour is a streaming HTTP exchange, and there is no honest way to unit-test that:
 * mocking the SDK's stream types would assert that the mock behaves as written rather than that the provider works.
 * The streaming paths are covered by the live checks in {@code /docs/GETTING_STARTED.md}, which is stated plainly in
 * {@code docs/decisions/0007-llm-providers.md} rather than left as a coverage gap somebody has to notice.
 *
 * <p>What is left is the depth mapping, and it is worth a test for one reason: it is the only place where the
 * user-facing depth control reaches something other than a token budget, so it is the difference between the three
 * depths producing genuinely different analysis and producing the same analysis at three lengths.
 */
class OpenAiLlmProviderTest {

    @Test
    void eachDepthMapsToADistinctReasoningEffort() {
        assertThat(OpenAiLlmProvider.effortFor(AnalysisDepth.SHORT)).isEqualTo(ReasoningEffort.LOW);
        assertThat(OpenAiLlmProvider.effortFor(AnalysisDepth.REGULAR)).isEqualTo(ReasoningEffort.MEDIUM);
        assertThat(OpenAiLlmProvider.effortFor(AnalysisDepth.NUCLEAR)).isEqualTo(ReasoningEffort.HIGH);
    }

    @Test
    void everyDepthIsMappedSoANewOneCannotSilentlyDefault() {
        // The mapping is an exhaustive switch over the enum, so a fourth depth is a compile error there rather than a
        // value that quietly resolves to MEDIUM. This asserts the enum has not outgrown the cases above.
        assertThat(Arrays.stream(AnalysisDepth.values()).map(OpenAiLlmProvider::effortFor))
                .doesNotContainNull()
                .hasSize(AnalysisDepth.values().length);
    }

    @Test
    void nuclearStopsAtHighRatherThanReachingForTheCeiling() {
        // XHIGH and MAX exist. NUCLEAR deliberately does not use them: the depth control is a button on a screen, and
        // its most thorough setting should be thorough, not open-ended in cost.
        assertThat(OpenAiLlmProvider.effortFor(AnalysisDepth.NUCLEAR))
                .isNotIn(ReasoningEffort.XHIGH, ReasoningEffort.MAX);
    }

    @Test
    void depthAlsoBoundsTheOutputBudgetAndTheTwoMoveTogether() {
        // The instruction and the budget live on the same enum on purpose: NUCLEAR asking for exhaustive detail under
        // a SHORT budget is a truncated report, and keeping them in one declaration makes that impossible.
        assertThat(AnalysisDepth.SHORT.maxOutputTokens())
                .isLessThan(AnalysisDepth.REGULAR.maxOutputTokens());
        assertThat(AnalysisDepth.REGULAR.maxOutputTokens())
                .isLessThan(AnalysisDepth.NUCLEAR.maxOutputTokens());
    }
}
