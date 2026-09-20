/**
 * Everything that talks to a language model, and nothing that decides when to.
 *
 * <p>This package is the application's entire comparison engine, which is a strange sentence to write about a package
 * with no comparison code in it. There is no diffing logic here or anywhere else: what gets compared is decided by
 * what {@link com.saasinvestigator.llm.PromptBuilder} shows the model and what
 * {@link com.saasinvestigator.llm.ChangeReportJsonParser} accepts back. Anyone looking for the algorithm should read
 * the prompt.
 *
 * <h2>The shape</h2>
 *
 * <ul>
 *   <li><b>Inputs</b> - {@link com.saasinvestigator.llm.RunContext} for a run or compare,
 *       {@link com.saasinvestigator.llm.AskContext} for a question. Both are provider-agnostic records assembled by
 *       {@code run/}; neither knows an SDK type exists.
 *   <li><b>Output</b> - {@link com.saasinvestigator.llm.GeneratedReport}, which is deliberately not a
 *       {@code ChangeReport}: a model cannot know an id, a product, who triggered the run, or which sources were
 *       fetched when, so the orchestrator assembles the entity from this plus what it already knows.
 *   <li><b>Callbacks</b> - {@link com.saasinvestigator.llm.LlmActivityListener} and
 *       {@link com.saasinvestigator.llm.TokenSink}, the two reasons the live execution view and the streaming answer
 *       show real progress rather than an animation.
 *   <li><b>The seam</b> - {@link com.saasinvestigator.llm.LlmProvider}, two implementations,
 *       {@link com.saasinvestigator.llm.LlmProviderResolver} to choose between them.
 * </ul>
 *
 * <h2>Invariants this package keeps</h2>
 *
 * <p><b>A plaintext key never outlives the call that needed it.</b> Providers are constructed per call with a
 * resolved key rather than registered as beans, and {@link com.saasinvestigator.llm.LlmProperties} deliberately holds
 * no keys at all. There is no cache to go stale and nothing to dump in a heap snapshot after a run ends.
 *
 * <p><b>The backend never calls an MCP server.</b> MCP sources are declared to the model as remote tools and the
 * model calls them itself. {@link com.saasinvestigator.llm.McpSourceRef} is the only place an MCP
 * {@code authToken} exists in plaintext, it overrides {@code toString()} to redact it, and it is built and discarded
 * inside a single provider call.
 *
 * <p><b>Both providers are given identical prompts.</b> Prompt text, depth instructions, the JSON schema, and the
 * parse-and-retry policy all live in shared classes. This is what makes "run this product through both providers" a
 * comparison of two models rather than a comparison of two prompt authors.
 *
 * <p><b>Nothing in here is asynchronous, scheduled, or aware of HTTP.</b> Provider calls block; the orchestration
 * that keeps them off request threads, emits SSE, records metrics, and persists results lives in {@code run/}. A
 * provider that knew about an {@code SseEmitter} would be untestable without one.
 */
package com.saasinvestigator.llm;
