package com.saasinvestigator.report;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests the three enums that constrain what a model is allowed to say.
 *
 * <p>These look like trivia and are not. {@link ChangeCategory} and {@link Confidence} sit directly on the boundary
 * where untrusted model output enters the system, and their lenient parsing is a deliberate decision about which
 * failures are worth discarding a report over. Getting that wrong in either direction is expensive: too strict and
 * one odd word throws away a good report, too loose and a typo'd category silently becomes {@code OTHER} for every
 * change in the run.
 */
class ReportEnumsTest {

    @ParameterizedTest
    @EnumSource(ChangeCategory.class)
    void everyCategoryRoundTripsThroughItsWireName(ChangeCategory category) {
        // The prompt asks for these exact strings, so a constant whose wire name does not parse back would mean
        // the model is being asked for a value the parser then rejects.
        assertThat(ChangeCategory.fromWire(category.wireName())).isEqualTo(category);
    }

    @ParameterizedTest
    @EnumSource(Confidence.class)
    void everyConfidenceRoundTripsThroughItsWireName(Confidence confidence) {
        assertThat(Confidence.fromWire(confidence.wireName())).isEqualTo(confidence);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Pricing", "PRICING", "  pricing  ", "pRiCiNg"})
    void categoryParsingToleratesCasingAndPadding(String written) {
        // Models are consistent about the word and inconsistent about the shell around it.
        assertThat(ChangeCategory.fromWire(written)).isEqualTo(ChangeCategory.PRICING);
    }

    @ParameterizedTest
    @ValueSource(strings = {"price change", "billing", "", "  ", "feature-flag"})
    void anInventedCategoryBecomesOtherRatherThanFailing(String invented) {
        // The description, evidence, and source of such a change are all still correct; only the badge is a guess.
        assertThat(ChangeCategory.fromWire(invented)).isEqualTo(ChangeCategory.OTHER);
    }

    @Test
    void aNullCategoryBecomesOther() {
        assertThat(ChangeCategory.fromWire(null)).isEqualTo(ChangeCategory.OTHER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"very high", "0.87", "", "unknown"})
    void anUnparseableConfidenceBecomesMediumNotAFourthValue(String written) {
        // Middle of the range is the honest reading of an uninformative confidence. A dedicated UNKNOWN would have
        // to be special-cased by the UI, both exporters, and any future sort.
        assertThat(Confidence.fromWire(written)).isEqualTo(Confidence.MEDIUM);
    }

    @Test
    void aNullConfidenceBecomesMedium() {
        assertThat(Confidence.fromWire(null)).isEqualTo(Confidence.MEDIUM);
    }

    @Test
    void regularIsTheDefaultDepth() {
        assertThat(AnalysisDepth.orDefault(null)).isEqualTo(AnalysisDepth.REGULAR);
        assertThat(AnalysisDepth.DEFAULT).isEqualTo(AnalysisDepth.REGULAR);
    }

    @Test
    void anExplicitDepthIsNotOverriddenByTheDefault() {
        assertThat(AnalysisDepth.orDefault(AnalysisDepth.SHORT)).isEqualTo(AnalysisDepth.SHORT);
        assertThat(AnalysisDepth.orDefault(AnalysisDepth.NUCLEAR)).isEqualTo(AnalysisDepth.NUCLEAR);
    }

    @ParameterizedTest
    @EnumSource(AnalysisDepth.class)
    void everyDepthCarriesAnInstructionAndABudget(AnalysisDepth depth) {
        assertThat(depth.promptInstruction()).isNotBlank();
        assertThat(depth.maxOutputTokens()).isPositive();
    }

    @Test
    void outputBudgetRisesWithDepth() {
        // NUCLEAR's raised ceiling is the half of the feature that is easy to forget: exhaustive instructions under
        // the default budget produce output that is exhaustive right up to where it is silently truncated.
        assertThat(AnalysisDepth.SHORT.maxOutputTokens())
                .isLessThan(AnalysisDepth.REGULAR.maxOutputTokens());
        assertThat(AnalysisDepth.REGULAR.maxOutputTokens())
                .isLessThan(AnalysisDepth.NUCLEAR.maxOutputTokens());
    }

    @Test
    void theThreeDepthInstructionsAreActuallyDifferent() {
        // Guards the case where a copy-paste leaves two depths asking for the same thing, which presents in the UI
        // as a working 3-option control that produces two distinct behaviours.
        assertThat(AnalysisDepth.SHORT.promptInstruction())
                .isNotEqualTo(AnalysisDepth.REGULAR.promptInstruction());
        assertThat(AnalysisDepth.REGULAR.promptInstruction())
                .isNotEqualTo(AnalysisDepth.NUCLEAR.promptInstruction());
    }
}
