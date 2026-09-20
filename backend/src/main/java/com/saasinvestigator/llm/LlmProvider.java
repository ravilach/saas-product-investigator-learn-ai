package com.saasinvestigator.llm;

/**
 * One LLM vendor's implementation of the two things this application asks a model to do.
 *
 * <p>The abstraction exists so that "which provider" is a per-user runtime choice rather than a build-time one, and
 * so that the interesting logic - what gets crawled, what gets compared, what the prompt says, what the output
 * must look like - lives once instead of twice. Everything provider-specific sits behind these two methods: SDK
 * types, how MCP servers are declared, how JSON is enforced, how streaming events are shaped.
 *
 * <p>Implementations receive an identical {@link RunContext} or {@link AskContext}, which is what makes running the
 * same product through both providers a fair comparison rather than a comparison of two differently-built prompts.
 * What they must <em>not</em> do is interpret that context differently: depth instructions come from
 * {@code AnalysisDepth}, the prompt comes from {@link PromptBuilder}, and the output contract is parsed by
 * {@link ChangeReportJsonParser}. A provider that wrote its own version of any of those would drift from the other
 * within a release.
 *
 * <p>Both methods are blocking, and are called from an orchestration thread rather than from a request thread - a
 * provider call is the slowest thing in the system, and the whole run/SSE design exists so nothing waits on it
 * holding an HTTP connection open. Adding a provider is a checklist rather than a design exercise; see the
 * {@code add-llm-provider} skill.
 */
public interface LlmProvider {

    /**
     * @return which provider this is. Used for credential resolution, for metric and audit tags, and to report
     *     back to the user which provider actually served their run.
     */
    LlmProviderType type();

    /**
     * Analyses a run's inputs and returns the model's findings.
     *
     * <p>Contractual behaviour every implementation owes its caller:
     *
     * <ul>
     *   <li>Declare every {@link RunContext#mcpSources()} entry to the model as a remote MCP tool, so the model
     *       calls those servers itself. The backend never calls an MCP server.
     *   <li>Ask for strict JSON matching the {@code change_reports} shape, using the strongest enforcement the
     *       provider offers, and parse it with {@link ChangeReportJsonParser} - including its single corrective
     *       retry - rather than hand-rolling either.
     *   <li>Honour {@code AnalysisDepth.maxOutputTokens()}, clamping rather than failing if the model's own ceiling
     *       is lower. A NUCLEAR analysis silently truncated is worse than a deliberate summary.
     *   <li>Report tool calls to {@code listener} as they happen, not in a batch at the end.
     * </ul>
     *
     * @param context the provider-agnostic inputs for this run
     * @param listener where to report progress; use {@link LlmActivityListener#NONE} if there is nowhere to report
     * @return the model's summary and findings
     * @throws com.saasinvestigator.error.ProviderUnavailableException if the provider cannot be reached, rejects
     *     the request, or returns something that is still not valid JSON after the corrective retry. Callers treat
     *     this as "this run failed", never as "this source failed" - by the time a provider is called, per-source
     *     failures have already been absorbed into {@link RunContext#failures()}.
     */
    GeneratedReport generateChangeReport(RunContext context, LlmActivityListener listener);

    /**
     * Answers a free-text question about a product from already-stored context, streaming as it goes.
     *
     * <p>No tools, no MCP servers, no JSON contract: the output is prose for a human to read. Implementations must
     * push text to {@code sink} as it arrives and must also return the complete answer, so that a caller which only
     * wants the whole thing does not have to accumulate chunks itself.
     *
     * @param context the question and the stored data to answer it from
     * @param sink where to push text as it is generated; use {@link TokenSink#NONE} to ignore streaming
     * @return the complete answer, exactly the concatenation of everything passed to {@code sink}
     * @throws com.saasinvestigator.error.ProviderUnavailableException if the provider cannot be reached or rejects
     *     the request
     */
    String answerQuestion(AskContext context, TokenSink sink);
}
