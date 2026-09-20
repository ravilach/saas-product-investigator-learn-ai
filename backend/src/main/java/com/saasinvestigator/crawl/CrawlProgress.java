package com.saasinvestigator.crawl;

/**
 * One page attempt, in the shape the live execution view needs.
 *
 * <p>This record exists so that the SSE {@code detail} string is built next to the numbers that produce it
 * rather than assembled from loose arguments somewhere in the orchestrator. The build prompt is specific about
 * that string - {@code "Crawling https://example.com/changelog (page 3 of ~20)"} - and specific for a reason:
 * the live view's whole justification is that it reports what is actually happening. A generic
 * "Fetching pages…" would make it decorative, and a decorative progress view is indistinguishable from a hung
 * run.
 *
 * <p>The {@code ~} in "of ~20" is honest rather than sloppy. The page budget is a ceiling, not a target: a site
 * with six pages finishes at six, and a progress indicator claiming "of 20" would look stuck at 30%.
 *
 * @param url the page that was attempted
 * @param pageNumber which attempt this was within the crawl, starting at 1
 * @param maxPages the page budget for this source, as the upper bound the counter runs towards
 * @param failureReason why the page could not be used, or {@code null} when it was fetched successfully
 */
public record CrawlProgress(String url, int pageNumber, int maxPages, String failureReason) {

    /**
     * @param url the page just fetched
     * @param pageNumber which attempt this was, starting at 1
     * @param maxPages the page budget for this source
     * @return progress for a successful fetch
     */
    public static CrawlProgress fetched(String url, int pageNumber, int maxPages) {
        return new CrawlProgress(url, pageNumber, maxPages, null);
    }

    /**
     * @param url the page that could not be used
     * @param pageNumber which attempt this was, starting at 1
     * @param maxPages the page budget for this source
     * @param reason a short, human-readable cause - a status line, a timeout, an unusable content type
     * @return progress for a failed or skipped fetch
     */
    public static CrawlProgress failed(String url, int pageNumber, int maxPages, String reason) {
        return new CrawlProgress(url, pageNumber, maxPages, reason);
    }

    /**
     * @return {@code true} if the page was fetched and its text is usable
     */
    public boolean succeeded() {
        return failureReason == null;
    }

    /**
     * The {@code detail} field of a {@code step_progress} SSE event.
     *
     * <p>Failures are reported rather than hidden. A run that skipped half its pages on {@code 403}s produced a
     * thin report for a reason, and that reason belongs in front of the person watching it happen, not only in
     * a server log they do not have.
     *
     * @return a specific, human-readable description of this page attempt
     */
    public String detail() {
        String position = " (page " + pageNumber + " of ~" + maxPages + ")";
        return succeeded()
                ? "Crawling " + url + position
                : "Skipped " + url + position + ": " + failureReason;
    }
}
