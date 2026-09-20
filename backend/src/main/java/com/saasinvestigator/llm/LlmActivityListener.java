package com.saasinvestigator.llm;

/**
 * Receives real, specific descriptions of what a provider is doing while it is doing it.
 *
 * <p>This interface is why the frontend's live execution view shows genuine backend progress instead of a spinner
 * with an animation timed against nothing. Both providers stream, and both surface MCP tool-call names as they
 * happen, so the string that reaches a user is the actual tool the model actually called:
 * {@code "Calling Docs MCP tool: search_docs"}. A generic {@code "Thinking..."} would be a lie about how much the
 * backend knows, and an obvious one to anyone who has watched a run take four minutes.
 *
 * <p>Deliberately a single method taking a finished string, the same shape as
 * {@code CrawlProgressListener}/{@code CrawlProgress.detail()}. The alternative - one method per event kind - would
 * put the sentence-building in the orchestrator, one step further from the provider event that knows the tool's
 * name, and would need a new method every time a provider adds an event type.
 */
@FunctionalInterface
public interface LlmActivityListener {

    /** Discards everything. For callers with nothing to report progress to, and for tests that assert on results. */
    LlmActivityListener NONE = detail -> {
    };

    /**
     * Called as each noteworthy provider event arrives, on the thread reading the provider's stream.
     *
     * <p>Implementations must be cheap and must not throw: this runs inside the loop consuming a live HTTP
     * response, so a slow or failing listener would stall or break the provider call it is reporting on. The SSE
     * implementation only offers to a queue.
     *
     * @param detail a specific, user-facing description of what just happened
     */
    void onActivity(String detail);
}
