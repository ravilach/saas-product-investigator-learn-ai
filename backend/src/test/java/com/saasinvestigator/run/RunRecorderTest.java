package com.saasinvestigator.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saasinvestigator.product.SaasProduct;
import com.saasinvestigator.report.AnalysisDepth;
import com.saasinvestigator.report.RunType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Tests {@link RunRecorder}, whose entire reason for existing is one guarantee.
 *
 * <h2>The guarantee</h2>
 *
 * <p><b>Bookkeeping cannot change an outcome.</b> This write happens in the orchestrator's {@code finally} block, after
 * the report has been saved and after the caller has been told the run succeeded. If it threw:
 *
 * <ul>
 *   <li>on the success path, a completed analysis with a stored report would be reported to the user as a failure;</li>
 *   <li>on the failure path, an exception from {@code finally} would <em>replace</em> the real cause - so the log line
 *       somebody is about to read to find out why the run failed would say "database unavailable" instead.</li>
 * </ul>
 *
 * <p>Both of those are worse than the alternative, which is one missing row in the Admin Console's statistics. The
 * swallow is therefore deliberate, and it is the kind of {@code catch} that looks like a mistake to anybody tidying up -
 * hence a test that fails if it is removed.
 *
 * <p>The rest of these tests pin down what the row contains, because two of its fields are denormalised copies and one
 * of them is the sanitised failure text. A record holding a raw exception message would put crawled page content and MCP
 * tokens into a collection the Data Explorer displays.
 */
@ExtendWith(MockitoExtension.class)
class RunRecorderTest {

    private static final Instant STARTED_AT = Instant.parse("2026-06-01T09:30:00Z");

    @Mock
    private RunRecordRepository records;

    // ----- The guarantee -----

    @Test
    void swallowsARepositoryFailureSoThatLosingAStatisticsRowCannotTurnASuccessIntoAFailure() {
        when(records.save(any()))
                .thenThrow(new DataAccessResourceFailureException("Mongo is unreachable"));

        assertThatCode(() -> new RunRecorder(records).record(product(), RunType.STANDARD, AnalysisDepth.REGULAR,
                RunOutcome.SUCCESS, "dana", STARTED_AT, Duration.ofSeconds(20), "report-1", null))
                .doesNotThrowAnyException();
    }

    @Test
    void swallowsAFailureOnTheFailurePathTooSoTheRealCauseIsNotReplacedByADatabaseError() {
        // The more damaging of the two cases: this runs inside finally, so an exception here discards the exception that
        // explains why the run failed at all.
        when(records.save(any()))
                .thenThrow(new DataAccessResourceFailureException("Mongo is unreachable"));

        assertThatCode(() -> new RunRecorder(records).record(product(), RunType.STANDARD, AnalysisDepth.NUCLEAR,
                RunOutcome.FAILURE, "dana", STARTED_AT, Duration.ofSeconds(3), null,
                "The analysis could not be completed."))
                .doesNotThrowAnyException();
    }

    // ----- What the row contains -----

    @Test
    void denormalisesTheProductNameSoARunSurvivesTheProductBeingDeleted() {
        new RunRecorder(records).record(product(), RunType.STANDARD, AnalysisDepth.REGULAR,
                RunOutcome.SUCCESS, "dana", STARTED_AT, Duration.ofSeconds(20), "report-1", null);

        RunRecord saved = savedRecord();
        assertThat(saved.getSaasProductId()).isEqualTo("product-1");
        // Copied rather than joined: deleting a product cascades its reports away, and the Admin Console's recent
        // activity list would otherwise show a dangling id where a name used to be.
        assertThat(saved.getProductName()).isEqualTo("Acme Analytics");
        assertThat(saved.getRunBy()).isEqualTo("dana");
        assertThat(saved.getRunType()).isEqualTo(RunType.STANDARD);
        assertThat(saved.getAnalysisDepth()).isEqualTo(AnalysisDepth.REGULAR);
        assertThat(saved.getOutcome()).isEqualTo(RunOutcome.SUCCESS);
        assertThat(saved.getStartedAt()).isEqualTo(STARTED_AT);
        assertThat(saved.getReportId()).isEqualTo("report-1");
        assertThat(saved.getFailureReason()).isNull();
    }

    @Test
    void storesTheDurationAsMillisecondsSoTheAverageCanBeAggregatedInTheDatabase() {
        new RunRecorder(records).record(product(), RunType.STANDARD, AnalysisDepth.SHORT,
                RunOutcome.SUCCESS, "dana", STARTED_AT, Duration.ofSeconds(20), "report-1", null);

        // A Duration would be stored as an object Mongo cannot average. The dashboard divides by 1000 to display it,
        // and a unit mismatch between the two ends looks plausible on screen for a long time.
        assertThat(savedRecord().getDurationMillis()).isEqualTo(20_000L);
    }

    @Test
    void recordsAFailureWithNoReportIdAndOnlyTheSanitisedReason() {
        new RunRecorder(records).record(product(), RunType.CUSTOM_RANGE, AnalysisDepth.NUCLEAR,
                RunOutcome.FAILURE, "dana", STARTED_AT, Duration.ofSeconds(3), null,
                "The analysis could not be completed.");

        RunRecord saved = savedRecord();
        assertThat(saved.getOutcome()).isEqualTo(RunOutcome.FAILURE);
        assertThat(saved.getReportId()).isNull();
        // The sanitised text the orchestrator produced, never an SDK exception message - those can contain the request
        // body, which here means crawled page content and MCP authorization tokens. This collection is displayed in the
        // Data Explorer, so anything stored in it is visible to an admin.
        assertThat(saved.getFailureReason()).isEqualTo("The analysis could not be completed.");
    }

    @Test
    void recordsAPartialRunWithTheReportItStillProducedRatherThanAsAFailure() {
        // PARTIAL is a run that reached the model and stored a report with some sources missing. Recording it without
        // the report id would make the Admin Console's "last successful run" skip a run that plainly succeeded enough.
        new RunRecorder(records).record(product(), RunType.STANDARD, AnalysisDepth.REGULAR,
                RunOutcome.PARTIAL, "dana", STARTED_AT, Duration.ofSeconds(18), "report-1",
                "One source could not be reached.");

        RunRecord saved = savedRecord();
        assertThat(saved.getOutcome()).isEqualTo(RunOutcome.PARTIAL);
        assertThat(saved.getReportId()).isEqualTo("report-1");
    }

    @Test
    void toleratesANullDurationRatherThanFailingToRecordTheRunAtAll() {
        // Defensive, because this is called from a finally block where the start time may not have been reached. Zero is
        // a wrong average of one; no row at all is a hole in the count as well.
        new RunRecorder(records).record(product(), RunType.STANDARD, AnalysisDepth.REGULAR,
                RunOutcome.FAILURE, "dana", STARTED_AT, null, null, "The analysis could not be completed.");

        assertThat(savedRecord().getDurationMillis()).isZero();
    }

    // ----- Helpers -----

    private RunRecord savedRecord() {
        ArgumentCaptor<RunRecord> captor = ArgumentCaptor.captor();
        verify(records).save(captor.capture());
        return captor.getValue();
    }

    private static SaasProduct product() {
        SaasProduct product = new SaasProduct("Acme Analytics", null, List.of(), "admin");
        product.setId("product-1");
        return product;
    }
}
