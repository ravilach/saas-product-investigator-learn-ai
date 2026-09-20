package com.saasinvestigator.run;

/**
 * The named phases of a run or a compare, as the live execution view lists them.
 *
 * <p>An enum rather than free-text strings in the orchestrator, because the frontend renders one status pill per step
 * and has to be able to match an incoming event to a row it already drew. A typo in a hand-written step label
 * produces a step that appears twice or never - and only at runtime, only for that one code path.
 *
 * <p>The two flows share {@link #COMPARING} and {@link #SUMMARIZING} and differ in their first two steps, which is
 * exactly the shape of the UI: the same component, driven by whichever step list the flow declares. See
 * {@link #forRun()} and {@link #forCompare()} - those are the declarations, and they are what the frontend asks for
 * so the sequence is not duplicated there.
 */
public enum RunStep {

    /** Crawling every Website / SaaS App URL source. Only a standard run fetches anything. */
    FETCHING_SOURCES("Fetching sources"),

    /**
     * The model calling the configured MCP servers itself.
     *
     * <p>A separate step from {@link #COMPARING} even though both happen inside one provider call, because from the
     * user's point of view they are different waits: this one produces a stream of named tool calls, and the next
     * one produces nothing until it produces everything.
     */
    CONSULTING_MCP_TOOLS("Consulting MCP tools"),

    /** Reading stored snapshots either side of the requested window. Compare only; nothing is fetched. */
    LOADING_SNAPSHOTS("Loading historical snapshots"),

    /** Collecting what earlier reports recorded for MCP sources inside the window. Compare only. */
    AGGREGATING_MCP_HISTORY("Aggregating MCP history"),

    /** The model working through the assembled material. */
    COMPARING("Comparing"),

    /** The model writing the summary. Reported separately because it is where a long run spends its last minute. */
    SUMMARIZING("Summarizing");

    private final String label;

    RunStep(String label) {
        this.label = label;
    }

    /**
     * @return the human-readable label, which is what appears in the event's {@code step} field and therefore on
     *     screen. Sent rather than the enum name so the UI does not own a translation table that can fall out of
     *     step with this enum.
     */
    public String label() {
        return label;
    }

    /** @return the steps a standard run passes through, in order */
    public static RunStep[] forRun() {
        return new RunStep[] {FETCHING_SOURCES, CONSULTING_MCP_TOOLS, COMPARING, SUMMARIZING};
    }

    /** @return the steps a custom-range compare passes through, in order */
    public static RunStep[] forCompare() {
        return new RunStep[] {LOADING_SNAPSHOTS, AGGREGATING_MCP_HISTORY, COMPARING, SUMMARIZING};
    }
}
