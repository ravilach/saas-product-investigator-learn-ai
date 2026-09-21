package com.saasinvestigator.run;

import com.saasinvestigator.report.ChangeReport;
import com.saasinvestigator.report.ChangeReportResponse;
import com.saasinvestigator.report.RunType;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One in-flight or recently-finished run, and the events it has emitted.
 *
 * <h2>Why this buffers</h2>
 *
 * <p>{@code POST /run} returns {@code 202 Accepted} with a {@code runId} and the client then opens
 * {@code GET /runs/{runId}/events}. Those are two separate HTTP requests, and the run starts working immediately -
 * so between the 202 being written and the {@code EventSource} connecting, the first crawl has often already
 * finished. Without a buffer the user would open the live view and find the first step already missing, or worse,
 * open it after a fast run had completed and see nothing at all forever.
 *
 * <p>So every event is kept, and a subscriber is replayed the whole history the moment it arrives. This also makes
 * the stream survive a reload, gives two people watching the same run identical views, and means the UI needs no
 * "did I miss anything?" reconciliation logic.
 *
 * <h2>The one concurrency rule</h2>
 *
 * <p>{@link #publish} and {@link #subscribe} are synchronized on the same monitor, and that is the invariant the
 * whole class rests on. A subscriber has to snapshot the history and register itself <em>atomically</em>: if those
 * were two steps, an event published in between would be both replayed and delivered live (a duplicate) or neither
 * (a gap), depending on the order. Since the publisher is a run thread and the subscriber is a request thread, that
 * interleaving is not hypothetical - it is what happens when a run's first crawl finishes quickly.
 *
 * <p>Holding a lock while writing to the network is normally worth flagging, and here it is deliberate: the
 * critical section is short, the writes are to a buffered response, and a failing subscriber is dropped rather than
 * retried. The alternative - per-subscriber queues drained by their own threads - buys nothing but a thread per
 * open browser tab.
 */
public class RunSession {

    private static final Logger log = LoggerFactory.getLogger(RunSession.class);

    private final String runId;
    private final String saasProductId;
    private final RunType runType;
    private final Instant startedAt;

    private final List<RunEvent> history = new ArrayList<>();
    private final List<RunEventSink> subscribers = new ArrayList<>();

    /** Set once a terminal event is published; the moment it happened drives eviction. */
    private Instant finishedAt;

    /**
     * @param runId the opaque id handed back to the client by the triggering request
     * @param saasProductId the product being analysed, for logging and for eviction bookkeeping
     * @param runType which flow this is, so a log line about a stalled session says which kind
     */
    RunSession(String runId, String saasProductId, RunType runType) {
        this.runId = runId;
        this.saasProductId = saasProductId;
        this.runType = runType;
        this.startedAt = Instant.now();
    }

    /** @return the id clients use to find this session */
    public String runId() {
        return runId;
    }

    /** @return the product being analysed */
    public String saasProductId() {
        return saasProductId;
    }

    /** @return whether this is a standard run or a custom-range compare */
    public RunType runType() {
        return runType;
    }

    /** @return when the session was opened, which is what {@code elapsedSeconds} is measured from */
    public Instant startedAt() {
        return startedAt;
    }

    /** @return when a terminal event was published, or {@code null} while the run is still in flight */
    public synchronized Instant finishedAt() {
        return finishedAt;
    }

    /** @return {@code true} once a terminal event has been published */
    public synchronized boolean isFinished() {
        return finishedAt != null;
    }

    /** Announces the start of a step. */
    public void stepStarted(RunStep step) {
        publish(RunEventType.STEP_STARTED, step, null);
    }

    /**
     * Announces the start of a step, saying what it is about to do.
     *
     * @param step the step beginning
     * @param detail what it is about to work on, e.g. {@code "Analyzing 4 sources against prior snapshots..."}
     */
    public void stepStarted(RunStep step, String detail) {
        publish(RunEventType.STEP_STARTED, step, detail);
    }

    /**
     * Reports something specific that just happened inside a step.
     *
     * @param step the step in progress
     * @param detail what happened, in words a user can read - a URL and a page count, a tool name
     */
    public void stepProgress(RunStep step, String detail) {
        publish(RunEventType.STEP_PROGRESS, step, detail);
    }

    /**
     * Marks a step finished.
     *
     * @param step the step that finished
     * @param detail a summary of what it produced, e.g. {@code "4 sources, 31 pages"}
     */
    public void stepCompleted(RunStep step, String detail) {
        publish(RunEventType.STEP_COMPLETED, step, detail);
    }

    /**
     * Marks a step failed without necessarily ending the run.
     *
     * @param step the step that failed
     * @param detail a user-facing reason; never an exception message or a stack trace
     */
    public void stepFailed(RunStep step, String detail) {
        publish(RunEventType.STEP_FAILED, step, detail);
    }

    /**
     * Publishes the terminal success event, carrying the persisted report.
     *
     * @param report the saved report, so the client needs no follow-up request
     */
    public void completed(ChangeReport report) {
        // Mapped to the API representation here rather than sent as the entity: the client uses one type for a
        // report however it arrives, and the entity is missing the derived fields that type promises.
        publish(RunEventType.RUN_COMPLETED, null, null, ChangeReportResponse.from(report));
    }

    /**
     * Publishes the terminal failure event.
     *
     * @param detail a user-facing reason for the failure
     */
    public void failed(String detail) {
        publish(RunEventType.RUN_FAILED, null, detail == null ? "The analysis failed." : detail);
    }

    /** Publishes a step-level event, which never carries a report. */
    private void publish(RunEventType type, RunStep step, String detail) {
        publish(type, step, detail, null);
    }

    /**
     * Appends an event to the history and delivers it to everyone watching.
     *
     * <p>Publications after a terminal event are dropped with a warning rather than appended. That is a guard
     * against a bug rather than an expected path - an orchestrator that failed a run and then kept emitting would
     * otherwise produce a stream that ends twice, which no client can sensibly render.
     */
    private synchronized void publish(RunEventType type, RunStep step, String detail, ChangeReportResponse report) {
        if (finishedAt != null) {
            log.warn("Ignoring {} published after run {} already finished.", type, runId);
            return;
        }

        RunEvent event = new RunEvent(runId, type, step == null ? null : step.label(), detail,
                Duration.between(startedAt, Instant.now()).toSeconds(), report);

        history.add(event);
        if (type.terminal()) {
            finishedAt = Instant.now();
        }

        deliver(event);
    }

    /**
     * Registers a subscriber, replaying everything already emitted.
     *
     * <p>Synchronized with {@link #publish} so the replay and the registration are one atomic step. A subscriber
     * arriving after the run has finished is replayed the full history - terminal event included - and
     * deliberately <em>not</em> registered, since nothing more can ever arrive; the sink sees the terminal event
     * and closes itself.
     *
     * @param sink where to deliver events
     * @return {@code true} if the run had already finished, so the caller knows the stream is complete
     */
    public synchronized boolean subscribe(RunEventSink sink) {
        for (RunEvent event : history) {
            try {
                sink.send(event);
            } catch (IOException | RuntimeException e) {
                // The client vanished mid-replay. Nothing to clean up: it was never registered.
                log.debug("Subscriber for run {} disconnected during replay: {}", runId, e.toString());
                return true;
            }
        }
        if (finishedAt != null) {
            return true;
        }
        subscribers.add(sink);
        return false;
    }

    /**
     * Sends a keep-alive to every subscriber, dropping any that have gone away.
     *
     * <p>Called on a timer by {@link RunEventStream}; see {@link RunEventSink#keepAlive()} for why silence during
     * the comparison step would otherwise look like a dead run.
     */
    synchronized void keepAlive() {
        subscribers.removeIf(sink -> {
            try {
                sink.keepAlive();
                return false;
            } catch (IOException | RuntimeException e) {
                log.debug("Dropping a disconnected subscriber of run {}: {}", runId, e.toString());
                return true;
            }
        });
    }

    /** Delivers to every subscriber, dropping the ones that fail. Caller holds the monitor. */
    private void deliver(RunEvent event) {
        subscribers.removeIf(sink -> {
            try {
                sink.send(event);
                return false;
            } catch (IOException | RuntimeException e) {
                log.debug("Dropping a disconnected subscriber of run {}: {}", runId, e.toString());
                return true;
            }
        });
    }

    /**
     * @return every event emitted so far, oldest first. A copy, so a caller iterating it cannot be tripped by a
     *     concurrent publication.
     */
    public synchronized List<RunEvent> history() {
        return List.copyOf(history);
    }

    /** @return how many clients are currently watching, for the admin stats panel and for tests */
    public synchronized int subscriberCount() {
        return subscribers.size();
    }
}
