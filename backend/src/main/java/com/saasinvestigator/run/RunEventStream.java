package com.saasinvestigator.run;

import com.saasinvestigator.report.RunType;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The registry of live and recently-finished runs, keyed by {@code runId}.
 *
 * <p>Deliberately in memory and deliberately not persisted. A run's durable output is its {@code change_reports}
 * document; this holds only the narration of how that document came to exist, which is worth nothing once the run
 * has finished and the user has seen it. Writing every {@code step_progress} to Mongo would mean a page-per-second
 * write load during a crawl in exchange for the ability to replay a crawl that finished last Tuesday - which nobody
 * wants and which the report already summarises.
 *
 * <p>The consequence, which is stated in {@code docs/ARCHITECTURE.md} rather than left to be discovered: a restart
 * loses in-flight runs' event streams, and in a multi-replica deployment the SSE request must reach the replica
 * that started the run. That is why {@code /docs/DEPLOYMENT.md} tells the Kubernetes and ECS samples to use sticky
 * sessions or a single replica, and why a restart mid-run shows the client a closed stream rather than a hang.
 *
 * <h2>Two janitorial jobs on one timer</h2>
 *
 * <p>A single daemon scheduler does both of the things this class needs done periodically. It sends keep-alives, so
 * a long provider call does not look like a dead connection to an intermediate proxy, and it evicts sessions, so a
 * long-lived process does not accumulate every run it has ever served. Finished sessions are kept for
 * {@link #FINISHED_RETENTION} so that a user who reloads just after a run ends still sees the result; unfinished
 * ones are given {@link #MAX_SESSION_AGE}, which only matters if a run thread died in a way that skipped its
 * terminal event.
 */
@Component
public class RunEventStream {

    private static final Logger log = LoggerFactory.getLogger(RunEventStream.class);

    /**
     * How often keep-alives go out and eviction runs.
     *
     * <p>Fifteen seconds is chosen against the shortest idle timeout commonly seen in front of an app like this -
     * nginx and most cloud load balancers default to 60 seconds - with enough margin that a missed tick is not a
     * dropped connection.
     */
    static final Duration SWEEP_INTERVAL = Duration.ofSeconds(15);

    /**
     * How long a finished run stays replayable.
     *
     * <p>Long enough to cover a reload, a shared link passed to a colleague, or a laptop that was asleep when the
     * run ended. Not so long that a busy instance holds thousands of finished narrations.
     */
    static final Duration FINISHED_RETENTION = Duration.ofMinutes(15);

    /**
     * The hard ceiling on an unfinished session's lifetime.
     *
     * <p>Comfortably longer than the providers' own 20-minute request timeout, so this only ever fires for a run
     * that failed to publish a terminal event at all - a bug, or a JVM that was under such pressure that the run
     * thread died. Either way, leaving the session forever would leak.
     */
    static final Duration MAX_SESSION_AGE = Duration.ofMinutes(45);

    private final ConcurrentHashMap<String, RunSession> sessions = new ConcurrentHashMap<>();

    /**
     * One daemon thread. Daemon so it can never be the reason a JVM refuses to exit, which for a purely
     * housekeeping thread would be an unhelpful way to hang a shutdown.
     */
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "run-event-sweeper");
        thread.setDaemon(true);
        return thread;
    });

    public RunEventStream() {
        long period = SWEEP_INTERVAL.toMillis();
        sweeper.scheduleWithFixedDelay(this::sweep, period, period, TimeUnit.MILLISECONDS);
    }

    /**
     * Opens a session for a run that is about to start.
     *
     * @param saasProductId the product being analysed
     * @param runType standard run or custom-range compare
     * @return the new session, whose {@link RunSession#runId()} is what the triggering request returns
     */
    public RunSession open(String saasProductId, RunType runType) {
        RunSession session = new RunSession(UUID.randomUUID().toString(), saasProductId, runType);
        sessions.put(session.runId(), session);
        return session;
    }

    /**
     * Finds a session by id.
     *
     * @param runId the id handed to the client
     * @return the session, or empty if it never existed or has been evicted. The endpoint turns empty into a 404
     *     whose message mentions expiry, because "unknown run" and "run finished a while ago" look identical from
     *     here and the second is far more likely.
     */
    public Optional<RunSession> find(String runId) {
        return Optional.ofNullable(sessions.get(runId));
    }

    /**
     * Discards a session immediately, used when a run could not be started after its session was opened.
     *
     * @param runId the session to drop
     */
    void discard(String runId) {
        sessions.remove(runId);
    }

    /** @return how many sessions are currently held, finished and unfinished, for the admin stats panel */
    public int sessionCount() {
        return sessions.size();
    }

    /** @return how many runs are currently in flight, for the admin stats panel */
    public long activeRunCount() {
        return sessions.values().stream().filter(session -> !session.isFinished()).count();
    }

    /**
     * One sweep: keep-alives for the live sessions, eviction for the expired ones.
     *
     * <p>Wrapped in a catch-all because this runs on a {@code scheduleWithFixedDelay} task, and such a task is
     * silently cancelled forever if it ever throws. Losing keep-alives for the lifetime of the process because one
     * session misbehaved once is not a trade worth making.
     */
    void sweep() {
        try {
            Instant now = Instant.now();
            sessions.values().removeIf(session -> expired(session, now));
            for (RunSession session : sessions.values()) {
                if (!session.isFinished()) {
                    session.keepAlive();
                }
            }
        } catch (RuntimeException e) {
            log.warn("Run event sweep failed; it will run again in {}s.", SWEEP_INTERVAL.toSeconds(), e);
        }
    }

    /**
     * Decides whether a session may be dropped.
     *
     * <p>Package-private and taking {@code now} as a parameter rather than reading the clock, so the two retention
     * windows can be asserted at their exact boundaries. The alternative - a test that sleeps - would either take
     * fifteen minutes or assert nothing.
     *
     * @param session the session to judge
     * @param now the instant to judge it against
     * @return {@code true} if it is past its retention window
     */
    static boolean expired(RunSession session, Instant now) {
        Instant finishedAt = session.finishedAt();
        if (finishedAt != null) {
            return finishedAt.plus(FINISHED_RETENTION).isBefore(now);
        }
        if (session.startedAt().plus(MAX_SESSION_AGE).isBefore(now)) {
            log.warn("Evicting run {} for product {}: it never published a terminal event and is now {} old.",
                    session.runId(), session.saasProductId(), MAX_SESSION_AGE);
            return true;
        }
        return false;
    }

    @PreDestroy
    void shutdown() {
        sweeper.shutdownNow();
    }
}
