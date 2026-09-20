package com.saasinvestigator.llm;

import com.saasinvestigator.report.AnalysisDepth;
import com.saasinvestigator.report.RunType;
import java.time.Instant;
import java.util.List;

/**
 * Everything a provider needs to produce one change report, with nothing provider-specific in it.
 *
 * <p>The point of this record is that {@link AnthropicLlmProvider} and {@link OpenAiLlmProvider} receive
 * identical input and are therefore genuinely comparable. Any field here that only one provider could use would
 * quietly make the two implementations different analyses rather than two routes to the same analysis.
 *
 * <p>It also carries the difference between the two run types without either provider branching on it. A standard
 * run arrives with populated {@link #mcpSources} (declared to the model as remote tools) and an empty
 * {@link #mcpHistory}; a custom-range compare arrives with the reverse, because it never contacts anything live.
 * {@link PromptBuilder} is the single place that knows what that distinction means.
 *
 * @param productName the product being analysed, used in the prompt so the model knows what it is looking at
 * @param productDescription the product's description, or {@code null}. Worth including when present: "the
 *     billing API" is context a changelog page does not restate.
 * @param runType {@code STANDARD} or {@code CUSTOM_RANGE}
 * @param analysisDepth how thorough to be; supplies both the prompt instruction and the output token budget
 * @param lastRunAt when this product was last run, or {@code null} if never. Used to scope the "anything new
 *     since?" instruction given for MCP sources - without it the model has no anchor and tends to report a
 *     project's entire history as new.
 * @param rangeFrom start of the requested window, {@code CUSTOM_RANGE} only, otherwise {@code null}
 * @param rangeTo end of the requested window, {@code CUSTOM_RANGE} only, otherwise {@code null}
 * @param comparisons one entry per crawled source that was read successfully. May be empty - a product whose
 *     sources are all MCP servers is a valid product.
 * @param mcpSources MCP sources to declare as remote tools. Always empty for {@code CUSTOM_RANGE}: a compare
 *     reasons only over stored data, and calling a live tool would answer about today rather than about the
 *     requested window.
 * @param mcpHistory previously-recorded MCP changes within the window. Always empty for {@code STANDARD}, where
 *     the model reads the live servers instead.
 * @param failures sources that could not be read. Passed to the model deliberately; see {@link SourceFailure}.
 */
public record RunContext(
        String productName,
        String productDescription,
        RunType runType,
        AnalysisDepth analysisDepth,
        Instant lastRunAt,
        Instant rangeFrom,
        Instant rangeTo,
        List<SourceComparison> comparisons,
        List<McpSourceRef> mcpSources,
        List<McpChangeHistory> mcpHistory,
        List<SourceFailure> failures) {

    /** Defensively copies every list, so a context cannot be altered while a provider is using it. */
    public RunContext {
        comparisons = List.copyOf(comparisons);
        mcpSources = List.copyOf(mcpSources);
        mcpHistory = List.copyOf(mcpHistory);
        failures = List.copyOf(failures);
    }

    /**
     * @return {@code true} if this run has nothing at all to analyse - every source failed, or the product has no
     *     sources. The orchestrator checks this before spending a provider call on a prompt with no data in it.
     */
    public boolean hasNothingToAnalyse() {
        return comparisons.isEmpty() && mcpSources.isEmpty() && mcpHistory.isEmpty();
    }

    /**
     * @return {@code true} if any crawled source has a previous state to compare against. When false, the report
     *     is a baseline description rather than a change list, and the prompt says so.
     */
    public boolean hasAnyPriorState() {
        return comparisons.stream().anyMatch(SourceComparison::hasPrior);
    }
}
