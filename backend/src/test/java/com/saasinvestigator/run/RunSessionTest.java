package com.saasinvestigator.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.saasinvestigator.report.AnalysisDepth;
import com.saasinvestigator.report.ChangeReport;
import com.saasinvestigator.report.RunType;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Tests the buffer-and-replay behaviour the live execution view depends on.
 *
 * <p>Everything here comes back to one fact about the HTTP flow: {@code POST /run} returns a {@code runId} and the
 * client opens the event stream in a <em>second</em> request. The run does not wait for that, so by the time anybody is
 * watching, the first crawl has often finished. Every guarantee asserted below exists to make that gap invisible:
 *
 * <ul>
 *   <li>A late subscriber is replayed the whole history, in order, exactly once.</li>
 *   <li>Replay and registration are one atomic step, so an event published at that moment is neither duplicated nor
 *       lost. This is the assertion that would fail if the {@code synchronized} keywords were removed, and the symptom
 *       in production would be an occasional doubled progress line - easy to dismiss, impossible to reproduce.</li>
 *   <li>A run that finished before anyone subscribed still delivers its entire story, including the report.</li>
 * </ul>
 */
class RunSessionTest {

    private RunSession session() {
        return new RunSession("run-1", "product-1", RunType.STANDARD);
    }

    private static ChangeReport report() {
        return new ChangeReport("product-1", "dana", AnalysisDepth.REGULAR, List.of(), "All quiet.", List.of());
    }

    // ---------------------------------------------------------------------
    // Replay
    // ---------------------------------------------------------------------

    @Test
    void aSubscriberArrivingLateIsReplayedEverythingItMissedInOrder() {
        RunSession session = session();
        session.stepStarted(RunStep.FETCHING_SOURCES, "Crawling 2 sources");
        session.stepProgress(RunStep.FETCHING_SOURCES, "Crawling https://example.com (page 1 of ~20)");
        session.stepCompleted(RunStep.FETCHING_SOURCES, "2 sources read");

        RecordingSink sink = new RecordingSink();
        boolean alreadyFinished = session.subscribe(sink);

        assertThat(alreadyFinished).isFalse();
        assertThat(sink.types()).containsExactly(
                RunEventType.STEP_STARTED, RunEventType.STEP_PROGRESS, RunEventType.STEP_COMPLETED);
        assertThat(sink.details()).containsExactly(
                "Crawling 2 sources", "Crawling https://example.com (page 1 of ~20)", "2 sources read");
    }

    @Test
    void afterReplayTheSubscriberKeepsReceivingLiveEvents() {
        RunSession session = session();
        session.stepStarted(RunStep.FETCHING_SOURCES);

        RecordingSink sink = new RecordingSink();
        session.subscribe(sink);
        session.stepCompleted(RunStep.FETCHING_SOURCES, "done");

        // Replayed once, then live - and crucially the replayed event is not delivered again by the live path.
        assertThat(sink.types()).containsExactly(RunEventType.STEP_STARTED, RunEventType.STEP_COMPLETED);
    }

    @Test
    void aRunThatFinishedBeforeAnybodyWatchedStillTellsItsWholeStory() {
        RunSession session = session();
        session.stepStarted(RunStep.FETCHING_SOURCES);
        session.completed(report());

        RecordingSink sink = new RecordingSink();
        boolean alreadyFinished = session.subscribe(sink);

        // True is the signal the controller uses to close the emitter, so a fast run does not leave a stream open for
        // thirty minutes waiting for events that cannot come.
        assertThat(alreadyFinished).isTrue();
        assertThat(sink.types()).containsExactly(RunEventType.STEP_STARTED, RunEventType.RUN_COMPLETED);
        assertThat(session.subscriberCount()).isZero();
    }

    @Test
    void twoWatchersOfTheSameRunSeeIdenticalStreams() {
        RunSession session = session();
        RecordingSink first = new RecordingSink();
        session.subscribe(first);
        session.stepStarted(RunStep.COMPARING, "Analyzing 2 sources against prior snapshots...");

        RecordingSink second = new RecordingSink();
        session.subscribe(second);
        session.stepCompleted(RunStep.COMPARING, "3 changes detected");

        // Same events, same order, regardless of when each one arrived. This is what makes a shared run link work.
        assertThat(second.received()).isEqualTo(first.received());
    }

    @Test
    void theHistoryIsACopySoAReaderCannotBeTrippedByAConcurrentPublication() {
        RunSession session = session();
        session.stepStarted(RunStep.FETCHING_SOURCES);
        List<RunEvent> snapshot = session.history();

        session.stepCompleted(RunStep.FETCHING_SOURCES, "done");

        assertThat(snapshot).hasSize(1);
        assertThat(session.history()).hasSize(2);
    }

    // ---------------------------------------------------------------------
    // The atomicity of replay-then-register
    // ---------------------------------------------------------------------

    @Test
    void anEventPublishedAtTheMomentOfSubscribingIsDeliveredExactlyOnce() throws Exception {
        // The race this guards against is real rather than theoretical: the publisher is a run thread and the
        // subscriber is a request thread, and the window is exactly as long as a fast first crawl.
        for (int attempt = 0; attempt < 200; attempt++) {
            RunSession session = session();
            session.stepStarted(RunStep.FETCHING_SOURCES, "first");

            RecordingSink sink = new RecordingSink();
            CountDownLatch ready = new CountDownLatch(2);

            Thread publisher = new Thread(() -> {
                ready.countDown();
                await(ready);
                session.stepProgress(RunStep.FETCHING_SOURCES, "racing");
            });
            Thread subscriber = new Thread(() -> {
                ready.countDown();
                await(ready);
                session.subscribe(sink);
            });

            publisher.start();
            subscriber.start();
            publisher.join(5_000);
            subscriber.join(5_000);

            // Whichever order they landed in, "racing" must appear exactly once - never twice (replayed and
            // delivered) and never zero times (registered before the replay snapshot was taken).
            assertThat(sink.details().stream().filter("racing"::equals).count())
                    .as("attempt %d saw %s", attempt, sink.details())
                    .isEqualTo(1);
            assertThat(sink.details()).startsWith("first");
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------------
    // Terminal events
    // ---------------------------------------------------------------------

    @Test
    void theCompletionEventCarriesTheReportSoTheClientNeedsNoFollowUpRequest() {
        RunSession session = session();
        RecordingSink sink = new RecordingSink();
        session.subscribe(sink);

        ChangeReport saved = report();
        session.completed(saved);

        // A follow-up fetch would race the stream closing, and would show a spinner at the exact moment the user
        // finally has an answer.
        assertThat(sink.received()).last().satisfies(event -> {
            assertThat(event.type()).isEqualTo(RunEventType.RUN_COMPLETED);
            assertThat(event.report()).isSameAs(saved);
            assertThat(event.step()).isNull();
        });
    }

    @Test
    void aFailureWithNoReasonStillSaysSomethingRatherThanNothing() {
        RunSession session = session();
        RecordingSink sink = new RecordingSink();
        session.subscribe(sink);

        session.failed(null);

        assertThat(sink.received()).last().satisfies(event -> {
            assertThat(event.type()).isEqualTo(RunEventType.RUN_FAILED);
            assertThat(event.detail()).isEqualTo("The analysis failed.");
        });
    }

    @Test
    void anythingPublishedAfterATerminalEventIsDroppedRatherThanEndingTheStreamTwice() {
        RunSession session = session();
        RecordingSink sink = new RecordingSink();
        session.subscribe(sink);

        session.failed("Every source was unreachable.");
        session.stepStarted(RunStep.SUMMARIZING);
        session.completed(report());

        // A stream that ends twice is a stream no client can render. This is a guard against an orchestrator bug,
        // not an expected path, which is why it logs a warning.
        assertThat(sink.types()).containsExactly(RunEventType.RUN_FAILED);
        assertThat(session.history()).hasSize(1);
    }

    @Test
    void finishingIsObservableSoTheSweeperKnowsWhenRetentionStarts() {
        RunSession session = session();
        assertThat(session.isFinished()).isFalse();
        assertThat(session.finishedAt()).isNull();

        session.completed(report());

        assertThat(session.isFinished()).isTrue();
        assertThat(session.finishedAt()).isNotNull().isAfterOrEqualTo(session.startedAt());
    }

    // ---------------------------------------------------------------------
    // Disconnected clients
    // ---------------------------------------------------------------------

    @Test
    void aSubscriberThatDisconnectsIsDroppedAndTheRunCarriesOn() {
        RunSession session = session();
        RecordingSink going = new RecordingSink();
        RecordingSink staying = new RecordingSink();
        session.subscribe(going);
        session.subscribe(staying);

        going.disconnect();
        session.stepStarted(RunStep.COMPARING, "Analyzing...");

        // The run is doing real, paid work; a closed tab is not a reason to stop it.
        assertThat(session.subscriberCount()).isEqualTo(1);
        assertThat(staying.types()).containsExactly(RunEventType.STEP_STARTED);
    }

    @Test
    void aSubscriberThatDisconnectsDuringReplayIsNeverRegistered() {
        RunSession session = session();
        session.stepStarted(RunStep.FETCHING_SOURCES);

        RecordingSink sink = new RecordingSink();
        sink.disconnect();
        boolean complete = session.subscribe(sink);

        assertThat(complete).isTrue();
        assertThat(session.subscriberCount()).isZero();
    }

    @Test
    void aKeepAliveDropsSubscribersThatHaveGoneAwayQuietly() {
        RunSession session = session();
        RecordingSink live = new RecordingSink();
        RecordingSink gone = new RecordingSink();
        session.subscribe(live);
        session.subscribe(gone);
        gone.disconnect();

        session.keepAlive();

        // Without this, a run whose only viewer left would keep a dead sink in the list for the whole comparison
        // step, and every event would pay for a failed write.
        assertThat(live.keepAlives()).isEqualTo(1);
        assertThat(session.subscriberCount()).isEqualTo(1);
    }

    // ---------------------------------------------------------------------
    // Event shape
    // ---------------------------------------------------------------------

    @Test
    void eventsCarryTheStepsDisplayLabelRatherThanItsEnumName() {
        RunSession session = session();
        RecordingSink sink = new RecordingSink();
        session.subscribe(sink);

        session.stepStarted(RunStep.AGGREGATING_MCP_HISTORY);

        // The label is sent so the UI owns no translation table, and so a renamed step changes in one place.
        assertThat(sink.received()).singleElement().satisfies(event -> {
            assertThat(event.step()).isEqualTo("Aggregating MCP history");
            assertThat(event.runId()).isEqualTo("run-1");
            assertThat(event.elapsedSeconds()).isGreaterThanOrEqualTo(0);
        });
    }

    @Test
    void aStepStartWithNoDetailSendsNoDetailRatherThanAPlaceholder() {
        RunSession session = session();
        RecordingSink sink = new RecordingSink();
        session.subscribe(sink);

        session.stepStarted(RunStep.SUMMARIZING);

        // Null is omitted from the JSON entirely, so the UI shows the step name alone. An invented placeholder -
        // "Working..." - is exactly the generic narration the live view exists to avoid.
        assertThat(sink.received()).singleElement()
                .satisfies(event -> assertThat(event.detail()).isNull());
    }
}
