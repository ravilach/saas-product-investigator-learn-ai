package com.saasinvestigator.product;

import com.saasinvestigator.report.AnalysisDepth;
import com.saasinvestigator.report.ChangeReport;
import com.saasinvestigator.report.RunType;
import java.time.Instant;
import java.util.List;

/**
 * A SaaS product as returned by the API, including its masked sources and a summary of its most recent report.
 *
 * <p><b>{@code lastRun} is computed on every read, never stored.</b> A {@code lastRunAt} column on the product
 * document would be a second copy of a fact the reports collection already owns, updated by a different write than
 * the one that creates the report - so the two can disagree, and when they do, the dashboard is wrong while the
 * history is right. Deriving it costs one indexed query per product and cannot drift. See {@link SaasProduct}'s own
 * note on the deliberately absent field.
 *
 * @param id the product id
 * @param name the display name
 * @param description the free-text description, possibly {@code null}
 * @param sources the configured sources, tokens masked to a {@code last4}
 * @param sourceCount how many sources are configured, so a dashboard card need not count the list itself
 * @param createdAt when the product was created
 * @param createdBy username of the creator
 * @param lastRun a summary of the newest report, or {@code null} if this product has never produced one
 */
public record SaasProductResponse(
        String id,
        String name,
        String description,
        List<SourceConfigResponse> sources,
        int sourceCount,
        Instant createdAt,
        String createdBy,
        LastRun lastRun) {

    /**
     * Just enough of the newest report for a dashboard card and the Product detail header.
     *
     * <p>Excludes {@code changes} and the full {@code overallSummary} deliberately: the dashboard lists every
     * product, and embedding a nuclear-depth report's findings in each row would make the list response larger than
     * the history endpoint it exists to link to.
     *
     * @param reportId the report's id, so the UI can link straight to it
     * @param runAt when that run happened
     * @param runType whether it was a live run or a custom-range compare
     * @param analysisDepth the depth it ran at
     * @param runBy who triggered it
     * @param changeCount how many findings it recorded
     */
    public record LastRun(
            String reportId,
            Instant runAt,
            RunType runType,
            AnalysisDepth analysisDepth,
            String runBy,
            int changeCount) {

        /**
         * @param report the newest report for a product
         * @return its summary
         */
        public static LastRun from(ChangeReport report) {
            return new LastRun(report.getId(), report.getRunAt(), report.getRunType(), report.getAnalysisDepth(),
                    report.getRunBy(), report.getChanges().size());
        }
    }
}
