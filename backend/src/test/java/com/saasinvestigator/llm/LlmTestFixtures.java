package com.saasinvestigator.llm;

import com.saasinvestigator.product.SourceType;
import com.saasinvestigator.report.AnalysisDepth;
import com.saasinvestigator.report.RunType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Builders for the context records the LLM layer takes as input.
 *
 * <p>These exist because a {@link RunContext} has eleven components and almost every test cares about two of them.
 * Constructing one inline makes a test about prompt budgeting look like a test about {@code rangeFrom}, and makes it
 * impossible to see at a glance which value is the one under test.
 */
final class LlmTestFixtures {

    /** A fixed "now" so that any date rendered into a prompt is stable across runs. */
    static final Instant NOW = Instant.parse("2026-09-20T12:00:00Z");

    private LlmTestFixtures() {
    }

    /** A crawled source with both a prior and a current capture - the ordinary case. */
    static SourceComparison changed(String name, String prior, String current) {
        return new SourceComparison(name, SourceType.WEBSITE, prior, NOW.minus(7, ChronoUnit.DAYS),
                current, NOW);
    }

    /** A crawled source seen for the first time, so there is nothing to compare it against. */
    static SourceComparison firstSeen(String name, String current) {
        return new SourceComparison(name, SourceType.WEBSITE, null, null, current, NOW);
    }

    /** A standard run over the given sources, at the given depth. */
    static RunContext standardRun(AnalysisDepth depth, List<SourceComparison> comparisons) {
        return new RunContext("Acme Billing", "Usage-based billing for APIs", RunType.STANDARD, depth,
                NOW.minus(7, ChronoUnit.DAYS), null, null, comparisons, List.of(), List.of(), List.of());
    }

    /** A standard run whose only content is an MCP server the model is expected to call itself. */
    static RunContext mcpRun(List<McpSourceRef> mcpSources) {
        return new RunContext("Acme Billing", null, RunType.STANDARD, AnalysisDepth.REGULAR,
                NOW.minus(1, ChronoUnit.DAYS), null, null, List.of(), mcpSources, List.of(), List.of());
    }

    /** A custom-range compare over the given window. */
    static RunContext compareRun(Instant from,
                                 Instant to,
                                 List<SourceComparison> comparisons,
                                 List<McpChangeHistory> mcpHistory,
                                 List<SourceFailure> failures) {
        return new RunContext("Acme Billing", null, RunType.CUSTOM_RANGE, AnalysisDepth.REGULAR,
                null, from, to, comparisons, List.of(), mcpHistory, failures);
    }

    /** A question with one snapshot behind it. */
    static AskContext ask(String question, String snapshotText) {
        return new AskContext("Acme Billing", question,
                List.of(new AskContext.SourceExcerpt("Changelog", SourceType.WEBSITE, snapshotText, NOW)),
                "Two pricing changes since the last run.", NOW);
    }
}
