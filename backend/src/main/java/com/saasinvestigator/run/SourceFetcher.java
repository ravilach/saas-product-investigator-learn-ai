package com.saasinvestigator.run;

import com.saasinvestigator.crawl.CrawlFailedException;
import com.saasinvestigator.crawl.CrawlResult;
import com.saasinvestigator.crawl.WebCrawler;
import com.saasinvestigator.llm.SourceComparison;
import com.saasinvestigator.llm.SourceFailure;
import com.saasinvestigator.product.SaasProduct;
import com.saasinvestigator.product.SourceConfig;
import com.saasinvestigator.report.SourceInclusion;
import com.saasinvestigator.snapshot.Snapshot;
import com.saasinvestigator.snapshot.SnapshotRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Crawls a product's crawled sources for a standard run, stores what it finds, and pairs it with what was there
 * last time.
 *
 * <p>This is the only place in the application that writes a {@link Snapshot}, and the order of operations inside
 * {@link #fetchAll} is the reason it is worth being a class of its own rather than a method on the orchestrator.
 *
 * <h2>Read before write, always</h2>
 *
 * <p>The previous snapshot is loaded <em>before</em> the new one is saved. Reversing those two lines produces a
 * system that runs, logs nothing unusual, writes plausible reports, and is completely useless: every run would
 * compare a source against itself and conclude that nothing ever changes. There is no test a user could perform on
 * one report to notice, which is exactly why the ordering is called out here and asserted by
 * {@code SourceFetcherTest}.
 *
 * <h2>One failed source does not fail the run</h2>
 *
 * <p>A product with six sources whose fifth one is behind a temporary {@code 503} should still produce a report
 * about the other five, saying so. So each source is crawled inside its own try/catch: a failure becomes a
 * {@link SourceFailure} that is carried into the prompt, counted on
 * {@code saas_source_fetch_errors_total}, reported to the live view, and left out of {@code sourcesIncluded} - which
 * is what lets a reader of the finished report tell "no changes here" from "we could not look".
 *
 * <p>A failed source also writes no snapshot. That matters for the next run: storing an error page as this source's
 * state would make the following run report the recovery as a change, and the one after that report nothing while
 * the outage continued.
 */
@Service
public class SourceFetcher {

    private static final Logger log = LoggerFactory.getLogger(SourceFetcher.class);

    private final WebCrawler crawler;
    private final SnapshotRepository snapshots;
    private final RunMetrics metrics;

    public SourceFetcher(WebCrawler crawler, SnapshotRepository snapshots, RunMetrics metrics) {
        this.crawler = crawler;
        this.snapshots = snapshots;
        this.metrics = metrics;
    }

    /**
     * Crawls every crawled source of a product, reporting each page as it is fetched.
     *
     * <p>Sources are crawled one after another rather than in parallel. The concurrency that matters is already
     * inside the crawler, which fetches several pages of one site at a time; running whole sources concurrently on
     * top of that would multiply the load a single run puts on somebody else's website, and would interleave the
     * per-page progress events into something unreadable.
     *
     * <p>Owns the {@code Fetching sources} step from start to finish, including its opening and closing events.
     * Each collaborator in a run owns its own step rather than having the orchestrator bracket the call, so that the
     * detail strings are written where the numbers that make them specific actually are.
     *
     * @param product the product being run
     * @param session where to report progress; each page fetched becomes one {@code step_progress}
     * @return the comparisons, failures, and inclusions this run's crawled sources produced
     */
    public FetchOutcome fetchAll(SaasProduct product, RunSession session) {
        List<SourceComparison> comparisons = new ArrayList<>();
        List<SourceFailure> failures = new ArrayList<>();
        List<SourceInclusion> inclusions = new ArrayList<>();

        int crawled = product.crawledSources().size();
        session.stepStarted(RunStep.FETCHING_SOURCES, crawled == 0
                ? "This product has no crawled sources; its MCP sources are read by the model itself"
                : "Crawling " + crawled + (crawled == 1 ? " source" : " sources"));

        for (SourceConfig source : product.crawledSources()) {
            try {
                comparisons.add(fetchOne(product, source, session, inclusions));
            } catch (CrawlFailedException e) {
                // getMessage() here is the crawler's own short, user-facing reason - a status line or a timeout -
                // not an internal exception message. See CrawlFailedException.
                recordFailure(product, source, session, failures, e.getMessage());
            } catch (RuntimeException e) {
                // Anything unforeseen still must not take the whole run down, but the user gets a generic reason
                // while the real one goes to the log, because an arbitrary exception message can carry internals.
                log.error("Unexpected failure crawling source '{}' of product '{}'.",
                        source.getName(), product.getName(), e);
                recordFailure(product, source, session, failures, "an unexpected error while fetching this source");
            }
        }

        FetchOutcome outcome = new FetchOutcome(comparisons, failures, inclusions);
        session.stepCompleted(RunStep.FETCHING_SOURCES, outcome.summary());
        return outcome;
    }

    /** Crawls one source, stores the result, and pairs it with the previous snapshot. */
    private SourceComparison fetchOne(SaasProduct product,
                                      SourceConfig source,
                                      RunSession session,
                                      List<SourceInclusion> inclusions) {
        // Before the crawl, and before anything is saved - see this class's documentation.
        Optional<Snapshot> previous = snapshots
                .findFirstBySaasProductIdAndSourceNameOrderByFetchedAtDesc(product.getId(), source.getName());

        CrawlResult result = crawler.crawl(source,
                progress -> session.stepProgress(RunStep.FETCHING_SOURCES, progress.detail()));

        Snapshot stored = snapshots.save(new Snapshot(
                product.getId(), source.getName(), source.getType(), result.content(), result.pageUrls()));

        // Every crawled source that succeeded gets a snapshot, whether or not its text changed. The alternative -
        // only storing changes - would break the "nearest snapshot at or before this date" lookup a custom-range
        // compare depends on, because the absence of a document would be ambiguous between "unchanged" and "not
        // run". Storage is cheap; an ambiguous history is not.
        inclusions.add(new SourceInclusion(source.getName(), source.getType(), stored.getFetchedAt()));

        session.stepProgress(RunStep.FETCHING_SOURCES,
                "Stored " + source.getName() + ": " + result.summary());

        if (result.hasFailures()) {
            // Page-level failures are not source-level failures: the source produced usable text. They are still
            // counted, because a source that loses half its pages every run is a misconfiguration worth seeing.
            metrics.recordSourceFetchError(product.getName(), source.getType());
        }

        return new SourceComparison(
                source.getName(),
                source.getType(),
                previous.map(Snapshot::getRawContent).orElse(null),
                previous.map(Snapshot::getFetchedAt).orElse(null),
                stored.getRawContent(),
                stored.getFetchedAt());
    }

    private void recordFailure(SaasProduct product,
                               SourceConfig source,
                               RunSession session,
                               List<SourceFailure> failures,
                               String reason) {
        log.warn("Source '{}' of product '{}' could not be read: {}", source.getName(), product.getName(), reason);
        failures.add(new SourceFailure(source.getName(), source.getType(), reason));
        metrics.recordSourceFetchError(product.getName(), source.getType());
        session.stepFailed(RunStep.FETCHING_SOURCES, source.getName() + " could not be read: " + reason);
    }

    /**
     * What one run's crawling produced.
     *
     * @param comparisons one entry per source that was read, each pairing the new text with the previous snapshot
     * @param failures one entry per source that could not be read at all
     * @param inclusions {@code sourcesIncluded} entries for the successful sources, with the moment each was
     *     actually fetched - which is per-source rather than per-run because a slow crawl can span minutes
     */
    public record FetchOutcome(List<SourceComparison> comparisons,
                               List<SourceFailure> failures,
                               List<SourceInclusion> inclusions) {

        /** Defensively copies, so an outcome cannot change after the crawling that produced it. */
        public FetchOutcome {
            comparisons = List.copyOf(comparisons);
            failures = List.copyOf(failures);
            inclusions = List.copyOf(inclusions);
        }

        /** @return a one-line summary for the {@code step_completed} detail */
        public String summary() {
            int read = comparisons.size();
            StringBuilder text = new StringBuilder(read + (read == 1 ? " source read" : " sources read"));
            if (!failures.isEmpty()) {
                text.append(", ").append(failures.size()).append(" unavailable");
            }
            return text.toString();
        }
    }
}
