package com.saasinvestigator.run;

import com.saasinvestigator.report.AnalysisDepth;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Body of {@code POST /api/saas-products/{id}/compare}.
 *
 * <h2>Why dates and not instants</h2>
 *
 * <p>The UI asks this question with a calendar, and a calendar has no opinion about time of day. Accepting
 * {@code Instant} would force the frontend to invent one - and whichever one it invented would be wrong for somebody,
 * because "compare 1 June to 15 June" from a browser in Auckland and the same request from Los Angeles would cover
 * different windows and produce different reports from identical user input.
 *
 * <p>So the wire format is a plain {@code yyyy-MM-dd} date and the window is resolved in UTC, here, once. Snapshots
 * are stamped in UTC, reports are stamped in UTC, and the audit log records UTC; interpreting a compare range in any
 * other zone would be the only place in the system that did something else.
 *
 * <h2>How the two ends differ</h2>
 *
 * <p>{@link #fromInstant()} is the start of its day and {@link #toInstant()} is the <em>end</em> of its day. That
 * asymmetry is the point: a user who picks the same date for both ends means "that day", and a user who picks today
 * as the end means "including the run I just did". Resolving {@code toDate} to midnight would exclude every capture
 * taken on the day the user actually named, which is an off-by-one-day bug that looks like missing data.
 *
 * @param fromDate first day of the window, inclusive
 * @param toDate last day of the window, inclusive
 * @param analysisDepth how thorough to be, or {@code null} for {@link AnalysisDepth#DEFAULT}
 */
public record CompareRequest(
        @NotNull(message = "fromDate is required, as yyyy-MM-dd") LocalDate fromDate,
        @NotNull(message = "toDate is required, as yyyy-MM-dd") LocalDate toDate,
        AnalysisDepth analysisDepth) {

    /**
     * @return midnight UTC at the start of {@code fromDate}
     */
    public Instant fromInstant() {
        return fromDate.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /**
     * @return the last instant of {@code toDate} in UTC, so captures taken during that day are inside the window
     */
    public Instant toInstant() {
        return toDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusMillis(1);
    }
}
