package com.saasinvestigator.crawl;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The crawler's tunable limits, bound from {@code app.crawler.*}.
 *
 * <p>Grouped into one record rather than injected as individual {@code @Value} parameters - which is the style
 * used elsewhere in this codebase - because these nine knobs are genuinely one subject. They are read together,
 * reasoned about together ("how much can one source cost us?"), and passed as a unit to the crawl. Nine
 * {@code @Value} parameters on a constructor is a signature nobody reads.
 *
 * <p>Two of these are ceilings rather than defaults, and the distinction matters. {@link #defaultMaxDepth()} and
 * {@link #defaultMaxPages()} are what a source gets when it does not say; {@link #maxAllowedDepth()} and
 * {@link #maxAllowedPages()} are what no source may exceed however it is configured, including via the Admin
 * Console. Crawling is a safety boundary (see {@code docs/ARCHITECTURE.md}), and a boundary that any admin
 * typo can move is not one - the blast radius of a mistake here lands on somebody else's web server.
 *
 * <p>All values are clamped to something sane on binding rather than validated and rejected. Nonsense config
 * ({@code concurrency=0}, a negative page budget) should not prevent the application from starting: the cost of
 * a clamp is a crawl that behaves slightly differently from what was typed, and the cost of a rejection is an
 * outage.
 *
 * @param defaultMaxDepth link-hops to follow when a source sets no {@code maxDepth}
 * @param defaultMaxPages page budget when a source sets no {@code maxPages}
 * @param maxAllowedDepth hard ceiling on link-hops, regardless of source or Admin Console setting
 * @param maxAllowedPages hard ceiling on the page budget, regardless of source or Admin Console setting
 * @param perPageTimeoutMs how long a single page fetch may take before it is abandoned
 * @param connectTimeoutMs how long establishing a connection may take
 * @param maxBytesPerPage how much of one response body is read before the rest is discarded
 * @param maxCharsPerSource cap on the assembled text handed to the LLM for one source
 * @param concurrency how many page fetches may be in flight at once
 * @param maxCrawlDelayMs ceiling on an honoured {@code robots.txt} {@code Crawl-delay}
 * @param userAgent the {@code User-Agent} sent with every request, and the product token matched in
 *     {@code robots.txt}
 */
@ConfigurationProperties(prefix = "app.crawler")
public record CrawlerProperties(
        int defaultMaxDepth,
        int defaultMaxPages,
        int maxAllowedDepth,
        int maxAllowedPages,
        long perPageTimeoutMs,
        long connectTimeoutMs,
        int maxBytesPerPage,
        int maxCharsPerSource,
        int concurrency,
        long maxCrawlDelayMs,
        String userAgent) {

    /** Clamps every value into a range the crawler can actually operate in. */
    public CrawlerProperties {
        maxAllowedDepth = Math.max(0, maxAllowedDepth);
        maxAllowedPages = Math.max(1, maxAllowedPages);
        defaultMaxDepth = Math.clamp(defaultMaxDepth, 0, maxAllowedDepth);
        defaultMaxPages = Math.clamp(defaultMaxPages, 1, maxAllowedPages);
        perPageTimeoutMs = Math.max(1_000, perPageTimeoutMs);
        connectTimeoutMs = Math.max(1_000, connectTimeoutMs);
        maxBytesPerPage = Math.max(1_024, maxBytesPerPage);
        maxCharsPerSource = Math.max(1_000, maxCharsPerSource);
        // Upper bound as well as lower: "concurrency" here is a politeness budget aimed at one origin, and a
        // config value of 500 would be a denial-of-service tool rather than a performance setting.
        concurrency = Math.clamp(concurrency, 1, 16);
        maxCrawlDelayMs = Math.max(0, maxCrawlDelayMs);
        userAgent = (userAgent == null || userAgent.isBlank()) ? "SaaSProductInvestigator" : userAgent.trim();
    }

    /**
     * @return the per-page fetch timeout as a {@link Duration}
     */
    public Duration perPageTimeout() {
        return Duration.ofMillis(perPageTimeoutMs);
    }

    /**
     * @return the connect timeout as a {@link Duration}
     */
    public Duration connectTimeout() {
        return Duration.ofMillis(connectTimeoutMs);
    }

    /**
     * The product token this crawler answers to in {@code robots.txt}.
     *
     * <p>A {@code User-Agent} header is conventionally {@code Product/Version (comment)}, but a
     * {@code robots.txt} {@code User-agent} line names only the product token. Sending one string and matching
     * a different one is the kind of mismatch that makes a crawler quietly ignore rules written specifically
     * for it, so the token is derived from the header rather than configured separately.
     *
     * @return the leading product token of {@link #userAgent()}, e.g. {@code SaaSProductInvestigator}
     */
    public String userAgentToken() {
        int end = userAgent.length();
        for (int i = 0; i < userAgent.length(); i++) {
            char c = userAgent.charAt(i);
            if (c == '/' || Character.isWhitespace(c)) {
                end = i;
                break;
            }
        }
        return userAgent.substring(0, end);
    }

    /**
     * The defaults used by tests and by any caller that wants the shipped configuration without a Spring
     * context. Kept in step with {@code application.properties}.
     *
     * @return a properties instance matching the values shipped in {@code application.properties}
     */
    public static CrawlerProperties defaults() {
        return new CrawlerProperties(2, 20, 5, 200, 10_000, 5_000, 5 * 1024 * 1024, 200_000, 4, 2_000,
                "SaaSProductInvestigator/0.1 (+https://github.com/ravilach/saas-product-investigator-learn-ai)");
    }
}
