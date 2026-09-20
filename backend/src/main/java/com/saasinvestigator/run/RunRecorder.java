package com.saasinvestigator.run;

import com.saasinvestigator.product.SaasProduct;
import com.saasinvestigator.report.AnalysisDepth;
import com.saasinvestigator.report.RunType;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Writes one {@link RunRecord} per finished run, and never lets that write break the run.
 *
 * <p>A separate service rather than a repository call inside {@link RunOrchestrator} for one reason, which is the
 * whole of this class: <b>bookkeeping must not be able to change an outcome.</b> The record is written from the
 * orchestrator's {@code finally} block, after the user has already been told the run succeeded and after the report
 * has been saved. If the database rejected that last insert and the exception escaped, a completed analysis would be
 * reported to the caller as a failure - and on the failure path, an exception thrown from {@code finally} would
 * replace the real cause with a database error, which is the worst possible thing to do to the log line someone is
 * about to read.
 *
 * <p>So every exception is caught and logged here. Losing a statistics row is a visible gap in the Admin Console;
 * losing a report or a diagnosis is not recoverable.
 */
@Service
public class RunRecorder {

    private static final Logger log = LoggerFactory.getLogger(RunRecorder.class);

    private final RunRecordRepository records;

    /**
     * @param records the collection this writes to
     */
    public RunRecorder(RunRecordRepository records) {
        this.records = records;
    }

    /**
     * Records how a run turned out.
     *
     * @param product the product analysed, read only for its id and name
     * @param runType standard run or custom-range compare
     * @param depth the depth used
     * @param outcome how it turned out
     * @param runBy username of whoever triggered it
     * @param startedAt when the work began
     * @param duration how long it took
     * @param reportId the report produced, or {@code null} when none was
     * @param failureReason the sanitised, user-facing reason it did not succeed, or {@code null}
     */
    public void record(SaasProduct product,
                       RunType runType,
                       AnalysisDepth depth,
                       RunOutcome outcome,
                       String runBy,
                       Instant startedAt,
                       Duration duration,
                       String reportId,
                       String failureReason) {
        try {
            records.save(new RunRecord(product.getId(), product.getName(), runType, depth, outcome, runBy,
                    startedAt, duration, reportId, failureReason));
        } catch (RuntimeException e) {
            log.warn("Could not record the outcome of a {} of product '{}'. The run itself was unaffected; the "
                    + "Admin Console's statistics will be missing this one row.", runType, product.getName(), e);
        }
    }
}
