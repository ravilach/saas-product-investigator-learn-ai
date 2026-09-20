package com.saasinvestigator.run;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The kinds of event a live run or compare emits over SSE.
 *
 * <p>The wire names are snake_case because they are the SSE event names the browser's {@code EventSource} listens
 * for, and they are declared here rather than written as literals at each emission site so that the frontend and
 * the backend cannot disagree about one of them. {@link #wireName()} is the serialised form of the {@code type}
 * field <em>and</em> the SSE event name, deliberately the same string: a client can dispatch on either.
 *
 * <p>Two of these are terminal - see {@link #terminal()}. That distinction is load-bearing rather than
 * informational: it is how a subscriber knows to close the stream, and how a late subscriber replaying a finished
 * run's history knows it has the whole story rather than an unfinished one.
 */
public enum RunEventType {

    /** A step has begun. Emitted exactly once per step that is reached. */
    STEP_STARTED("step_started"),

    /**
     * Something specific happened inside the current step - a page was crawled, an MCP tool was called.
     *
     * <p>Emitted many times, and carrying a real description each time. A run that emitted one of these per step
     * with the text "Working..." would be indistinguishable from a spinner and would be worse, because it would
     * look like information.
     */
    STEP_PROGRESS("step_progress"),

    /** A step finished successfully, with a detail summarising what it produced. */
    STEP_COMPLETED("step_completed"),

    /**
     * A step failed.
     *
     * <p>Not necessarily fatal. A source that cannot be fetched fails its own way into the report as
     * "unavailable" and the run continues; only a failure that leaves nothing to analyse escalates to
     * {@link #RUN_FAILED}.
     */
    STEP_FAILED("step_failed"),

    /** The run finished and its report is persisted. Carries the report, so the UI needs no follow-up request. */
    RUN_COMPLETED("run_completed"),

    /** The run could not produce a report. Carries a user-facing reason and nothing internal. */
    RUN_FAILED("run_failed");

    private final String wireName;

    RunEventType(String wireName) {
        this.wireName = wireName;
    }

    /** @return the snake_case name used both as the JSON {@code type} value and as the SSE event name */
    @JsonValue
    public String wireName() {
        return wireName;
    }

    /**
     * @return {@code true} if no further events will follow this one. A subscriber closes the stream on a terminal
     *     event; the session stops accepting publications after one.
     */
    public boolean terminal() {
        return this == RUN_COMPLETED || this == RUN_FAILED;
    }
}
