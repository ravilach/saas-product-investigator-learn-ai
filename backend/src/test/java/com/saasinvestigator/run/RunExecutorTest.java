package com.saasinvestigator.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.saasinvestigator.error.ProviderUnavailableException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests the ceiling on concurrent work, and what happens the moment it is reached.
 *
 * <p>The behaviour worth pinning is the rejection. An unbounded queue would be the easy choice and would look correct
 * in every test: submission always succeeds, every run eventually executes, nothing throws. The cost only appears in
 * production, because the resource being rationed is not this process's CPU - it is somebody else's website and a
 * metered API. Accepting a hundred runs means crawling a hundred sites as fast as the network allows and paying for a
 * hundred model calls, so a bounded queue that says "try again in a few minutes" is the correct answer rather than a
 * degraded one.
 *
 * <p>The two-pool split is asserted for the same kind of reason: a shared queue passes every test and then, under
 * three concurrent nuclear runs, makes the question box stop responding with no error at all - just a request waiting
 * behind twenty minutes of work.
 */
class RunExecutorTest {

    private RunExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    /** Blocks a pool's only thread until released, so the queue behaviour can be observed deterministically. */
    private static Runnable blockUntil(CountDownLatch release) {
        return () -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }

    @Test
    void aSubmittedRunExecutesOnItsOwnNamedPoolThreadRatherThanTheCallers() throws Exception {
        executor = new RunExecutor(1, 1, 1);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> ranOn = new AtomicReference<>();

        executor.submitRun(() -> {
            ranOn.set(Thread.currentThread().getName());
            done.countDown();
        });

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        // Off the caller's thread, because the caller is a request thread and this work takes minutes. Named, because
        // a thread dump is the only debugging tool available on a hung run and it has to say which pool is stuck.
        assertThat(ranOn.get()).startsWith("saas-run-").isNotEqualTo(Thread.currentThread().getName());
    }

    @Test
    void aRunIsRejectedRatherThanQueuedForeverOnceTheQueueIsFull() {
        executor = new RunExecutor(1, 1, 1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.submitRun(blockUntil(release)); // occupies the single thread
            executor.submitRun(blockUntil(release)); // fills the single queue slot

            // A runId for a run that will not start for half an hour is worse than an honest refusal: the user watches
            // an empty live view and concludes the feature is broken.
            assertThatThrownBy(() -> executor.submitRun(blockUntil(release)))
                    .isInstanceOf(ProviderUnavailableException.class)
                    .hasMessageContaining("as many analyses as it is configured to run at once")
                    .hasMessageContaining("Try again in a few minutes");
        } finally {
            release.countDown();
        }
    }

    @Test
    void aQuestionIsRejectedWithItsOwnWordingRatherThanTheRunWording() {
        executor = new RunExecutor(1, 1, 1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            // The ask queue is sized at four times the ask concurrency, so with concurrency 1 that is one thread and
            // four slots.
            for (int i = 0; i < 5; i++) {
                executor.submitAsk(blockUntil(release));
            }

            assertThatThrownBy(() -> executor.submitAsk(blockUntil(release)))
                    .isInstanceOf(ProviderUnavailableException.class)
                    .hasMessageContaining("as many questions as it is configured");
        } finally {
            release.countDown();
        }
    }

    @Test
    void aFullRunPoolDoesNotStopQuestionsFromBeingAnswered() throws Exception {
        executor = new RunExecutor(1, 1, 2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch answered = new CountDownLatch(1);
        try {
            executor.submitRun(blockUntil(release));
            executor.submitRun(blockUntil(release));
            assertThatThrownBy(() -> executor.submitRun(blockUntil(release)))
                    .isInstanceOf(ProviderUnavailableException.class);

            executor.submitAsk(answered::countDown);

            // This is the whole justification for two pools rather than one. With a shared queue the question would
            // sit behind two blocked runs and the user would see a query bar that simply never answers.
            assertThat(answered.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
        }
    }

    @Test
    void theRejectionCarriesTheOriginalCauseForTheServerLog() {
        executor = new RunExecutor(1, 1, 1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.submitRun(blockUntil(release));
            executor.submitRun(blockUntil(release));

            // The user-facing message is deliberately vague about the mechanism; the cause is kept so the log is not.
            assertThatThrownBy(() -> executor.submitRun(blockUntil(release)))
                    .hasCauseInstanceOf(java.util.concurrent.RejectedExecutionException.class);
        } finally {
            release.countDown();
        }
    }

    @Test
    void activeAndQueuedCountsReflectWhatIsActuallyHappening() throws Exception {
        executor = new RunExecutor(1, 2, 1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.submitRun(() -> {
                started.countDown();
                blockUntil(release).run();
            });
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            executor.submitRun(blockUntil(release));

            // These two numbers are what the admin console shows, and a queue depth that never moves off zero is how
            // an operator would fail to notice they had set the concurrency too low.
            assertThat(executor.activeRuns()).isEqualTo(1);
            assertThat(executor.queuedRuns()).isEqualTo(1);
        } finally {
            release.countDown();
        }
    }

    @Test
    void shutdownIsSafeToCallTwiceSoContextClosingTwiceCannotThrow() {
        executor = new RunExecutor(1, 1, 1);

        executor.shutdown();
        executor.shutdown();

        assertThat(executor.activeRuns()).isZero();
    }
}
