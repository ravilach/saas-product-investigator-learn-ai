package com.saasinvestigator.crawl;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests {@link PageTextExtractor}: what text comes out of a page, and which links are worth following.
 *
 * <p>Two properties are being pinned down, and they pull in opposite directions.
 *
 * <p>The first is that <b>nothing editorial is removed.</b> It is tempting to strip navigation, footers, and
 * cookie banners - they are the noisiest part of any page, and a reordered nav menu is exactly the false
 * positive a change report should not contain. The tests below assert they survive anyway, because "this part of
 * the page doesn't matter" is a judgement about what counts as a change, and in this codebase that judgement
 * belongs to the LLM. A heuristic that drops a {@code <footer>} is a heuristic that drops a pricing footnote.
 *
 * <p>The second is that <b>link filtering is strict.</b> Same-origin means scheme, host, and port - the crawler
 * is a program that makes requests to somebody else's server, and every loosening of this check widens what a
 * typo in a URL field can reach.
 */
class PageTextExtractorTest {

    private static final URI ORIGIN = URI.create("https://example.com/");

    private static Document parse(String html) {
        return Jsoup.parse(html, "https://example.com/docs/index.html");
    }

    @Test
    void extractsVisibleTextLedByTheTitle() {
        // The title is included because it is often where a change announces itself, and because it labels the
        // block of text that follows it in the assembled prompt.
        Document document = parse("""
                <html><head><title>Pricing</title></head>
                <body><h1>Plans</h1><p>Team is $25 per user.</p></body></html>
                """);

        assertThat(PageTextExtractor.extractText(document))
                .isEqualTo("Pricing\n\nPlans Team is $25 per user.");
    }

    @Test
    void stripsOnlyElementsTheBrowserNeverRenders() {
        Document document = parse("""
                <html><head><title>T</title>
                <style>body { color: red }</style>
                <script>var buildHash = "a91f3c";</script>
                </head>
                <body>
                  <noscript>Enable JavaScript</noscript>
                  <p>Real content.</p>
                  <template><p>Not rendered</p></template>
                </body></html>
                """);

        String text = PageTextExtractor.extractText(document);

        assertThat(text).contains("Real content.");
        // A minified bundle's hash changing every deploy would otherwise read as a change to the page.
        assertThat(text).doesNotContain("buildHash", "a91f3c", "color: red", "Enable JavaScript", "Not rendered");
    }

    @Test
    void keepsNavigationAndFooterContentOnPurpose() {
        Document document = parse("""
                <html><head><title>Docs</title></head><body>
                  <nav><a href="/pricing">Pricing</a><a href="/docs">Docs</a></nav>
                  <main><p>Body text.</p></main>
                  <footer>Prices exclude VAT. Effective 1 Jan 2026.</footer>
                </body></html>
                """);

        String text = PageTextExtractor.extractText(document);

        assertThat(text).contains("Body text.");
        // The footnote in that footer is a pricing change waiting to happen.
        assertThat(text).contains("Prices exclude VAT. Effective 1 Jan 2026.");
        assertThat(text).contains("Pricing", "Docs");
    }

    @Test
    void aPageWithNoTitleIsJustItsText() {
        assertThat(PageTextExtractor.extractText(parse("<html><body><p>Only text.</p></body></html>")))
                .isEqualTo("Only text.");
    }

    @Test
    void aPageWithNoTextIsJustItsTitle() {
        assertThat(PageTextExtractor.extractText(parse("<html><head><title>Empty</title></head><body></body>")))
                .isEqualTo("Empty");
    }

    @Test
    void extractionDoesNotMutateTheDocumentItWasGiven() {
        // Links are read from the same Document after text extraction, so removing script tags in place would
        // work fine and then break the day somebody reorders those two calls.
        Document document = parse("""
                <html><head><title>T</title><script>x</script></head>
                <body><a href="/a">A</a></body></html>
                """);

        PageTextExtractor.extractText(document);

        assertThat(document.select("script")).hasSize(1);
        assertThat(PageTextExtractor.sameOriginLinks(document, ORIGIN)).containsExactly("https://example.com/a");
    }

    @Test
    void resolvesRelativeLinksAgainstThePagesOwnUrl() {
        Document document = parse("""
                <html><body>
                  <a href="install.html">Sibling</a>
                  <a href="../guide">Parent</a>
                  <a href="/top">Root-relative</a>
                  <a href="https://example.com/absolute">Absolute</a>
                </body></html>
                """);

        assertThat(PageTextExtractor.sameOriginLinks(document, ORIGIN)).containsExactly(
                "https://example.com/docs/install.html",
                "https://example.com/guide",
                "https://example.com/top",
                "https://example.com/absolute");
    }

    @Test
    void returnsLinksInDocumentOrderWhichIsTheOrderToVisitThemIn() {
        Document document = parse("""
                <html><body><a href="/c">c</a><a href="/a">a</a><a href="/b">b</a></body></html>
                """);

        // Not sorted: breadth-first order should follow the page's own structure, so the pages a human would
        // reach first are the pages the page budget is spent on first.
        assertThat(PageTextExtractor.sameOriginLinks(document, ORIGIN))
                .containsExactly("https://example.com/c", "https://example.com/a", "https://example.com/b");
    }

    @Test
    void collapsesFragmentsOntoOnePageButKeepsQueryStrings() {
        Document document = parse("""
                <html><body>
                  <a href="/docs">Docs</a>
                  <a href="/docs#install">Install section</a>
                  <a href="/docs#config">Config section</a>
                  <a href="/docs?page=2">Page two</a>
                </body></html>
                """);

        // Three links to one page would otherwise spend three pages of the budget fetching it. ?page=2, on the
        // other hand, usually is a different page.
        assertThat(PageTextExtractor.sameOriginLinks(document, ORIGIN))
                .containsExactly("https://example.com/docs", "https://example.com/docs?page=2");
    }

    @Test
    void dropsLinksToOtherOrigins() {
        Document document = parse("""
                <html><body>
                  <a href="https://example.com/keep">Same origin</a>
                  <a href="https://other.example.com/nope">Other host</a>
                  <a href="http://example.com/nope">Scheme downgrade</a>
                  <a href="https://example.com:8443/nope">Other port</a>
                  <a href="https://sub.example.com/nope">Subdomain</a>
                </body></html>
                """);

        assertThat(PageTextExtractor.sameOriginLinks(document, ORIGIN))
                .containsExactly("https://example.com/keep");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "mailto:hello@example.com",
            "javascript:void(0)",
            "tel:+15551234",
            "ftp://example.com/file",
    })
    void dropsLinksThatAreNotHttpRequests(String href) {
        Document document = parse("<html><body><a href=\"" + href + "\">x</a></body></html>");

        assertThat(PageTextExtractor.sameOriginLinks(document, ORIGIN)).isEmpty();
    }

    @Test
    void dropsLinksToThingsThatAreObviouslyNotPages() {
        Document document = parse("""
                <html><body>
                  <a href="/whitepaper.pdf">PDF</a>
                  <a href="/logo.png">Image</a>
                  <a href="/bundle.js">Script</a>
                  <a href="/export.csv">Data</a>
                  <a href="/release-notes.html">Page</a>
                  <a href="/changelog">Extensionless page</a>
                  <a href="/v1.2/docs">Version in a path segment</a>
                </body></html>
                """);

        // The content-type check at fetch time is the real filter; this just avoids spending pages of the budget
        // discovering that a PDF is a PDF. Note the last two: a path with no extension, and a dot in a directory
        // name rather than a filename, are both pages.
        assertThat(PageTextExtractor.sameOriginLinks(document, ORIGIN)).containsExactly(
                "https://example.com/release-notes.html",
                "https://example.com/changelog",
                "https://example.com/v1.2/docs");
    }

    @Test
    void treatsAnImpliedRootPathAsExplicit() {
        // "https://example.com" and "https://example.com/" are one page; normalising them apart would fetch it
        // twice and store two snapshots of it.
        assertThat(PageTextExtractor.normalise("https://example.com", ORIGIN)).isEqualTo("https://example.com/");
    }

    @Test
    void sameOriginComparesPortsAfterDefaultingThem() {
        // URI.getPort() returns -1 for an implied port, so a literal comparison calls these two different
        // origins and the crawler stops at the first page.
        assertThat(PageTextExtractor.isSameOrigin(
                URI.create("https://example.com:443/docs"), URI.create("https://example.com/"))).isTrue();
        assertThat(PageTextExtractor.isSameOrigin(
                URI.create("http://example.com:80/docs"), URI.create("http://example.com/"))).isTrue();
        assertThat(PageTextExtractor.isSameOrigin(
                URI.create("https://example.com:8443/docs"), URI.create("https://example.com/"))).isFalse();
    }

    @Test
    void aMalformedHrefIsSkippedRatherThanThrowing() {
        // One unparseable link on a page must not cost the whole page.
        Document document = parse("""
                <html><body>
                  <a href="http://exa mple.com/broken">Broken</a>
                  <a href="/fine">Fine</a>
                </body></html>
                """);

        assertThat(PageTextExtractor.sameOriginLinks(document, ORIGIN))
                .containsExactly("https://example.com/fine");
    }
}
