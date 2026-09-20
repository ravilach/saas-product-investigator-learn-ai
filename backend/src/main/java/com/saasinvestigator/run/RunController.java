package com.saasinvestigator.run;

import com.saasinvestigator.error.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The four endpoints that make a product do something: run it, compare two dates, watch either happen, ask it a
 * question.
 *
 * <h2>Everything here is available to both roles</h2>
 *
 * <p>{@code READ_ONLY} is a restriction on <em>configuration</em>, not on use. A read-only user cannot create a
 * product, change its sources, or add a user - but they can run the products that exist, compare their history, and
 * ask questions about them, because that is the entire value of the application and a role that could not do it would
 * be a login that shows you a dashboard you may never use. The class-level {@code @PreAuthorize("isAuthenticated()")}
 * says so explicitly rather than leaning on {@code SecurityConfig}'s catch-all: it means a future narrowing of one of
 * these endpoints has to be a deliberate edit to this line.
 *
 * <h2>Two shapes of asynchrony, for two different reasons</h2>
 *
 * <p>A run returns {@code 202} with a {@code runId} and the client subscribes to the event stream separately. An ask
 * streams directly on the POST. That difference is not an inconsistency:
 *
 * <ul>
 *   <li>A run is a <em>resource</em>. It survives its trigger, it can be watched by more than one browser tab, it can
 *       be re-attached to after a reload, and the report it produces is stored. Its narration therefore needs an
 *       address of its own, and {@link RunSession} replays what was missed to anyone who subscribes late - which is
 *       everyone, since the first crawl can finish before the client has opened the stream.</li>
 *   <li>An ask is a <em>conversation turn</em>. It persists nothing, cannot be resumed, and is meaningless to a second
 *       viewer. Handing back an id to fetch it with would be two round trips to reach the same bytes, and would leave
 *       the server holding an answer nobody came back for.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/saas-products/{id}")
@PreAuthorize("isAuthenticated()")
@Tag(name = "Runs", description = "Trigger runs and comparisons, watch them live, and ask ad-hoc questions")
public class RunController {

    private static final Logger log = LoggerFactory.getLogger(RunController.class);

    /**
     * How long a run's event stream may stay open.
     *
     * <p>Generous, because it has to outlast the work it narrates: a nuclear-depth run over a large documentation
     * site crawls for minutes before the model call even starts. The stream is not idle during that time - the
     * sweeper in {@link RunEventStream} sends a keep-alive every fifteen seconds - so this timeout only ever fires on
     * a run that has genuinely hung, which is exactly when the client should be told rather than left waiting.
     */
    private static final Duration EVENT_STREAM_TIMEOUT = Duration.ofMinutes(30);

    private final RunOrchestrator orchestrator;
    private final RunEventStream eventStream;
    private final AskService askService;

    /**
     * @param orchestrator starts runs and comparisons
     * @param eventStream holds the live narration of runs in flight
     * @param askService answers ad-hoc questions from stored data
     */
    public RunController(RunOrchestrator orchestrator, RunEventStream eventStream, AskService askService) {
        this.orchestrator = orchestrator;
        this.eventStream = eventStream;
        this.askService = askService;
    }

    /**
     * Triggers a fresh run: fetch every source, compare against the last snapshots, write a report.
     *
     * <p>The body is optional. A request with no body at all runs at {@link com.saasinvestigator.report.AnalysisDepth}
     * {@code REGULAR}, which is what the dashboard's Run button sends.
     *
     * @param id the product to run
     * @param request the requested depth, or {@code null}
     * @return the id of the stream this run will narrate itself on
     * @throws NotFoundException if the product does not exist
     */
    @PostMapping("/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Run a SaaS product now",
            description = "Fetches every configured source, compares against the previous snapshots, and stores a "
                    + "change report. Returns immediately with a runId to subscribe to; the run itself continues in "
                    + "the background. Available to both roles.")
    public RunStartedResponse run(@PathVariable String id,
                                  @RequestBody(required = false) RunRequest request) {
        String runId = orchestrator.startRun(id, request == null ? null : request.analysisDepth());
        return new RunStartedResponse(runId);
    }

    /**
     * Compares two past dates from stored snapshots only.
     *
     * <p>Fetches nothing and crawls nothing - see {@link HistoryLoader}. A range that cannot be answered is rejected
     * here as a {@code 400} naming the earliest date data exists for, rather than accepted and failed on the stream.
     *
     * @param id the product to compare
     * @param request the window, and optionally the depth
     * @return the id of the stream this comparison will narrate itself on
     * @throws NotFoundException if the product does not exist
     * @throws com.saasinvestigator.error.BadRequestException if the range is inverted, in the future, or predates all
     *     stored data for this product
     */
    @PostMapping("/compare")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Compare two dates from stored history",
            description = "Builds a report describing what changed between two past dates, using only snapshots "
                    + "already stored. Never fetches or crawls anything new. MCP sources are summarised from what "
                    + "earlier runs recorded, which the report flags as mcpHistoryLimited. Available to both roles.")
    public RunStartedResponse compare(@PathVariable String id,
                                      @Valid @RequestBody CompareRequest request) {
        String runId = orchestrator.startCompare(
                id, request.fromInstant(), request.toInstant(), request.analysisDepth());
        return new RunStartedResponse(runId);
    }

    /**
     * Streams the live narration of a run or comparison.
     *
     * <p>Subscribing replays everything the run has already emitted before attaching for what comes next, so a client
     * that opens this stream a second after triggering the run still sees the first crawl's progress, and a reloaded
     * page catches up rather than showing a run that appears to start halfway through.
     *
     * <p>Consequently a finished run can still be read here - for {@link RunEventStream}'s retention window - and
     * doing so delivers the whole history and closes. After that window the answer is a {@code 404}: the report
     * itself is permanent and on the history timeline, but its narration is not.
     *
     * @param id the product the run belongs to
     * @param runId the run to watch
     * @return an emitter carrying {@link RunEvent}s, closed after the terminal event
     * @throws NotFoundException if no such run is in memory, or it belongs to a different product
     */
    @GetMapping(path = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Watch a run happen",
            description = "Server-sent events describing each step as it happens: step_started, step_progress, "
                    + "step_completed, step_failed, then one run_completed (carrying the finished report) or "
                    + "run_failed. Events emitted before subscribing are replayed first. Available to both roles.")
    public SseEmitter events(@PathVariable String id, @PathVariable String runId) {
        RunSession session = eventStream.find(runId)
                .filter(candidate -> candidate.saasProductId().equals(id))
                .orElseThrow(() -> new NotFoundException("No live run " + runId + " for this product. A run's "
                        + "progress is only streamable while it is running and for a short time after it finishes - "
                        + "if it has completed, its report is on the product's history."));

        SseEmitter emitter = new SseEmitter(EVENT_STREAM_TIMEOUT.toMillis());
        EmitterSink sink = new EmitterSink(emitter, runId);
        if (session.subscribe(sink)) {
            // Nothing more will ever be published to this sink: either the run had already finished, or the client
            // disconnected part-way through the replay. Close so the container releases the async request rather than
            // holding it open for thirty minutes waiting for events that cannot come.
            sink.close();
        }
        return emitter;
    }

    /**
     * Asks a free-text question about this product, streaming the answer as it is written.
     *
     * <p>Answers from stored data only, and persists nothing. See {@link AskService}.
     *
     * @param id the product to ask about
     * @param request the question
     * @return an emitter carrying {@code chunk} events then one {@code done} or {@code error}
     * @throws NotFoundException if the product does not exist
     */
    @PostMapping(path = "/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Ask a question about this product",
            description = "Answers from the latest stored snapshots and the most recent report, streaming the answer "
                    + "as server-sent events: chunk events, then one done event carrying the complete answer. "
                    + "Fetches nothing, and stores nothing. Available to both roles.")
    public SseEmitter ask(@PathVariable String id, @Valid @RequestBody AskRequest request) {
        return askService.ask(id, request.question());
    }

    /**
     * Adapts one subscribed browser connection to {@link RunEventSink}.
     *
     * <p>The interface exists so that {@link RunSession}'s replay-then-register ordering can be tested against a
     * list, and this is the only implementation that talks to a real client. It carries one piece of state of its own:
     * whether the emitter has been completed, because {@code SseEmitter.complete()} is not idempotent and there are
     * two independent paths to it - the terminal event arriving live, and the terminal event arriving during replay of
     * an already-finished run.
     *
     * <p>Nothing here catches {@link IOException}. A client that has gone away should be dropped from the session's
     * subscriber list, and letting the exception out is how that happens: {@code RunSession} removes any sink that
     * throws. Swallowing it would leave the session publishing into a dead connection for the rest of the run.
     */
    private static final class EmitterSink implements RunEventSink {

        private final SseEmitter emitter;
        private final String runId;
        private final AtomicBoolean completed = new AtomicBoolean();

        private EmitterSink(SseEmitter emitter, String runId) {
            this.emitter = emitter;
            this.runId = runId;
        }

        @Override
        public void send(RunEvent event) throws IOException {
            // The event name is what an EventSource listener binds to; the JSON body repeats it as `type` so a client
            // using onmessage rather than addEventListener sees it too.
            emitter.send(SseEmitter.event()
                    .name(event.type().wireName())
                    .data(event, MediaType.APPLICATION_JSON));
            if (event.type().terminal()) {
                close();
            }
        }

        @Override
        public void keepAlive() throws IOException {
            // An SSE comment. EventSource ignores it entirely, which is exactly what is wanted: it exists to give
            // proxies and load balancers bytes to see during the minutes a model call takes, so they do not close an
            // apparently idle connection mid-run.
            emitter.send(SseEmitter.event().comment("keep-alive"));
        }

        /** Completes the emitter at most once, whichever path got here first. */
        private void close() {
            if (completed.compareAndSet(false, true)) {
                emitter.complete();
                log.debug("Closed the event stream for run {}.", runId);
            }
        }
    }
}
