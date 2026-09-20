package com.saasinvestigator.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.saasinvestigator.report.AnalysisDepth;
import com.saasinvestigator.report.ChangeReport;
import com.saasinvestigator.report.RunType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests the registry of live runs: what it hands out, and what it lets go of.
 *
 * <p>The eviction windows are the substance here, and they are asserted through {@link RunEventStream#expired} with an
 * explicit {@code now} rather than by sleeping - a test that genuinely waited out the fifteen-minute retention would
 * take fifteen minutes, and one that waited a second would assert nothing.
 *
 * <p>The two windows encode two different judgements. A <em>finished</em> run is kept for a while because somebody may
 * reload, share a link, or come back from lunch; that is a convenience with a clear expiry. An <em>unfinished</em> one
 * is kept much longer and its eviction is logged as a warning, because a session that never published a terminal event
 * is a bug rather than a slow run - the providers time out well inside the window.
 */
class RunEventStreamTest {

    private RunEventStream stream;

    @BeforeEach
    void setUp() {
        stream = new RunEventStream();
    }

    @AfterEach
    void tearDown() {
        stream.shutdown();
    }

    private static ChangeReport report() {
        return new ChangeReport("product-1", "dana", AnalysisDepth.REGULAR, List.of(), "All quiet.", List.of());
    }

    // ---------------------------------------------------------------------
    // Handing out sessions
    // ---------------------------------------------------------------------

    @Test
    void eachRunGetsItsOwnUnguessableIdAndIsFindableByIt() {
        RunSession first = stream.open("product-1", RunType.STANDARD);
        RunSession second = stream.open("product-1", RunType.CUSTOM_RANGE);

        assertThat(first.runId()).isNotEqualTo(second.runId());
        assertThat(stream.find(first.runId())).containsSame(first);
        assertThat(stream.find(second.runId())).containsSame(second);
        assertThat(second.runType()).isEqualTo(RunType.CUSTOM_RANGE);
    }

    @Test
    void anUnknownIdIsEmptyRatherThanAnError() {
        // The endpoint turns this into a 404 whose message mentions expiry, because from here "never existed" and
        // "finished a while ago" are indistinguishable and the second is far more likely.
        assertThat(stream.find("no-such-run")).isEmpty();
    }

    @Test
    void aSessionCanBeDiscardedWhenItsRunCouldNotBeStarted() {
        RunSession session = stream.open("product-1", RunType.STANDARD);

        stream.discard(session.runId());

        // Without this, a run rejected by a full queue would leave a session nobody will ever publish to, and a
        // client would watch it in silence until the 45-minute ceiling.
        assertThat(stream.find(session.runId())).isEmpty();
        assertThat(stream.sessionCount()).isZero();
    }

    @Test
    void activeAndTotalCountsAreTrackedSeparatelyForTheAdminPanel() {
        RunSession running = stream.open("product-1", RunType.STANDARD);
        RunSession finished = stream.open("product-2", RunType.STANDARD);
        finished.completed(report());

        assertThat(stream.sessionCount()).isEqualTo(2);
        assertThat(stream.activeRunCount()).isEqualTo(1);
        assertThat(running.isFinished()).isFalse();
    }

    // ---------------------------------------------------------------------
    // Eviction
    // ---------------------------------------------------------------------

    @Test
    void aFinishedRunStaysReplayableForTheWholeRetentionWindow() {
        RunSession session = stream.open("product-1", RunType.STANDARD);
        session.completed(report());
        Instant finishedAt = session.finishedAt();

        assertThat(RunEventStream.expired(session, finishedAt)).isFalse();
        assertThat(RunEventStream.expired(session,
                finishedAt.plus(RunEventStream.FINISHED_RETENTION).minusSeconds(1))).isFalse();
    }

    @Test
    void aFinishedRunIsDroppedOnceItsRetentionWindowPasses() {
        RunSession session = stream.open("product-1", RunType.STANDARD);
        session.completed(report());

        assertThat(RunEventStream.expired(session,
                session.finishedAt().plus(RunEventStream.FINISHED_RETENTION).plusSeconds(1))).isTrue();
    }

    @Test
    void anUnfinishedRunSurvivesFarLongerThanAFinishedOne() {
        RunSession session = stream.open("product-1", RunType.STANDARD);

        // A nuclear-depth crawl plus a provider call can legitimately run for many minutes, and evicting a session
        // out from under a run in progress would break the live view of the slowest, most expensive runs - exactly
        // the ones somebody is most likely to be watching.
        assertThat(RunEventStream.expired(session,
                session.startedAt().plus(RunEventStream.FINISHED_RETENTION).plusSeconds(60))).isFalse();
        assertThat(RunEventStream.expired(session,
                session.startedAt().plus(RunEventStream.MAX_SESSION_AGE).minusSeconds(1))).isFalse();
    }

    @Test
    void anUnfinishedRunIsEvictedAtTheHardCeilingSoASessionCannotLeakForever() {
        RunSession session = stream.open("product-1", RunType.STANDARD);

        assertThat(RunEventStream.expired(session,
                session.startedAt().plus(RunEventStream.MAX_SESSION_AGE).plusSeconds(1))).isTrue();
    }

    @Test
    void theCeilingIsComfortablyBeyondAnyLegitimateRun() {
        // Stated as an assertion because the relationship is the whole justification for the number: MAX_SESSION_AGE
        // only ever fires on a run that failed to publish a terminal event at all.
        assertThat(RunEventStream.MAX_SESSION_AGE).isGreaterThan(RunEventStream.FINISHED_RETENTION);
        assertThat(RunEventStream.MAX_SESSION_AGE.toMinutes()).isGreaterThanOrEqualTo(45);
    }

    // ---------------------------------------------------------------------
    // Sweeping
    // ---------------------------------------------------------------------

    @Test
    void aSweepKeepsLiveStreamsAliveWithoutTouchingFinishedOnes() {
        RunSession running = stream.open("product-1", RunType.STANDARD);
        RunSession finished = stream.open("product-2", RunType.STANDARD);
        RecordingSink watchingRunning = new RecordingSink();
        RecordingSink watchingFinished = new RecordingSink();
        running.subscribe(watchingRunning);
        finished.subscribe(watchingFinished);
        finished.completed(report());

        stream.sweep();

        // Keep-alives exist so a proxy does not close the connection during the minutes a model call takes. A
        // finished run's stream has already been closed by its terminal event, so writing to it would be pointless.
        assertThat(watchingRunning.keepAlives()).isEqualTo(1);
        assertThat(watchingFinished.keepAlives()).isZero();
    }

    @Test
    void aSweepIntervalWellInsideTheUsualProxyIdleTimeout() {
        // Thirty to sixty seconds is a common idle timeout, so the interval has to leave room for a missed sweep.
        assertThat(RunEventStream.SWEEP_INTERVAL.toSeconds()).isLessThanOrEqualTo(15);
    }

    @Test
    void aSinkThatThrowsSomethingUnexpectedCannotStopFutureSweeps() {
        RunSession session = stream.open("product-1", RunType.STANDARD);
        RecordingSink healthy = new RecordingSink();
        session.subscribe(new RunEventSink() {
            @Override
            public void send(RunEvent event) {
                // Not exercised in this test.
            }

            @Override
            public void keepAlive() {
                throw new IllegalStateException("something unexpected");
            }
        });
        session.subscribe(healthy);

        // The risk being guarded against is specific: a scheduleWithFixedDelay task that throws is silently
        // cancelled forever, so one misbehaving sink would cost every session its keep-alives for the life of the
        // process. Two layers prevent it - the session drops the offender, and sweep() has a catch-all behind that.
        stream.sweep();
        stream.sweep();

        assertThat(session.subscriberCount()).isEqualTo(1);
        assertThat(healthy.keepAlives()).isEqualTo(2);
        assertThat(stream.sessionCount()).isEqualTo(1);
    }
}
