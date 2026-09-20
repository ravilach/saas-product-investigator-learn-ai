package com.saasinvestigator.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.saasinvestigator.report.AnalysisDepth;
import com.saasinvestigator.report.RunType;
import com.saasinvestigator.run.RunOutcome;
import com.saasinvestigator.run.RunRecord;
import com.saasinvestigator.run.RunRecordRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Tests {@link AdminStatsService} against a real MongoDB.
 *
 * <h2>Why a container rather than a mocked template</h2>
 *
 * <p>Because the thing that can be wrong is the aggregation pipeline, and a mock would return whatever it was told to
 * regardless of whether Mongo would accept the pipeline at all. Two of the ways these particular pipelines fail are
 * invisible to any other kind of test:
 *
 * <ul>
 *   <li>The outcome is matched against {@code RunOutcome.FAILURE.name()} rather than the enum, because an untyped
 *       aggregation has no entity for Spring Data to consult and would hand Mongo an object it cannot compare. Passing
 *       the enum silently matches nothing - and "nothing failed" is a plausible-looking 100% success rate.</li>
 *   <li>{@code $group} with no bucket key emits one document per bucket, so an empty window produces <em>no</em>
 *       document rather than a zeroed one. Reading that as a number is a {@code NullPointerException} on a fresh
 *       install's first page load.</li>
 * </ul>
 *
 * <p>The second is also why the two averages are nullable: a fresh instance has not failed every run, it has not run
 * anything, and "0%" on an untouched dashboard reads as a broken installation.
 */
@Testcontainers
@DataMongoTest
class AdminStatsServiceMongoTest {

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");

    private static final Instant NOW = Instant.now();

    @Autowired
    private MongoTemplate mongo;

    @Autowired
    private RunRecordRepository runRecords;

    private AdminStatsService service;

    @BeforeEach
    void setUp() {
        service = new AdminStatsService(mongo, runRecords);
        mongo.getCollection("users").drop();
        mongo.getCollection("saas_products").drop();
        mongo.getCollection("run_records").drop();
    }

    // ----- The empty instance -----

    @Test
    void reportsZeroCountsAndNullRatesOnAnInstanceThatHasNeverBeenUsed() {
        AdminStatsResponse stats = service.stats();

        assertThat(stats.totalUsers()).isZero();
        assertThat(stats.totalProducts()).isZero();
        assertThat(stats.totalSourcesConfigured()).isZero();
        assertThat(stats.runsLast24h()).isZero();
        assertThat(stats.runsLast7d()).isZero();
        // Null rather than 0.0: a 0% success rate and an unused installation look identical otherwise, and only one of
        // them is a reason to check the logs.
        assertThat(stats.runSuccessRate7d()).isNull();
        assertThat(stats.avgRunDurationSeconds7d()).isNull();
        assertThat(stats.recentRuns()).isEmpty();
    }

    // ----- Counts -----

    @Test
    void countsUsersProductsAndTheSourcesInsideThoseProducts() {
        mongo.getCollection("users").insertOne(new Document("username", "admin"));
        mongo.getCollection("users").insertOne(new Document("username", "dana"));
        mongo.getCollection("saas_products").insertOne(product("Acme", 2));
        mongo.getCollection("saas_products").insertOne(product("Beta", 3));
        // A product created through the UI before its sources are configured has an empty array; one written by an older
        // version may have no field at all. Both must count as zero rather than breaking $size.
        mongo.getCollection("saas_products").insertOne(new Document("name", "Gamma"));

        AdminStatsResponse stats = service.stats();

        assertThat(stats.totalUsers()).isEqualTo(2);
        assertThat(stats.totalProducts()).isEqualTo(3);
        assertThat(stats.totalSourcesConfigured()).isEqualTo(5);
    }

    // ----- Run windows -----

    @Test
    void countsOnlyTheRunsInsideEachWindow() {
        save(run(RunOutcome.SUCCESS, NOW.minus(Duration.ofHours(1)), Duration.ofSeconds(30)));
        save(run(RunOutcome.SUCCESS, NOW.minus(Duration.ofHours(30)), Duration.ofSeconds(30)));
        save(run(RunOutcome.SUCCESS, NOW.minus(Duration.ofDays(10)), Duration.ofSeconds(30)));

        AdminStatsResponse stats = service.stats();

        assertThat(stats.runsLast24h()).isEqualTo(1);
        assertThat(stats.runsLast7d()).isEqualTo(2);
    }

    @Test
    void computesTheSuccessRateOverTheWeekTreatingAPartialRunAsASuccess() {
        // PARTIAL means some sources were analysed and some could not be reached, which produced a usable report. A rate
        // that counted it as a failure would show a broken-looking dashboard for a deployment working as designed.
        save(run(RunOutcome.SUCCESS, NOW.minus(Duration.ofHours(2)), Duration.ofSeconds(10)));
        save(run(RunOutcome.PARTIAL, NOW.minus(Duration.ofHours(3)), Duration.ofSeconds(20)));
        save(run(RunOutcome.FAILURE, NOW.minus(Duration.ofHours(4)), Duration.ofSeconds(30)));
        save(run(RunOutcome.FAILURE, NOW.minus(Duration.ofHours(5)), Duration.ofSeconds(40)));

        AdminStatsResponse stats = service.stats();

        assertThat(stats.runsLast7d()).isEqualTo(4);
        assertThat(stats.runSuccessRate7d()).isEqualTo(0.5);
        // Seconds, not the milliseconds the field is stored in: the dashboard shows "25.0s", and a unit error here is the
        // kind that looks plausible on screen for a long time.
        assertThat(stats.avgRunDurationSeconds7d()).isEqualTo(25.0);
    }

    @Test
    void reportsAFullSuccessRateOnlyWhenNothingActuallyFailed() {
        // The inverse of the enum-matching bug: if the FAILURE comparison silently matched nothing, this test and the
        // one above would both report 100% and only one of them would be right.
        save(run(RunOutcome.SUCCESS, NOW.minus(Duration.ofHours(2)), Duration.ofSeconds(10)));
        save(run(RunOutcome.SUCCESS, NOW.minus(Duration.ofHours(3)), Duration.ofSeconds(10)));

        assertThat(service.stats().runSuccessRate7d()).isEqualTo(1.0);
    }

    @Test
    void ignoresRunsOlderThanTheWeekWhenComputingTheRate() {
        save(run(RunOutcome.FAILURE, NOW.minus(Duration.ofDays(9)), Duration.ofSeconds(10)));
        save(run(RunOutcome.SUCCESS, NOW.minus(Duration.ofHours(1)), Duration.ofSeconds(10)));

        AdminStatsResponse stats = service.stats();

        assertThat(stats.runsLast7d()).isEqualTo(1);
        assertThat(stats.runSuccessRate7d()).isEqualTo(1.0);
    }

    // ----- Recent runs -----

    @Test
    void listsTheMostRecentRunsNewestFirstCappedAtTheDisplayLimit() {
        for (int i = 0; i < AdminStatsService.RECENT_RUN_LIMIT + 4; i++) {
            save(run(RunOutcome.SUCCESS, NOW.minus(Duration.ofMinutes(i)), Duration.ofSeconds(5)));
        }

        List<AdminStatsResponse.RecentRun> recent = service.stats().recentRuns();

        assertThat(recent).hasSize(AdminStatsService.RECENT_RUN_LIMIT);
        assertThat(recent.get(0).runAt()).isAfter(recent.get(1).runAt());
    }

    @Test
    void describesARecentRunWithTheProductNameAndTheWireFormOfItsOutcome() {
        save(run(RunOutcome.PARTIAL, NOW.minus(Duration.ofMinutes(5)), Duration.ofSeconds(12)));

        assertThat(service.stats().recentRuns()).singleElement().satisfies(recent -> {
            // The product name is denormalised onto the run record, so the dashboard can list a run whose product has
            // since been deleted rather than showing a dangling id.
            assertThat(recent.productName()).isEqualTo("Acme Analytics");
            assertThat(recent.runType()).isEqualTo(RunType.STANDARD);
            assertThat(recent.status()).isEqualTo("partial");
        });
    }

    // ----- Fixtures -----

    private void save(RunRecord record) {
        runRecords.save(record);
    }

    private static RunRecord run(RunOutcome outcome, Instant startedAt, Duration duration) {
        return new RunRecord("product-1", "Acme Analytics", RunType.STANDARD, AnalysisDepth.REGULAR,
                outcome, "dana", startedAt, duration,
                outcome == RunOutcome.FAILURE ? null : "report-1",
                outcome == RunOutcome.FAILURE ? "The analysis could not be completed." : null);
    }

    private static Document product(String name, int sourceCount) {
        List<Document> sources = new ArrayList<>();
        for (int i = 0; i < sourceCount; i++) {
            sources.add(new Document("name", "source-" + i).append("type", "WEBSITE"));
        }
        return new Document("name", name).append("sources", sources);
    }
}
