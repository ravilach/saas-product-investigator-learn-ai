package com.saasinvestigator.crawl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Receives one callback per page attempt while a crawl is running.
 *
 * <p>A callback rather than a return value because the point is liveness: the orchestrator turns each call into
 * an SSE {@code step_progress} event, and a crawl that reported its pages only on completion would leave the
 * live execution view blank for the entire minute it takes. The crawler therefore knows nothing about SSE, and
 * the orchestrator knows nothing about crawling.
 *
 * <p>Implementations are called from the crawl's worker threads and may be called concurrently, so they must be
 * thread-safe. They must also be fast and must not throw - see {@link #onPage}.
 */
@FunctionalInterface
public interface CrawlProgressListener {

    /**
     * Called once for every page the crawl attempts, whether or not it succeeded.
     *
     * <p>The crawler catches and logs any exception thrown here rather than letting it fail the page. A progress
     * listener is an observer: a client that has disconnected from the event stream mid-run is a completely
     * normal thing to happen, and it must not be able to abort the run it was watching.
     *
     * @param progress the page attempt, including the {@code detail} string for the event
     */
    void onPage(CrawlProgress progress);

    /**
     * A listener that only logs, for callers with nobody watching - scheduled work, tests, and the second half
     * of a run whose client has gone away.
     *
     * @return a listener that writes each page attempt at debug level
     */
    static CrawlProgressListener logging() {
        Logger log = LoggerFactory.getLogger(CrawlProgressListener.class);
        return progress -> log.debug("{}", progress.detail());
    }
}
