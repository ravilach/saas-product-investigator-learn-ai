package com.saasinvestigator.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.saasinvestigator.product.SourceType;
import com.saasinvestigator.report.AnalysisDepth;
import com.saasinvestigator.report.RunType;
import io.micrometer.core.instrument.Tag;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Asserts the metric names and tags that leave this application, as Prometheus sees them.
 *
 * <p>This test uses a real {@link PrometheusMeterRegistry} and reads its scrape output rather than inspecting
 * Micrometer's in-memory meter names, and that choice is the entire point of the class. Micrometer names meters in a
 * registry-neutral dotted form, and the Prometheus registry rewrites those names on the way out - dots become
 * underscores, and <em>every counter gains a {@code _total} suffix</em>. So the name a developer reads in
 * {@code RunMetrics} is not the name an operator types into a dashboard, and a test asserting on the former would
 * pass while the latter was wrong.
 *
 * <p>These names are a published contract: they are listed in {@code docs/DEPLOYMENT.md}, and anything built on them -
 * a dashboard, an alert rule, a recording rule - breaks silently if they change. A silent break is exactly what a
 * Micrometer upgrade that adjusted its naming conventions would cause, so the assertions below are deliberately about
 * literal strings rather than about constants: writing {@code RunMetrics.RUN_COUNTER + "_total"} here would make the
 * test agree with whatever the code happened to do.
 */
class RunMetricsTest {

    private PrometheusMeterRegistry registry;
    private RunMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        metrics = new RunMetrics(registry);
    }

    /** @return the registry's exposition output, which is byte-for-byte what a Prometheus scrape receives */
    private String scrape() {
        return registry.scrape();
    }

    // ---------------------------------------------------------------------
    // The four required names
    // ---------------------------------------------------------------------

    @Test
    void aFinishedRunPublishesTheCounterNameTheDocsPromise() {
        metrics.recordRun("Acme Billing", RunType.STANDARD, AnalysisDepth.REGULAR, RunOutcome.SUCCESS,
                Duration.ofSeconds(42));

        assertThat(scrape()).contains("saas_run_total");
    }

    @Test
    void theRunCounterIsNotDoubleSuffixed() {
        metrics.recordRun("Acme Billing", RunType.STANDARD, AnalysisDepth.REGULAR, RunOutcome.SUCCESS,
                Duration.ofSeconds(42));

        // The mistake this guards against is registering the meter as "saas.run.total", which publishes
        // "saas_run_total_total" - a name that looks close enough to be missed in review and far enough to make
        // every query return nothing.
        assertThat(scrape()).doesNotContain("saas_run_total_total");
    }

    @Test
    void theRunTimerPublishesSecondsBecauseThatIsWhatPrometheusExpects() {
        metrics.recordRun("Acme Billing", RunType.STANDARD, AnalysisDepth.NUCLEAR, RunOutcome.SUCCESS,
                Duration.ofSeconds(90));

        // The "_seconds" is not in the registered name: it comes from the registry's base time unit. Asserting on the
        // three published series is the only way to know a histogram_quantile() over them would be in seconds rather
        // than milliseconds - a factor-of-1000 error that makes a graph look plausible and be wrong.
        String scrape = scrape();
        assertThat(scrape).contains("saas_run_duration_seconds_count");
        assertThat(scrape).contains("saas_run_duration_seconds_sum");
        assertThat(scrape).contains("saas_run_duration_seconds_max");
    }

    @Test
    void theTimerRecordsTheDurationItWasGivenRatherThanMeasuringItself() {
        metrics.recordRun("Acme Billing", RunType.STANDARD, AnalysisDepth.SHORT, RunOutcome.SUCCESS,
                Duration.ofSeconds(90));

        // The orchestrator measures wall-clock time itself and hands the duration over, because the run finishes on a
        // background thread and nothing about this call is on that timeline.
        assertThat(registry.get(RunMetrics.RUN_TIMER).timer().totalTime(TimeUnit.SECONDS)).isEqualTo(90.0d);
    }

    @Test
    void anUnreadableSourcePublishesTheSourceErrorCounterName() {
        metrics.recordSourceFetchError("Acme Billing", SourceType.WEBSITE);

        String scrape = scrape();
        assertThat(scrape).contains("saas_source_fetch_errors_total");
        assertThat(scrape).doesNotContain("saas_source_fetch_errors_total_total");
    }

    @Test
    void anAnsweredQuestionPublishesTheAskCounterName() {
        metrics.recordAsk("Acme Billing", true);

        String scrape = scrape();
        assertThat(scrape).contains("saas_ask_total");
        assertThat(scrape).doesNotContain("saas_ask_total_total");
    }

    // ---------------------------------------------------------------------
    // Tags
    // ---------------------------------------------------------------------

    @Test
    void theRunCounterCarriesEveryTagADashboardNeedsToSliceBy() {
        metrics.recordRun("Acme Billing", RunType.CUSTOM_RANGE, AnalysisDepth.NUCLEAR, RunOutcome.PARTIAL,
                Duration.ofSeconds(5));

        assertThat(scrape())
                .contains("product=\"Acme Billing\"")
                .contains("status=\"partial\"")
                .contains("runType=\"CUSTOM_RANGE\"")
                .contains("analysisDepth=\"NUCLEAR\"");
    }

    @Test
    void theStatusTagUsesTheThreeLowercaseValuesTheDocsList() {
        metrics.recordRun("A", RunType.STANDARD, AnalysisDepth.SHORT, RunOutcome.SUCCESS, Duration.ofSeconds(1));
        metrics.recordRun("B", RunType.STANDARD, AnalysisDepth.SHORT, RunOutcome.PARTIAL, Duration.ofSeconds(1));
        metrics.recordRun("C", RunType.STANDARD, AnalysisDepth.SHORT, RunOutcome.FAILURE, Duration.ofSeconds(1));

        // Lowercase, and exactly these three words, because an alert rule is written against the string not the enum.
        assertThat(scrape())
                .contains("status=\"success\"")
                .contains("status=\"partial\"")
                .contains("status=\"failure\"");
    }

    @Test
    void aFailedRunIsCountedJustLikeASuccessfulOne() {
        metrics.recordRun("Acme Billing", RunType.STANDARD, AnalysisDepth.REGULAR, RunOutcome.FAILURE,
                Duration.ofSeconds(3));

        // A counter incremented only on the happy path makes a completely broken instance look idle, which is the
        // opposite of what a monitoring tool's own monitoring should do. Hence the finally block in the orchestrator.
        assertThat(registry.get(RunMetrics.RUN_COUNTER).tag("status", "failure").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get(RunMetrics.RUN_TIMER).timer().count()).isEqualTo(1L);
    }

    @Test
    void sourceErrorsAreTaggedByTypeRatherThanBySourceName() {
        metrics.recordSourceFetchError("Acme Billing", SourceType.GENERIC_MCP);

        // Source names are user-written and unbounded; the question worth graphing is whether the crawled sources or
        // the MCP ones are failing, because those point at a network problem and a credential problem respectively.
        assertThat(scrape())
                .contains("sourceType=\"GENERIC_MCP\"")
                .contains("product=\"Acme Billing\"");
    }

    @Test
    void anAskFailureAndAnAskSuccessShareOneCounterSplitByStatus() {
        metrics.recordAsk("Acme Billing", true);
        metrics.recordAsk("Acme Billing", false);
        metrics.recordAsk("Acme Billing", false);

        assertThat(registry.get(RunMetrics.ASK_COUNTER).tag("status", "success").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get(RunMetrics.ASK_COUNTER).tag("status", "failure").counter().count()).isEqualTo(2.0d);
    }

    @Test
    void repeatedRunsOfTheSameShapeShareOneSeriesRatherThanCreatingNewOnes() {
        for (int i = 0; i < 5; i++) {
            metrics.recordRun("Acme Billing", RunType.STANDARD, AnalysisDepth.REGULAR, RunOutcome.SUCCESS,
                    Duration.ofSeconds(10));
        }

        // Counter.builder(...).register(...) is idempotent for a given name-and-tag-set, which is what makes it safe
        // to build the meter inside the recording method instead of holding fields for every combination of tags.
        assertThat(registry.get(RunMetrics.RUN_COUNTER).counters()).hasSize(1);
        assertThat(registry.get(RunMetrics.RUN_COUNTER).counter().count()).isEqualTo(5.0d);
    }

    @Test
    void nothingIsTaggedWithAUserAUrlOrARunId() {
        metrics.recordRun("Acme Billing", RunType.STANDARD, AnalysisDepth.REGULAR, RunOutcome.SUCCESS,
                Duration.ofSeconds(1));
        metrics.recordSourceFetchError("Acme Billing", SourceType.WEBSITE);
        metrics.recordAsk("Acme Billing", true);

        // All three are unbounded, and unbounded tags are how a Prometheus instance runs out of memory because of a
        // monitoring change nobody thought was risky. Product count is the only cardinality driver here, and products
        // are created by hand in the dozens.
        assertThat(registry.getMeters())
                .allSatisfy(meter -> assertThat(meter.getId().getTags())
                        .extracting(Tag::getKey)
                        .doesNotContain("user", "userId", "url", "runId", "sourceName"));
    }
}
