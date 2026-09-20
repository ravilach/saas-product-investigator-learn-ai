package com.saasinvestigator.llm;

import com.saasinvestigator.product.SourceType;

/**
 * One source that could not be read for this run.
 *
 * <p>Carried into the prompt rather than silently dropped, because a report that omits a failed source reads
 * exactly like a report about a source that had no changes. The model is told which sources were unavailable and
 * why, so the summary can say so; the orchestrator separately counts these for
 * {@code saas_source_fetch_errors_total} and keeps the run going.
 *
 * @param sourceName the source's name within its product
 * @param sourceType what kind of source failed, which is what the failure metric is tagged by
 * @param reason a short human-readable cause, safe to show a user and safe to send to a provider - a status line,
 *     a timeout, an unreachable host. Never a stack trace or an internal exception message: this string reaches
 *     both the model's context and the user's screen.
 */
public record SourceFailure(String sourceName, SourceType sourceType, String reason) {
}
