package com.saasinvestigator.run;

import com.saasinvestigator.error.BadRequestException;
import com.saasinvestigator.llm.McpChangeHistory;
import com.saasinvestigator.llm.SourceComparison;
import com.saasinvestigator.llm.SourceFailure;
import com.saasinvestigator.product.SaasProduct;
import com.saasinvestigator.product.SourceConfig;
import com.saasinvestigator.report.Change;
import com.saasinvestigator.report.ChangeReport;
import com.saasinvestigator.report.ChangeReportRepository;
import com.saasinvestigator.report.RunType;
import com.saasinvestigator.report.SourceInclusion;
import com.saasinvestigator.snapshot.Snapshot;
import com.saasinvestigator.snapshot.SnapshotRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Assembles a custom-range compare entirely from stored data.
 *
 * <p>The defining property of this class is what it does <em>not</em> do: it never crawls, never calls an MCP
 * server, and never writes a snapshot. A compare answers "what changed between these two dates", and anything
 * fetched now would answer about today instead - so a compare that reached out to the network would be quietly
 * wrong in a way its output would not reveal. There is no {@code WebCrawler} in this class's constructor, and that
 * absence is the enforcement.
 *
 * <h2>The two halves are not equally good, and the report says so</h2>
 *
 * <p>Crawled sources rewind perfectly: their text was stored on every past run, so "as of 1 June" is a lookup.
 * MCP sources cannot be rewound at all - their tools report the state of a Jira project or a doc set now, and no
 * protocol exists for asking one what it would have said in June. For those, this class re-reads what earlier
 * reports recorded inside the window and sets {@code mcpHistoryLimited}, which the UI shows as a caveat rather than
 * hiding. See {@link McpChangeHistory} for the longer version of why that is the honest answer rather than a
 * shortcut.
 *
 * <h2>Anchoring backwards</h2>
 *
 * <p>Both ends of the window resolve to the most recent snapshot <em>at or before</em> the chosen date, never the
 * nearest one in either direction. Erring forwards would pick up a capture taken after {@code fromDate} that may
 * already contain the very change the compare is meant to find, attributing a change to the wrong window. Erring
 * backwards can only make the window wider than asked for, which the report states per source.
 */
@Service
public class HistoryLoader {

    private static final Logger log = LoggerFactory.getLogger(HistoryLoader.class);

    /** Dates in progress details and error messages read as dates, not as instants. */
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneOffset.UTC);

    private final SnapshotRepository snapshots;
    private final ChangeReportRepository reports;

    public HistoryLoader(SnapshotRepository snapshots, ChangeReportRepository reports) {
        this.snapshots = snapshots;
        this.reports = reports;
    }

    /**
     * Rejects a compare that cannot be answered, before any work is started.
     *
     * <p>Called on the request thread rather than inside the run task, deliberately. All three of these checks are
     * cheap, and a {@code 400} with an actionable message is a far better answer than a {@code 202} followed
     * seconds later by a {@code run_failed} event the user has to open the live view to read.
     *
     * <p>The third check is the one that needs the database: a window that starts before this product has any data
     * at all cannot be compared, and the useful thing to say is not "no data" but which date the data actually
     * starts at, so the user can move their picker there. That is the whole reason
     * {@code findFirstBySaasProductIdOrderByFetchedAtAsc} exists.
     *
     * @param product the product being compared
     * @param from start of the window
     * @param to end of the window
     * @throws BadRequestException if the range is inverted, in the future, or predates all stored data
     */
    public void validateRange(SaasProduct product, Instant from, Instant to) {
        if (from.isAfter(to)) {
            throw new BadRequestException("The start of the range must not be after its end.");
        }

        Instant now = Instant.now();
        if (from.isAfter(now) || to.isAfter(now)) {
            throw new BadRequestException(
                    "A comparison range cannot extend into the future - there is no data from after now.");
        }

        Optional<Snapshot> earliest = snapshots.findFirstBySaasProductIdOrderByFetchedAtAsc(product.getId());
        if (earliest.isEmpty()) {
            throw new BadRequestException("This product has never been run, so there is no history to compare. "
                    + "Run it at least twice before comparing two dates.");
        }
        Instant earliestAt = earliest.get().getFetchedAt();
        if (earliestAt.isAfter(from)) {
            throw new BadRequestException("There is no stored data for this product from before "
                    + DAY.format(from) + ". The earliest data available is from " + DAY.format(earliestAt)
                    + " - start the range on or after that date.");
        }
    }

    /**
     * Loads everything a compare needs for one window.
     *
     * @param product the product being compared
     * @param from start of the window
     * @param to end of the window
     * @param session where to report per-source progress
     * @return the comparisons, failures, inclusions, and MCP history for the window
     */
    public CompareData load(SaasProduct product, Instant from, Instant to, RunSession session) {
        session.stepStarted(RunStep.LOADING_SNAPSHOTS, "Reading stored snapshots between " + DAY.format(from)
                + " and " + DAY.format(to) + " - a comparison fetches nothing new");

        Collected collected = new Collected();
        for (SourceConfig source : product.crawledSources()) {
            loadOne(product, source, from, to, session, collected);
        }

        // Closed before the MCP step opens: the live view draws one pill per step, and a step that completes after
        // its successor has already started reads as a bug in the run rather than as a quirk of the emitter.
        session.stepCompleted(RunStep.LOADING_SNAPSHOTS, summarise(collected));

        return new CompareData(collected.comparisons, collected.unavailable, collected.unchanged,
                collected.inclusions, loadMcpHistory(product, from, to, session),
                !product.mcpSources().isEmpty());
    }

    /** The {@code step_completed} detail for the snapshot step: what was found, and why anything was left out. */
    private static String summarise(Collected collected) {
        StringBuilder text = new StringBuilder(collected.comparisons.size() + " comparable "
                + (collected.comparisons.size() == 1 ? "source" : "sources"));
        if (!collected.unchanged.isEmpty()) {
            text.append(", ").append(collected.unchanged.size()).append(" unchanged in range");
        }
        if (!collected.unavailable.isEmpty()) {
            text.append(", ").append(collected.unavailable.size()).append(" with no data in range");
        }
        return text.toString();
    }

    /** Resolves one crawled source's two ends of the window. */
    private void loadOne(SaasProduct product,
                         SourceConfig source,
                         Instant from,
                         Instant to,
                         RunSession session,
                         Collected collected) {
        List<SourceComparison> comparisons = collected.comparisons;
        List<SourceInclusion> inclusions = collected.inclusions;
        Optional<Snapshot> before = snapshotAsOf(product.getId(), source.getName(), from);
        Optional<Snapshot> after = snapshotAsOf(product.getId(), source.getName(), to);

        if (after.isEmpty()) {
            // The source has no stored data anywhere in or before the window - usually a source added after the
            // window ended. Carried as an unavailable source so the summary mentions it rather than omitting it,
            // which would be indistinguishable from "nothing changed here".
            String reason = "no snapshot of this source exists from on or before " + DAY.format(to);
            collected.unavailable.add(new SourceFailure(source.getName(), source.getType(), reason));
            session.stepFailed(RunStep.LOADING_SNAPSHOTS, source.getName() + ": " + reason);
            return;
        }

        Snapshot later = after.get();
        Optional<Snapshot> earlier = before.filter(candidate -> !Objects.equals(candidate.getId(), later.getId()));

        if (before.isPresent() && earlier.isEmpty()) {
            // Both ends of the window resolved to the same capture: no new snapshot was taken inside the window at
            // all, so for this source there is provably nothing to compare. Sending the same text twice would burn
            // half the prompt budget to have the model confirm two identical blocks are identical.
            String reason = "no new capture was taken between " + DAY.format(from) + " and " + DAY.format(to)
                    + "; its state is unchanged from " + DAY.format(later.getFetchedAt());
            collected.unchanged.add(new SourceFailure(source.getName(), source.getType(), reason));
            session.stepProgress(RunStep.LOADING_SNAPSHOTS, source.getName() + ": " + reason);
            inclusions.add(new SourceInclusion(source.getName(), source.getType(), later.getFetchedAt()));
            return;
        }

        session.stepProgress(RunStep.LOADING_SNAPSHOTS, "Loading " + source.getName() + " snapshot for "
                + hostOrName(source) + " as of " + DAY.format(later.getFetchedAt())
                + earlier.map(snapshot -> ", compared against " + DAY.format(snapshot.getFetchedAt())).orElse(""));

        comparisons.add(new SourceComparison(
                source.getName(),
                source.getType(),
                earlier.map(Snapshot::getRawContent).orElse(null),
                earlier.map(Snapshot::getFetchedAt).orElse(null),
                later.getRawContent(),
                later.getFetchedAt()));

        // The inclusion records the snapshot date actually used, not the date asked for. Those differ whenever no
        // run happened on the chosen day, which is most days - and a report claiming to be "as of 1 June" when its
        // newest data is from 28 May would be a small lie that compounds across a timeline.
        inclusions.add(new SourceInclusion(source.getName(), source.getType(), later.getFetchedAt()));
    }

    private Optional<Snapshot> snapshotAsOf(String productId, String sourceName, Instant at) {
        return snapshots.findFirstBySaasProductIdAndSourceNameAndFetchedAtLessThanEqualOrderByFetchedAtDesc(
                productId, sourceName, at);
    }

    /**
     * Collects what earlier reports said about each MCP source inside the window.
     *
     * <p>Only {@code STANDARD} reports are read. Earlier {@code CUSTOM_RANGE} reports in the same window were
     * themselves built by aggregating these same standard runs, so including them would count every MCP finding
     * twice - and would weight the double-counted ones more heavily in the summary, purely because somebody had
     * run a compare over that period before.
     */
    private List<McpChangeHistory> loadMcpHistory(SaasProduct product,
                                                  Instant from,
                                                  Instant to,
                                                  RunSession session) {
        List<SourceConfig> mcpSources = product.mcpSources();

        // The step is opened and closed even for a product with no MCP sources. Skipping it entirely would leave the
        // live view's fourth pill drawn but never resolved, which looks like a stalled run; saying "none configured"
        // costs one event and answers the question.
        session.stepStarted(RunStep.AGGREGATING_MCP_HISTORY, mcpSources.isEmpty()
                ? "This product has no MCP sources"
                : "Reading what earlier runs recorded for " + mcpSources.size()
                        + (mcpSources.size() == 1 ? " MCP source" : " MCP sources"));

        if (mcpSources.isEmpty()) {
            session.stepCompleted(RunStep.AGGREGATING_MCP_HISTORY, "Nothing to aggregate");
            return List.of();
        }

        List<ChangeReport> inWindow = reports.findWithinWindow(product.getId(), from, to).stream()
                .filter(report -> report.getRunType() == RunType.STANDARD)
                .toList();

        List<McpChangeHistory> history = new ArrayList<>(mcpSources.size());
        for (SourceConfig source : mcpSources) {
            List<Change> changes = inWindow.stream()
                    .flatMap(report -> report.getChanges().stream())
                    .filter(change -> source.getName().equalsIgnoreCase(change.sourceName()))
                    .toList();
            history.add(new McpChangeHistory(source.getName(), source.getType(), changes));
            session.stepProgress(RunStep.AGGREGATING_MCP_HISTORY, changes.isEmpty()
                    ? "No previously recorded changes for " + source.getName() + " in this range"
                    : "Found " + changes.size() + " previously recorded change"
                            + (changes.size() == 1 ? "" : "s") + " for " + source.getName());
        }

        log.debug("Aggregated MCP history for product {} from {} standard reports in window.",
                product.getId(), inWindow.size());
        session.stepCompleted(RunStep.AGGREGATING_MCP_HISTORY,
                "Read " + inWindow.size() + (inWindow.size() == 1 ? " earlier report" : " earlier reports")
                        + " covering " + mcpSources.size() + (mcpSources.size() == 1 ? " MCP source" : " MCP sources"));
        return history;
    }

    /** The host of a crawled source's URL, for a progress line, falling back to its name if it will not parse. */
    private static String hostOrName(SourceConfig source) {
        try {
            String host = java.net.URI.create(source.getEndpointUrl()).getHost();
            return host == null ? source.getName() : host;
        } catch (IllegalArgumentException e) {
            return source.getName();
        }
    }

    /** Mutable accumulator, so {@link #loadOne} does not need five out-parameters. */
    private static final class Collected {
        private final List<SourceComparison> comparisons = new ArrayList<>();
        private final List<SourceFailure> unavailable = new ArrayList<>();
        private final List<SourceFailure> unchanged = new ArrayList<>();
        private final List<SourceInclusion> inclusions = new ArrayList<>();
    }

    /**
     * Everything a compare assembled from storage.
     *
     * <p>The two lists of {@link SourceFailure} are the reason this is not simply "comparisons and failures". Both
     * end up in the prompt's "sources unavailable" section, because in both cases the model has nothing to compare
     * and should say so - but they mean opposite things about the health of the system, and only one of them should
     * colour a run's {@code status} tag. A source with no data in range is a gap somebody may need to fix; a source
     * whose two ends of the window are the same capture is the system working correctly and reporting a quiet
     * period. Conflating them would mark almost every compare {@code partial} and make the metric useless.
     *
     * @param comparisons crawled sources with two distinct captures to compare
     * @param unavailable sources with no stored data at all in or before the window - a genuine gap
     * @param unchanged sources whose window contains no new capture, so their state provably did not move
     * @param inclusions {@code sourcesIncluded} entries carrying the snapshot dates actually used
     * @param mcpHistory previously-recorded changes per MCP source, empty for a product with none
     * @param mcpHistoryLimited whether the report must carry the MCP caveat - true whenever the product has any
     *     MCP source, because the limitation is a property of the method rather than of how much it happened to
     *     find
     */
    public record CompareData(List<SourceComparison> comparisons,
                              List<SourceFailure> unavailable,
                              List<SourceFailure> unchanged,
                              List<SourceInclusion> inclusions,
                              List<McpChangeHistory> mcpHistory,
                              boolean mcpHistoryLimited) {

        /** Defensively copies every list. */
        public CompareData {
            comparisons = List.copyOf(comparisons);
            unavailable = List.copyOf(unavailable);
            unchanged = List.copyOf(unchanged);
            inclusions = List.copyOf(inclusions);
            mcpHistory = List.copyOf(mcpHistory);
        }

        /**
         * @return everything the model should be told it cannot compare, both kinds together, since the prompt's
         *     instruction is the same for both: mention it in the summary, do not report changes for it
         */
        public List<SourceFailure> promptFailures() {
            List<SourceFailure> all = new ArrayList<>(unavailable.size() + unchanged.size());
            all.addAll(unavailable);
            all.addAll(unchanged);
            return all;
        }
    }
}
