package com.saasinvestigator.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Asserts that every {@code management.metrics.distribution.*} key in {@code application.properties} names a meter
 * that actually exists.
 *
 * <p>This is the other half of {@code ApplicationPropertiesBindingTest}, which proves those properties <em>bind</em>
 * and stops there - correctly, because the part after the prefix is a map key and Boot has no opinion about its
 * contents. The gap that leaves is the one this repo shipped from step 7 to step 12:
 *
 * <pre>
 * management.metrics.distribution.percentiles-histogram.saas.run.duration.seconds=true
 * </pre>
 *
 * <p>That key is well-formed, binds successfully, and does nothing at all. Boot's {@code PropertiesMeterFilter}
 * matches these keys against meter names by walking prefixes - for the meter {@code saas.run.duration} it looks up
 * {@code saas.run.duration}, then {@code saas.run}, then {@code saas}, then {@code all} - and never looks up a key
 * <em>longer</em> than the meter name. So the {@code .seconds} suffix, which belongs to the series Prometheus
 * publishes ({@code saas_run_duration_seconds}) and not to the Micrometer meter, made the filter apply to nothing.
 *
 * <p>The symptom is the quiet kind. The timer kept publishing {@code _count}/{@code _sum}/{@code _max} with no
 * {@code _bucket} series, the scrape looked populated, nothing logged a warning, and
 * {@code histogram_quantile(0.95, rate(saas_run_duration_seconds_bucket[1h]))} returned an empty result - which on a
 * dashboard is indistinguishable from "no runs happened."
 *
 * <p>Checked here rather than in {@code ApplicationPropertiesBindingTest} because the assertion needs the meter
 * names, and those are {@link RunMetrics}' constants in this package. Referencing the constants rather than string
 * literals is the point: renaming a meter without revisiting the property that configures it should fail this test,
 * which is the same mistake in the other direction.
 */
class RunMetricsDistributionPropertiesTest {

    private static final String DISTRIBUTION_PREFIX = "management.metrics.distribution.";

    /**
     * Every meter name a distribution property in this application may legitimately target.
     *
     * <p>{@code http.server.requests} is Boot's own and is not ours to rename. The rest are {@link RunMetrics}'
     * constants, so this set moves with them.
     */
    private static final Set<String> KNOWN_METER_NAMES = Set.of(
            "http.server.requests",
            RunMetrics.RUN_COUNTER,
            RunMetrics.RUN_TIMER,
            RunMetrics.SOURCE_ERROR_COUNTER,
            RunMetrics.ASK_COUNTER);

    private static Properties applicationProperties;

    @BeforeAll
    static void readApplicationProperties() throws IOException {
        applicationProperties = new Properties();
        try (InputStream in = RunMetricsDistributionPropertiesTest.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            assertThat(in).as("application.properties must be on the test classpath").isNotNull();
            applicationProperties.load(in);
        }
    }

    @Test
    void thereAreDistributionPropertiesToCheck() {
        // Guards the guard. Delete the properties and every assertion below passes vacuously.
        assertThat(distributionKeys()).as("management.metrics.distribution.* keys").isNotEmpty();
    }

    @Test
    void everyDistributionPropertyNamesAMeterThatExists() {
        List<String> orphans = new ArrayList<>();
        for (String key : distributionKeys()) {
            String meterName = key.substring(key.indexOf('.', DISTRIBUTION_PREFIX.length()) + 1);
            // "all" is Boot's own catch-all key, applying to every meter rather than naming one.
            if (meterName.equals("all")) {
                continue;
            }
            if (KNOWN_METER_NAMES.stream().noneMatch(known -> appliesTo(meterName, known))) {
                orphans.add(key + "  (matches no meter; nearest names: " + KNOWN_METER_NAMES + ")");
            }
        }

        assertThat(orphans)
                .as("a distribution key that matches no meter name binds fine and silently configures nothing - "
                        + "the usual cause is using the Prometheus series name (saas_run_duration_seconds) where "
                        + "the Micrometer meter name (saas.run.duration) belongs")
                .isEmpty();
    }

    @Test
    void theRunTimerIsConfiguredAsABoundedHistogram() {
        // The specific regression, named by its consequence rather than left to the generic check above.
        assertThat(applicationProperties.getProperty(
                        "management.metrics.distribution.percentiles-histogram." + RunMetrics.RUN_TIMER))
                .as("without this, saas_run_duration_seconds publishes as a summary and has no _bucket series, so "
                        + "no quantile over run latency can be computed")
                .isEqualTo("true");

        // Bounds matter as much as the histogram itself: Micrometer's default range is sized for HTTP requests
        // (roughly 1ms-30s), and a run measured in minutes would pile into the top bucket, making every quantile
        // read as "30 seconds or more" no matter how long runs actually take.
        assertThat(applicationProperties.getProperty(
                        "management.metrics.distribution.minimum-expected-value." + RunMetrics.RUN_TIMER))
                .isNotNull();
        assertThat(applicationProperties.getProperty(
                        "management.metrics.distribution.maximum-expected-value." + RunMetrics.RUN_TIMER))
                .isNotNull();
    }

    /**
     * Whether a configured key applies to a meter, by Boot's prefix-walking rule: the key must equal the meter name
     * or be one of its dot-separated ancestors. Notably <em>not</em> symmetric - a key longer than the meter name
     * matches nothing, which is the whole bug.
     */
    private boolean appliesTo(String configuredKey, String meterName) {
        return meterName.equals(configuredKey) || meterName.startsWith(configuredKey + ".");
    }

    private List<String> distributionKeys() {
        return applicationProperties.stringPropertyNames().stream()
                .filter(name -> name.startsWith(DISTRIBUTION_PREFIX))
                .sorted()
                .toList();
    }
}
