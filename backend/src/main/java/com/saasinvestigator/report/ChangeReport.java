package com.saasinvestigator.report;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The output of one run or compare: what changed, stored in the {@code change_reports} collection.
 *
 * <p>This is the application's actual deliverable. Everything else - the crawler, the snapshots, the provider
 * abstraction, the SSE plumbing - exists to produce one of these. It is written once, at the end of a run, and
 * never edited: a report is a statement about a moment, and a report that can be revised is a report that cannot be
 * cited. There is no update endpoint for the same reason there is none for an audit entry.
 *
 * <p><b>Nothing in this class or anywhere behind it decided what counts as a change.</b> The contents of
 * {@code changes} and {@code overallSummary} come from the model; the backend's contribution is the metadata around
 * them - who asked, when, over what window, at what depth, from which sources, and whether any of it came with a
 * caveat. See {@code docs/ARCHITECTURE.md} on why that division is the whole design and not an implementation
 * detail.
 *
 * <p>The metadata fields are not decoration. A report whose provenance is unknown - which sources, as of when, how
 * hard the model was asked to look - is a paragraph of plausible text, and the honest response to one is to not
 * rely on it.
 */
@Document(collection = "change_reports")
public class ChangeReport {

    @Id
    private String id;

    /** Which product this report is about. Indexed with {@code runAt} descending for the History timeline. */
    private String saasProductId;

    private Instant runAt;

    /** Username of whoever triggered this. Denormalised, like an audit entry's actor. */
    private String runBy;

    private RunType runType;

    /** The verbosity chosen for this invocation, so History can show which depth produced which report. */
    private AnalysisDepth analysisDepth;

    /** Start of the compared window. Set only for {@link RunType#CUSTOM_RANGE}. */
    private Instant rangeFrom;

    /** End of the compared window. Set only for {@link RunType#CUSTOM_RANGE}. */
    private Instant rangeTo;

    /**
     * {@code true} when some part of this report came from aggregated earlier reports rather than from a real
     * before-and-after comparison.
     *
     * <p>Set on a custom-range compare that involved MCP sources. The backend never stores MCP content, so there is
     * no historical state for those sources to compare against, and their portion of the report is assembled from
     * what earlier reports said about them within the window.
     *
     * <p>Carried as an explicit field, and surfaced in the UI and in both export formats, because the alternative
     * is a report that silently mixes two different standards of evidence. A reader who does not know which they
     * are holding will assume the stronger one.
     */
    private boolean mcpHistoryLimited;

    /**
     * Which sources contributed and as of when.
     *
     * <p>Not derivable from {@code changes}: a source that was consulted and found nothing appears here and
     * nowhere else, which is the only thing that distinguishes "quiet" from "skipped".
     */
    private List<SourceInclusion> sourcesIncluded = new ArrayList<>();

    /** The model's prose summary across all sources. What a reader sees before expanding anything. */
    private String overallSummary;

    /** Every individual finding. May legitimately be empty - a run over a quiet week should produce none. */
    private List<Change> changes = new ArrayList<>();

    /** Required by Spring Data's mapping layer. */
    public ChangeReport() {}

    /**
     * Creates a standard-run report with {@code runAt} stamped and no date range.
     *
     * @param saasProductId the product this is about
     * @param runBy username of whoever triggered the run
     * @param analysisDepth the depth chosen for this invocation
     * @param sourcesIncluded which sources contributed; copied defensively, {@code null} treated as empty
     * @param overallSummary the model's prose summary
     * @param changes the individual findings; copied defensively, {@code null} treated as empty
     */
    public ChangeReport(String saasProductId, String runBy, AnalysisDepth analysisDepth,
                        List<SourceInclusion> sourcesIncluded, String overallSummary, List<Change> changes) {
        this.saasProductId = saasProductId;
        this.runBy = runBy;
        this.runType = RunType.STANDARD;
        this.analysisDepth = analysisDepth;
        this.sourcesIncluded = sourcesIncluded == null ? new ArrayList<>() : new ArrayList<>(sourcesIncluded);
        this.overallSummary = overallSummary;
        this.changes = changes == null ? new ArrayList<>() : new ArrayList<>(changes);
        this.runAt = Instant.now();
    }

    /**
     * Marks this report as covering a user-chosen window.
     *
     * <p>A method rather than a second constructor with two extra nullable {@code Instant}s, which at a call site
     * is indistinguishable from the standard form and easy to pass {@code null}, {@code null} to by accident. This
     * way the range and the {@link RunType#CUSTOM_RANGE} that makes it meaningful are set together and cannot
     * disagree.
     *
     * @param from start of the window
     * @param to end of the window
     * @param mcpHistoryLimited whether any MCP source's portion came from aggregated history
     * @return this report, for chaining
     */
    public ChangeReport asCustomRange(Instant from, Instant to, boolean mcpHistoryLimited) {
        this.runType = RunType.CUSTOM_RANGE;
        this.rangeFrom = from;
        this.rangeTo = to;
        this.mcpHistoryLimited = mcpHistoryLimited;
        return this;
    }

    /**
     * Groups the findings by category, for the History detail view and both export formats.
     *
     * <p>Lives on the model rather than in each of the three consumers, so the PDF, the DOCX, and the UI cannot
     * group differently and quietly disagree about what a report says.
     *
     * @return findings by category; categories with no findings are absent, and insertion order follows
     *     {@link ChangeCategory}'s declaration order so the grouping is stable between reports
     */
    public Map<ChangeCategory, List<Change>> changesByCategory() {
        return changes.stream().collect(Collectors.groupingBy(
                Change::category,
                () -> new java.util.EnumMap<>(ChangeCategory.class),
                Collectors.toList()));
    }

    /** @return the Mongo document id */
    public String getId() {
        return id;
    }

    /** @param id the Mongo document id */
    public void setId(String id) {
        this.id = id;
    }

    /** @return the product this report is about */
    public String getSaasProductId() {
        return saasProductId;
    }

    /** @param saasProductId the product this report is about */
    public void setSaasProductId(String saasProductId) {
        this.saasProductId = saasProductId;
    }

    /** @return when the run that produced this report started being recorded */
    public Instant getRunAt() {
        return runAt;
    }

    /** @param runAt when the run happened */
    public void setRunAt(Instant runAt) {
        this.runAt = runAt;
    }

    /** @return username of whoever triggered the run */
    public String getRunBy() {
        return runBy;
    }

    /** @param runBy username of whoever triggered the run */
    public void setRunBy(String runBy) {
        this.runBy = runBy;
    }

    /** @return whether this was a live run or a compare over stored history */
    public RunType getRunType() {
        return runType;
    }

    /** @param runType whether this was a live run or a compare over stored history */
    public void setRunType(RunType runType) {
        this.runType = runType;
    }

    /** @return the depth chosen for this invocation */
    public AnalysisDepth getAnalysisDepth() {
        return analysisDepth;
    }

    /** @param analysisDepth the depth chosen for this invocation */
    public void setAnalysisDepth(AnalysisDepth analysisDepth) {
        this.analysisDepth = analysisDepth;
    }

    /** @return start of the compared window, or {@code null} for a standard run */
    public Instant getRangeFrom() {
        return rangeFrom;
    }

    /** @param rangeFrom start of the compared window */
    public void setRangeFrom(Instant rangeFrom) {
        this.rangeFrom = rangeFrom;
    }

    /** @return end of the compared window, or {@code null} for a standard run */
    public Instant getRangeTo() {
        return rangeTo;
    }

    /** @param rangeTo end of the compared window */
    public void setRangeTo(Instant rangeTo) {
        this.rangeTo = rangeTo;
    }

    /** @return whether part of this report came from aggregated history rather than a real comparison */
    public boolean isMcpHistoryLimited() {
        return mcpHistoryLimited;
    }

    /** @param mcpHistoryLimited whether part of this report came from aggregated history */
    public void setMcpHistoryLimited(boolean mcpHistoryLimited) {
        this.mcpHistoryLimited = mcpHistoryLimited;
    }

    /** @return which sources contributed and as of when. Never {@code null}. */
    public List<SourceInclusion> getSourcesIncluded() {
        return sourcesIncluded;
    }

    /** @param sourcesIncluded which sources contributed; {@code null} is treated as empty */
    public void setSourcesIncluded(List<SourceInclusion> sourcesIncluded) {
        this.sourcesIncluded = sourcesIncluded == null ? new ArrayList<>() : sourcesIncluded;
    }

    /** @return the model's prose summary */
    public String getOverallSummary() {
        return overallSummary;
    }

    /** @param overallSummary the model's prose summary */
    public void setOverallSummary(String overallSummary) {
        this.overallSummary = overallSummary;
    }

    /** @return the individual findings. Never {@code null}; legitimately empty for a quiet run. */
    public List<Change> getChanges() {
        return changes;
    }

    /** @param changes the individual findings; {@code null} is treated as empty */
    public void setChanges(List<Change> changes) {
        this.changes = changes == null ? new ArrayList<>() : changes;
    }
}
