package com.saasinvestigator.crawl;

/**
 * Thrown when a crawl produced nothing usable at all.
 *
 * <p>The distinction this type draws is the one the build prompt's error-handling rule depends on. A crawl in
 * which some pages failed is a {@link CrawlResult} with entries in {@code failures} - a successful crawl that
 * says so. A crawl in which the starting point itself was unreachable, forbidden, or not a web page has nothing
 * to return, and returning an empty result would be worse than throwing: an empty snapshot would be stored,
 * compared against the previous one, and reported as "everything was removed".
 *
 * <p>So the contract is: the orchestrator catches this, marks that one source unavailable in the report, and
 * carries on with the others. It never aborts the run. The message is written to be shown to a user - it names
 * the URL and the specific cause - because "one source failed" is useless without "which, and why".
 */
public class CrawlFailedException extends RuntimeException {

    private final String url;

    /**
     * @param url the URL the crawl was unable to proceed from
     * @param reason a specific, user-safe explanation, e.g. {@code "404 Not Found"} or
     *     {@code "robots.txt disallows /internal for SaaSProductInvestigator"}
     */
    public CrawlFailedException(String url, String reason) {
        super("Could not crawl " + url + ": " + reason);
        this.url = url;
    }

    /**
     * @param url the URL the crawl was unable to proceed from
     * @param reason a specific, user-safe explanation
     * @param cause the underlying failure, kept for the server-side log and never shown to a client
     */
    public CrawlFailedException(String url, String reason, Throwable cause) {
        super("Could not crawl " + url + ": " + reason, cause);
        this.url = url;
    }

    /**
     * @return the URL that could not be crawled
     */
    public String getUrl() {
        return url;
    }
}
