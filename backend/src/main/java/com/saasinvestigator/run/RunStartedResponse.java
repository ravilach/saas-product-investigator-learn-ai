package com.saasinvestigator.run;

/**
 * The {@code 202 Accepted} body of a run or a compare.
 *
 * <p>A single field, because there is a single thing the client needs and does not yet have: the id of the stream to
 * subscribe to. Returning the report instead would mean holding the request open for as long as the crawl and the
 * model call take - minutes, on a nuclear-depth run over twenty pages - which no proxy or phone browser will do
 * reliably, and which would leave the user watching a spinner that cannot say what it is waiting for.
 *
 * @param runId the id to pass to {@code GET /api/saas-products/{id}/runs/{runId}/events}
 */
public record RunStartedResponse(String runId) {
}
