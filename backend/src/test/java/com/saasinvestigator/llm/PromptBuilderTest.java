package com.saasinvestigator.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.saasinvestigator.product.SourceType;
import com.saasinvestigator.report.AnalysisDepth;
import com.saasinvestigator.report.Change;
import com.saasinvestigator.report.ChangeCategory;
import com.saasinvestigator.report.Confidence;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests the prompts.
 *
 * <p>A prompt is not normally worth asserting on word by word, and this class does not try to. What it asserts is the
 * set of things that are <em>load-bearing</em> - where getting the text wrong produces a report that is confidently
 * incorrect rather than obviously broken, and where nothing else in the system would notice:
 *
 * <ul>
 *   <li>That the JSON contract in the system prompt lists every enum value the parser will accept. A category added
 *       to {@link ChangeCategory} but not to the prompt is a category the model will never use, and the symptom is
 *       "the model never reports deprecations" rather than an error.</li>
 *   <li>That the three run scopes - first run, subsequent run, custom range - each say something different. If the
 *       compare prompt failed to say its text is historical, the model would answer about today inside a report
 *       labelled with two past dates.</li>
 *   <li>That unavailable sources are stated rather than omitted, since a silent omission is indistinguishable from
 *       a source with no changes.</li>
 *   <li>That the character budget is enforced by dropping whole sources and <em>saying so</em>, rather than by
 *       silently slicing text mid-sentence.</li>
 * </ul>
 */
class PromptBuilderTest {

    /** The floor {@code LlmProperties} clamps to, which is the smallest budget a test can actually exercise. */
    private static final int SMALL_BUDGET = 20_000;

    private final PromptBuilder builder = new PromptBuilder(
            new LlmProperties("claude-sonnet-5", "gpt-6-astra", SMALL_BUDGET));

    // ---------------------------------------------------------------------
    // The JSON contract
    // ---------------------------------------------------------------------

    @Test
    void theSystemPromptOffersEveryCategoryTheParserCanAccept() {
        String prompt = builder.systemPrompt();

        // Not "contains some categories": every one. The failure this guards against is adding a value to
        // ChangeCategory, teaching the parser to accept it, and never telling the model it exists.
        assertThat(PromptBuilder.categoryWireNames()).allSatisfy(name -> assertThat(prompt).contains(name));
        assertThat(PromptBuilder.confidenceWireNames()).allSatisfy(name -> assertThat(prompt).contains(name));
    }

    @Test
    void theSystemPromptSaysAnEmptyChangeListIsAValidAnswer() {
        // Without this, the model's incentive is to find something, and a run over an unchanged product produces
        // invented churn that a reader cannot tell from real churn.
        assertThat(builder.systemPrompt())
                .contains("\"changes\" may be an empty array")
                .contains("Never invent a change");
    }

    @Test
    void theSystemPromptForbidsProseAndCodeFences() {
        assertThat(builder.systemPrompt())
                .contains("Return ONLY a single JSON object")
                .contains("no markdown code fences");
    }

    // ---------------------------------------------------------------------
    // The three scopes
    // ---------------------------------------------------------------------

    @Test
    void aFirstRunAsksForABaselineRatherThanForChanges() {
        RunContext context = new RunContext("Acme Billing", null, com.saasinvestigator.report.RunType.STANDARD,
                AnalysisDepth.REGULAR, null, null, null,
                List.of(LlmTestFixtures.firstSeen("Changelog", "v1 released")),
                List.of(), List.of(), List.of());

        assertThat(builder.runPrompt(context))
                .contains("first time this product has been analysed")
                .contains("Describe the current state of the product as a baseline");
    }

    @Test
    void aSubsequentRunNamesTheDateItIsMeasuringFrom() {
        RunContext context = LlmTestFixtures.standardRun(AnalysisDepth.REGULAR,
                List.of(LlmTestFixtures.changed("Changelog", "v1", "v2")));

        assertThat(builder.runPrompt(context))
                .contains("Report what has changed since this product was last analysed on")
                .contains("13 September 2026");
    }

    @Test
    void aCompareSaysItsTextIsHistoricalAndNotAFreshLook() {
        Instant from = Instant.parse("2026-06-01T00:00:00Z");
        Instant to = Instant.parse("2026-06-15T23:59:59Z");
        RunContext context = LlmTestFixtures.compareRun(from, to,
                List.of(LlmTestFixtures.changed("Changelog", "june first", "june fifteenth")),
                List.of(), List.of());

        // This is the sentence that stops a compare's answer from being about today.
        assertThat(builder.runPrompt(context))
                .contains("Nothing here was fetched just now")
                .contains("do not speculate about anything after the later one")
                .contains(PromptBuilder.formatDay(from))
                .contains(PromptBuilder.formatDay(to));
    }

    @Test
    void depthReachesThePromptAsAnInstructionAndNotJustAsATokenBudget() {
        String shortPrompt = builder.runPrompt(LlmTestFixtures.standardRun(AnalysisDepth.SHORT,
                List.of(LlmTestFixtures.changed("Changelog", "a", "b"))));
        String nuclearPrompt = builder.runPrompt(LlmTestFixtures.standardRun(AnalysisDepth.NUCLEAR,
                List.of(LlmTestFixtures.changed("Changelog", "a", "b"))));

        assertThat(shortPrompt).contains(AnalysisDepth.SHORT.promptInstruction());
        assertThat(nuclearPrompt).contains(AnalysisDepth.NUCLEAR.promptInstruction());

        // The three depths must produce genuinely different instructions, which is what makes the depth control
        // worth having on screen. Identical instructions with different token budgets would produce the same
        // analysis at three lengths.
        assertThat(shortPrompt).isNotEqualTo(nuclearPrompt);
    }

    // ---------------------------------------------------------------------
    // Sources that could not be read
    // ---------------------------------------------------------------------

    @Test
    void unavailableSourcesAreStatedAndExcludedFromTheChangeList() {
        RunContext context = LlmTestFixtures.compareRun(
                Instant.parse("2026-06-01T00:00:00Z"), Instant.parse("2026-06-15T00:00:00Z"),
                List.of(LlmTestFixtures.changed("Changelog", "a", "b")),
                List.of(),
                List.of(new SourceFailure("Pricing", SourceType.WEBSITE, "the site returned HTTP 503")));

        assertThat(builder.runPrompt(context))
                .contains("SOURCES UNAVAILABLE FOR THIS RUN:")
                .contains("\"Pricing\"")
                .contains("the site returned HTTP 503")
                .contains("Mention these in your overall summary. Do not report changes for them.");
    }

    @Test
    void aFailureReasonSpanningLinesIsCollapsedSoOneSourceStaysOneLine() {
        RunContext context = LlmTestFixtures.standardRun(AnalysisDepth.REGULAR,
                List.of(LlmTestFixtures.changed("Changelog", "a", "b")));
        RunContext withFailure = new RunContext(context.productName(), context.productDescription(),
                context.runType(), context.analysisDepth(), context.lastRunAt(), null, null,
                context.comparisons(), List.of(), List.of(),
                List.of(new SourceFailure("Pricing", SourceType.WEBSITE, "timed out\nafter   10s")));

        assertThat(builder.runPrompt(withFailure)).contains("timed out after 10s");
    }

    // ---------------------------------------------------------------------
    // MCP
    // ---------------------------------------------------------------------

    @Test
    void mcpSourcesAreDescribedAsToolsToCallAndNotAsTextToRead() {
        RunContext context = LlmTestFixtures.mcpRun(List.of(
                new McpSourceRef("Docs", SourceType.DOCS_MCP, "https://docs.example.com/mcp", null)));

        assertThat(builder.runPrompt(context))
                .contains("LIVE TOOLS AVAILABLE TO YOU:")
                .contains("Call them yourself")
                .contains("the text blocks further down do not cover them");
    }

    @Test
    void aGenericMcpServerGetsNoGuessesAboutWhatItDoes() {
        String generic = builder.runPrompt(LlmTestFixtures.mcpRun(List.of(
                new McpSourceRef("Whatever", SourceType.GENERIC_MCP, "https://x.example.com/mcp", null))));
        String atlassian = builder.runPrompt(LlmTestFixtures.mcpRun(List.of(
                new McpSourceRef("Jira", SourceType.ATLASSIAN_MCP, "https://x.atlassian.net/mcp", null))));

        // A generic server's tool shape is unknown ahead of time, so the prompt tells the model to list the tools
        // rather than sending it looking for ones that may not exist.
        assertThat(generic).contains("list this server's tools")
                .contains("Nothing is assumed about what this server does");
        assertThat(atlassian).contains("Atlassian tools")
                .doesNotContain("Nothing is assumed about what this server does");
    }

    @Test
    void anMcpSourcesAuthTokenNeverReachesThePrompt() {
        RunContext context = LlmTestFixtures.mcpRun(List.of(
                new McpSourceRef("Jira", SourceType.ATLASSIAN_MCP, "https://x.atlassian.net/mcp", "s3cr3t-token")));

        // The token authenticates the transport; it is not information about the product. A prompt is logged,
        // exported, and sent to a third party, so a token in one is a token leaked three ways.
        assertThat(builder.runPrompt(context)).doesNotContain("s3cr3t-token");
    }

    @Test
    void anMcpSourceWithNoRecordedHistoryIsNotReportedAsUnchanged() {
        RunContext context = LlmTestFixtures.compareRun(
                Instant.parse("2026-06-01T00:00:00Z"), Instant.parse("2026-06-15T00:00:00Z"),
                List.of(), List.of(new McpChangeHistory("Jira", SourceType.ATLASSIAN_MCP, List.of())), List.of());

        // "No run recorded anything" and "nothing changed" are different claims, and only one of them is supported
        // by the data. Conflating them turns an absence of evidence into a positive finding.
        assertThat(builder.runPrompt(context))
                .contains("no run in this window recorded anything for this source")
                .contains("That is not the same as nothing having changed");
    }

    @Test
    void recordedMcpChangesArePassedThroughWithTheirCategoryAndConfidence() {
        RunContext context = LlmTestFixtures.compareRun(
                Instant.parse("2026-06-01T00:00:00Z"), Instant.parse("2026-06-15T00:00:00Z"),
                List.of(),
                List.of(new McpChangeHistory("Jira", SourceType.ATLASSIAN_MCP, List.of(
                        new Change("Jira", SourceType.ATLASSIAN_MCP, ChangeCategory.FEATURE,
                                "Bulk export shipped", Confidence.HIGH, "BILL-42 released")))),
                List.of());

        assertThat(builder.runPrompt(context))
                .contains("PREVIOUSLY RECORDED CHANGES FOR MCP SOURCES:")
                .contains("cannot be rewound to a past date")
                .contains("[feature, high] Bulk export shipped")
                .contains("Treat them as second-hand");
    }

    // ---------------------------------------------------------------------
    // The character budget
    // ---------------------------------------------------------------------

    @Test
    void aSourceTooLargeForItsShareIsCutWithAMarkerSayingSo() {
        String huge = "x".repeat(SMALL_BUDGET);
        RunContext context = LlmTestFixtures.standardRun(AnalysisDepth.REGULAR,
                List.of(LlmTestFixtures.changed("Changelog", huge, huge)));

        String prompt = builder.runPrompt(context);

        // The marker is not decoration: text that simply stops mid-stream looks to the model like content that was
        // deleted from the source, which it would then dutifully report as a change.
        assertThat(prompt).contains("truncated here")
                .contains("Do not treat this cut-off as removed content");
    }

    @Test
    void whenEvenAFairShareWouldBeUselessWholeSourcesAreDroppedAndNamed() {
        // Eleven sources with a prior state each is twenty-two blocks, so a fair share falls under the minimum
        // block size and slicing every source into fragments would be worse than analysing fewer of them.
        List<SourceComparison> many = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            many.add(LlmTestFixtures.changed("Source " + i, "old " + i, "new " + i));
        }

        String prompt = builder.runPrompt(LlmTestFixtures.standardRun(AnalysisDepth.REGULAR, many));

        assertThat(prompt).contains("more source text than fits in one analysis")
                .contains("omitted entirely rather than truncated to fragments")
                .contains("Source 0")
                .contains("Source 10")
                .contains("Say so in your overall summary");
    }

    @Test
    void aSourceWithNoPriorCaptureSaysSoRatherThanShowingAnEmptyPreviousBlock() {
        RunContext context = LlmTestFixtures.standardRun(AnalysisDepth.REGULAR,
                List.of(LlmTestFixtures.firstSeen("Pricing", "$10 per seat")));

        assertThat(builder.runPrompt(context))
                .contains("no previous capture exists for this source")
                .doesNotContain("PREVIOUS STATE");
    }

    // ---------------------------------------------------------------------
    // Attribution
    // ---------------------------------------------------------------------

    @Test
    void aSourceTypeIsResolvedFromConfigurationRatherThanAskedOfTheModel() {
        RunContext context = new RunContext("Acme", null, com.saasinvestigator.report.RunType.STANDARD,
                AnalysisDepth.REGULAR, LlmTestFixtures.NOW, null, null,
                List.of(LlmTestFixtures.changed("Changelog", "a", "b")),
                List.of(new McpSourceRef("Docs", SourceType.DOCS_MCP, "https://d/mcp", null)),
                List.of(new McpChangeHistory("Jira", SourceType.ATLASSIAN_MCP, List.of())),
                List.of(new SourceFailure("Pricing", SourceType.SAAS_URL, "down")));

        // All four collections are searched, because a change can legitimately be attributed to a source in any of
        // them - including a failed one, where the model is describing what it could not see.
        assertThat(PromptBuilder.resolveSourceType(context, "Changelog")).isEqualTo(SourceType.WEBSITE);
        assertThat(PromptBuilder.resolveSourceType(context, "Docs")).isEqualTo(SourceType.DOCS_MCP);
        assertThat(PromptBuilder.resolveSourceType(context, "Jira")).isEqualTo(SourceType.ATLASSIAN_MCP);
        assertThat(PromptBuilder.resolveSourceType(context, "Pricing")).isEqualTo(SourceType.SAAS_URL);
    }

    @Test
    void sourceNamesAreMatchedLooselyEnoughToSurviveCasingAndWhitespace() {
        RunContext context = LlmTestFixtures.standardRun(AnalysisDepth.REGULAR,
                List.of(LlmTestFixtures.changed("Changelog", "a", "b")));

        // The model is copying a name out of a prompt, and "changelog" for "Changelog" is not a mistake worth
        // discarding a finding over.
        assertThat(PromptBuilder.resolveSourceType(context, "  changelog ")).isEqualTo(SourceType.WEBSITE);
    }

    @Test
    void anInventedSourceNameResolvesToNothing() {
        RunContext context = LlmTestFixtures.standardRun(AnalysisDepth.REGULAR,
                List.of(LlmTestFixtures.changed("Changelog", "a", "b")));

        // Returning null is what lets the parser drop the change. A fallback type would attach a real-looking
        // badge to a finding that came from nowhere.
        assertThat(PromptBuilder.resolveSourceType(context, "Release Notes")).isNull();
        assertThat(PromptBuilder.resolveSourceType(context, null)).isNull();
    }

    // ---------------------------------------------------------------------
    // Asking
    // ---------------------------------------------------------------------

    @Test
    void theAskSystemPromptRefusesToFillGapsWithGeneralKnowledge() {
        assertThat(builder.askSystemPrompt())
                .contains("Answer only from the material provided")
                .contains("a confident wrong answer about someone's pricing page is worse than no answer");
    }

    @Test
    void theAskSystemPromptSaysTheMaterialIsAStoredCaptureWithADate() {
        // The single most likely way for this endpoint to mislead is to answer as though it had just looked.
        assertThat(builder.askSystemPrompt())
                .contains("not a live look at the product")
                .contains("say when it was captured");
    }

    @Test
    void anAskCarriesEachSnapshotsCaptureDateAndEndsWithTheQuestion() {
        String prompt = builder.askPrompt(LlmTestFixtures.ask("Did the price change?", "Pro: $20 per seat"));

        assertThat(prompt).contains("STORED SOURCE TEXT:")
                .contains("captured 20 September 2026 at 12:00 UTC")
                .contains("MOST RECENT CHANGE REPORT")
                .contains("Pro: $20 per seat");
        assertThat(prompt.trim()).endsWith("QUESTION: Did the price change?");
    }

    @Test
    void anAskAboutAProductThatHasNeverRunSaysSoExplicitly() {
        AskContext empty = new AskContext("Acme Billing", "What does it cost?", List.of(), null, null);

        // Stated rather than left as an absence, so the answer is "this has never been run" rather than an answer
        // improvised from the product's name.
        assertThat(builder.askPrompt(empty))
                .contains("There is no stored material for this product yet")
                .contains("it has never been run");
    }
}
