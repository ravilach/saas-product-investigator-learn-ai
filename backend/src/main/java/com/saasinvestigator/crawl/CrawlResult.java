package com.saasinvestigator.crawl;

import java.util.List;

/**
 * Everything one crawl of one source produced.
 *
 * <p>{@link #content()} is what goes to the LLM and what is stored as a {@link
 * com.saasinvestigator.snapshot.Snapshot}'s {@code rawContent}; {@link #pageUrls()} becomes that snapshot's
 * {@code pageUrls}. The remaining fields are not persisted - they exist so the orchestrator can report honestly
 * on what happened: emit {@code saas_source_fetch_errors_total}, log the shape of the crawl, and tell the user
 * that a report is thin because half the pages timed out rather than because nothing changed.
 *
 * @param content the assembled text, one {@code --- PAGE: url ---} block per included page, in crawl order,
 *     capped at {@code app.crawler.max-chars-per-source}
 * @param pageUrls the pages whose text is actually in {@code content}, in crawl order. Deliberately not "every
 *     page fetched": when truncation drops the tail of a crawl, claiming those URLs are covered by this
 *     snapshot would be a lie told to whoever later asks why a change on one of them was missed.
 * @param pagesAttempted how many pages were requested, successes and failures together - the number the page
 *     budget was spent on
 * @param pagesIncluded how many pages contributed text to {@code content}
 * @param truncated whether the character cap cut the crawl short of its fetched pages
 * @param failures pages that could not be used, with the reason for each
 * @param skippedByRobots how many discovered links were never requested because {@code robots.txt} forbade them
 * @param crawlDelayApplied whether a {@code robots.txt} {@code Crawl-delay} forced fetches to be serialised,
 *     which is the usual explanation for a crawl that took far longer than its page count suggests
 */
public record CrawlResult(
        String content,
        List<String> pageUrls,
        int pagesAttempted,
        int pagesIncluded,
        boolean truncated,
        List<CrawlResult.PageFailure> failures,
        int skippedByRobots,
        boolean crawlDelayApplied) {

    /**
     * One page that could not be used.
     *
     * @param url the page attempted
     * @param reason a short human-readable cause, safe to show a user - a status line, a timeout, an unusable
     *     content type. Never a stack trace or an internal exception message; see {@code docs/ARCHITECTURE.md}
     *     on error handling.
     */
    public record PageFailure(String url, String reason) {
    }

    /** Defensively copies the two lists, so a result cannot be mutated after the crawl that produced it. */
    public CrawlResult {
        pageUrls = List.copyOf(pageUrls);
        failures = List.copyOf(failures);
    }

    /**
     * @return {@code true} if at least one page failed - not a reason to fail the run, but a reason to say so
     */
    public boolean hasFailures() {
        return !failures.isEmpty();
    }

    /**
     * A one-line summary for logs and for the {@code step_completed} event's detail.
     *
     * @return e.g. {@code "7 pages, 41,203 chars"}, with any caveats appended
     */
    public String summary() {
        StringBuilder text = new StringBuilder(String.format("%d page%s, %,d chars",
                pagesIncluded, pagesIncluded == 1 ? "" : "s", content.length()));
        if (truncated) {
            text.append(" (truncated at the character cap)");
        }
        if (hasFailures()) {
            text.append(", ").append(failures.size()).append(" failed");
        }
        if (skippedByRobots > 0) {
            text.append(", ").append(skippedByRobots).append(" skipped by robots.txt");
        }
        return text.toString();
    }
}
