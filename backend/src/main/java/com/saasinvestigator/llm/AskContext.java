package com.saasinvestigator.llm;

import com.saasinvestigator.product.SourceType;
import java.time.Instant;
import java.util.List;

/**
 * Everything a provider needs to answer one free-text question about a product.
 *
 * <p>An ask is deliberately much cheaper than a run: it reads only what is already stored - the latest snapshot per
 * source and the most recent report - and fetches nothing, crawls nothing, and calls no MCP tool. That is what
 * makes it safe to leave in a query bar that a user might type into repeatedly, and it is why an ask persists
 * nothing either. The answer is a view over stored data, not a new finding about the product.
 *
 * <p>The consequence is worth stating in the prompt and is: the answer can be stale. {@link PromptBuilder} passes
 * each snapshot's {@code fetchedAt} through so the model can answer "as of 3 June" rather than implying it just
 * looked.
 *
 * @param productName the product being asked about
 * @param question the user's question, verbatim
 * @param snapshots the latest stored text per source, newest-first. May be empty for a product that has never been
 *     run, in which case the model is told to say so rather than guess.
 * @param latestReportSummary the {@code overallSummary} of the most recent report, or {@code null} if there is
 *     none. Included because most questions after a run are about that run.
 * @param latestReportAt when that report was produced, or {@code null}
 */
public record AskContext(
        String productName,
        String question,
        List<SourceExcerpt> snapshots,
        String latestReportSummary,
        Instant latestReportAt) {

    /** Defensively copies, so the excerpt list cannot change while a provider is streaming an answer. */
    public AskContext {
        snapshots = List.copyOf(snapshots);
    }

    /**
     * @return {@code true} when there is no stored data at all to answer from
     */
    public boolean hasNoContext() {
        return snapshots.isEmpty() && latestReportSummary == null;
    }

    /**
     * One source's most recent stored text.
     *
     * @param sourceName the source's name within its product
     * @param sourceType what kind of source it is
     * @param text the stored extracted text
     * @param fetchedAt when it was captured - the reason an answer can be dated rather than presented as current
     */
    public record SourceExcerpt(String sourceName, SourceType sourceType, String text, Instant fetchedAt) {
    }
}
