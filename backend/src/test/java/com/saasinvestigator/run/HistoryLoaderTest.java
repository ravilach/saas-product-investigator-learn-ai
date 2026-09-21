package com.saasinvestigator.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saasinvestigator.error.BadRequestException;
import com.saasinvestigator.llm.McpChangeHistory;
import com.saasinvestigator.llm.SourceComparison;
import com.saasinvestigator.llm.SourceFailure;
import com.saasinvestigator.product.SaasProduct;
import com.saasinvestigator.product.SourceConfig;
import com.saasinvestigator.product.SourceType;
import com.saasinvestigator.report.AnalysisDepth;
import com.saasinvestigator.report.Change;
import com.saasinvestigator.report.ChangeCategory;
import com.saasinvestigator.report.ChangeReport;
import com.saasinvestigator.report.ChangeReportRepository;
import com.saasinvestigator.report.Confidence;
import com.saasinvestigator.report.RunType;
import com.saasinvestigator.report.SourceInclusion;
import com.saasinvestigator.snapshot.Snapshot;
import com.saasinvestigator.snapshot.SnapshotRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests the compare path, whose whole job is to answer about the past without touching the present.
 *
 * <p>Two things are asserted here that no other test can assert. The first is that a compare reads and never fetches -
 * enforced structurally by there being no crawler in {@link HistoryLoader}'s constructor, and asserted behaviourally by
 * the absence of any snapshot write. A compare that quietly crawled would produce a report about today under a heading
 * naming two dates in June, and nothing in its output would reveal the substitution.
 *
 * <p>The second is the pair of asymmetries a reader would not guess: both ends of the window anchor <em>backwards</em>
 * to the most recent capture at or before the chosen date, and the two flavours of "cannot compare this source" are
 * kept in separate lists. The first asymmetry prevents attributing a change to the wrong window; the second is what
 * stops almost every compare being marked {@code partial}, since "no new capture was taken in this range" is the system
 * working correctly rather than a gap in the data.
 */
class HistoryLoaderTest {

    private static final Instant JUNE_1 = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant JUNE_30 = Instant.parse("2026-06-30T23:59:59Z");
    private static final Instant MAY_28 = Instant.parse("2026-05-28T10:00:00Z");
    private static final Instant JUNE_20 = Instant.parse("2026-06-20T10:00:00Z");

    private SnapshotRepository snapshots;
    private ChangeReportRepository reports;
    private HistoryLoader loader;

    @BeforeEach
    void setUp() {
        snapshots = mock(SnapshotRepository.class);
        reports = mock(ChangeReportRepository.class);
        loader = new HistoryLoader(snapshots, reports);

        lenient().when(snapshots.findFirstBySaasProductIdAndSourceNameAndFetchedAtLessThanEqualOrderByFetchedAtDesc(
                anyString(), anyString(), any())).thenReturn(Optional.empty());
        lenient().when(reports.findWithinWindow(anyString(), any(), any())).thenReturn(List.of());
    }

    // ---------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------

    private static SourceConfig source(SourceType type, String name) {
        return new SourceConfig(type, name, "https://docs.example.com/" + name.toLowerCase(), null);
    }

    private static SaasProduct product(SourceConfig... sources) {
        SaasProduct product = new SaasProduct("Acme Billing", "Billing SaaS", List.of(sources), "admin");
        product.setId("product-1");
        return product;
    }

    private static RunSession session() {
        return new RunSession("run-1", "product-1", RunType.CUSTOM_RANGE);
    }

    private static Snapshot snapshot(String id, String sourceName, String content, Instant at) {
        Snapshot snapshot = new Snapshot("product-1", sourceName, SourceType.WEBSITE, content, List.of());
        snapshot.setId(id);
        snapshot.setFetchedAt(at);
        return snapshot;
    }

    /** Stubs the two window-end lookups for one source, which is the shape every compare test needs. */
    private void windowEnds(String sourceName, Snapshot atFrom, Snapshot atTo) {
        when(snapshots.findFirstBySaasProductIdAndSourceNameAndFetchedAtLessThanEqualOrderByFetchedAtDesc(
                "product-1", sourceName, JUNE_1)).thenReturn(Optional.ofNullable(atFrom));
        when(snapshots.findFirstBySaasProductIdAndSourceNameAndFetchedAtLessThanEqualOrderByFetchedAtDesc(
                "product-1", sourceName, JUNE_30)).thenReturn(Optional.ofNullable(atTo));
    }

    private static ChangeReport standardReport(Change... changes) {
        return new ChangeReport("product-1", "dana", AnalysisDepth.REGULAR, List.of(), "summary",
                List.of(changes));
    }

    private static Change change(String sourceName, String description) {
        return new Change(sourceName, SourceType.DOCS_MCP, ChangeCategory.FEATURE, description,
                Confidence.HIGH, null);
    }

    // ---------------------------------------------------------------------
    // validateRange
    // ---------------------------------------------------------------------

    @Test
    void anInvertedRangeIsRejectedBeforeAnyDatabaseWorkIsDone() {
        assertThatThrownBy(() -> loader.validateRange(product(), JUNE_30, JUNE_1))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("must not be after its end");
    }

    @Test
    void aRangeExtendingIntoTheFutureIsRejectedWithTheReasonRatherThanEmptyOutput() {
        Instant tomorrow = Instant.now().plusSeconds(86_400);

        assertThatThrownBy(() -> loader.validateRange(product(), JUNE_1, tomorrow))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("cannot extend into the future");
    }

    @Test
    void aRangeEndingAtTheEndOfTodayIsAcceptedBecauseThatIsWhatPickingTodayMeans() {
        when(snapshots.findFirstBySaasProductIdOrderByFetchedAtAsc("product-1"))
                .thenReturn(Optional.of(snapshot("s1", "Changelog", "text", JUNE_1)));

        // Exactly what CompareRequest.toInstant() produces for toDate = today, and the single most likely range a
        // user picks. Checked against now() instead of today it is "the future" for all but the last millisecond of
        // the day, so this passing is the difference between Compare working and Compare being unusable.
        Instant endOfToday = LocalDate.now(ZoneOffset.UTC)
                .plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusMillis(1);

        loader.validateRange(product(), JUNE_1, endOfToday);
    }

    @Test
    void aProductFirstRunTodayCanBeComparedOverToday() {
        Instant thisAfternoon = Instant.now();
        when(snapshots.findFirstBySaasProductIdOrderByFetchedAtAsc("product-1"))
                .thenReturn(Optional.of(snapshot("s1", "Changelog", "text", thisAfternoon)));

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant startOfToday = today.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant endOfToday = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusMillis(1);

        // Instant-granular, this is refused with "start the range on or after <today>" - advice the user has already
        // taken, since today is the date they picked. Both bounds and the data are on the same day, so it is fine.
        loader.validateRange(product(), startOfToday, endOfToday);
    }

    @Test
    void aProductThatHasNeverBeenRunIsToldToRunItRatherThanToldItHasNoData() {
        when(snapshots.findFirstBySaasProductIdOrderByFetchedAtAsc("product-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> loader.validateRange(product(), JUNE_1, JUNE_30))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("never been run")
                .hasMessageContaining("Run it at least twice");
    }

    @Test
    void aWindowStartingBeforeAllStoredDataNamesTheEarliestDateAvailable() {
        when(snapshots.findFirstBySaasProductIdOrderByFetchedAtAsc("product-1"))
                .thenReturn(Optional.of(snapshot("s1", "Changelog", "text", JUNE_20)));

        // "No data" leaves the user guessing where to drag the picker; naming the date turns a dead end into the next
        // action. This is the only reason findFirstBySaasProductIdOrderByFetchedAtAsc exists.
        assertThatThrownBy(() -> loader.validateRange(product(), JUNE_1, JUNE_30))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("no stored data for this product from before 1 Jun 2026")
                .hasMessageContaining("earliest data available is from 20 Jun 2026");
    }

    @Test
    void aWindowThatStartsExactlyAtTheEarliestSnapshotIsAccepted() {
        when(snapshots.findFirstBySaasProductIdOrderByFetchedAtAsc("product-1"))
                .thenReturn(Optional.of(snapshot("s1", "Changelog", "text", JUNE_1)));

        // The boundary is inclusive: rejecting a range that starts on the day the data starts would be an off-by-one
        // the user would experience as the date picker refusing a date it had just recommended.
        loader.validateRange(product(), JUNE_1, JUNE_30);
    }

    // ---------------------------------------------------------------------
    // Anchoring backwards
    // ---------------------------------------------------------------------

    @Test
    void bothEndsOfTheWindowResolveToTheMostRecentCaptureAtOrBeforeTheChosenDate() {
        SaasProduct product = product(source(SourceType.WEBSITE, "Changelog"));
        windowEnds("Changelog",
                snapshot("early", "Changelog", "May text", MAY_28),
                snapshot("late", "Changelog", "June text", JUNE_20));

        HistoryLoader.CompareData data = loader.load(product, JUNE_1, JUNE_30, session());

        // Erring forwards instead would pick up a capture taken after fromDate that may already contain the very
        // change the compare is meant to find, attributing it to the wrong window.
        assertThat(data.comparisons()).singleElement().satisfies(comparison -> {
            assertThat(comparison.priorText()).isEqualTo("May text");
            assertThat(comparison.priorFetchedAt()).isEqualTo(MAY_28);
            assertThat(comparison.currentText()).isEqualTo("June text");
            assertThat(comparison.currentFetchedAt()).isEqualTo(JUNE_20);
        });
    }

    @Test
    void theInclusionRecordsTheSnapshotDateUsedRatherThanTheDateAskedFor() {
        SaasProduct product = product(source(SourceType.WEBSITE, "Changelog"));
        windowEnds("Changelog",
                snapshot("early", "Changelog", "May text", MAY_28),
                snapshot("late", "Changelog", "June text", JUNE_20));

        HistoryLoader.CompareData data = loader.load(product, JUNE_1, JUNE_30, session());

        // A report claiming to be "as of 30 June" when its newest data is from the 20th is a small lie that compounds
        // across a timeline of reports.
        assertThat(data.inclusions()).extracting(SourceInclusion::fetchedAt).containsExactly(JUNE_20);
    }

    @Test
    void aSourceWithNoCaptureBeforeTheStartIsComparedAsAFirstLookRatherThanSkipped() {
        SaasProduct product = product(source(SourceType.WEBSITE, "Changelog"));
        windowEnds("Changelog", null, snapshot("late", "Changelog", "June text", JUNE_20));

        HistoryLoader.CompareData data = loader.load(product, JUNE_1, JUNE_30, session());

        // A source first captured inside the window has a real answer - "here is what it looked like by the end" -
        // and dropping it would lose a source from the report for no reason the reader could see.
        assertThat(data.comparisons()).singleElement().satisfies(comparison -> {
            assertThat(comparison.hasPrior()).isFalse();
            assertThat(comparison.currentText()).isEqualTo("June text");
        });
        assertThat(data.unavailable()).isEmpty();
    }

    // ---------------------------------------------------------------------
    // The two kinds of "cannot compare"
    // ---------------------------------------------------------------------

    @Test
    void aSourceWithNoDataInOrBeforeTheWindowIsAGenuineGap() {
        SaasProduct product = product(source(SourceType.WEBSITE, "Changelog"));
        windowEnds("Changelog", null, null);

        HistoryLoader.CompareData data = loader.load(product, JUNE_1, JUNE_30, session());

        assertThat(data.comparisons()).isEmpty();
        assertThat(data.unavailable()).singleElement().satisfies(failure ->
                assertThat(failure.reason()).contains("no snapshot of this source exists from on or before")
                        .contains("30 Jun 2026"));
        assertThat(data.unchanged()).isEmpty();
    }

    @Test
    void aSourceWhoseWindowContainsNoNewCaptureIsUnchangedRatherThanUnavailable() {
        SaasProduct product = product(source(SourceType.WEBSITE, "Changelog"));
        Snapshot same = snapshot("same", "Changelog", "May text", MAY_28);
        windowEnds("Changelog", same, same);

        HistoryLoader.CompareData data = loader.load(product, JUNE_1, JUNE_30, session());

        // Both ends resolved to the same document, so there is provably nothing to compare. Sending the same text
        // twice would spend half the prompt budget having the model confirm two identical blocks are identical.
        assertThat(data.comparisons()).isEmpty();
        assertThat(data.unavailable()).isEmpty();
        assertThat(data.unchanged()).singleElement().satisfies(failure ->
                assertThat(failure.reason()).contains("no new capture was taken")
                        .contains("unchanged from 28 May 2026"));
    }

    @Test
    void anUnchangedSourceIsStillListedAsIncludedBecauseItsStateIsKnown() {
        SaasProduct product = product(source(SourceType.WEBSITE, "Changelog"));
        Snapshot same = snapshot("same", "Changelog", "May text", MAY_28);
        windowEnds("Changelog", same, same);

        HistoryLoader.CompareData data = loader.load(product, JUNE_1, JUNE_30, session());

        // "We know this did not change" is a finding, not a gap, and the report should show the source was covered.
        assertThat(data.inclusions()).extracting(SourceInclusion::sourceName).containsExactly("Changelog");
    }

    @Test
    void bothKindsOfFailureReachThePromptBecauseTheInstructionForThemIsTheSame() {
        SaasProduct product = product(
                source(SourceType.WEBSITE, "Changelog"),
                source(SourceType.SAAS_URL, "App"));
        Snapshot same = snapshot("same", "Changelog", "May text", MAY_28);
        windowEnds("Changelog", same, same);
        windowEnds("App", null, null);

        HistoryLoader.CompareData data = loader.load(product, JUNE_1, JUNE_30, session());

        // Separate lists for the status tag, one list for the prompt: the model's instruction is "mention it in the
        // summary, do not report changes for it" in both cases.
        assertThat(data.promptFailures()).extracting(SourceFailure::sourceName)
                .containsExactlyInAnyOrder("App", "Changelog");
    }

    @Test
    void theClosingDetailCountsEachKindSeparatelySoTheLiveViewIsNotMisleading() {
        SaasProduct product = product(
                source(SourceType.WEBSITE, "Changelog"),
                source(SourceType.SAAS_URL, "App"),
                source(SourceType.WEBSITE, "Marketing"));
        windowEnds("Changelog",
                snapshot("early", "Changelog", "May", MAY_28),
                snapshot("late", "Changelog", "June", JUNE_20));
        Snapshot same = snapshot("same", "App", "unchanged", MAY_28);
        windowEnds("App", same, same);
        windowEnds("Marketing", null, null);

        RunSession session = session();
        RecordingSink sink = new RecordingSink();
        session.subscribe(sink);

        loader.load(product, JUNE_1, JUNE_30, session);

        assertThat(sink.details()).contains("1 comparable source, 1 unchanged in range, 1 with no data in range");
    }

    // ---------------------------------------------------------------------
    // Nothing is fetched
    // ---------------------------------------------------------------------

    @Test
    void aCompareWritesNoSnapshotAndTheOpeningDetailSaysNothingIsFetched() {
        SaasProduct product = product(source(SourceType.WEBSITE, "Changelog"));
        windowEnds("Changelog",
                snapshot("early", "Changelog", "May", MAY_28),
                snapshot("late", "Changelog", "June", JUNE_20));

        RunSession session = session();
        RecordingSink sink = new RecordingSink();
        session.subscribe(sink);

        loader.load(product, JUNE_1, JUNE_30, session);

        // The user is told this explicitly because "Compare" sitting next to "Run now" invites the assumption that it
        // also goes and looks, and the difference decides whether the answer is about June or about today.
        assertThat(sink.details()).first().asString()
                .contains("Reading stored snapshots between 1 Jun 2026 and 30 Jun 2026")
                .contains("fetches nothing new");
        verify(snapshots, never()).save(any(Snapshot.class));
    }

    // ---------------------------------------------------------------------
    // MCP history
    // ---------------------------------------------------------------------

    @Test
    void mcpHistoryComesFromWhatEarlierStandardRunsRecorded() {
        SaasProduct product = product(source(SourceType.DOCS_MCP, "Docs"));
        when(reports.findWithinWindow("product-1", JUNE_1, JUNE_30)).thenReturn(List.of(
                standardReport(change("Docs", "Bulk export documented"), change("Other", "unrelated")),
                standardReport(change("Docs", "Webhooks page added"))));

        HistoryLoader.CompareData data = loader.load(product, JUNE_1, JUNE_30, session());

        assertThat(data.mcpHistory()).singleElement().satisfies(history -> {
            assertThat(history.sourceName()).isEqualTo("Docs");
            assertThat(history.changes()).extracting(Change::description)
                    .containsExactly("Bulk export documented", "Webhooks page added");
        });
    }

    @Test
    void earlierCustomRangeReportsAreExcludedSoFindingsAreNotCountedTwice() {
        SaasProduct product = product(source(SourceType.DOCS_MCP, "Docs"));
        ChangeReport priorCompare = standardReport(change("Docs", "Bulk export documented"))
                .asCustomRange(JUNE_1, JUNE_30, true);
        when(reports.findWithinWindow("product-1", JUNE_1, JUNE_30)).thenReturn(List.of(
                priorCompare,
                standardReport(change("Docs", "Bulk export documented"))));

        HistoryLoader.CompareData data = loader.load(product, JUNE_1, JUNE_30, session());

        // An earlier compare over the same window was itself built by aggregating these standard runs, so counting it
        // would both double the finding and weight it more heavily purely because somebody had compared before.
        assertThat(data.mcpHistory()).singleElement()
                .satisfies(history -> assertThat(history.changes()).hasSize(1));
    }

    @Test
    void anMcpSourceNameIsMatchedCaseInsensitivelyBecauseTheModelWroteIt() {
        SaasProduct product = product(source(SourceType.DOCS_MCP, "Docs"));
        when(reports.findWithinWindow("product-1", JUNE_1, JUNE_30))
                .thenReturn(List.of(standardReport(change("docs", "Bulk export documented"))));

        HistoryLoader.CompareData data = loader.load(product, JUNE_1, JUNE_30, session());

        assertThat(data.mcpHistory()).singleElement()
                .satisfies(history -> assertThat(history.changes()).hasSize(1));
    }

    @Test
    void anMcpSourceWithNothingRecordedIsCarriedAsAnEmptyHistoryRatherThanOmitted() {
        SaasProduct product = product(source(SourceType.DOCS_MCP, "Docs"));

        HistoryLoader.CompareData data = loader.load(product, JUNE_1, JUNE_30, session());

        // Omitting it would let the model treat the source as unchanged, when the truth is that nobody looked.
        assertThat(data.mcpHistory()).extracting(McpChangeHistory::sourceName).containsExactly("Docs");
        assertThat(data.mcpHistory()).singleElement()
                .satisfies(history -> assertThat(history.changes()).isEmpty());
    }

    @Test
    void theCaveatIsSetByHavingAnyMcpSourceRatherThanByFindingAnything() {
        SaasProduct product = product(source(SourceType.DOCS_MCP, "Docs"));

        HistoryLoader.CompareData data = loader.load(product, JUNE_1, JUNE_30, session());

        // The limitation is a property of the method, not of the yield: an MCP server cannot be asked what it would
        // have said in June whether or not an earlier run happened to record something.
        assertThat(data.mcpHistoryLimited()).isTrue();
    }

    @Test
    void aProductWithNoMcpSourcesCarriesNoCaveatAtAll() {
        SaasProduct product = product(source(SourceType.WEBSITE, "Changelog"));
        windowEnds("Changelog",
                snapshot("early", "Changelog", "May", MAY_28),
                snapshot("late", "Changelog", "June", JUNE_20));

        HistoryLoader.CompareData data = loader.load(product, JUNE_1, JUNE_30, session());

        // Showing the caveat here would train users to ignore it on the reports where it matters.
        assertThat(data.mcpHistoryLimited()).isFalse();
        assertThat(data.mcpHistory()).isEmpty();
    }

    // ---------------------------------------------------------------------
    // Step narration
    // ---------------------------------------------------------------------

    @Test
    void theMcpStepOpensAndClosesEvenWhenThereAreNoMcpSources() {
        SaasProduct product = product(source(SourceType.WEBSITE, "Changelog"));
        windowEnds("Changelog",
                snapshot("early", "Changelog", "May", MAY_28),
                snapshot("late", "Changelog", "June", JUNE_20));

        RunSession session = session();
        RecordingSink sink = new RecordingSink();
        session.subscribe(sink);

        loader.load(product, JUNE_1, JUNE_30, session);

        // The live view draws a pill per step. Skipping the step leaves that pill drawn and never resolved, which
        // looks exactly like a stalled run; saying "none configured" costs one event and answers the question.
        assertThat(sink.details()).contains("This product has no MCP sources", "Nothing to aggregate");
    }

    @Test
    void eachStepClosesBeforeTheNextOneOpens() {
        SaasProduct product = product(
                source(SourceType.WEBSITE, "Changelog"),
                source(SourceType.DOCS_MCP, "Docs"));
        windowEnds("Changelog",
                snapshot("early", "Changelog", "May", MAY_28),
                snapshot("late", "Changelog", "June", JUNE_20));

        RunSession session = session();
        RecordingSink sink = new RecordingSink();
        session.subscribe(sink);

        loader.load(product, JUNE_1, JUNE_30, session);

        // A step that completes after its successor has already started reads as a bug in the run rather than as a
        // quirk of the emitter, so the ordering is part of the contract.
        List<String> steps = sink.received().stream().map(event -> event.type() + " " + event.step()).toList();
        assertThat(steps.indexOf("STEP_COMPLETED " + RunStep.LOADING_SNAPSHOTS.label()))
                .isLessThan(steps.indexOf("STEP_STARTED " + RunStep.AGGREGATING_MCP_HISTORY.label()));
    }

    @Test
    void perSourceProgressNamesTheSourceAndTheDatesActuallyUsed() {
        SaasProduct product = product(source(SourceType.WEBSITE, "Changelog"));
        windowEnds("Changelog",
                snapshot("early", "Changelog", "May", MAY_28),
                snapshot("late", "Changelog", "June", JUNE_20));

        RunSession session = session();
        RecordingSink sink = new RecordingSink();
        session.subscribe(sink);

        loader.load(product, JUNE_1, JUNE_30, session);

        assertThat(sink.details()).anySatisfy(detail -> assertThat(detail)
                .contains("Loading Changelog snapshot for docs.example.com")
                .contains("as of 20 Jun 2026")
                .contains("compared against 28 May 2026"));
    }

    @Test
    void theSnapshotLookupIsBoundedToTheProductSoOneProductCannotReadAnothersHistory() {
        SaasProduct product = product(source(SourceType.WEBSITE, "Changelog"));
        windowEnds("Changelog",
                snapshot("early", "Changelog", "May", MAY_28),
                snapshot("late", "Changelog", "June", JUNE_20));

        loader.load(product, JUNE_1, JUNE_30, session());

        verify(snapshots, times(2))
                .findFirstBySaasProductIdAndSourceNameAndFetchedAtLessThanEqualOrderByFetchedAtDesc(
                        eq("product-1"), eq("Changelog"), any());
    }

    @Test
    void theAssembledDataCannotBeChangedAfterwards() {
        HistoryLoader.CompareData data = new HistoryLoader.CompareData(
                new ArrayList<SourceComparison>(), new ArrayList<>(),
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), false);

        assertThat(data.comparisons()).isUnmodifiable();
        assertThat(data.unavailable()).isUnmodifiable();
        assertThat(data.unchanged()).isUnmodifiable();
        assertThat(data.inclusions()).isUnmodifiable();
        assertThat(data.mcpHistory()).isUnmodifiable();
    }
}
