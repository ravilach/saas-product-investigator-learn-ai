package com.saasinvestigator.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Asserts that every Spring-owned property in {@code application.properties} is one the current Spring Boot
 * version actually binds.
 *
 * <p>This exists because of a bug that cost real time to find. Spring Boot 4.0 moved MongoDB <i>connection</i>
 * configuration out of the Spring Data namespace: {@code spring.data.mongodb.uri} became
 * {@code spring.mongodb.uri}, and the old name was removed at deprecation level {@code error}. A property removed
 * at that level is not bound and not warned about - it is silently ignored. So the application booted fine, every
 * query worked, and the data went to Boot's default {@code mongodb://localhost/test} instead of the database the
 * URI named. {@code MONGODB_URI} had no effect at all, which would have shipped in the container image and in
 * every deployment sample.
 *
 * <p>Nothing else in the suite could catch that. The Testcontainers tests use {@code @ServiceConnection}, which
 * supplies the connection programmatically and never reads this file; every other test either mocks the
 * repositories or does not touch Mongo. A wrong-but-valid property name is invisible to all of them.
 *
 * <p>The check is deliberately generic rather than a hardcoded assertion about the Mongo URI: the failure mode is
 * a property <i>rename</i>, and renames arrive as a batch on a Boot upgrade. Reading the metadata that Boot ships
 * catches the next one for free, which a test asserting one known-good key would not.
 *
 * <h2>How it works</h2>
 *
 * <p>Every Boot module ships {@code META-INF/spring-configuration-metadata.json} describing the properties it
 * binds, including {@code deprecation.level} and {@code deprecation.replacement} for the ones it no longer does.
 * The classpath is enumerated through {@link ClassLoader#getResources} rather than by splitting
 * {@code java.class.path}, because Surefire may hand the JVM a manifest-only jar whose {@code Class-Path} the
 * system property does not expand.
 *
 * <p>Two categories are exempt. Properties under {@code app.} are this application's own
 * {@code @ConfigurationProperties} and are proven to bind by the tests that read them. Map-valued properties
 * ({@code logging.level.*}, {@code management.metrics.distribution.*}) appear in the metadata as the prefix alone,
 * so they are matched by prefix - the key after it is user-defined by design.
 */
class ApplicationPropertiesBindingTest {

    /** Prefixes owned by this application rather than by Spring, verified by the tests that consume them. */
    private static final String OWN_PREFIX = "app.";

    /**
     * Properties whose metadata entry is the prefix and whose remainder is a user-chosen map key. Listed
     * explicitly rather than inferred, so that adding one is a deliberate act with a reason attached.
     */
    private static final Set<String> MAP_VALUED_PREFIXES = Set.of(
            "logging.level",
            "management.metrics.distribution.percentiles-histogram",
            "management.metrics.distribution.percentiles",
            "management.metrics.distribution.slo",
            "management.metrics.tags");

    /** Property name -> replacement (or {@code ""} when the metadata names none). */
    private static Map<String, String> removed;

    /** Every property name the classpath can bind, including non-error-level deprecations. */
    private static Set<String> bindable;

    private static Properties applicationProperties;

    @BeforeAll
    static void readMetadataAndProperties() throws IOException {
        Map<String, String> removedByName = new TreeMap<>();
        Set<String> bindableNames = new java.util.HashSet<>();
        ObjectMapper mapper = new ObjectMapper(); // Jackson 2: a constructed mapper, never the injected HTTP one.

        for (String resource : List.of(
                "META-INF/spring-configuration-metadata.json",
                "META-INF/additional-spring-configuration-metadata.json")) {
            Enumeration<URL> urls = ApplicationPropertiesBindingTest.class.getClassLoader().getResources(resource);
            while (urls.hasMoreElements()) {
                try (InputStream in = urls.nextElement().openStream()) {
                    JsonNode properties = mapper.readTree(in).path("properties");
                    for (JsonNode property : properties) {
                        String name = property.path("name").asText();
                        JsonNode deprecation = property.path("deprecation");
                        if ("error".equals(deprecation.path("level").asText())) {
                            removedByName.put(name, deprecation.path("replacement").asText(""));
                        } else {
                            bindableNames.add(name);
                        }
                    }
                }
            }
        }

        // A name can be bindable in one module and removed in another (the Mongo case: spring-boot-mongodb
        // declares the removal while an older module still lists the name). Removal wins - it is the module that
        // owns the binding today.
        bindableNames.removeAll(removedByName.keySet());

        removed = removedByName;
        bindable = bindableNames;

        applicationProperties = new Properties();
        try (InputStream in = ApplicationPropertiesBindingTest.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            assertThat(in).as("application.properties must be on the test classpath").isNotNull();
            applicationProperties.load(in);
        }
    }

    @Test
    void theMetadataScanFoundSomethingToCheckAgainst() {
        // Guards the guard: if the metadata ever stops being discoverable, every other assertion here passes
        // vacuously and the suite quietly loses the protection it was written for.
        assertThat(bindable).as("Spring configuration metadata on the classpath").hasSizeGreaterThan(500);
        assertThat(removed).as("removed properties, which Boot 4 has many of").isNotEmpty();
        assertThat(applicationProperties).isNotEmpty();
    }

    @Test
    void noPropertyHasBeenRemovedByTheCurrentSpringBootVersion() {
        List<String> offenders = new ArrayList<>();
        for (String name : springOwnedProperties().keySet()) {
            String replacement = removed.get(name);
            if (replacement != null) {
                offenders.add(name + " -> use "
                        + (replacement.isEmpty() ? "(no replacement; the feature moved or was dropped)" : replacement));
            }
        }

        assertThat(offenders)
                .as("properties removed at deprecation level 'error' are silently ignored, not warned about - "
                        + "the app boots and the setting simply does nothing")
                .isEmpty();
    }

    @Test
    void everyPropertyIsOneSomeModuleOnTheClasspathBinds() {
        List<String> unknown = new ArrayList<>();
        for (String name : springOwnedProperties().keySet()) {
            if (bindable.contains(name) || isMapValued(name)) {
                continue;
            }
            unknown.add(name);
        }

        assertThat(unknown)
                .as("a property no module declares is either a typo or a name that moved; if it is a legitimate "
                        + "map key, add its prefix to MAP_VALUED_PREFIXES with a note saying why")
                .isEmpty();
    }

    @Test
    void theMongoConnectionUriUsesTheNameBootFourBinds() {
        // The specific regression. Kept alongside the generic checks because this one names the consequence:
        // the wrong name here does not fail, it writes to a database nobody configured.
        assertThat(applicationProperties.stringPropertyNames())
                .contains("spring.mongodb.uri")
                .doesNotContain("spring.data.mongodb.uri");
        assertThat(applicationProperties.getProperty("spring.mongodb.uri"))
                .as("MONGODB_URI must be honoured, with a named database in the fallback")
                .isEqualTo("${MONGODB_URI:mongodb://localhost:27017/saas-investigator}");
    }

    /** The properties this test is responsible for: everything not under {@code app.}. */
    private Map<String, String> springOwnedProperties() {
        Map<String, String> springOwned = new LinkedHashMap<>();
        for (String name : applicationProperties.stringPropertyNames()) {
            if (!name.startsWith(OWN_PREFIX)) {
                springOwned.put(name, applicationProperties.getProperty(name));
            }
        }
        return springOwned;
    }

    private boolean isMapValued(String name) {
        return MAP_VALUED_PREFIXES.stream().anyMatch(prefix -> name.startsWith(prefix + "."));
    }
}
