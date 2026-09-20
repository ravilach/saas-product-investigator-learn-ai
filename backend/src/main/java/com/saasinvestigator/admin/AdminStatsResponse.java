package com.saasinvestigator.admin;

import com.saasinvestigator.report.RunType;
import java.time.Instant;
import java.util.List;

/**
 * The Admin Console's Overview numbers.
 *
 * @param totalUsers how many accounts exist
 * @param totalProducts how many SaaS products are configured
 * @param totalSourcesConfigured how many sources across all of them - the number that actually predicts how much work
 *     a full sweep is, which the product count alone does not
 * @param runsLast24h runs and comparisons started in the last 24 hours
 * @param runsLast7d runs and comparisons started in the last 7 days
 * @param runSuccessRate7d fraction of those that produced a report, {@code 0.0}-{@code 1.0}, or {@code null} when
 *     there were none. Null rather than zero on purpose: a fresh instance has not failed every run, it has not run
 *     anything, and "0%" on an untouched dashboard reads as a broken installation
 * @param avgRunDurationSeconds7d mean wall-clock duration over the same window, or {@code null} when there were none
 * @param recentRuns the last few runs, newest first, whatever their outcome
 */
public record AdminStatsResponse(
        long totalUsers,
        long totalProducts,
        long totalSourcesConfigured,
        long runsLast24h,
        long runsLast7d,
        Double runSuccessRate7d,
        Double avgRunDurationSeconds7d,
        List<RecentRun> recentRuns) {

    /**
     * One row of the recent-activity list.
     *
     * <p>{@code productName} is the name recorded at the time of the run rather than a join back to the product. That
     * is deliberate: a product can be renamed, and a run that happened under the old name did happen under the old
     * name. It also means this list survives the product's deletion right up until the cascade removes its run
     * records.
     *
     * @param productName the product as it was named when the run started
     * @param runType live run or custom-range compare
     * @param status {@code success}, {@code partial}, or {@code failure}
     * @param runAt when the run started
     */
    public record RecentRun(String productName, RunType runType, String status, Instant runAt) {
    }
}
