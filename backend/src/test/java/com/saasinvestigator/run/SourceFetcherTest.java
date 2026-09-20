package com.saasinvestigator.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saasinvestigator.crawl.CrawlFailedException;
import com.saasinvestigator.crawl.CrawlProgress;
import com.saasinvestigator.crawl.CrawlProgressListener;
import com.saasinvestigator.crawl.CrawlResult;
import com.saasinvestigator.crawl.WebCrawler;
import com.saasinvestigator.llm.SourceComparison;
import com.saasinvestigator.product.SaasProduct;
import com.saasinvestigator.product.SourceConfig;
import com.saasinvestigator.product.SourceType;
import com.saasinvestigator.report.RunType;
import com.saasinvestigator.report.SourceInclusion;
import com.saasinvestigator.snapshot.Snapshot;
import com.saasinvestigator.snapshot.SnapshotRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * Tests the one ordering bug in this application that would leave no symptom.
 *
 * <p>{@link SourceFetcher} loads a source's previous snapshot, crawls the source, then saves the new snapshot. Swap the
 * load and the save and everything still works: the crawl runs, a report is written, the run is counted as a success,
 * no exception is thrown and no log line looks unusual. The only difference is that every source is compared against
 * itself, so every report says nothing has changed - which is also what a correct report of a quiet week says. There is
 * no observation a user could make on one report to tell those apart, which is why the ordering is asserted here with
 * an {@link InOrder} rather than left to a careful reader.
 *
 * <p>The rest of the class is about the same distinction one level up: telling "we looked and found nothing" apart from
 * "we could not look". That is what the failure handling, the {@code sourcesIncluded} entries, and the decision not to
 * store a snapshot for a failed source all exist to preserve.
 */
class SourceFetcherTest {

    private static final Instant PRIOR_AT = Instant.parse("2026-09-13T09:00:00Z");
    private static final String MARKETING_URL = "https://example.com/marketing-site";

    private WebCrawler crawler;
    private SnapshotRepository snapshots;
    private RunMetrics metrics;
    private SourceFetcher fetcher;

    @BeforeEach
    void setUp() {
        crawler = mock(WebCrawler.class);
        snapshots = mock(SnapshotRepository.class);
        metrics = mock(RunMetrics.class);
        fetcher = new SourceFetcher(crawler, snapshots, metrics);

        // The repository echoes back whatever it is asked to save, as Spring Data does, so the fetcher's use of the
        // saved document's fetchedAt is exercised rather than stubbed away.
        when(snapshots.save(any(Snapshot.class))).thenAnswer(call -> call.getArgument(0));
        when(snapshots.findFirstBySaasProductIdAndSourceNameOrderByFetchedAtDesc(anyString(), anyString()))
                .thenReturn(Optional.empty());
    }

    // ---------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------

    private static SourceConfig source(SourceType type, String name) {
        return new SourceConfig(type, name, "https://example.com/" + name.toLowerCase().replace(' ', '-'), null);
    }

    private static SaasProduct product(SourceConfig... sources) {
        SaasProduct product = new SaasProduct("Acme Billing", "Billing SaaS", List.of(sources), "admin");
        product.setId("product-1");
        return product;
    }

    private static RunSession session() {
        return new RunSession("run-1", "product-1", RunType.STANDARD);
    }

    private static CrawlResult crawlResult(String content) {
        return new CrawlResult(content, List.of("https://example.com/changelog"), 1, 1, false, List.of(), 0, false);
    }

    private static Snapshot priorSnapshot(String content) {
        Snapshot snapshot = new Snapshot("product-1", "Changelog", SourceType.WEBSITE, content,
                List.of("https://example.com/changelog"));
        snapshot.setFetchedAt(PRIOR_AT);
        return snapshot;
    }

    // ---------------------------------------------------------------------
    // Read before write
    // ---------------------------------------------------------------------

    @Test
    void thePreviousSnapshotIsReadBeforeTheNewOneIsSaved() {
        SaasProduct product = product(source(SourceType.WEBSITE, "Changelog"));
        when(crawler.crawl(any(), any())).thenReturn(crawlResult("new text"));

        fetcher.fetchAll(product, session());

        // Reverse these two lines in SourceFetcher and this is the only thing in the whole build that notices.
        InOrder order = inOrder(snapshots);
        order.verify(snapshots)
                .findFirstBySaasProductIdAndSourceNameOrderByFetchedAtDesc("product-1", "Changelog");
        order.verify(snapshots).save(any(Snapshot.class));
    }

    @Test
    void theComparisonPairsTheStoredTextWithThePreviousTextRatherThanWithItself() {
        SaasProduct product = product(source(SourceType.WEBSITE, "Changelog"));
        when(snapshots.findFirstBySaasProductIdAndSourceNameOrderByFetchedAtDesc("product-1", "Changelog"))
                .thenReturn(Optional.of(priorSnapshot("old text")));
        when(crawler.crawl(any(), any())).thenReturn(crawlResult("new text"));

        SourceFetcher.FetchOutcome outcome = fetcher.fetchAll(product, session());

        assertThat(outcome.comparisons()).singleElement().satisfies(comparison -> {
            assertThat(comparison.priorText()).isEqualTo("old text");
            assertThat(comparison.currentText()).isEqualTo("new text");
            assertThat(comparison.priorFetchedAt()).isEqualTo(PRIOR_AT);
            assertThat(comparison.hasPrior()).isTrue();
        });
    }

    @Test
    void aFirstEverRunCarriesNoPriorStateRatherThanAnEmptyOne() {
        SaasProduct product = product(source(SourceType.WEBSITE, "Changelog"));
        when(crawler.crawl(any(), any())).thenReturn(crawlResult("current text"));

        SourceFetcher.FetchOutcome outcome = fetcher.fetchAll(product, session());

        // Null rather than "" matters downstream: the prompt asks for a baseline description when hasPrior() is false,
        // and an empty string would instead make every line of the page look like a brand-new addition.
        assertThat(outcome.comparisons()).singleElement().satisfies(comparison -> {
            assertThat(comparison.priorText()).isNull();
            assertThat(comparison.priorFetchedAt()).isNull();
            assertThat(comparison.hasPrior()).isFalse();
        });
    }

    // ---------------------------------------------------------------------
    // One failed source does not fail the run
    // ---------------------------------------------------------------------

    @Test
    void aSourceThatCannotBeReadIsCarriedAsAFailureAndTheOthersAreStillRead() {
        SaasProduct product = product(
                source(SourceType.WEBSITE, "Marketing site"),
                source(SourceType.SAAS_URL, "Changelog"));
        when(crawler.crawl(any(), any()))
                .thenThrow(new CrawlFailedException(MARKETING_URL, "the server returned 503"))
                .thenReturn(crawlResult("changelog text"));

        SourceFetcher.FetchOutcome outcome = fetcher.fetchAll(product, session());

        assertThat(outcome.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.sourceName()).isEqualTo("Marketing site");
            // The crawler's own message is forwarded verbatim because it is written for a user: it names the URL and
            // the specific cause, and "one source failed" is useless without "which, and why".
            assertThat(failure.reason()).isEqualTo("Could not crawl " + MARKETING_URL + ": the server returned 503");
        });
        assertThat(outcome.comparisons()).extracting(SourceComparison::sourceName).containsExactly("Changelog");
    }

    @Test
    void aFailedSourceWritesNoSnapshotSoTheNextRunDoesNotReportTheRecoveryAsAChange() {
        SaasProduct product = product(source(SourceType.WEBSITE, "Marketing site"));
        when(crawler.crawl(any(), any())).thenThrow(new CrawlFailedException(MARKETING_URL, "the request timed out"));

        fetcher.fetchAll(product, session());

        // Storing the error page would make the following run report the site coming back as a product change, and the
        // run after that report nothing while a second outage was in progress.
        verify(snapshots, never()).save(any(Snapshot.class));
    }

    @Test
    void anUnexpectedExceptionGivesTheUserAGenericReasonRatherThanItsMessage() {
        SaasProduct product = product(source(SourceType.WEBSITE, "Marketing site"));
        when(crawler.crawl(any(), any()))
                .thenThrow(new IllegalStateException("jdbc:mongodb://user:hunter2@10.0.0.4/internal"));

        SourceFetcher.FetchOutcome outcome = fetcher.fetchAll(product, session());

        // An arbitrary exception message can contain a connection string, a token, or a chunk of request body. The
        // crawler's own CrawlFailedException message is vetted and is forwarded; nothing else is.
        assertThat(outcome.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.reason()).isEqualTo("an unexpected error while fetching this source");
            assertThat(failure.reason()).doesNotContain("hunter2");
        });
    }

    @Test
    void everyFailedSourceIsCountedOnTheErrorMetric() {
        SaasProduct product = product(
                source(SourceType.WEBSITE, "Marketing site"),
                source(SourceType.SAAS_URL, "Changelog"));
        when(crawler.crawl(any(), any())).thenThrow(new CrawlFailedException(MARKETING_URL, "unreachable"));

        fetcher.fetchAll(product, session());

        verify(metrics).recordSourceFetchError("Acme Billing", SourceType.WEBSITE);
        verify(metrics).recordSourceFetchError("Acme Billing", SourceType.SAAS_URL);
    }

    @Test
    void pageLevelFailuresAreCountedWithoutMakingTheSourceItselfAFailure() {
        SaasProduct product = product(source(SourceType.WEBSITE, "Changelog"));
        when(crawler.crawl(any(), any())).thenReturn(new CrawlResult("usable text",
                List.of("https://example.com/changelog"), 5, 4, false,
                List.of(new CrawlResult.PageFailure("https://example.com/gone", "404 Not Found")), 0, false));

        SourceFetcher.FetchOutcome outcome = fetcher.fetchAll(product, session());

        // The source produced usable text, so it is compared and included - but a source that loses a page every run
        // is a misconfiguration worth seeing on a graph.
        assertThat(outcome.failures()).isEmpty();
        assertThat(outcome.comparisons()).hasSize(1);
        verify(metrics).recordSourceFetchError("Acme Billing", SourceType.WEBSITE);
    }

    // ---------------------------------------------------------------------
    // What ends up in sourcesIncluded
    // ---------------------------------------------------------------------

    @Test
    void onlySourcesThatWereActuallyReadAppearInTheInclusions() {
        SaasProduct product = product(
                source(SourceType.WEBSITE, "Marketing site"),
                source(SourceType.SAAS_URL, "Changelog"));
        when(crawler.crawl(any(), any()))
                .thenThrow(new CrawlFailedException(MARKETING_URL, "unreachable"))
                .thenReturn(crawlResult("changelog text"));

        SourceFetcher.FetchOutcome outcome = fetcher.fetchAll(product, session());

        // This is what lets a reader of the finished report tell "no changes here" from "we could not look".
        assertThat(outcome.inclusions()).extracting(SourceInclusion::sourceName).containsExactly("Changelog");
    }

    @Test
    void eachInclusionCarriesTheMomentThatSourceWasFetchedRatherThanOneTimeForTheRun() {
        SaasProduct product = product(source(SourceType.WEBSITE, "Changelog"));
        when(crawler.crawl(any(), any())).thenReturn(crawlResult("text"));

        SourceFetcher.FetchOutcome outcome = fetcher.fetchAll(product, session());

        // Per-source rather than per-run because a nuclear crawl of several sites can span minutes, and "captured at"
        // is a claim about this source's text specifically.
        assertThat(outcome.inclusions()).singleElement().satisfies(inclusion -> {
            assertThat(inclusion.fetchedAt()).isNotNull();
            assertThat(inclusion.sourceType()).isEqualTo(SourceType.WEBSITE);
        });
    }

    @Test
    void anUnchangedSourceStillGetsASnapshotSoTheHistoryHasNoGaps() {
        SaasProduct product = product(source(SourceType.WEBSITE, "Changelog"));
        when(snapshots.findFirstBySaasProductIdAndSourceNameOrderByFetchedAtDesc("product-1", "Changelog"))
                .thenReturn(Optional.of(priorSnapshot("identical text")));
        when(crawler.crawl(any(), any())).thenReturn(crawlResult("identical text"));

        fetcher.fetchAll(product, session());

        // Only storing changes would break the "nearest snapshot at or before this date" lookup a custom-range compare
        // depends on, because a missing document would be ambiguous between "unchanged" and "never run".
        verify(snapshots).save(any(Snapshot.class));
    }

    // ---------------------------------------------------------------------
    // MCP sources, and the step the fetcher owns
    // ---------------------------------------------------------------------

    @Test
    void mcpSourcesAreNeverHandedToTheCrawler() {
        SaasProduct product = product(
                source(SourceType.DOCS_MCP, "Docs"),
                source(SourceType.ATLASSIAN_MCP, "Jira"));

        SourceFetcher.FetchOutcome outcome = fetcher.fetchAll(product, session());

        // The model calls those servers itself during the analysis call. Crawling an MCP endpoint would at best fetch
        // a JSON-RPC error page and at worst send its authorization token to an HTTP client that has no business
        // holding one.
        verify(crawler, never()).crawl(any(), any());
        verify(snapshots, never()).save(any(Snapshot.class));
        assertThat(outcome.comparisons()).isEmpty();
        assertThat(outcome.failures()).isEmpty();
    }

    @Test
    void aProductWithNoCrawledSourcesSaysSoRatherThanReportingZeroSources() {
        SaasProduct product = product(source(SourceType.DOCS_MCP, "Docs"));
        RunSession session = session();
        RecordingSink sink = new RecordingSink();
        session.subscribe(sink);

        fetcher.fetchAll(product, session);

        // "Crawling 0 sources" reads like a bug to the person watching. The step still opens and closes, because a
        // step that silently never appears reads like a hang.
        assertThat(sink.details()).first().asString()
                .contains("no crawled sources")
                .contains("read by the model itself");
        assertThat(sink.types()).containsExactly(RunEventType.STEP_STARTED, RunEventType.STEP_COMPLETED);
    }

    @Test
    void everyPageTheCrawlerFetchesBecomesItsOwnProgressEvent() {
        SaasProduct product = product(source(SourceType.WEBSITE, "Changelog"));
        when(crawler.crawl(any(), any())).thenAnswer(call -> {
            CrawlProgressListener listener = call.getArgument(1);
            listener.onPage(CrawlProgress.fetched("https://example.com/a", 1, 20));
            listener.onPage(CrawlProgress.fetched("https://example.com/b", 2, 20));
            return crawlResult("text");
        });

        RunSession session = session();
        RecordingSink sink = new RecordingSink();
        session.subscribe(sink);

        fetcher.fetchAll(product, session);

        // The build prompt is specific that the live view shows a real URL and a page count rather than "Thinking...",
        // and this is the seam where that specificity either survives or is flattened into a summary.
        assertThat(sink.details()).contains(
                "Crawling https://example.com/a (page 1 of ~20)",
                "Crawling https://example.com/b (page 2 of ~20)");
    }

    @Test
    void theStepsClosingDetailCountsWhatWasReadAndWhatWasNot() {
        SaasProduct product = product(
                source(SourceType.WEBSITE, "Marketing site"),
                source(SourceType.SAAS_URL, "Changelog"));
        when(crawler.crawl(any(), any()))
                .thenReturn(crawlResult("marketing text"))
                .thenThrow(new CrawlFailedException(MARKETING_URL, "unreachable"));

        SourceFetcher.FetchOutcome outcome = fetcher.fetchAll(product, session());

        assertThat(outcome.summary()).isEqualTo("1 source read, 1 unavailable");
    }

    @Test
    void aCleanRunsClosingDetailMentionsNoFailuresAtAll() {
        SaasProduct product = product(
                source(SourceType.WEBSITE, "Marketing site"),
                source(SourceType.SAAS_URL, "Changelog"));
        when(crawler.crawl(any(), any())).thenReturn(crawlResult("text"));

        assertThat(fetcher.fetchAll(product, session()).summary()).isEqualTo("2 sources read");
    }

    @Test
    void theSourceConfigHandedToTheCrawlerIsTheConfiguredOneSoItsLimitsApply() {
        SourceConfig configured = source(SourceType.WEBSITE, "Changelog");
        configured.setMaxDepth(1);
        configured.setMaxPages(5);
        when(crawler.crawl(any(), any())).thenReturn(crawlResult("text"));

        fetcher.fetchAll(product(configured), session());

        // The crawl bounds are a safety boundary rather than a tuning knob, so they must reach the crawler as the user
        // set them rather than being re-derived here.
        verify(crawler).crawl(eq(configured), any());
    }

    @Test
    void theOutcomeCannotBeChangedAfterTheCrawlingThatProducedIt() {
        SourceFetcher.FetchOutcome outcome = new SourceFetcher.FetchOutcome(
                new java.util.ArrayList<>(), new java.util.ArrayList<>(), new java.util.ArrayList<>());

        assertThat(outcome.comparisons()).isUnmodifiable();
        assertThat(outcome.failures()).isUnmodifiable();
        assertThat(outcome.inclusions()).isUnmodifiable();
    }
}
