package com.saasinvestigator.crawl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.saasinvestigator.product.SourceConfig;
import com.saasinvestigator.product.SourceType;
import com.saasinvestigator.systemconfig.SystemConfigService;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests {@link WebCrawler} against a real HTTP server on a loopback port (see {@link TestWebSite}).
 *
 * <p>Against real HTTP rather than a mocked client, because almost everything worth asserting here is about
 * behaviour at the boundary: a redirect followed and then rejected for leaving the origin, a {@code Content-Type}
 * deciding whether a body is parsed or discarded, a socket read hitting its timeout, and concurrency actually
 * being bounded. A mock would let the crawler assert that it calls itself the way it was written to.
 *
 * <p>The tests fall into three groups, and the middle one is the important one:
 *
 * <ol>
 *   <li><b>It crawls</b> - breadth-first, same-origin, with page headers and de-duplication.
 *   <li><b>It stays inside its bounds</b> - depth, pages, {@code robots.txt}, origin after redirects, character
 *       cap, and concurrency. These are safety properties, not performance ones: the crawler makes requests to
 *       somebody else's server on the strength of a URL typed into a form, and each of these tests describes
 *       something a typo in that form must not be able to do.
 *   <li><b>It fails per page, not per crawl</b> - one bad page is recorded and skipped; only a crawl with nothing
 *       at all to show throws.
 * </ol>
 *
 * <p>No Docker required, unlike the repository tests.
 */
class WebCrawlerTest {

    private TestWebSite site;
    private final List<CrawlProgress> progress = new CopyOnWriteArrayList<>();
    private final CrawlProgressListener listener = progress::add;

    @BeforeEach
    void startSite() throws IOException {
        site = new TestWebSite();
        // Present and permissive by default, so each test only stubs the robots.txt it is actually about.
        site.text("/robots.txt", "User-agent: *\nDisallow:\n");
    }

    @AfterEach
    void stopSite() {
        site.close();
    }

    // ---------------------------------------------------------------------------------------------------------
    // It crawls
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void aDepthOfZeroFetchesOnlyTheStartingPage() {
        site.page("/", "Home", "/a", "/b");
        site.page("/a", "A");

        CrawlResult result = crawler().crawl(source("/", 0, 20), listener);

        assertThat(result.pageUrls()).containsExactly(site.url("/"));
        assertThat(result.pagesAttempted()).isEqualTo(1);
        assertThat(site.requestedPaths()).containsExactly("/robots.txt", "/");
    }

    @Test
    void followsLinksBreadthFirstUpToMaxDepth() {
        // Depth counts hops from the starting URL: 1 reaches the pages the start page links to, and no further.
        site.page("/", "Home", "/a", "/b");
        site.page("/a", "A", "/a-child");
        site.page("/b", "B");
        site.page("/a-child", "A child");

        CrawlResult result = crawler().crawl(source("/", 1, 20), listener);

        assertThat(result.pageUrls()).containsExactly(site.url("/"), site.url("/a"), site.url("/b"));
        assertThat(site.requestedPaths()).doesNotContain("/a-child");
    }

    @Test
    void aDeeperMaxDepthReachesFurther() {
        site.page("/", "Home", "/a");
        site.page("/a", "A", "/a-child");
        site.page("/a-child", "A child", "/a-grandchild");
        site.page("/a-grandchild", "Too far");

        CrawlResult result = crawler().crawl(source("/", 2, 20), listener);

        assertThat(result.pageUrls()).containsExactly(site.url("/"), site.url("/a"), site.url("/a-child"));
    }

    @Test
    void eachPageIsHeadedByItsOwnUrl() {
        // The header is what lets the model attribute a change to a page, and what makes a report's
        // evidenceSnippet traceable back to where it came from.
        site.page("/", "Home", "/pricing");
        site.html("/pricing", "<html><head><title>Pricing</title></head><body><p>Team $25</p></body></html>");

        CrawlResult result = crawler().crawl(source("/", 1, 20), listener);

        assertThat(result.content())
                .contains("--- PAGE: " + site.url("/") + " ---")
                .contains("--- PAGE: " + site.url("/pricing") + " ---")
                .contains("Team $25");
        // In crawl order, so the starting page - the one a human chose - comes first.
        assertThat(result.content().indexOf(site.url("/") + " ---"))
                .isLessThan(result.content().indexOf(site.url("/pricing")));
    }

    @Test
    void aPageLinkedFromEverywhereIsStillFetchedOnce() {
        site.page("/", "Home", "/a", "/b", "/shared");
        site.page("/a", "A", "/shared", "/");
        site.page("/b", "B", "/shared", "/a");
        site.page("/shared", "Shared", "/", "/a", "/b");

        CrawlResult result = crawler().crawl(source("/", 3, 20), listener);

        assertThat(result.pagesAttempted()).isEqualTo(4);
        assertThat(result.pageUrls()).hasSize(4).doesNotHaveDuplicates();
        assertThat(site.requestedPaths()).filteredOn("/shared"::equals).hasSize(1);
    }

    @Test
    void emitsOneProgressEventPerPageAttemptAsItHappens() {
        site.page("/", "Home", "/a", "/b");
        site.page("/a", "A");
        site.status("/b", 500);

        crawler().crawl(source("/", 1, 20), listener);

        assertThat(progress).hasSize(3);
        assertThat(progress).extracting(CrawlProgress::detail).containsExactly(
                "Crawling " + site.url("/") + " (page 1 of ~20)",
                "Crawling " + site.url("/a") + " (page 2 of ~20)",
                "Skipped " + site.url("/b") + " (page 3 of ~20): HTTP 500");
    }

    @Test
    void readsPlainTextSourcesTooSinceAChangelogIsOftenAMarkdownFile() {
        site.typed("/CHANGELOG.md", "text/markdown", "# 1.4.0\n\n- Raised the Team plan to $25\n");

        CrawlResult result = crawler().crawl(source("/CHANGELOG.md", 0, 20), listener);

        assertThat(result.content()).contains("Raised the Team plan to $25");
    }

    @Test
    void aResponseWithNoContentTypeIsGivenTheBenefitOfTheDoubt() {
        // Servers do this. Discarding the page would lose real content over a missing header.
        site.untyped("/", "<html><head><title>Untyped</title></head><body><p>Still a page</p></body></html>");

        assertThat(crawler().crawl(source("/", 0, 20), listener).content()).contains("Still a page");
    }

    // ---------------------------------------------------------------------------------------------------------
    // It stays inside its bounds
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void stopsAtMaxPagesEvenWithPlentyLeftToCrawl() {
        site.page("/", "Home", "/a", "/b", "/c", "/d", "/e");
        for (String path : List.of("/a", "/b", "/c", "/d", "/e")) {
            site.page(path, path);
        }

        CrawlResult result = crawler().crawl(source("/", 2, 3), listener);

        assertThat(result.pagesAttempted()).isEqualTo(3);
        // The budget is spent in discovery order, so what gets dropped is what is furthest from the start.
        assertThat(result.pageUrls()).containsExactly(site.url("/"), site.url("/a"), site.url("/b"));
    }

    @Test
    void failedPagesSpendThePageBudgetToo() {
        // Counted in requests made, not pages successfully read. A site answering half its URLs with 500s must
        // not thereby earn twice as many requests from us.
        site.page("/", "Home", "/broken-1", "/broken-2", "/fine");
        site.status("/broken-1", 500);
        site.status("/broken-2", 500);
        site.page("/fine", "Fine");

        CrawlResult result = crawler().crawl(source("/", 1, 3), listener);

        assertThat(result.pagesAttempted()).isEqualTo(3);
        assertThat(site.requestedPaths()).doesNotContain("/fine");
    }

    @Test
    void neverLeavesTheStartingOrigin() throws IOException {
        try (TestWebSite elsewhere = new TestWebSite()) {
            elsewhere.text("/robots.txt", "User-agent: *\nDisallow:\n");
            elsewhere.page("/", "Somebody else's site");
            site.page("/", "Home", elsewhere.url("/"), "/a");
            site.page("/a", "A");

            CrawlResult result = crawler().crawl(source("/", 2, 20), listener);

            assertThat(result.pageUrls()).containsExactly(site.url("/"), site.url("/a"));
            assertThat(elsewhere.requestedPaths()).isEmpty();
        }
    }

    @Test
    void refusesToFollowARedirectOffTheOrigin() throws IOException {
        // The check has to happen after the redirect, not only before the request. An open redirect on the target
        // site is otherwise an invitation to crawl somewhere nobody configured.
        try (TestWebSite elsewhere = new TestWebSite()) {
            elsewhere.page("/landing", "Off-origin");
            site.page("/", "Home", "/away");
            site.redirect("/away", elsewhere.url("/landing"));

            CrawlResult result = crawler().crawl(source("/", 1, 20), listener);

            assertThat(result.pageUrls()).containsExactly(site.url("/"));
            assertThat(result.failures()).singleElement()
                    .satisfies(failure -> assertThat(failure.reason()).contains("redirected off-origin"));
        }
    }

    @Test
    void followsRedirectsWithinTheOrigin() {
        site.page("/", "Home", "/old");
        site.redirect("/old", site.url("/new"));
        site.page("/new", "Moved here");

        CrawlResult result = crawler().crawl(source("/", 1, 20), listener);

        assertThat(result.content()).contains("Moved here");
        assertThat(result.failures()).isEmpty();
    }

    @Test
    void skipsPathsDisallowedByRobotsTxtAndSaysHowMany() {
        site.text("/robots.txt", "User-agent: *\nDisallow: /internal\n");
        site.page("/", "Home", "/public", "/internal", "/internal/notes");
        site.page("/public", "Public");
        site.page("/internal", "Internal");
        site.page("/internal/notes", "Internal notes");

        CrawlResult result = crawler().crawl(source("/", 1, 20), listener);

        assertThat(result.pageUrls()).containsExactly(site.url("/"), site.url("/public"));
        assertThat(result.skippedByRobots()).isEqualTo(2);
        assertThat(site.requestedPaths()).doesNotContain("/internal", "/internal/notes");
    }

    @Test
    void honoursAnAllowNestedInsideADisallow() {
        // The case that makes longest-match precedence worth implementing: without it, the one page this source
        // was configured for is the page that gets skipped.
        site.text("/robots.txt", "User-agent: *\nDisallow: /docs\nAllow: /docs/changelog\n");
        site.page("/", "Home", "/docs", "/docs/changelog");
        site.page("/docs", "Docs");
        site.page("/docs/changelog", "Changelog");

        CrawlResult result = crawler().crawl(source("/", 1, 20), listener);

        assertThat(result.pageUrls()).containsExactly(site.url("/"), site.url("/docs/changelog"));
    }

    @Test
    void refusesTheCrawlOutrightWhenTheStartingPathIsDisallowed() {
        site.text("/robots.txt", "User-agent: *\nDisallow: /internal\n");
        site.page("/internal/changelog", "Changelog");

        assertThatThrownBy(() -> crawler().crawl(source("/internal/changelog", 1, 20), listener))
                .isInstanceOf(CrawlFailedException.class)
                .hasMessageContaining("robots.txt disallows this path")
                .hasMessageContaining("SaaSProductInvestigator");
        assertThat(site.requestedPaths()).containsExactly("/robots.txt");
    }

    @Test
    void aMissingRobotsTxtMeansNoRestrictions() {
        // RFC 9309: a 404 is a site saying nothing, which is not the same as a site saying no.
        site.status("/robots.txt", 404);
        site.page("/", "Home", "/a");
        site.page("/a", "A");

        assertThat(crawler().crawl(source("/", 1, 20), listener).pageUrls()).hasSize(2);
    }

    @Test
    void anUnreadableRobotsTxtIsTreatedAsARefusalRatherThanAsPermission() {
        // The asymmetry with the 404 case above is deliberate and is RFC 9309's: a missing file is a site saying
        // nothing, a broken file is a site we cannot hear. Guessing "allowed" in the second case means crawling a
        // site that may have spent effort telling us not to.
        site.status("/robots.txt", 503);
        site.page("/", "Home");

        assertThatThrownBy(() -> crawler().crawl(source("/", 1, 20), listener))
                .isInstanceOf(CrawlFailedException.class)
                .hasMessageContaining("robots.txt returned HTTP 503")
                .hasMessageContaining("RFC 9309");
        assertThat(site.requestedPaths()).containsExactly("/robots.txt");
    }

    @Test
    void serialisesFetchesWhenRobotsTxtAsksForACrawlDelay() {
        site.text("/robots.txt", "User-agent: *\nCrawl-delay: 0.1\n");
        site.page("/", "Home", "/a", "/b", "/c");
        for (String path : List.of("/a", "/b", "/c")) {
            site.slowPage(path, 50);
        }

        CrawlResult result = crawler().crawl(source("/", 1, 20), listener);

        assertThat(result.crawlDelayApplied()).isTrue();
        // Four parallel request streams each pausing 100ms is not what a 100ms delay asked for.
        assertThat(site.peakConcurrency()).isEqualTo(1);
        assertThat(result.pageUrls()).hasSize(4);
    }

    @Test
    void fetchesConcurrentlyButNeverMoreThanTheConfiguredLimit() {
        // Both halves matter. Unbounded sequential fetching is merely slow; unbounded parallel fetching is a
        // denial-of-service tool pointed at whoever's URL was typed into the form.
        site.page("/", "Home", "/p1", "/p2", "/p3", "/p4", "/p5", "/p6", "/p7", "/p8");
        for (int i = 1; i <= 8; i++) {
            site.slowPage("/p" + i, 120);
        }

        CrawlResult result = crawler().crawl(source("/", 1, 20), listener);

        assertThat(result.pageUrls()).hasSize(9);
        assertThat(site.peakConcurrency())
                .as("must actually fetch in parallel")
                .isGreaterThan(1)
                .as("must never exceed app.crawler.concurrency")
                .isLessThanOrEqualTo(CrawlerProperties.defaults().concurrency());
    }

    @Test
    void truncatesAtTheCharacterCapKeepingTheEarliestPages() {
        // "Earliest-crawled" and "most important" coincide here for a reason worth stating: the starting URL is
        // the one a human chose, and breadth-first distance from it is the best proxy for importance available
        // without inspecting content - which would be a judgement this layer does not get to make.
        String filler = "word ".repeat(1_400); // ~7k chars per page
        site.page("/", "Home", "/a", "/b", "/c");
        for (String path : List.of("/a", "/b", "/c")) {
            site.html(path, "<html><head><title>" + path + "</title></head><body><p>"
                    + filler + "</p></body></html>");
        }
        CrawlerProperties tinyCap = withMaxChars(10_000);

        CrawlResult result = crawler(tinyCap).crawl(source("/", 1, 20), listener);

        assertThat(result.truncated()).isTrue();
        assertThat(result.content().length()).isLessThanOrEqualTo(10_000);
        // All four pages were fetched; only the ones that fit are claimed as covered by this snapshot.
        assertThat(result.pagesAttempted()).isEqualTo(4);
        assertThat(result.pageUrls()).hasSizeLessThan(4).startsWith(site.url("/"));
        assertThat(result.summary()).contains("truncated");
    }

    @Test
    void includesPartOfAPageTooLargeToFitRatherThanNothingAtAll() {
        // A single page longer than the whole cap would otherwise produce an empty snapshot, which the next run
        // would compare against and report as the entire site having been removed.
        site.html("/", "<html><head><title>Huge</title></head><body><p>" + "word ".repeat(10_000)
                + "</p></body></html>");

        CrawlResult result = crawler(withMaxChars(5_000)).crawl(source("/", 0, 20), listener);

        assertThat(result.truncated()).isTrue();
        assertThat(result.pageUrls()).containsExactly(site.url("/"));
        assertThat(result.content()).contains("--- PAGE:").contains("truncated at the per-source character cap");
        assertThat(result.content().length()).isLessThanOrEqualTo(5_000);
    }

    // ---------------------------------------------------------------------------------------------------------
    // It fails per page, not per crawl
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void oneFailedPageDoesNotFailTheCrawl() {
        site.page("/", "Home", "/gone", "/forbidden", "/fine");
        site.status("/gone", 404);
        site.status("/forbidden", 403);
        site.page("/fine", "Fine");

        CrawlResult result = crawler().crawl(source("/", 1, 20), listener);

        assertThat(result.pageUrls()).containsExactly(site.url("/"), site.url("/fine"));
        assertThat(result.failures()).extracting(CrawlResult.PageFailure::reason)
                .containsExactly("HTTP 404", "HTTP 403");
        assertThat(result.hasFailures()).isTrue();
        assertThat(result.summary()).contains("2 failed");
    }

    @Test
    void aPageThatTimesOutIsSkippedAndTheRestOfTheCrawlContinues() {
        // 1000ms rather than something faster because CrawlerProperties floors the per-page timeout there: a
        // sub-second timeout would abandon pages that were merely slow, which is a worse failure than waiting.
        site.page("/", "Home", "/slow", "/fast");
        site.slowPage("/slow", 5_000);
        site.page("/fast", "Fast");

        CrawlResult result = crawler(withPerPageTimeout(1_000)).crawl(source("/", 1, 20), listener);

        assertThat(result.pageUrls()).containsExactly(site.url("/"), site.url("/fast"));
        assertThat(result.failures()).singleElement()
                .satisfies(failure -> assertThat(failure.reason()).contains("timed out after 1000ms"));
    }

    @Test
    void discardsBodiesThatAreNotWebPages() {
        site.page("/", "Home", "/report.txt", "/data");
        site.typed("/report.txt", "text/plain", "Plain text is fine");
        site.typed("/data", "application/octet-stream", "\u0000\u0001binary");

        CrawlResult result = crawler().crawl(source("/", 1, 20), listener);

        assertThat(result.content()).contains("Plain text is fine").doesNotContain("binary");
        assertThat(result.failures()).singleElement()
                .satisfies(failure -> assertThat(failure.reason()).contains("is not a web page"));
    }

    @Test
    void throwsWhenTheStartingPageItselfCannotBeFetched() {
        // The one case that fails the whole crawl. Returning an empty result would store an empty snapshot,
        // which the next run would faithfully report as the entire site having been deleted.
        site.status("/missing", 404);

        assertThatThrownBy(() -> crawler().crawl(source("/missing", 1, 20), listener))
                .isInstanceOf(CrawlFailedException.class)
                .hasMessageContaining("HTTP 404")
                .extracting("url").isEqualTo(site.url("/missing"));
    }

    @Test
    void throwsWhenThePageHasNoReadableTextAtAll() {
        site.html("/", "<html><head></head><body><script>var x = 1;</script></body></html>");

        assertThatThrownBy(() -> crawler().crawl(source("/", 0, 20), listener))
                .isInstanceOf(CrawlFailedException.class)
                .hasMessageContaining("no readable text");
    }

    @Test
    void throwsWhenTheHostDoesNotExist() {
        SourceConfig source = new SourceConfig(SourceType.WEBSITE, "Broken",
                "https://no-such-host.invalid/changelog", null);

        assertThatThrownBy(() -> crawler().crawl(source, listener))
                .isInstanceOf(CrawlFailedException.class)
                // The message describes what the target did, never our exception hierarchy.
                .hasMessageContaining("host could not be resolved")
                .hasMessageNotContaining("Exception");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "not a url at all", "ftp://example.com/files", "mailto:a@b.com",
            "file:///etc/passwd", "https://", "/relative/only"})
    void rejectsAStartingUrlThatIsNotSomethingCrawlable(String endpointUrl) {
        SourceConfig source = new SourceConfig(SourceType.WEBSITE, "Misconfigured", endpointUrl, null);

        assertThatThrownBy(() -> crawler().crawl(source, listener))
                .isInstanceOf(CrawlFailedException.class);
    }

    @ParameterizedTest
    @EnumSource(value = SourceType.class, names = {"DOCS_MCP", "ATLASSIAN_MCP", "GENERIC_MCP"})
    void refusesToCrawlAnMcpSource(SourceType type) {
        // MCP sources are declared to the provider as remote tools and fetched by the model, never by us. Asking
        // the crawler for one is a programming error, not a configuration one, so it is not a CrawlFailedException
        // that the orchestrator would quietly absorb as "one source unavailable".
        SourceConfig source = new SourceConfig(type, "Docs", site.url("/"), null);

        assertThatThrownBy(() -> crawler().crawl(source, listener))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(type.name());
    }

    @Test
    void aListenerThatThrowsCannotAbortTheRunItWasWatching() {
        // A client disconnecting from the SSE stream mid-run is entirely normal. It must not be able to end the
        // run it was observing.
        site.page("/", "Home", "/a");
        site.page("/a", "A");

        CrawlResult result = crawler().crawl(source("/", 1, 20), p -> {
            throw new IllegalStateException("client went away");
        });

        assertThat(result.pageUrls()).hasSize(2);
    }

    @Test
    void anMcpSourcesAuthTokenIsNeverTouchedByTheCrawler() {
        // Nothing in the crawl package decrypts anything. Authenticated crawling would be a deliberate addition,
        // and this asserts that a token sitting on a crawled source is inert rather than quietly sent.
        SourceConfig source = source("/", 0, 20);
        source.setAuthTokenEncrypted("ciphertext-that-must-never-be-sent");
        site.page("/", "Home");

        crawler().crawl(source, listener);

        assertThat(source.getAuthTokenEncrypted()).isEqualTo("ciphertext-that-must-never-be-sent");
    }

    // ---------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------

    private WebCrawler crawler() {
        return crawler(CrawlerProperties.defaults());
    }

    private WebCrawler crawler(CrawlerProperties properties) {
        SystemConfigService systemConfig = mock(SystemConfigService.class);
        // No Admin Console override stored, so the shipped defaults apply. CrawlSettingsTest covers the other
        // layers of that resolution; here it would only obscure what is being tested.
        when(systemConfig.getValue(any())).thenReturn(Optional.empty());
        return new WebCrawler(new CrawlSettings(systemConfig, properties), properties);
    }

    private SourceConfig source(String path, int maxDepth, int maxPages) {
        SourceConfig source = new SourceConfig(SourceType.WEBSITE, "Test site", site.url(path), null);
        source.setMaxDepth(maxDepth);
        source.setMaxPages(maxPages);
        return source;
    }

    private static CrawlerProperties withMaxChars(int maxChars) {
        CrawlerProperties base = CrawlerProperties.defaults();
        return new CrawlerProperties(base.defaultMaxDepth(), base.defaultMaxPages(), base.maxAllowedDepth(),
                base.maxAllowedPages(), base.perPageTimeoutMs(), base.connectTimeoutMs(), base.maxBytesPerPage(),
                maxChars, base.concurrency(), base.maxCrawlDelayMs(), base.userAgent());
    }

    private static CrawlerProperties withPerPageTimeout(long timeoutMs) {
        CrawlerProperties base = CrawlerProperties.defaults();
        return new CrawlerProperties(base.defaultMaxDepth(), base.defaultMaxPages(), base.maxAllowedDepth(),
                base.maxAllowedPages(), timeoutMs, base.connectTimeoutMs(), base.maxBytesPerPage(),
                base.maxCharsPerSource(), base.concurrency(), base.maxCrawlDelayMs(), base.userAgent());
    }
}
