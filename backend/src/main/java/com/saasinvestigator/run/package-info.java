/**
 * Turns a button press into a stored report, and narrates it while it happens.
 *
 * <h2>What lives here, and why it is not in {@code llm}</h2>
 *
 * <p>The {@code llm} package is deliberately ignorant: it knows how to turn a {@link com.saasinvestigator.llm.RunContext}
 * into a {@link com.saasinvestigator.llm.GeneratedReport}, and nothing else. It does not know what a thread pool is,
 * has never heard of HTTP, writes to no database, and increments no counter. Everything it does not know is in this
 * package:
 *
 * <ul>
 *   <li><b>Fetching</b> - {@link com.saasinvestigator.run.SourceFetcher} for a live run,
 *       {@link com.saasinvestigator.run.HistoryLoader} for a comparison. Exactly one of the two runs per report, and
 *       the difference between them is the difference between the two report types.</li>
 *   <li><b>Asynchrony</b> - {@link com.saasinvestigator.run.RunExecutor}, two bounded pools so that expensive runs
 *       cannot starve the query bar.</li>
 *   <li><b>Narration</b> - {@link com.saasinvestigator.run.RunEventStream} and
 *       {@link com.saasinvestigator.run.RunSession}, which buffer every event so a client that subscribes late still
 *       sees the whole run.</li>
 *   <li><b>Sequencing, persistence, and audit</b> - {@link com.saasinvestigator.run.RunOrchestrator}.</li>
 *   <li><b>Measurement</b> - {@link com.saasinvestigator.run.RunMetrics}.</li>
 *   <li><b>The HTTP surface</b> - {@link com.saasinvestigator.run.RunController}.</li>
 * </ul>
 *
 * <h2>Three flows, sharing most of their machinery</h2>
 *
 * <dl>
 *   <dt>Run</dt>
 *   <dd>Crawl every source now, compare each against the last snapshot of itself, store a {@code STANDARD} report.
 *       Steps: <em>Fetching sources - Consulting MCP tools - Comparing - Summarizing</em>.</dd>
 *   <dt>Compare</dt>
 *   <dd>Read two past points from storage, compare them, store a {@code CUSTOM_RANGE} report. Touches the network
 *       only to call the model. Steps: <em>Loading historical snapshots - Aggregating MCP history - Comparing -
 *       Summarizing</em>.</dd>
 *   <dt>Ask</dt>
 *   <dd>Answer a free-text question from the latest snapshots and the most recent report, streaming the answer.
 *       No steps, no report, no database write of any kind.</dd>
 * </dl>
 *
 * <h2>There is no diffing code in this package</h2>
 *
 * <p>Nothing here compares two pieces of text, looks for added lines, or decides that a change matters. That is the
 * central architectural commitment of the whole application: the model decides what changed and how significant it
 * is, and the backend's job is to give it the right two versions of the right sources, to say honestly which sources
 * it could not provide, and to store what comes back. A heuristic added here - "ignore changes under ten characters",
 * "skip pages whose hash is unchanged" - would silently overrule that judgment, and would do it before the model ever
 * saw the material.
 *
 * <h2>Run narration is in memory, and that has a deployment consequence</h2>
 *
 * <p>{@link com.saasinvestigator.run.RunEventStream} keeps sessions in a map on the heap. Reports and snapshots are in
 * Mongo and survive anything; the narration of a run in flight does not. Two things follow, and both are documented in
 * {@code /docs/DEPLOYMENT.md} rather than left to be discovered:
 *
 * <ul>
 *   <li>Restarting the backend abandons any run in progress. The report is not written, and the live view's stream
 *       ends.</li>
 *   <li>Across more than one replica, a client must reach the replica that started its run - so either run a single
 *       replica, or use sticky sessions. Without one of those, {@code GET .../events} will land on a replica that has
 *       never heard of the run and return {@code 404} roughly (n-1)/n of the time.</li>
 * </ul>
 *
 * <p>The alternative - persisting events to Mongo and polling, or putting a broker in front of them - would buy
 * horizontal scale-out for a feature whose entire lifetime is the couple of minutes somebody is watching it. That is a
 * real trade, made knowingly, and it is reversible: {@link com.saasinvestigator.run.RunEventSink} is the seam a
 * distributed implementation would slot into.
 *
 * <h2>Secrets</h2>
 *
 * <p>A run is the only place in the application where an MCP {@code authToken} exists in plaintext, and
 * {@link com.saasinvestigator.run.RunOrchestrator} is the only class that produces one. It lives in the
 * {@link com.saasinvestigator.llm.McpSourceRef} list for the duration of one provider call and nowhere else: not in a
 * {@link com.saasinvestigator.run.RunSession}, not in the stored report, not in the audit entry, and - because
 * {@code McpSourceRef} overrides {@code toString} - not in a log line either.
 *
 * <p>Nothing in this package puts an internal exception message on the wire. A
 * {@link com.saasinvestigator.crawl.CrawlFailedException} message is a vetted short reason and is safe to show; any
 * other failure produces a fixed user-facing sentence, with the real detail going only to the log. An SDK exception
 * message can contain the request body, which here means crawled page content and MCP authorization tokens.
 */
package com.saasinvestigator.run;
