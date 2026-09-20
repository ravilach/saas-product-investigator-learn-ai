package com.saasinvestigator.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.saasinvestigator.product.SourceType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Tests {@link ChangeReportRepository} against a real MongoDB, and that a report survives a round trip intact.
 *
 * <p>Two things are being protected. First, the queries - same reasoning as for snapshots: neither a derived method
 * name nor a {@code @Query} string is checked at compile time, and a half-open range written as closed is a bug that
 * produces entirely plausible output. This class earned its keep immediately:
 * {@link ChangeReportRepository#findWithinWindow} was originally a derived query and threw
 * {@code InvalidMongoDbApiUsageException} on every call, because Spring Data cannot derive two conditions on one
 * property. Nothing about that is visible until it runs.
 *
 * <p>Second, that the embedded records ({@link Change}, {@link SourceInclusion}) actually persist and come back,
 * including their enums and their deliberate nulls. Mongo maps records via the driver's record codec rather than
 * through Spring Data's usual property access, so it is worth a test rather than an assumption.
 *
 * <p>Requires a running Docker daemon; fails rather than skips without one (see {@code docs/SETUP.md}).
 */
@Testcontainers
@DataMongoTest
class ChangeReportRepositoryMongoTest {

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");

    @Autowired
    private ChangeReportRepository reports;

    @Autowired
    private MongoTemplate mongoTemplate;

    private static final String PRODUCT = "product-1";
    private static final String OTHER_PRODUCT = "product-2";
    private static final Instant T0 = Instant.parse("2026-09-01T12:00:00Z");

    @BeforeEach
    void seed() {
        mongoTemplate.getCollection("change_reports").drop();
        save(PRODUCT, "first summary", T0);
        save(PRODUCT, "second summary", T0.plus(1, ChronoUnit.DAYS));
        save(PRODUCT, "third summary", T0.plus(2, ChronoUnit.DAYS));
        save(OTHER_PRODUCT, "other product summary", T0.plus(3, ChronoUnit.DAYS));
    }

    @Test
    void theHistoryTimelineIsNewestFirstAndScopedToOneProduct() {
        var page = reports.findBySaasProductIdOrderByRunAtDesc(PRODUCT, PageRequest.of(0, 2));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(ChangeReport::getOverallSummary)
                .containsExactly("third summary", "second summary");
        assertThat(page.isLast()).isFalse();
    }

    @Test
    void theMostRecentReportIsWhatLastRunAndSinceLastRunBothResolveTo() {
        // Computed on every read rather than stored on the product, so it cannot go stale. Both the lastRun summary
        // and the date picker's "Since last run" preset depend on this being the genuinely newest report.
        assertThat(reports.findFirstBySaasProductIdOrderByRunAtDesc(PRODUCT))
                .get().extracting(ChangeReport::getOverallSummary).isEqualTo("third summary");
    }

    @Test
    void aProductThatHasNeverRunHasNoMostRecentReport() {
        // Which is what makes lastRun legitimately null, rather than an empty object the frontend has to distinguish.
        assertThat(reports.findFirstBySaasProductIdOrderByRunAtDesc("never-run")).isEmpty();
    }

    @Test
    void theWindowUsedToAggregateMcpHistoryExcludesItsStartAndIncludesItsEnd() {
        // Half-open on purpose. A report written exactly at the start of the range describes the state the range
        // begins from; including it would attribute changes from before the window to inside it.
        var withinWindow = reports.findWithinWindow(
                PRODUCT, T0, T0.plus(2, ChronoUnit.DAYS));

        assertThat(withinWindow).extracting(ChangeReport::getOverallSummary)
                .containsExactly("third summary", "second summary");
    }

    @Test
    void anEmptyWindowYieldsNoReportsRatherThanAllOfThem() {
        // A range with nothing in it is a normal answer ("no reports were written then"), and the failure mode worth
        // guarding is the criteria silently not applying at all, which looks like a suspiciously thorough compare.
        assertThat(reports.findWithinWindow(
                PRODUCT, T0.plus(10, ChronoUnit.DAYS), T0.plus(20, ChronoUnit.DAYS))).isEmpty();
    }

    @Test
    void theWindowDoesNotLeakAcrossProducts() {
        assertThat(reports.findWithinWindow(
                PRODUCT, T0.minus(1, ChronoUnit.DAYS), T0.plus(30, ChronoUnit.DAYS)))
                .extracting(ChangeReport::getSaasProductId).containsOnly(PRODUCT);
    }

    @Test
    void countingIsPerProduct() {
        assertThat(reports.countBySaasProductId(PRODUCT)).isEqualTo(3);
        assertThat(reports.countBySaasProductId(OTHER_PRODUCT)).isEqualTo(1);
        assertThat(reports.countBySaasProductId("never-run")).isZero();
    }

    @Test
    void deletingByProductRemovesOnlyThatProductsReports() {
        long removed = reports.deleteBySaasProductId(PRODUCT);

        assertThat(removed).isEqualTo(3);
        assertThat(reports.findAll()).hasSize(1)
                .allSatisfy(r -> assertThat(r.getSaasProductId()).isEqualTo(OTHER_PRODUCT));
    }

    @Test
    void aStandardReportRoundTripsWithItsEmbeddedRecordsAndEnums() {
        ChangeReport saved = reports.save(new ChangeReport(PRODUCT, "admin", AnalysisDepth.NUCLEAR,
                List.of(new SourceInclusion("Changelog", SourceType.SAAS_URL, T0),
                        new SourceInclusion("Docs", SourceType.DOCS_MCP, null)),
                "One pricing change and one deprecation.",
                List.of(new Change("Changelog", SourceType.SAAS_URL, ChangeCategory.PRICING,
                                "Team plan went from $20 to $25.", Confidence.HIGH, "Team - $25/user/month"),
                        new Change("Docs", SourceType.DOCS_MCP, ChangeCategory.DEPRECATION,
                                "The v1 endpoint is marked deprecated.", Confidence.MEDIUM, null))));

        ChangeReport reloaded = reports.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getAnalysisDepth()).isEqualTo(AnalysisDepth.NUCLEAR);
        assertThat(reloaded.getRunType()).isEqualTo(RunType.STANDARD);
        assertThat(reloaded.getChanges()).hasSize(2);
        assertThat(reloaded.getChanges().getFirst().category()).isEqualTo(ChangeCategory.PRICING);
        assertThat(reloaded.getChanges().getFirst().confidence()).isEqualTo(Confidence.HIGH);
        // A change with nothing to quote - a removed section - keeps a null snippet rather than an empty string.
        assertThat(reloaded.getChanges().get(1).evidenceSnippet()).isNull();
        // An MCP source's null fetchedAt survives as null: the backend never held its content and cannot claim one.
        assertThat(reloaded.getSourcesIncluded()).hasSize(2);
        assertThat(reloaded.getSourcesIncluded().get(1).fetchedAt()).isNull();
        assertThat(reloaded.getSourcesIncluded().get(1).sourceType()).isEqualTo(SourceType.DOCS_MCP);
    }

    @Test
    void aCustomRangeReportRoundTripsWithItsBoundsAndCaveat() {
        ChangeReport saved = reports.save(new ChangeReport(PRODUCT, "dana", AnalysisDepth.SHORT, List.of(),
                "Aggregated from earlier reports.", List.of())
                .asCustomRange(T0, T0.plus(2, ChronoUnit.DAYS), true));

        ChangeReport reloaded = reports.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getRunType()).isEqualTo(RunType.CUSTOM_RANGE);
        assertThat(reloaded.getRangeFrom()).isEqualTo(T0);
        assertThat(reloaded.getRangeTo()).isEqualTo(T0.plus(2, ChronoUnit.DAYS));
        assertThat(reloaded.isMcpHistoryLimited()).isTrue();
        assertThat(reloaded.getRunBy()).isEqualTo("dana");
    }

    private void save(String productId, String summary, Instant runAt) {
        ChangeReport report = new ChangeReport(productId, "admin", AnalysisDepth.REGULAR, List.of(), summary,
                List.of());
        report.setRunAt(runAt);
        mongoTemplate.save(report);
    }
}
