package com.saasinvestigator.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.saasinvestigator.llm.ChangeReportJsonParser.MalformedReportException;
import com.saasinvestigator.product.SourceType;
import com.saasinvestigator.report.AnalysisDepth;
import com.saasinvestigator.report.ChangeCategory;
import com.saasinvestigator.report.Confidence;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Tests the parser, which is the boundary between "a model said something" and "the application believes something".
 *
 * <p>The organising question behind every test here is the parser's own asymmetry: <b>structural problems retry,
 * field-level problems degrade</b>. Getting that split wrong is expensive in both directions. Too strict, and a
 * seven-change report is discarded because one badge is spelled wrong and the user pays for a second nuclear-depth
 * call. Too lenient, and a change attributed to a source the product does not have gets stored, sending a reader
 * looking for evidence on a page that was never consulted.
 *
 * <p>Attribution gets the most coverage because it is the one field-level problem that is <em>not</em> shrugged off.
 * A wrong category is a wrong badge; a wrong source makes the finding uncheckable.
 */
class ChangeReportJsonParserTest {

    private final PromptBuilder promptBuilder =
            new PromptBuilder(new LlmProperties("claude-sonnet-5", "gpt-6-astra", "", "", 600_000));
    private final ChangeReportJsonParser parser = new ChangeReportJsonParser(promptBuilder);

    private final RunContext oneSource = LlmTestFixtures.standardRun(AnalysisDepth.REGULAR,
            List.of(LlmTestFixtures.changed("Changelog", "old", "new")));

    private final RunContext twoSources = LlmTestFixtures.standardRun(AnalysisDepth.REGULAR,
            List.of(LlmTestFixtures.changed("Changelog", "old", "new"),
                    LlmTestFixtures.changed("Pricing", "$10", "$12")));

    /** Fails if called - the assertion is that no retry happened. */
    private static final ChangeReportJsonParser.CorrectionCall NO_RETRY = prompt -> {
        throw new AssertionError("A retry was sent when the first response should have been accepted.");
    };

    // ---------------------------------------------------------------------
    // The happy path, and what "happy" includes
    // ---------------------------------------------------------------------

    @Test
    void aCompliantResponseParsesWithNoRetry() {
        String json = """
                {
                  "overallSummary": "Pricing rose and a bulk export shipped.",
                  "changes": [
                    {
                      "sourceName": "Changelog",
                      "category": "feature",
                      "description": "Bulk CSV export is now available.",
                      "confidence": "high",
                      "evidenceSnippet": "Added: bulk CSV export"
                    }
                  ]
                }
                """;

        GeneratedReport report = parser.parseWithRetry(oneSource, json, NO_RETRY);

        assertThat(report.overallSummary()).isEqualTo("Pricing rose and a bulk export shipped.");
        assertThat(report.changes()).singleElement().satisfies(change -> {
            assertThat(change.sourceName()).isEqualTo("Changelog");
            assertThat(change.sourceType()).isEqualTo(SourceType.WEBSITE);
            assertThat(change.category()).isEqualTo(ChangeCategory.FEATURE);
            assertThat(change.confidence()).isEqualTo(Confidence.HIGH);
            assertThat(change.evidenceSnippet()).isEqualTo("Added: bulk CSV export");
        });
    }

    @Test
    void anEmptyChangeListIsASuccessfulReportAndNotAFailure() {
        String json = """
                {"overallSummary": "Nothing substantive changed since the last run.", "changes": []}
                """;

        GeneratedReport report = parser.parseWithRetry(oneSource, json, NO_RETRY);

        // "Nothing changed" is the most common real answer for a product checked daily. Treating it as a failure
        // would make the application usable only on the days something happened.
        assertThat(report.changes()).isEmpty();
        assertThat(report.overallSummary()).contains("Nothing substantive changed");
    }

    @Test
    void anAbsentChangesFieldIsTreatedAsNoChangesRatherThanAsMalformed() {
        GeneratedReport report = parser.parseWithRetry(oneSource,
                "{\"overallSummary\": \"No movement.\"}", NO_RETRY);

        assertThat(report.changes()).isEmpty();
    }

    // ---------------------------------------------------------------------
    // Salvage: things that are recoverable without a second call
    // ---------------------------------------------------------------------

    @Test
    void jsonWrappedInMarkdownFencesIsRecoveredWithoutARetry() {
        String fenced = """
                Here is the analysis:

                ```json
                {"overallSummary": "One pricing change.", "changes": []}
                ```
                """;

        // By far the most common deviation, and recovering from it costs one substring instead of a second
        // full-price model call.
        GeneratedReport report = parser.parseWithRetry(oneSource, fenced, NO_RETRY);

        assertThat(report.overallSummary()).isEqualTo("One pricing change.");
    }

    @Test
    void trailingProseAfterTheObjectIsDiscarded() {
        String trailing = "{\"overallSummary\": \"Quiet week.\", \"changes\": []}\n\nLet me know if you want more.";

        assertThat(parser.parseWithRetry(oneSource, trailing, NO_RETRY).overallSummary()).isEqualTo("Quiet week.");
    }

    // ---------------------------------------------------------------------
    // Field-level problems degrade
    // ---------------------------------------------------------------------

    @Test
    void anInventedCategoryBecomesOtherRatherThanFailingTheReport() {
        String json = """
                {"overallSummary": "s", "changes": [
                  {"sourceName": "Changelog", "category": "price-increase",
                   "description": "Pro tier went from $10 to $12.", "confidence": "high"}]}
                """;

        GeneratedReport report = parser.parseWithRetry(oneSource, json, NO_RETRY);

        // The description and the evidence are exactly right; only the badge is a guess. Discarding the finding
        // would be strictness for its own sake.
        assertThat(report.changes()).singleElement().satisfies(change -> {
            assertThat(change.category()).isEqualTo(ChangeCategory.OTHER);
            assertThat(change.description()).isEqualTo("Pro tier went from $10 to $12.");
        });
    }

    @Test
    void aMissingConfidenceBecomesMediumRatherThanFailingTheReport() {
        String json = """
                {"overallSummary": "s", "changes": [
                  {"sourceName": "Changelog", "category": "feature", "description": "Export added."}]}
                """;

        assertThat(parser.parseWithRetry(oneSource, json, NO_RETRY).changes())
                .singleElement()
                .satisfies(change -> assertThat(change.confidence()).isEqualTo(Confidence.MEDIUM));
    }

    @Test
    void aBlankEvidenceSnippetBecomesNullSoTheUiCanOmitTheQuoteBlock() {
        String json = """
                {"overallSummary": "s", "changes": [
                  {"sourceName": "Changelog", "category": "deprecation",
                   "description": "The v1 API is gone.", "confidence": "high", "evidenceSnippet": "   "}]}
                """;

        // A change can legitimately have no quotable evidence - the absence of something that used to be there is
        // the obvious case - and an empty quote block renders as a broken UI element.
        assertThat(parser.parseWithRetry(oneSource, json, NO_RETRY).changes())
                .singleElement()
                .satisfies(change -> assertThat(change.evidenceSnippet()).isNull());
    }

    @Test
    void anOverlongEvidenceSnippetIsTrimmed() {
        String json = "{\"overallSummary\": \"s\", \"changes\": [{\"sourceName\": \"Changelog\","
                + "\"category\": \"feature\", \"description\": \"d\", \"confidence\": \"high\","
                + "\"evidenceSnippet\": \"" + "q".repeat(ChangeReportJsonParser.MAX_EVIDENCE_CHARS * 2) + "\"}]}";

        // "A short verbatim quote" is what was asked for; a model pasting a whole page instead would make a report
        // card unreadable and a PDF export enormous.
        assertThat(parser.parseWithRetry(oneSource, json, NO_RETRY).changes())
                .singleElement()
                .satisfies(change -> assertThat(change.evidenceSnippet())
                        .hasSize(ChangeReportJsonParser.MAX_EVIDENCE_CHARS + 3)
                        .endsWith("..."));
    }

    @Test
    void aChangeWithNoDescriptionIsDroppedBecauseThereIsNothingToShow() {
        String json = """
                {"overallSummary": "s", "changes": [
                  {"sourceName": "Changelog", "category": "feature", "confidence": "high"},
                  {"sourceName": "Changelog", "category": "feature", "description": "Real one.",
                   "confidence": "high"}]}
                """;

        assertThat(parser.parseWithRetry(oneSource, json, NO_RETRY).changes())
                .singleElement()
                .satisfies(change -> assertThat(change.description()).isEqualTo("Real one."));
    }

    // ---------------------------------------------------------------------
    // Attribution: lenient about matching, strict about inventing
    // ---------------------------------------------------------------------

    @Test
    void aDecoratedSourceNameIsMatchedAndStoredUnderTheConfiguredSpelling() {
        String json = """
                {"overallSummary": "s", "changes": [
                  {"sourceName": "Changelog (example.com/changelog)", "category": "feature",
                   "description": "d", "confidence": "high"}]}
                """;

        // Stored under the configured name, not the model's - otherwise the same source would group under two
        // different labels across two runs, and the history timeline would look like two sources.
        assertThat(parser.parseWithRetry(twoSources, json, NO_RETRY).changes())
                .singleElement()
                .satisfies(change -> assertThat(change.sourceName()).isEqualTo("Changelog"));
    }

    @Test
    void anInventedSourceNameIsDroppedRatherThanFiledAgainstSomethingPlausible() {
        String json = """
                {"overallSummary": "Something happened on the blog.", "changes": [
                  {"sourceName": "Company Blog", "category": "feature", "description": "d", "confidence": "high"},
                  {"sourceName": "Pricing", "category": "pricing", "description": "Real one.",
                   "confidence": "high"}]}
                """;

        GeneratedReport report = parser.parseWithRetry(twoSources, json, NO_RETRY);

        // The unattributable change is dropped, the report is not. The summary still describes the product
        // correctly, so a report missing one finding beats no report at all.
        assertThat(report.changes()).singleElement()
                .satisfies(change -> assertThat(change.sourceName()).isEqualTo("Pricing"));
        assertThat(report.overallSummary()).isEqualTo("Something happened on the blog.");
    }

    @Test
    void anAmbiguousNameMatchingTwoConfiguredSourcesIsDroppedRatherThanGuessed() {
        RunContext overlapping = LlmTestFixtures.standardRun(AnalysisDepth.REGULAR,
                List.of(LlmTestFixtures.changed("Docs", "a", "b"),
                        LlmTestFixtures.changed("Docs API", "c", "d")));
        String json = """
                {"overallSummary": "s", "changes": [
                  {"sourceName": "Docs ", "category": "documentation", "description": "d", "confidence": "high"}]}
                """;

        // "Docs " resolves exactly to "Docs" after trimming, so this one is attributable. The point of the fixture
        // is the next assertion: a name that only loosely matches both is a coin flip, and a coin flip in an
        // attribution field is worse than a dropped finding.
        assertThat(parser.parseWithRetry(overlapping, json, NO_RETRY).changes()).hasSize(1);

        String looseJson = """
                {"overallSummary": "s", "changes": [
                  {"sourceName": "docs a", "category": "documentation", "description": "d",
                   "confidence": "high"}]}
                """;
        assertThat(parser.parseWithRetry(overlapping, looseJson, NO_RETRY).changes()).isEmpty();
    }

    @Test
    void withExactlyOneConfiguredSourceAMissingNameIsStillAttributable() {
        String json = """
                {"overallSummary": "s", "changes": [
                  {"category": "feature", "description": "Export added.", "confidence": "high"}]}
                """;

        // There is only one possible answer, so dropping the finding would discard information for the sake of a
        // rule with nothing to protect.
        assertThat(parser.parseWithRetry(oneSource, json, NO_RETRY).changes())
                .singleElement()
                .satisfies(change -> assertThat(change.sourceName()).isEqualTo("Changelog"));
    }

    @Test
    void withTwoConfiguredSourcesAMissingNameIsDropped() {
        String json = """
                {"overallSummary": "s", "changes": [
                  {"category": "feature", "description": "Export added.", "confidence": "high"}]}
                """;

        assertThat(parser.parseWithRetry(twoSources, json, NO_RETRY).changes()).isEmpty();
    }

    @Test
    void aChangeCanBeAttributedToAnMcpSourceTheBackendNeverRead() {
        RunContext withMcp = LlmTestFixtures.mcpRun(
                List.of(new McpSourceRef("Jira", SourceType.ATLASSIAN_MCP, "https://x/mcp", "token")));
        String json = """
                {"overallSummary": "s", "changes": [
                  {"sourceName": "Jira", "category": "feature", "description": "BILL-42 shipped.",
                   "confidence": "high"}]}
                """;

        // The whole point of an MCP source is that the model reads it and the backend does not, so its findings
        // arrive with no snapshot behind them and must still be attributable.
        assertThat(parser.parseWithRetry(withMcp, json, NO_RETRY).changes())
                .singleElement()
                .satisfies(change -> assertThat(change.sourceType()).isEqualTo(SourceType.ATLASSIAN_MCP));
    }

    // ---------------------------------------------------------------------
    // Structural problems retry, exactly once
    // ---------------------------------------------------------------------

    @Test
    void unparseableOutputIsRetriedOnceAndTheSecondResponseIsUsed() {
        AtomicInteger attempts = new AtomicInteger();

        GeneratedReport report = parser.parseWithRetry(oneSource, "I'm sorry, I can't help with that.", prompt -> {
            attempts.incrementAndGet();
            assertThat(prompt).isEqualTo(promptBuilder.jsonCorrectionPrompt());
            return "{\"overallSummary\": \"Recovered.\", \"changes\": []}";
        });

        assertThat(attempts).hasValue(1);
        assertThat(report.overallSummary()).isEqualTo("Recovered.");
    }

    @Test
    void aSecondFailureGivesUpRatherThanLoopingAtFullPrice() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> parser.parseWithRetry(oneSource, "not json", prompt -> {
            attempts.incrementAndGet();
            return "still not json";
        }))
                .isInstanceOf(MalformedReportException.class)
                .hasMessageContaining("even after one corrective retry");

        // One retry, not a loop. A model told plainly what was wrong and failing again is failing for a reason a
        // third expensive call will not fix.
        assertThat(attempts).hasValue(1);
    }

    @Test
    void aMissingOverallSummaryIsWorthARetryEvenThoughEverythingElseDegrades() {
        AtomicInteger attempts = new AtomicInteger();

        parser.parseWithRetry(oneSource, "{\"changes\": []}", prompt -> {
            attempts.incrementAndGet();
            return "{\"overallSummary\": \"Recovered.\", \"changes\": []}";
        });

        // The one field with no sensible default: an empty summary renders as a blank report card that looks like a
        // rendering bug, and every export would have a hole where its only prose belongs.
        assertThat(attempts).hasValue(1);
    }

    @Test
    void changesPresentButNotAnArrayIsStructuralAndRetries() {
        AtomicInteger attempts = new AtomicInteger();

        parser.parseWithRetry(oneSource, "{\"overallSummary\": \"s\", \"changes\": \"none\"}", prompt -> {
            attempts.incrementAndGet();
            return "{\"overallSummary\": \"s\", \"changes\": []}";
        });

        assertThat(attempts).hasValue(1);
    }

    @Test
    void anEmptyResponseRetries() {
        AtomicInteger attempts = new AtomicInteger();

        parser.parseWithRetry(oneSource, "   ", prompt -> {
            attempts.incrementAndGet();
            return "{\"overallSummary\": \"s\", \"changes\": []}";
        });

        assertThat(attempts).hasValue(1);
    }

    @Test
    void aFailureToEvenSendTheRetryStillFailsSafelyWithNoInternalDetail() {
        assertThatThrownBy(() -> parser.parseWithRetry(oneSource, "not json", prompt -> {
            throw new IllegalStateException("socket closed at com.example.Internal.line(42)");
        }))
                .isInstanceOf(MalformedReportException.class)
                .hasMessageContaining("the corrective retry could not be sent");
    }

    // ---------------------------------------------------------------------
    // What the exception message may say
    // ---------------------------------------------------------------------

    @Test
    void aParseFailureMessageDescribesTheShapeAndNeverTheContent() {
        // This message reaches a user, via ProviderUnavailableException. A model response can contain crawled page
        // content, so quoting it back would put a customer's private pricing page into an error banner.
        assertThatThrownBy(() -> parser.parse(oneSource, "internal secret: sk-ant-api03-abcdef"))
                .isInstanceOf(MalformedReportException.class)
                .hasMessageContaining("no JSON object")
                .hasMessageNotContaining("sk-ant-api03-abcdef");
    }
}
