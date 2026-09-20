package com.saasinvestigator.crawl;

import com.saasinvestigator.product.SourceConfig;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import javax.net.ssl.SSLException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Crawls one Website or SaaS App URL source into the text block that goes to the LLM.
 *
 * <p>Breadth-first from {@code endpointUrl}, same-origin only, honouring {@code robots.txt}, bounded by
 * {@code maxDepth} and {@code maxPages}, with a fixed number of fetches in flight at a time. The output is a
 * single string of {@code --- PAGE: <url> ---} blocks in crawl order, capped so that one enthusiastic source
 * cannot consume a whole context window.
 *
 * <h2>Why every one of those bounds is there</h2>
 *
 * <p>They are not performance tuning. A crawler is a program that makes requests to a computer somebody else
 * pays for, on the strength of a URL somebody typed into a form. The bounds are what keeps a typo from becoming
 * a traffic incident:
 *
 * <ul>
 *   <li><b>Same-origin</b> - the check is repeated after redirects, not only before the request. An open
 *       redirect on the target site is otherwise an invitation to crawl somewhere nobody configured.
 *   <li><b>{@code robots.txt}</b> - fetched once per crawl (a same-origin crawl has exactly one), and a
 *       {@code 5xx} or an unreachable {@code robots.txt} is treated as a refusal rather than as permission. See
 *       {@link #fetchRobots}.
 *   <li><b>{@code maxDepth}/{@code maxPages}</b> - resolved through {@link CrawlSettings}, which clamps them to
 *       ceilings no source or admin setting can exceed.
 *   <li><b>Bounded concurrency</b> - a fixed pool of {@code app.crawler.concurrency} threads, which is the whole
 *       mechanism. Unbounded sequential fetching is slow; unbounded parallel fetching is rude. A published
 *       {@code Crawl-delay} drops the effective concurrency to one, because four parallel request streams each
 *       pausing a second between requests is not what a one-second delay asked for.
 *   <li><b>Byte and character caps</b> - a bounded read per response, and a bounded total per source.
 * </ul>
 *
 * <h2>Failure is per page, not per crawl</h2>
 *
 * <p>Individual pages that time out, return an error, or turn out to be PDFs are recorded in
 * {@link CrawlResult#failures()} and the crawl continues - the house rule from the build prompt's error handling
 * section. The one case that throws is a crawl with nothing at all to show: see {@link CrawlFailedException} for
 * why an empty result would be actively worse than an exception.
 *
 * <p>This service is stateless and thread-safe. All per-crawl state lives in local variables of
 * {@link #crawl}, which is what makes concurrent runs of different products independent.
 */
@Service
public class WebCrawler {

    private static final Logger log = LoggerFactory.getLogger(WebCrawler.class);

    /** The block header the build prompt specifies, so the model can attribute a change to a page. */
    private static final String PAGE_HEADER_PREFIX = "--- PAGE: ";
    private static final String PAGE_HEADER_SUFFIX = " ---";
    private static final String BLOCK_SEPARATOR = "\n\n";
    private static final String TRUNCATION_MARKER = "\n\n[truncated at the per-source character cap]";

    /**
     * How much room a page needs before it is worth including a partial copy of it. Below this, the fragment
     * would be a sentence and a half - enough to look like content and not enough to mean anything.
     */
    private static final int MIN_PARTIAL_PAGE_CHARS = 2_000;

    /** {@code robots.txt} is a text file of rules. Anything past this is not one, and is not read. */
    private static final int ROBOTS_MAX_BYTES = 512 * 1024;

    private final CrawlSettings settings;
    private final CrawlerProperties properties;
    private final HttpClient http;

    /**
     * @param settings resolves the per-source crawl limits
     * @param properties timeouts, caps, concurrency, and the user agent
     */
    public WebCrawler(CrawlSettings settings, CrawlerProperties properties) {
        this.settings = settings;
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                // NORMAL rather than ALWAYS: it declines to follow an HTTPS page to an HTTP one, which is a
                // downgrade we have no reason to accept. Off-origin redirects are rejected after the fact in
                // fetch(), because the client cannot express "same origin only" itself.
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(properties.connectTimeout())
                .build();
    }

    /**
     * Crawls a source with no progress reporting, for callers with nobody watching.
     *
     * @param source a {@code WEBSITE} or {@code SAAS_URL} source
     * @return the crawl result
     * @throws CrawlFailedException if the crawl produced nothing usable
     */
    public CrawlResult crawl(SourceConfig source) {
        return crawl(source, CrawlProgressListener.logging());
    }

    /**
     * Crawls a source, reporting each page attempt as it happens.
     *
     * @param source a {@code WEBSITE} or {@code SAAS_URL} source. MCP-type sources are not crawled at all - they
     *     are declared to the LLM as remote tools - so passing one is a programming error rather than a
     *     configuration one.
     * @param listener called once per page attempt; exceptions it throws are logged and swallowed
     * @return the assembled text, the pages it covers, and what went wrong along the way
     * @throws CrawlFailedException if the starting URL is unusable or no page yielded any text
     * @throws IllegalArgumentException if the source is an MCP type
     */
    public CrawlResult crawl(SourceConfig source, CrawlProgressListener listener) {
        if (!source.isCrawled()) {
            throw new IllegalArgumentException("Source '" + source.getName() + "' is of type " + source.getType()
                    + ", which is declared to the LLM as an MCP tool rather than crawled");
        }

        URI start = parseStart(source.getEndpointUrl());
        int maxDepth = settings.resolveMaxDepth(source.getMaxDepth());
        int maxPages = settings.resolveMaxPages(source.getMaxPages());

        RobotsTxt robots = fetchRobots(start);
        if (!robots.isAllowed(pathAndQuery(start))) {
            throw new CrawlFailedException(start.toString(), "robots.txt disallows this path for "
                    + properties.userAgentToken());
        }
        Duration delay = robots.crawlDelay();
        int concurrency = delay.isZero() ? properties.concurrency() : 1;

        log.info("Crawling source '{}' from {} (maxDepth={}, maxPages={}, concurrency={}, crawlDelay={}ms)",
                source.getName(), start, maxDepth, maxPages, concurrency, delay.toMillis());

        List<CrawledPage> pages = new ArrayList<>();
        List<CrawlResult.PageFailure> failures = new ArrayList<>();
        // Every URL ever queued, so a page linked from six other pages is still fetched once. Insertion-ordered
        // so that logging it reads as the crawl order rather than as a hash dump.
        Set<String> queued = new LinkedHashSet<>();
        List<String> frontier = new ArrayList<>();
        frontier.add(start.toString());
        queued.add(start.toString());
        int attempted = 0;
        int skippedByRobots = 0;

        try (ExecutorService pool = Executors.newFixedThreadPool(concurrency, threadFactory(source.getName()))) {
            for (int depth = 0; depth <= maxDepth && !frontier.isEmpty() && attempted < maxPages; depth++) {
                // The page budget is spent in the order pages were discovered, so if it runs out mid-level the
                // pages that are dropped are the ones furthest from the starting URL.
                int budget = maxPages - attempted;
                List<String> batch = frontier.size() <= budget ? frontier : frontier.subList(0, budget);

                List<Future<FetchOutcome>> inFlight = new ArrayList<>(batch.size());
                for (String url : batch) {
                    inFlight.add(pool.submit(() -> fetch(url, start, delay)));
                }

                List<String> nextFrontier = new ArrayList<>();
                for (Future<FetchOutcome> future : inFlight) {
                    FetchOutcome outcome = await(future, start);
                    attempted++;
                    if (outcome.succeeded()) {
                        pages.add(new CrawledPage(outcome.url(), outcome.text()));
                        report(listener, CrawlProgress.fetched(outcome.url(), attempted, maxPages));
                        if (depth < maxDepth) {
                            for (String link : outcome.links()) {
                                if (!queued.add(link)) {
                                    continue;
                                }
                                if (!robots.isAllowed(pathAndQuery(link))) {
                                    skippedByRobots++;
                                    continue;
                                }
                                nextFrontier.add(link);
                            }
                        }
                    } else {
                        failures.add(new CrawlResult.PageFailure(outcome.url(), outcome.failureReason()));
                        report(listener, CrawlProgress.failed(
                                outcome.url(), attempted, maxPages, outcome.failureReason()));
                    }
                }
                frontier = nextFrontier;
            }
        }

        if (pages.isEmpty()) {
            // Nothing to snapshot. Returning an empty result here would store an empty snapshot, which the next
            // run would compare against and report as the entire site having been deleted.
            String reason = failures.isEmpty()
                    ? "no page yielded any readable text"
                    : failures.getFirst().reason();
            throw new CrawlFailedException(start.toString(), reason);
        }

        Assembled assembled = assemble(pages);
        CrawlResult result = new CrawlResult(assembled.content(), assembled.pageUrls(), attempted,
                assembled.pageUrls().size(), assembled.truncated(), failures, skippedByRobots,
                !delay.isZero());
        log.info("Crawled source '{}': {}", source.getName(), result.summary());
        return result;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Fetching
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Fetches and extracts one page. Never throws: every failure becomes a {@link FetchOutcome} carrying a short
     * reason, because a page is allowed to fail without taking the crawl with it.
     *
     * @param url the page to fetch
     * @param origin the crawl's starting URI, for the same-origin re-check after redirects
     * @param delay a {@code Crawl-delay} to observe before requesting, or {@link Duration#ZERO}
     * @return the page's text and links, or the reason it could not be used
     */
    private FetchOutcome fetch(String url, URI origin, Duration delay) {
        if (!delay.isZero()) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return FetchOutcome.failed(url, "crawl was interrupted");
            }
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", properties.userAgent())
                    .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.1")
                    .header("Accept-Language", "en")
                    .timeout(properties.perPageTimeout())
                    .GET()
                    .build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() / 100 != 2) {
                    return FetchOutcome.failed(url, "HTTP " + response.statusCode());
                }
                URI finalUri = response.uri();
                if (!PageTextExtractor.isSameOrigin(finalUri, origin)) {
                    // Following this would mean crawling a host nobody configured, on the say-so of the host
                    // that was configured.
                    return FetchOutcome.failed(url, "redirected off-origin to " + finalUri.getHost());
                }
                String contentType = response.headers().firstValue("content-type").orElse("").toLowerCase(
                        Locale.ROOT);
                boolean html = contentType.startsWith("text/html")
                        || contentType.startsWith("application/xhtml+xml")
                        // A server that sends no Content-Type at all is given the benefit of the doubt; jsoup
                        // handles whatever comes back, and the alternative is discarding a real page.
                        || contentType.isBlank();
                boolean plainText = contentType.startsWith("text/") || contentType.contains("json")
                        || contentType.contains("xml");
                if (!html && !plainText) {
                    return FetchOutcome.failed(url, "content type " + contentType + " is not a web page");
                }

                // Bounded read: a response with no Content-Length cannot be pre-checked for size, so the cap has
                // to be applied while reading rather than before.
                byte[] bytes = body.readNBytes(properties.maxBytesPerPage());
                String raw = new String(bytes, charsetOf(contentType));
                if (html) {
                    Document document = Jsoup.parse(raw, finalUri.toString());
                    String text = PageTextExtractor.extractText(document);
                    if (text.isBlank()) {
                        return FetchOutcome.failed(url, "page contains no readable text");
                    }
                    return FetchOutcome.page(url, text, PageTextExtractor.sameOriginLinks(document, origin));
                }
                String text = raw.strip();
                if (text.isEmpty()) {
                    return FetchOutcome.failed(url, "page is empty");
                }
                return FetchOutcome.page(url, text, List.of());
            }
        } catch (HttpTimeoutException e) {
            return FetchOutcome.failed(url, "timed out after " + properties.perPageTimeoutMs() + "ms");
        } catch (IOException e) {
            // The cause is logged in full and reported in summary: the client is told what the target site did,
            // never what our exception hierarchy looks like.
            log.debug("Fetch of {} failed", url, e);
            return FetchOutcome.failed(url, describe(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FetchOutcome.failed(url, "crawl was interrupted");
        } catch (RuntimeException e) {
            log.warn("Unexpected error fetching {}", url, e);
            return FetchOutcome.failed(url, "could not be read");
        }
    }

    /**
     * Fetches and parses the origin's {@code robots.txt}.
     *
     * <p>Once per crawl, because a same-origin crawl has exactly one origin and therefore exactly one
     * {@code robots.txt} - no cache, no invalidation, nothing to get stale.
     *
     * <p>The status handling follows RFC 9309, including the part that surprises people: a {@code 4xx} means
     * "no rules, crawl freely", but a {@code 5xx} or an unreachable file means <b>treat the whole site as
     * disallowed</b>. That asymmetry is right even though it is inconvenient. A missing file is a site saying
     * nothing; a broken file is a site we cannot hear. Guessing "allowed" in the second case means crawling a
     * site that may have spent effort telling us not to, and the only evidence either way was the request that
     * just failed.
     *
     * @param start the crawl's starting URI, supplying the origin
     * @return the parsed rules, or {@link RobotsTxt#allowAll()} if the site publishes none
     * @throws CrawlFailedException if {@code robots.txt} could not be fetched, which makes the source
     *     unavailable for this run with an explicit reason rather than crawled on an assumption
     */
    private RobotsTxt fetchRobots(URI start) {
        URI robotsUri = start.resolve("/robots.txt");
        try {
            HttpRequest request = HttpRequest.newBuilder(robotsUri)
                    .header("User-Agent", properties.userAgent())
                    .header("Accept", "text/plain,*/*;q=0.1")
                    .timeout(properties.perPageTimeout())
                    .GET()
                    .build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            try (InputStream body = response.body()) {
                if (status / 100 == 2) {
                    String content = new String(body.readNBytes(ROBOTS_MAX_BYTES), StandardCharsets.UTF_8);
                    RobotsTxt robots = RobotsTxt.parse(content, properties.userAgentToken(),
                            Duration.ofMillis(properties.maxCrawlDelayMs()));
                    log.debug("{} parsed: {} rule(s) apply to {}, crawl-delay {}ms", robotsUri,
                            robots.ruleCount(), properties.userAgentToken(), robots.crawlDelay().toMillis());
                    return robots;
                }
            }
            if (status / 100 == 4) {
                log.debug("{} returned HTTP {}; no crawl restrictions published", robotsUri, status);
                return RobotsTxt.allowAll();
            }
            throw new CrawlFailedException(start.toString(), "robots.txt returned HTTP " + status
                    + ", which must be treated as a refusal to be crawled (RFC 9309)");
        } catch (HttpTimeoutException e) {
            throw new CrawlFailedException(start.toString(),
                    "robots.txt could not be fetched (timed out), so the site's crawl rules are unknown", e);
        } catch (IOException e) {
            throw new CrawlFailedException(start.toString(),
                    "robots.txt could not be fetched (" + describe(e)
                            + "), so the site's crawl rules are unknown", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CrawlFailedException(start.toString(), "crawl was interrupted", e);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Assembly
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Concatenates the crawled pages into one capped block of text.
     *
     * <p>Earliest-crawled first, which given breadth-first traversal means the starting URL and then the pages
     * nearest to it - the build prompt's "keeping the earliest-crawled/most important pages first". Those two
     * descriptions coincide here for a reason worth stating: the starting URL is the one a human chose, and
     * distance from it is the best available proxy for importance without inspecting content, which would be a
     * judgement this layer does not get to make.
     *
     * <p>A page that straddles the cap is included in part rather than dropped, with a marker. Dropping it
     * whole would mean a single page longer than the cap produces an empty snapshot.
     *
     * @param pages the crawled pages in crawl order
     * @return the assembled content, the URLs it actually covers, and whether anything was cut
     */
    private Assembled assemble(List<CrawledPage> pages) {
        int cap = properties.maxCharsPerSource();
        StringBuilder content = new StringBuilder();
        List<String> included = new ArrayList<>();
        boolean truncated = false;

        for (CrawledPage page : pages) {
            String header = PAGE_HEADER_PREFIX + page.url() + PAGE_HEADER_SUFFIX + BLOCK_SEPARATOR;
            String separator = content.isEmpty() ? "" : BLOCK_SEPARATOR;
            int remaining = cap - content.length();
            int needed = separator.length() + header.length() + page.text().length();

            if (needed <= remaining) {
                content.append(separator).append(header).append(page.text());
                included.add(page.url());
                continue;
            }

            int roomForText = remaining - separator.length() - header.length() - TRUNCATION_MARKER.length();
            if (roomForText >= MIN_PARTIAL_PAGE_CHARS) {
                content.append(separator).append(header)
                        .append(page.text(), 0, roomForText)
                        .append(TRUNCATION_MARKER);
                included.add(page.url());
            }
            truncated = true;
            break;
        }
        return new Assembled(content.toString(), included, truncated);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Validates and normalises the starting URL.
     *
     * @param endpointUrl the source's configured {@code endpointUrl}
     * @return the URL with a non-empty path and no fragment, ready to be the crawl's origin
     * @throws CrawlFailedException if it is missing, malformed, or not something that can be crawled
     */
    private URI parseStart(String endpointUrl) {
        if (endpointUrl == null || endpointUrl.isBlank()) {
            throw new CrawlFailedException("(none)", "the source has no URL configured");
        }
        String trimmed = endpointUrl.trim();
        URI uri;
        try {
            uri = new URI(trimmed).normalize();
        } catch (URISyntaxException e) {
            throw new CrawlFailedException(trimmed, "not a valid URL", e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new CrawlFailedException(trimmed, "only http and https URLs can be crawled");
        }
        if (uri.getHost() == null) {
            throw new CrawlFailedException(trimmed, "the URL has no host");
        }
        try {
            String path = (uri.getPath() == null || uri.getPath().isEmpty()) ? "/" : uri.getPath();
            return new URI(scheme, uri.getAuthority(), path, uri.getQuery(), null);
        } catch (URISyntaxException e) {
            throw new CrawlFailedException(trimmed, "not a valid URL", e);
        }
    }

    /**
     * Waits for one page fetch. A task that somehow threw is turned into a failed page rather than propagated,
     * so a bug in {@link #fetch} degrades one page instead of the crawl.
     */
    private FetchOutcome await(Future<FetchOutcome> future, URI start) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CrawlFailedException(start.toString(), "crawl was interrupted", e);
        } catch (ExecutionException e) {
            log.warn("Page fetch task failed unexpectedly", e.getCause());
            return FetchOutcome.failed(start.toString(), "could not be read");
        }
    }

    /**
     * Hands one page attempt to the listener. Failures here are logged and ignored: the listener is an observer
     * of the crawl, and a client that walked away from the event stream must not be able to end the run.
     */
    private void report(CrawlProgressListener listener, CrawlProgress progress) {
        try {
            listener.onPage(progress);
        } catch (RuntimeException e) {
            log.debug("Progress listener threw for {}; continuing", progress.url(), e);
        }
    }

    /** Names crawl threads after their source, so an interleaved log can be read one crawl at a time. */
    private static ThreadFactory threadFactory(String sourceName) {
        String safe = sourceName == null ? "source" : sourceName.replaceAll("\\s+", "-");
        return Thread.ofPlatform().name("crawl-" + safe + "-", 0).daemon(true).factory();
    }

    /**
     * Turns a network exception into something safe and useful to show a user.
     *
     * <p>Deliberately not {@code e.getMessage()}. These messages describe the target site rather than our
     * internals, but they are still written for whoever is holding a stack trace, and the rule that no internal
     * exception text reaches a client is easier to keep when it has no exceptions.
     *
     * <p>The whole cause chain is searched, most specific cause first rather than outermost exception first,
     * because the distinction that matters most to a user is the one that is buried. {@code HttpClient} reports
     * an unresolvable host as a plain {@link ConnectException} wrapping an {@link UnresolvedAddressException} -
     * so classifying on the outer type tells somebody who mistyped a hostname that their connection was refused,
     * sending them to look at firewalls and TLS for what is a typo.
     */
    private static String describe(IOException e) {
        if (hasCause(e, UnknownHostException.class) || hasCause(e, UnresolvedAddressException.class)) {
            return "host could not be resolved";
        }
        if (hasCause(e, SSLException.class)) {
            return "TLS handshake failed";
        }
        if (hasCause(e, ConnectException.class)) {
            return "connection refused";
        }
        return "network error";
    }

    private static boolean hasCause(Throwable thrown, Class<? extends Throwable> type) {
        for (Throwable current = thrown; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    /** The charset named in a {@code Content-Type}, or UTF-8 when it is absent or unrecognised. */
    private static Charset charsetOf(String contentType) {
        int index = contentType.indexOf("charset=");
        if (index < 0) {
            return StandardCharsets.UTF_8;
        }
        String name = contentType.substring(index + "charset=".length()).trim();
        int semicolon = name.indexOf(';');
        if (semicolon >= 0) {
            name = name.substring(0, semicolon);
        }
        name = name.replace("\"", "").trim();
        try {
            return Charset.forName(name);
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    /** The request target a {@code robots.txt} rule is matched against. */
    private static String pathAndQuery(URI uri) {
        String path = (uri.getPath() == null || uri.getPath().isEmpty()) ? "/" : uri.getPath();
        return uri.getQuery() == null ? path : path + "?" + uri.getQuery();
    }

    /** As above, for a URL that has already been normalised to an absolute string. */
    private static String pathAndQuery(String url) {
        try {
            return pathAndQuery(new URI(url));
        } catch (URISyntaxException e) {
            return "/";
        }
    }

    /** One successfully extracted page, before assembly decides whether it fits. */
    private record CrawledPage(String url, String text) {
    }

    /** The assembled text and the pages it covers. */
    private record Assembled(String content, List<String> pageUrls, boolean truncated) {
    }

    /**
     * The result of one page fetch: either text and links, or a reason.
     *
     * <p>A single record with a nullable failure reason rather than a sealed hierarchy, because the consumer is
     * one loop that needs both cases and nothing else ever sees it.
     */
    private record FetchOutcome(String url, String text, List<String> links, String failureReason) {

        static FetchOutcome page(String url, String text, List<String> links) {
            return new FetchOutcome(url, text, links, null);
        }

        static FetchOutcome failed(String url, String reason) {
            return new FetchOutcome(url, null, List.of(), reason);
        }

        boolean succeeded() {
            return failureReason == null;
        }
    }
}
