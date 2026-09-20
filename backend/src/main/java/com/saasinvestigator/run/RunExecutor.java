package com.saasinvestigator.run;

import com.saasinvestigator.error.ProviderUnavailableException;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The threads that runs, compares, and questions execute on - away from request threads, and bounded.
 *
 * <h2>Why not request threads</h2>
 *
 * <p>A run crawls several websites and then waits on a model for up to twenty minutes. Doing that on the thread
 * that served {@code POST /run} would hold an HTTP connection open for the duration, meaning every proxy, load
 * balancer, and browser between the user and this process gets a vote on whether the run finishes - and several of
 * them default to sixty seconds. Hence {@code 202 Accepted} plus a separate SSE stream, and hence this class.
 *
 * <h2>Why two pools</h2>
 *
 * <p>A run is expensive, slow, and something the user expects to take minutes. A question is cheap, fast, and typed
 * into a query bar where a two-second delay feels broken. Sharing one queue would mean four concurrent nuclear runs
 * make the query bar stop working, with no error - just a request sitting in a queue behind twenty minutes of work.
 * Separate pools make the two failure modes independent.
 *
 * <h2>Why bounded, and what happens at the bound</h2>
 *
 * <p>Both pools have a fixed size and a bounded queue, because the resource being protected is not this process's
 * CPU - it is somebody else's website and a metered API. An unbounded queue would accept a hundred runs, crawl
 * whatever it was pointed at as fast as it could, and produce a bill. At the bound, submission is rejected
 * <em>immediately</em> with a message telling the user to try again shortly, which is a far better answer than a
 * {@code runId} for a run that will not start for half an hour.
 *
 * <p>Rejection surfaces as {@link ProviderUnavailableException} to get a {@code 503} out of the global handler. The
 * name is a slight stretch - nothing is wrong with the LLM provider - but {@code 503 Service Unavailable} with
 * {@code "try again shortly"} is exactly the right answer to "this server is already as busy as it is willing to
 * be", and inventing a second exception class for the same status code would be worse.
 */
@Component
public class RunExecutor {

    private static final Logger log = LoggerFactory.getLogger(RunExecutor.class);

    /** How long a pool is given to finish in-flight work at shutdown before its threads are interrupted. */
    private static final long SHUTDOWN_GRACE_SECONDS = 20;

    private final ThreadPoolExecutor runs;
    private final ThreadPoolExecutor asks;

    /**
     * @param runConcurrency how many runs or compares may execute at once
     * @param runQueueCapacity how many may wait; beyond this, submission is rejected rather than queued
     * @param askConcurrency how many questions may be answered at once
     */
    public RunExecutor(@Value("${app.run.concurrency:3}") int runConcurrency,
                       @Value("${app.run.queue-capacity:12}") int runQueueCapacity,
                       @Value("${app.run.ask-concurrency:6}") int askConcurrency) {
        this.runs = pool("saas-run", runConcurrency, runQueueCapacity);
        this.asks = pool("saas-ask", askConcurrency, askConcurrency * 4);
        log.info("Run executor ready: {} concurrent runs (queue {}), {} concurrent questions.",
                runConcurrency, runQueueCapacity, askConcurrency);
    }

    /**
     * Schedules a run or compare.
     *
     * @param task the run body; must not throw, since nothing will be watching for it
     * @throws ProviderUnavailableException if the server is already at its configured limit
     */
    public void submitRun(Runnable task) {
        submit(runs, task, "analyses");
    }

    /**
     * Schedules a question.
     *
     * @param task the ask body; must not throw
     * @throws ProviderUnavailableException if the server is already at its configured limit
     */
    public void submitAsk(Runnable task) {
        submit(asks, task, "questions");
    }

    /** @return how many runs or compares are executing right now, for the admin stats panel */
    public int activeRuns() {
        return runs.getActiveCount();
    }

    /** @return how many runs or compares are waiting to start, for the admin stats panel */
    public int queuedRuns() {
        return runs.getQueue().size();
    }

    private static void submit(ThreadPoolExecutor pool, Runnable task, String what) {
        try {
            pool.execute(task);
        } catch (RejectedExecutionException e) {
            throw new ProviderUnavailableException("This server is already handling as many " + what
                    + " as it is configured to run at once. Try again in a few minutes.", e);
        }
    }

    private static ThreadPoolExecutor pool(String namePrefix, int size, int queueCapacity) {
        // A fixed pool rather than a growing one: the point is a ceiling, and core == max means the queue fills
        // before anything is rejected, which is the behaviour the capacity argument is meant to describe.
        ThreadPoolExecutor pool = new ThreadPoolExecutor(size, size, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), namedThreads(namePrefix));
        pool.allowCoreThreadTimeOut(false);
        return pool;
    }

    /** Named, non-daemon threads: a thread dump during a slow run should say which pool it belongs to. */
    private static ThreadFactory namedThreads(String prefix) {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.getAndIncrement());
            thread.setDaemon(false);
            return thread;
        };
    }

    /**
     * Stops accepting work and gives in-flight runs a short grace period.
     *
     * <p>Graceful rather than immediate because an interrupted run leaves a session with no terminal event and a
     * user watching a stream that will never close. The grace period is not long enough to finish a real run - on a
     * restart, in-flight runs are lost, which {@code docs/ARCHITECTURE.md} states plainly - but it is long enough
     * for a run that was nearly done to persist its report.
     */
    @PreDestroy
    void shutdown() {
        shutdown(runs, "runs");
        shutdown(asks, "questions");
    }

    private static void shutdown(ThreadPoolExecutor pool, String what) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
                log.warn("In-flight {} did not finish within {}s of shutdown; interrupting.",
                        what, SHUTDOWN_GRACE_SECONDS);
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
