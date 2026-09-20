package com.saasinvestigator.run;

/**
 * How a run ended, as the {@code status} tag on {@code saas_run_total}.
 *
 * <p>Three values rather than two, because "it worked" and "it worked but you should look at it" are the two
 * states an alert needs to tell apart. A dashboard built on a boolean would either page someone every time one
 * page of a twenty-page crawl timed out, or stay green while a product's most important source had been
 * unreachable for a week.
 */
public enum RunOutcome {

    /** Every source was read and a report was produced. */
    SUCCESS("success"),

    /**
     * A report was produced, but at least one source could not be read.
     *
     * <p>The report says so in its summary and the failed sources are absent from {@code sourcesIncluded}. Worth
     * alerting on as a rate rather than an event: one partial run is a flaky site, a week of them is a source
     * whose URL has changed.
     */
    PARTIAL("partial"),

    /** No report was produced. Every source failed, or the provider could not be used. */
    FAILURE("failure");

    private final String wireName;

    RunOutcome(String wireName) {
        this.wireName = wireName;
    }

    /** @return the lowercase tag value, matching the metric names documented in {@code docs/DEPLOYMENT.md} */
    public String wireName() {
        return wireName;
    }
}
