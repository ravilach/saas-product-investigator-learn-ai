package com.saasinvestigator.report;

/**
 * How thorough an analysis to ask the model for.
 *
 * <p>A <b>per-invocation</b> choice, not a product setting: the same product is legitimately worth a two-sentence
 * skim most mornings and an exhaustive audit before a release. It is stored on the resulting report so History can
 * show which depth produced which output - without that, a thin report is indistinguishable from a quiet week.
 *
 * <p>Depth controls <em>how hard the model looks at the data</em>, never <em>which data it gets</em>. Crawl
 * {@code maxDepth}/{@code maxPages} are the knob for the latter and are entirely separate. Keeping that line clean
 * is what makes the three levels comparable: run the same source at all three and the differences you see are
 * differences in analysis, not in input.
 *
 * <p>Each constant carries its own prompt instruction and output budget so all three sit side by side in one
 * readable place. Scattering them across the two provider implementations is how they drift until NUCLEAR means
 * something different depending on who is serving the request.
 */
public enum AnalysisDepth {

    /** A skim: the handful of things that actually matter, nothing cosmetic. */
    SHORT("""
            Give a concise 2-4 sentence summary and list only the handful of most significant changes \
            (roughly top 3-5), one sentence each. Omit minor or cosmetic changes entirely.""",
            4_096),

    /** The default: comprehensive but not exhaustive. */
    REGULAR("""
            Be comprehensive but not exhaustive. Cover every change a user of this product would want to know \
            about, and skip the trivia - a reworded heading or a reformatted table is not a change worth \
            reporting unless it altered the meaning.""",
            16_384),

    /**
     * Exhaustive, with a raised output budget.
     *
     * <p>The budget is the part that is easy to forget and the part that makes the difference real: asking for
     * exhaustive output under the default ceiling produces a response that is exhaustive right up until it is
     * truncated, which is worse than a deliberate summary because the truncation is not labelled.
     */
    NUCLEAR("""
            Be exhaustive - identify every discernible change across every source, however minor, with a fuller \
            explanation and more of the supporting evidence quoted per change. Do not omit anything for brevity.""",
            64_000);

    /** The default when a request does not specify one. */
    public static final AnalysisDepth DEFAULT = REGULAR;

    private final String promptInstruction;
    private final int maxOutputTokens;

    AnalysisDepth(String promptInstruction, int maxOutputTokens) {
        this.promptInstruction = promptInstruction;
        this.maxOutputTokens = maxOutputTokens;
    }

    /**
     * @return the sentence or two handed to the model describing how thorough to be. Composed into the prompt by
     *     each {@code LlmProvider}; no provider writes its own version of this.
     */
    public String promptInstruction() {
        return promptInstruction;
    }

    /**
     * @return the output token ceiling for a call at this depth. A provider whose model caps lower than this is
     *     expected to clamp rather than fail the request - the number here is a budget, not a guarantee.
     */
    public int maxOutputTokens() {
        return maxOutputTokens;
    }

    /**
     * Resolves a possibly-absent request value.
     *
     * @param requested the depth from the request body, or {@code null}
     * @return {@code requested}, or {@link #DEFAULT} if it was {@code null}
     */
    public static AnalysisDepth orDefault(AnalysisDepth requested) {
        return requested == null ? DEFAULT : requested;
    }
}
