package com.saasinvestigator.llm;

import com.saasinvestigator.report.Change;
import java.util.List;

/**
 * What a provider produces: the model's findings, and nothing about how they will be stored.
 *
 * <p>The build prompt sketched this method as returning a {@code ChangeReport}, the persisted entity. It returns
 * this instead, because a provider cannot fill in most of a {@code ChangeReport}: not its id, not the product it
 * belongs to, not who triggered the run, not the {@code sourcesIncluded} timestamps that came from the crawler
 * rather than the model. Returning a half-populated entity would make "which fields are real yet" a thing every
 * caller has to remember, and would put persistence concerns inside two provider implementations. The orchestrator
 * assembles the entity from this plus what it already knows.
 *
 * @param overallSummary the prose summary, a few sentences to a few paragraphs depending on
 *     {@code AnalysisDepth}. Never {@code null}: a report with no summary is not a report, and
 *     {@link ChangeReportJsonParser} rejects a response missing it.
 * @param changes the individual findings, possibly empty. Empty is a real and common answer - "nothing changed" is
 *     the most useful result a monitoring tool can give - and is distinguished from a failed run by the run
 *     completing at all.
 */
public record GeneratedReport(String overallSummary, List<Change> changes) {

    /** Defensively copies, so a parsed result is immutable once handed to the orchestrator. */
    public GeneratedReport {
        changes = List.copyOf(changes);
    }
}
