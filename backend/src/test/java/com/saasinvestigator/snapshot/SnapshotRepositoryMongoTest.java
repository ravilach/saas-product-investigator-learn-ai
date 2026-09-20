package com.saasinvestigator.snapshot;

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
 * Tests {@link SnapshotRepository} against a real MongoDB.
 *
 * <p>Every finder in that interface is a <b>derived query</b> - Spring Data parses the method name and builds the
 * query from it - which means nothing about it is checked at compile time. A misspelled property, a
 * {@code LessThanEqual} that should have been {@code LessThan}, an {@code OrderBy} pointing at the wrong field: all
 * of those compile, and all of them fail at runtime or, worse, succeed while returning the wrong document. Mocking
 * the repository would assert only that the test agrees with itself.
 *
 * <p>The two finders that matter most are the "previous snapshot" lookup, which is what makes change detection
 * possible at all, and the at-or-before lookup a custom-range compare anchors on. Both are about picking one
 * document out of a history, and both are wrong in an invisible way if the ordering is off by one.
 *
 * <p>Requires a running Docker daemon; fails rather than skips without one (see {@code docs/SETUP.md}).
 */
@Testcontainers
@DataMongoTest
class SnapshotRepositoryMongoTest {

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");

    @Autowired
    private SnapshotRepository snapshots;

    @Autowired
    private MongoTemplate mongoTemplate;

    private static final String PRODUCT = "product-1";
    private static final String OTHER_PRODUCT = "product-2";
    private static final Instant T0 = Instant.parse("2026-09-01T12:00:00Z");

    @BeforeEach
    void seed() {
        mongoTemplate.getCollection("snapshots").drop();
        // Three runs of "Changelog", one of "Docs site", and one belonging to a different product entirely.
        save(PRODUCT, "Changelog", SourceType.SAAS_URL, "v1 content", T0);
        save(PRODUCT, "Changelog", SourceType.SAAS_URL, "v2 content", T0.plus(1, ChronoUnit.DAYS));
        save(PRODUCT, "Changelog", SourceType.SAAS_URL, "v3 content", T0.plus(2, ChronoUnit.DAYS));
        save(PRODUCT, "Docs site", SourceType.WEBSITE, "docs content", T0.plus(1, ChronoUnit.DAYS));
        save(OTHER_PRODUCT, "Changelog", SourceType.SAAS_URL, "other product content", T0.plus(3, ChronoUnit.DAYS));
    }

    @Test
    void theMostRecentSnapshotForASourceIsTheOneAComparisonUses() {
        var found = snapshots.findFirstBySaasProductIdAndSourceNameOrderByFetchedAtDesc(PRODUCT, "Changelog");

        assertThat(found).isPresent();
        assertThat(found.get().getRawContent()).isEqualTo("v3 content");
    }

    @Test
    void theLookupIsScopedToOneProductAndOneSource() {
        // Two products can legitimately have a source with the same name. Leaking across them would mean comparing
        // one product's changelog against another's - producing a report that is entirely fiction but reads fine.
        assertThat(snapshots.findFirstBySaasProductIdAndSourceNameOrderByFetchedAtDesc(OTHER_PRODUCT, "Changelog"))
                .get().extracting(Snapshot::getRawContent).isEqualTo("other product content");

        assertThat(snapshots.findFirstBySaasProductIdAndSourceNameOrderByFetchedAtDesc(PRODUCT, "Docs site"))
                .get().extracting(Snapshot::getRawContent).isEqualTo("docs content");
    }

    @Test
    void aSourceWithNoHistoryYieldsEmptyRatherThanAnything() {
        // A product's first-ever run hits this for every source. It must be empty, not the newest snapshot of some
        // other source, or the first report would claim changes that are really just two different sources.
        assertThat(snapshots.findFirstBySaasProductIdAndSourceNameOrderByFetchedAtDesc(PRODUCT, "Never crawled"))
                .isEmpty();
    }

    @Test
    void theAtOrBeforeLookupPicksTheNearestEarlierSnapshotNotTheNearestOverall() {
        // Anchored between v2 and v3. The nearest snapshot overall is arguably v3, but v3 may already contain the
        // changes the compare is meant to find, so "at or before" is the only safe reading of a date.
        var found = snapshots
                .findFirstBySaasProductIdAndSourceNameAndFetchedAtLessThanEqualOrderByFetchedAtDesc(
                        PRODUCT, "Changelog", T0.plus(1, ChronoUnit.DAYS).plus(6, ChronoUnit.HOURS));

        assertThat(found).get().extracting(Snapshot::getRawContent).isEqualTo("v2 content");
    }

    @Test
    void theAtOrBeforeLookupIncludesAnExactBoundaryMatch() {
        // "At or before", not "before": a compare anchored exactly at a snapshot's timestamp should use it. An
        // off-by-one here silently shifts every custom-range compare back by one run.
        var found = snapshots
                .findFirstBySaasProductIdAndSourceNameAndFetchedAtLessThanEqualOrderByFetchedAtDesc(
                        PRODUCT, "Changelog", T0.plus(1, ChronoUnit.DAYS));

        assertThat(found).get().extracting(Snapshot::getRawContent).isEqualTo("v2 content");
    }

    @Test
    void anAnchorBeforeAllHistoryYieldsEmpty() {
        // A user picking a date from before they started tracking the product. The compare has to say so rather than
        // fall back to the oldest snapshot and present it as the state on that date.
        assertThat(snapshots.findFirstBySaasProductIdAndSourceNameAndFetchedAtLessThanEqualOrderByFetchedAtDesc(
                PRODUCT, "Changelog", T0.minus(1, ChronoUnit.DAYS))).isEmpty();
    }

    @Test
    void oneSourcesHistoryIsPagedNewestFirst() {
        var page = snapshots.findBySaasProductIdAndSourceNameOrderByFetchedAtDesc(
                PRODUCT, "Changelog", PageRequest.of(0, 2));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(Snapshot::getRawContent)
                .containsExactly("v3 content", "v2 content");
        assertThat(page.isLast()).isFalse();
    }

    @Test
    void awholeProductsHistorySpansItsSourcesNewestFirst() {
        var page = snapshots.findBySaasProductIdOrderByFetchedAtDesc(PRODUCT, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(4);
        assertThat(page.getContent())
                .extracting(Snapshot::getFetchedAt)
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
        // The other product's snapshot is excluded even though it is the newest document in the collection.
        assertThat(page.getContent()).extracting(Snapshot::getSaasProductId).containsOnly(PRODUCT);
    }

    @Test
    void deletingByProductRemovesOnlyThatProductsSnapshots() {
        long removed = snapshots.deleteBySaasProductId(PRODUCT);

        assertThat(removed).isEqualTo(4);
        assertThat(snapshots.findAll()).hasSize(1)
                .allSatisfy(s -> assertThat(s.getSaasProductId()).isEqualTo(OTHER_PRODUCT));
    }

    @Test
    void pageUrlsAndSourceTypeSurviveARoundTrip() {
        // pageUrls is the field that answers "why did the report miss the pricing page", and sourceType is what keeps
        // a snapshot interpretable after its source has been deleted. Both are worth proving actually persist.
        Snapshot saved = snapshots.save(new Snapshot(PRODUCT, "Multi page", SourceType.WEBSITE, "text",
                List.of("https://example.com/", "https://example.com/pricing")));

        Snapshot reloaded = snapshots.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getPageUrls())
                .containsExactly("https://example.com/", "https://example.com/pricing");
        assertThat(reloaded.getSourceType()).isEqualTo(SourceType.WEBSITE);
        assertThat(reloaded.getFetchedAt()).isNotNull();
    }

    private void save(String productId, String sourceName, SourceType type, String content, Instant fetchedAt) {
        Snapshot snapshot = new Snapshot(productId, sourceName, type, content, List.of("https://example.com/"));
        snapshot.setFetchedAt(fetchedAt);
        mongoTemplate.save(snapshot);
    }
}
