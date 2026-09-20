package com.saasinvestigator.run;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.saasinvestigator.report.ChangeReport;

/**
 * One event in a run's live execution stream, exactly as the browser receives it.
 *
 * <p>The shape is fixed by the build prompt: {@code type}, {@code step}, {@code detail},
 * {@code elapsedSeconds}. Two extras are carried here because the alternative is worse for the client:
 * {@code report} on completion, so the UI can render the finished report without a follow-up fetch that would race
 * the stream closing, and {@code runId}, so an event is self-describing in a log or a replay.
 *
 * <p>{@code detail} is the field that decides whether the live view is worth looking at, and the rule for it is
 * that it must come from something that actually happened. "Crawling https://example.com/changelog (page 3 of ~20)"
 * is produced by the crawler as it fetches that page; "Calling Docs MCP tool: search_docs" is produced by a
 * provider stream event naming that tool. Nothing here invents a plausible-sounding description, and nothing here
 * says "Thinking...".
 *
 * <p>Instances are built by {@link RunSession}, not by callers, because {@code elapsedSeconds} has to be measured
 * against the session's start rather than passed in by whoever happens to be emitting.
 *
 * @param runId the run this belongs to, echoed so a replayed or logged event stands alone
 * @param type what kind of event this is
 * @param step the human-readable step label from {@link RunStep#label()}, or {@code null} on a run-level event
 * @param detail a specific description of what happened, or {@code null} where there is nothing to add
 * @param elapsedSeconds whole seconds since the run started, so the UI can show a counter that survives a
 *     reconnect and a replay
 * @param report the persisted report, on {@link RunEventType#RUN_COMPLETED} only; {@code null} otherwise and
 *     omitted from the JSON
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunEvent(
        String runId,
        RunEventType type,
        String step,
        String detail,
        long elapsedSeconds,
        ChangeReport report) {
}
