package com.saasinvestigator.run;

import com.saasinvestigator.product.SourceType;
import com.saasinvestigator.report.AnalysisDepth;
import com.saasinvestigator.report.RunType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * The application's own metrics for runs, source failures, and questions.
 *
 * <h2>Why the metric names here look wrong</h2>
 *
 * <p>The build prompt asks for {@code saas_run_total}, {@code saas_source_fetch_errors_total} and
 * {@code saas_ask_total}, and this class registers {@code saas.run}, {@code saas.source.fetch.errors} and
 * {@code saas.ask} - no {@code total}, dots instead of underscores. Both halves of that are deliberate.
 *
 * <p>Micrometer names meters in a registry-neutral dotted form and each registry applies its own conventions;
 * the Prometheus registry replaces dots with underscores <em>and appends {@code _total} to every counter</em>. So
 * registering {@code saas.run.total} would publish {@code saas_run_total_total}, which is the kind of mistake that
 * is only ever noticed after someone has built a dashboard on the wrong name. The timer is the same story in
 * reverse: {@code saas.run.duration} declared with a base unit of seconds publishes
 * {@code saas_run_duration_seconds_count} / {@code _sum} / {@code _max}, which is where the {@code _seconds} in the
 * required name comes from.
 *
 * <p>The exact published names are listed in {@code docs/DEPLOYMENT.md}, and
 * {@code RunMetricsTest} asserts them against a real {@code PrometheusMeterRegistry} rather than trusting this
 * comment - a convention that changes in a Micrometer upgrade should break a test, not a dashboard.
 *
 * <h2>Cardinality</h2>
 *
 * <p>Every meter is tagged with {@code product}, using the product's name rather than its id: an operator reading a
 * graph needs to know which product is failing, and a Mongo ObjectId does not tell them. That makes product count
 * the driver of cardinality here, which is acceptable for a tool where products are created by hand in the dozens.
 * Nothing is tagged with a user, a URL, or a run id, all of which are unbounded.
 */
@Component
public class RunMetrics {

    /** Published by the Prometheus registry as {@code saas_run_total}. */
    static final String RUN_COUNTER = "saas.run";

    /** Published as {@code saas_run_duration_seconds_count} / {@code _sum} / {@code _max}. */
    static final String RUN_TIMER = "saas.run.duration";

    /** Published as {@code saas_source_fetch_errors_total}. */
    static final String SOURCE_ERROR_COUNTER = "saas.source.fetch.errors";

    /** Published as {@code saas_ask_total}. */
    static final String ASK_COUNTER = "saas.ask";

    private static final String TAG_PRODUCT = "product";
    private static final String TAG_STATUS = "status";
    private static final String TAG_RUN_TYPE = "runType";
    private static final String TAG_DEPTH = "analysisDepth";
    private static final String TAG_SOURCE_TYPE = "sourceType";

    private final MeterRegistry registry;

    public RunMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Records one finished run - successful, partial, or failed.
     *
     * <p>Called from a {@code finally} block in the orchestrator, so that every run is counted exactly once
     * whichever way it ended. A metric recorded only on the happy path makes a failing instance look idle, which is
     * the opposite of what a monitoring tool's own monitoring should do.
     *
     * @param productName the product analysed
     * @param runType standard run or custom-range compare
     * @param depth the depth requested
     * @param outcome how it ended
     * @param elapsed how long it took, wall-clock
     */
    public void recordRun(String productName, RunType runType, AnalysisDepth depth, RunOutcome outcome,
                          Duration elapsed) {
        Counter.builder(RUN_COUNTER)
                .description("Runs and compares completed, by outcome")
                .tag(TAG_PRODUCT, productName)
                .tag(TAG_STATUS, outcome.wireName())
                .tag(TAG_RUN_TYPE, runType.name())
                .tag(TAG_DEPTH, depth.name())
                .register(registry)
                .increment();

        Timer.builder(RUN_TIMER)
                .description("Wall-clock duration of a run or compare, including crawling and the provider call")
                .tag(TAG_PRODUCT, productName)
                .tag(TAG_RUN_TYPE, runType.name())
                .tag(TAG_DEPTH, depth.name())
                .register(registry)
                .record(elapsed);
    }

    /**
     * Records one source that could not be read.
     *
     * <p>Tagged by source type rather than source name, because source names are user-written and unbounded while
     * the useful question is "are the crawled sources failing or the MCP ones" - a distinction that points at a
     * network problem versus a credential problem.
     *
     * @param productName the product whose source failed
     * @param sourceType what kind of source it was
     */
    public void recordSourceFetchError(String productName, SourceType sourceType) {
        Counter.builder(SOURCE_ERROR_COUNTER)
                .description("Sources that could not be read during a run")
                .tag(TAG_PRODUCT, productName)
                .tag(TAG_SOURCE_TYPE, sourceType.name())
                .register(registry)
                .increment();
    }

    /**
     * Records one answered or failed question.
     *
     * @param productName the product asked about
     * @param succeeded whether an answer was produced
     */
    public void recordAsk(String productName, boolean succeeded) {
        Counter.builder(ASK_COUNTER)
                .description("Ad-hoc questions submitted, by outcome")
                .tag(TAG_PRODUCT, productName)
                .tag(TAG_STATUS, succeeded ? RunOutcome.SUCCESS.wireName() : RunOutcome.FAILURE.wireName())
                .register(registry)
                .increment();
    }
}
