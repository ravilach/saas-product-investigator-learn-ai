package com.saasinvestigator.run;

import com.saasinvestigator.report.AnalysisDepth;
import com.saasinvestigator.report.RunType;
import java.time.Duration;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * What happened when a run or compare finished, stored in the {@code run_records} collection.
 *
 * <h2>Why this exists when {@code change_reports} already does</h2>
 *
 * <p>A {@link com.saasinvestigator.report.ChangeReport} is the <em>output</em> of a successful analysis. A run that
 * failed has no output, so it leaves no report - which means the report collection can answer "what did we learn"
 * but cannot answer "how often does this work", "how long does it take", or "what happened last night". Those are
 * exactly the questions the Admin Console's Stats tab asks, and they cannot be answered from a collection that only
 * contains successes: a success rate computed over reports alone is 100% by construction.
 *
 * <p>The alternative considered and rejected was reading the numbers back out of Prometheus. The counters are
 * already there, but they live in this process's memory, reset on restart, and are aggregated by a system whose job
 * is alerting rather than record-keeping - so the Stats tab would show different history depending on which replica
 * served the request and how recently it was deployed. One small document per run, queried with an aggregation, is
 * both cheaper and honest. See {@code docs/decisions/0008-run-records.md}.
 *
 * <p>Deliberately <b>not</b> the audit log. An audit entry records that a person asked for something, is written
 * before the work starts, and must never be rewritten; this records how the work turned out, is written when it
 * ends, and is operational telemetry rather than an accountability trail. Merging them would mean either an audit
 * entry that gets updated later or a trail with no record of what the user actually saw.
 *
 * <p>{@code productName} is denormalised for the same reason it is on an audit entry: the Stats tab lists recent
 * runs, and a product that has since been renamed or deleted should still be readable in that list rather than
 * showing an id nobody recognises.
 */
@Document(collection = "run_records")
public class RunRecord {

    @Id
    private String id;

    /** Which product was analysed. Indexed with {@code startedAt} so a cascade delete and the stats query are both covered. */
    private String saasProductId;

    /** The product's name at the time of the run, kept readable after a rename or a delete. */
    private String productName;

    private RunType runType;

    private AnalysisDepth analysisDepth;

    /** Whether this run produced a full report, a partial one, or nothing at all. */
    private RunOutcome outcome;

    /** Username of whoever triggered it. */
    private String runBy;

    /** When the work began, which is also what the Stats tab's windows are measured against. */
    private Instant startedAt;

    /**
     * Wall-clock duration in milliseconds.
     *
     * <p>Milliseconds rather than seconds because a short run over one small page finishes in well under a second,
     * and a stored zero would make the average duration look like the timing was never implemented.
     */
    private long durationMillis;

    /** The report this run produced, or {@code null} when it produced none. */
    private String reportId;

    /**
     * The user-facing reason a run did not succeed, or {@code null} when it did.
     *
     * <p>The same sanitised string that was published to the live view - never an exception message, so nothing
     * here can carry a crawled page, a request body, or a credential. That property is what makes it safe to show
     * this field in the Admin Console next to a run that failed hours ago.
     */
    private String failureReason;

    /** Required by Spring Data's mapping layer. */
    public RunRecord() {}

    /**
     * Creates a completed record.
     *
     * @param saasProductId the product analysed
     * @param productName the product's name at the time
     * @param runType standard run or custom-range compare
     * @param analysisDepth the depth used
     * @param outcome how it turned out
     * @param runBy username of whoever triggered it
     * @param startedAt when the work began
     * @param duration how long it took
     * @param reportId the report produced, or {@code null}
     * @param failureReason the user-facing failure reason, or {@code null} on success
     */
    public RunRecord(String saasProductId, String productName, RunType runType, AnalysisDepth analysisDepth,
                     RunOutcome outcome, String runBy, Instant startedAt, Duration duration,
                     String reportId, String failureReason) {
        this.saasProductId = saasProductId;
        this.productName = productName;
        this.runType = runType;
        this.analysisDepth = analysisDepth;
        this.outcome = outcome;
        this.runBy = runBy;
        this.startedAt = startedAt;
        this.durationMillis = duration == null ? 0L : duration.toMillis();
        this.reportId = reportId;
        this.failureReason = failureReason;
    }

    /** @return the Mongo document id */
    public String getId() {
        return id;
    }

    /** @param id the Mongo document id */
    public void setId(String id) {
        this.id = id;
    }

    /** @return the product analysed */
    public String getSaasProductId() {
        return saasProductId;
    }

    /** @param saasProductId the product analysed */
    public void setSaasProductId(String saasProductId) {
        this.saasProductId = saasProductId;
    }

    /** @return the product's name at the time of the run */
    public String getProductName() {
        return productName;
    }

    /** @param productName the product's name at the time of the run */
    public void setProductName(String productName) {
        this.productName = productName;
    }

    /** @return standard run or custom-range compare */
    public RunType getRunType() {
        return runType;
    }

    /** @param runType standard run or custom-range compare */
    public void setRunType(RunType runType) {
        this.runType = runType;
    }

    /** @return the depth used */
    public AnalysisDepth getAnalysisDepth() {
        return analysisDepth;
    }

    /** @param analysisDepth the depth used */
    public void setAnalysisDepth(AnalysisDepth analysisDepth) {
        this.analysisDepth = analysisDepth;
    }

    /** @return how the run turned out */
    public RunOutcome getOutcome() {
        return outcome;
    }

    /** @param outcome how the run turned out */
    public void setOutcome(RunOutcome outcome) {
        this.outcome = outcome;
    }

    /** @return username of whoever triggered it */
    public String getRunBy() {
        return runBy;
    }

    /** @param runBy username of whoever triggered it */
    public void setRunBy(String runBy) {
        this.runBy = runBy;
    }

    /** @return when the work began */
    public Instant getStartedAt() {
        return startedAt;
    }

    /** @param startedAt when the work began */
    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    /** @return wall-clock duration in milliseconds */
    public long getDurationMillis() {
        return durationMillis;
    }

    /** @param durationMillis wall-clock duration in milliseconds */
    public void setDurationMillis(long durationMillis) {
        this.durationMillis = durationMillis;
    }

    /** @return the report produced, or {@code null} */
    public String getReportId() {
        return reportId;
    }

    /** @param reportId the report produced, or {@code null} */
    public void setReportId(String reportId) {
        this.reportId = reportId;
    }

    /** @return the user-facing failure reason, or {@code null} on success */
    public String getFailureReason() {
        return failureReason;
    }

    /** @param failureReason the user-facing failure reason */
    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }
}
