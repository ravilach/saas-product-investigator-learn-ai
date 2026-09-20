package com.saasinvestigator.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saasinvestigator.audit.AuditAction;
import com.saasinvestigator.audit.AuditService;
import com.saasinvestigator.error.NotFoundException;
import com.saasinvestigator.error.ProviderUnavailableException;
import com.saasinvestigator.llm.GeneratedReport;
import com.saasinvestigator.llm.LlmProvider;
import com.saasinvestigator.llm.LlmProviderResolver;
import com.saasinvestigator.llm.LlmProviderType;
import com.saasinvestigator.llm.McpSourceRef;
import com.saasinvestigator.llm.RunContext;
import com.saasinvestigator.llm.SourceComparison;
import com.saasinvestigator.llm.SourceFailure;
import com.saasinvestigator.product.SaasProduct;
import com.saasinvestigator.product.SaasProductRepository;
import com.saasinvestigator.product.SourceConfig;
import com.saasinvestigator.product.SourceConfigMapper;
import com.saasinvestigator.product.SourceType;
import com.saasinvestigator.report.AnalysisDepth;
import com.saasinvestigator.report.Change;
import com.saasinvestigator.report.ChangeCategory;
import com.saasinvestigator.report.ChangeReport;
import com.saasinvestigator.report.ChangeReportRepository;
import com.saasinvestigator.report.Confidence;
import com.saasinvestigator.report.RunType;
import com.saasinvestigator.report.SourceInclusion;
import com.saasinvestigator.security.AuthenticatedUser;
import com.saasinvestigator.user.Role;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Tests the two analysis flows end to end, with the provider and the executor replaced.
 *
 * <p>The {@link RunExecutor} is stubbed to run submitted work on the calling thread, which makes an otherwise
 * asynchronous flow assertable in one place. That is a deliberate trade: it means these tests say nothing about
 * threading - {@code RunExecutorTest} and {@code RunSessionTest} cover that - and everything about the ordering and
 * bookkeeping that would be invisible in an integration test.
 *
 * <p>Three orderings are asserted here because each one, if reversed, produces a working system that is quietly wrong:
 *
 * <ul>
 *   <li>The audit entry is written before the run is submitted, so a run rejected by a full queue is still recorded as
 *       attempted. An audit log that only contains successful actions cannot answer the question it exists for.</li>
 *   <li>{@code lastRunAt} is read before this run's own report is saved. Afterwards, "the previous run" would be this
 *       one, and the model would be asked what changed since a moment that has not happened yet.</li>
 *   <li>The range is validated before anything is audited or opened, so a bad date range is a {@code 400} with the
 *       earliest available date in it rather than a {@code 202} followed by a failure event.</li>
 * </ul>
 */
class RunOrchestratorTest {

    private static final AuthenticatedUser DANA =
            new AuthenticatedUser("id-dana", "dana", "Dana Reyes", Role.READ_ONLY);
    private static final Instant LAST_RUN = Instant.parse("2026-09-13T09:00:00Z");
    private static final Instant JUNE_1 = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant JUNE_30 = Instant.parse("2026-06-30T23:59:59Z");

    private SaasProductRepository products;
    private ChangeReportRepository reports;
    private SourceFetcher sourceFetcher;
    private HistoryLoader historyLoader;
    private LlmProviderResolver providers;
    private SourceConfigMapper sourceMapper;
    private RunEventStream eventStream;
    private RunExecutor executor;
    private RunMetrics metrics;
    private RunRecorder recorder;
    private AuditService audit;
    private LlmProvider provider;
    private RunOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        products = mock(SaasProductRepository.class);
        reports = mock(ChangeReportRepository.class);
        sourceFetcher = mock(SourceFetcher.class);
        historyLoader = mock(HistoryLoader.class);
        providers = mock(LlmProviderResolver.class);
        sourceMapper = mock(SourceConfigMapper.class);
        eventStream = mock(RunEventStream.class);
        executor = mock(RunExecutor.class);
        metrics = mock(RunMetrics.class);
        recorder = mock(RunRecorder.class);
        audit = mock(AuditService.class);
        provider = mock(LlmProvider.class);

        orchestrator = new RunOrchestrator(products, reports, sourceFetcher, historyLoader, providers, sourceMapper,
                eventStream, executor, metrics, recorder, audit);

        // Runs execute inline, so a single test method can assert on what the whole flow did.
        doAnswer(call -> {
            call.getArgument(0, Runnable.class).run();
            return null;
        }).when(executor).submitRun(any());

        lenient().when(eventStream.open(anyString(), any()))
                .thenAnswer(call -> new RunSession("run-1", call.getArgument(0), call.getArgument(1)));
        lenient().when(providers.resolve(anyString())).thenReturn(
                new LlmProviderResolver.ResolvedProvider(provider,
                        com.saasinvestigator.credential.CredentialSource.PERSONAL));
        lenient().when(provider.type()).thenReturn(LlmProviderType.ANTHROPIC);
        lenient().when(provider.generateChangeReport(any(), any()))
                .thenReturn(new GeneratedReport("Two changes.", List.of(aChange())));
        lenient().when(reports.save(any(ChangeReport.class))).thenAnswer(call -> call.getArgument(0));
        lenient().when(reports.findFirstBySaasProductIdOrderByRunAtDesc(anyString())).thenReturn(Optional.empty());
        lenient().when(sourceMapper.decryptAuthToken(any())).thenReturn(Optional.empty());
        lenient().when(sourceFetcher.fetchAll(any(), any())).thenReturn(fetchOutcome(List.of(), List.of()));
        lenient().when(historyLoader.load(any(), any(), any(), any())).thenReturn(compareData(List.of(), List.of()));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(DANA, "n/a", List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------

    private static Change aChange() {
        return new Change("Changelog", SourceType.WEBSITE, ChangeCategory.FEATURE, "Bulk export shipped",
                Confidence.HIGH, null);
    }

    private static SourceConfig source(SourceType type, String name) {
        return new SourceConfig(type, name, "https://example.com/" + name.toLowerCase(), null);
    }

    private static SourceComparison comparison(String sourceName) {
        return new SourceComparison(sourceName, SourceType.WEBSITE, "old", LAST_RUN, "new", Instant.now());
    }

    private SaasProduct given(SourceConfig... sources) {
        SaasProduct product = new SaasProduct("Acme Billing", "Billing SaaS", List.of(sources), "admin");
        product.setId("product-1");
        when(products.findById("product-1")).thenReturn(Optional.of(product));
        return product;
    }

    private static SourceFetcher.FetchOutcome fetchOutcome(List<SourceComparison> comparisons,
                                                           List<SourceFailure> failures) {
        List<SourceInclusion> inclusions = comparisons.stream()
                .map(comparison -> new SourceInclusion(comparison.sourceName(), comparison.sourceType(),
                        comparison.currentFetchedAt()))
                .toList();
        return new SourceFetcher.FetchOutcome(comparisons, failures, inclusions);
    }

    private static HistoryLoader.CompareData compareData(List<SourceComparison> comparisons,
                                                         List<SourceFailure> unavailable) {
        List<SourceInclusion> inclusions = comparisons.stream()
                .map(comparison -> new SourceInclusion(comparison.sourceName(), comparison.sourceType(),
                        comparison.currentFetchedAt()))
                .toList();
        return new HistoryLoader.CompareData(comparisons, unavailable, List.of(), inclusions, List.of(), false);
    }

    /** @return the context handed to the provider, which is where most of this class's decisions become visible */
    private RunContext capturedContext() {
        ArgumentCaptor<RunContext> captor = ArgumentCaptor.forClass(RunContext.class);
        verify(provider).generateChangeReport(captor.capture(), any());
        return captor.getValue();
    }

    private ChangeReport savedReport() {
        ArgumentCaptor<ChangeReport> captor = ArgumentCaptor.forClass(ChangeReport.class);
        verify(reports).save(captor.capture());
        return captor.getValue();
    }

    // ---------------------------------------------------------------------
    // Starting a run
    // ---------------------------------------------------------------------

    @Test
    void anUnknownProductIsA404BeforeAnythingIsAuditedOrOpened() {
        when(products.findById("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orchestrator.startRun("nope", null))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("No SaaS product with id nope");

        verify(audit, never()).log(any(), anyString(), anyString(), any());
        verify(eventStream, never()).open(anyString(), any());
    }

    @Test
    void theTriggerIsAuditedBeforeTheRunIsSubmitted() {
        given(source(SourceType.WEBSITE, "Changelog"));
        when(sourceFetcher.fetchAll(any(), any())).thenReturn(fetchOutcome(List.of(comparison("Changelog")),
                List.of()));

        orchestrator.startRun("product-1", AnalysisDepth.NUCLEAR);

        // A run rejected by a full queue must still appear in the audit log as attempted; an audit log that only
        // records successes cannot answer the question it exists for.
        InOrder order = inOrder(audit, executor);
        order.verify(audit).log(eq(AuditAction.PRODUCT_RUN_TRIGGERED), eq("saas_product"), eq("product-1"), any());
        order.verify(executor).submitRun(any());
    }

    @Test
    void theRunIdReturnedIsTheSessionsSoTheClientCanSubscribeToIt() {
        given(source(SourceType.WEBSITE, "Changelog"));

        assertThat(orchestrator.startRun("product-1", null)).isEqualTo("run-1");
        verify(eventStream).open("product-1", RunType.STANDARD);
    }

    @Test
    void aRequestWithNoDepthUsesTheDefaultRatherThanFailing() {
        given(source(SourceType.WEBSITE, "Changelog"));
        when(sourceFetcher.fetchAll(any(), any()))
                .thenReturn(fetchOutcome(List.of(comparison("Changelog")), List.of()));

        orchestrator.startRun("product-1", null);

        // The endpoint accepts no body at all, so null is the common case rather than an edge case.
        assertThat(capturedContext().analysisDepth()).isEqualTo(AnalysisDepth.DEFAULT);
    }

    @Test
    void aSessionIsDiscardedWhenTheServerIsTooBusyToAcceptTheRun() {
        given(source(SourceType.WEBSITE, "Changelog"));
        doThrow(new ProviderUnavailableException("too busy")).when(executor).submitRun(any());

        assertThatThrownBy(() -> orchestrator.startRun("product-1", null))
                .isInstanceOf(ProviderUnavailableException.class);

        // Without the discard, the caller would have been handed a runId for a run that was never scheduled and would
        // watch an open stream for the full 45-minute session ceiling.
        verify(eventStream).discard("run-1");
    }

    // ---------------------------------------------------------------------
    // Read before write
    // ---------------------------------------------------------------------

    @Test
    void thePreviousRunsTimestampIsReadBeforeThisRunsReportIsSaved() {
        given(source(SourceType.WEBSITE, "Changelog"));
        when(sourceFetcher.fetchAll(any(), any()))
                .thenReturn(fetchOutcome(List.of(comparison("Changelog")), List.of()));

        orchestrator.startRun("product-1", null);

        // Afterwards, "the previous run" would be this one, and the model would be asked what changed since a moment
        // that has not happened yet.
        InOrder order = inOrder(reports);
        order.verify(reports).findFirstBySaasProductIdOrderByRunAtDesc("product-1");
        order.verify(reports).save(any(ChangeReport.class));
    }

    @Test
    void thePreviousRunsTimestampReachesThePromptContext() {
        given(source(SourceType.WEBSITE, "Changelog"));
        ChangeReport previous = new ChangeReport("product-1", "dana", AnalysisDepth.REGULAR, List.of(), "s",
                List.of());
        when(reports.findFirstBySaasProductIdOrderByRunAtDesc("product-1")).thenReturn(Optional.of(previous));
        when(sourceFetcher.fetchAll(any(), any()))
                .thenReturn(fetchOutcome(List.of(comparison("Changelog")), List.of()));

        orchestrator.startRun("product-1", null);

        // "Changed since Tuesday" and "changed since March" are different claims, so the date is part of the prompt
        // rather than something the model is left to infer from the snapshot timestamps.
        assertThat(capturedContext().lastRunAt()).isEqualTo(previous.getRunAt());
    }

    @Test
    void aFirstEverRunCarriesNoPreviousRunDate() {
        given(source(SourceType.WEBSITE, "Changelog"));
        when(sourceFetcher.fetchAll(any(), any()))
                .thenReturn(fetchOutcome(List.of(comparison("Changelog")), List.of()));

        orchestrator.startRun("product-1", null);

        assertThat(capturedContext().lastRunAt()).isNull();
    }

    // ---------------------------------------------------------------------
    // Outcomes and metrics
    // ---------------------------------------------------------------------

    @Test
    void aRunWithEverySourceReadIsRecordedAsASuccess() {
        given(source(SourceType.WEBSITE, "Changelog"));
        when(sourceFetcher.fetchAll(any(), any()))
                .thenReturn(fetchOutcome(List.of(comparison("Changelog")), List.of()));

        orchestrator.startRun("product-1", AnalysisDepth.SHORT);

        verify(metrics).recordRun(eq("Acme Billing"), eq(RunType.STANDARD), eq(AnalysisDepth.SHORT),
                eq(RunOutcome.SUCCESS), any(Duration.class));
    }

    @Test
    void aRunWithOneUnreadableSourceIsPartialRatherThanSuccessOrFailure() {
        given(source(SourceType.WEBSITE, "Changelog"), source(SourceType.SAAS_URL, "App"));
        when(sourceFetcher.fetchAll(any(), any())).thenReturn(fetchOutcome(
                List.of(comparison("Changelog")),
                List.of(new SourceFailure("App", SourceType.SAAS_URL, "unreachable"))));

        orchestrator.startRun("product-1", null);

        // A report was produced, so this is not a failure; a source was missed, so it is not a clean success either.
        // Collapsing the three into two would either page someone for one flaky page or stay green for a week while a
        // product's most important source was gone.
        verify(metrics).recordRun(anyString(), eq(RunType.STANDARD), any(), eq(RunOutcome.PARTIAL),
                any(Duration.class));
    }

    @Test
    void aRunThatProducedNoReportIsRecordedAsAFailureRatherThanNotAtAll() {
        given(source(SourceType.WEBSITE, "Changelog"));
        when(sourceFetcher.fetchAll(any(), any()))
                .thenReturn(fetchOutcome(List.of(comparison("Changelog")), List.of()));
        when(provider.generateChangeReport(any(), any())).thenThrow(new IllegalStateException("boom"));

        orchestrator.startRun("product-1", null);

        // Recorded from a finally block, so a completely broken instance does not look idle on the dashboard.
        verify(metrics).recordRun(anyString(), any(), any(), eq(RunOutcome.FAILURE), any(Duration.class));
        verify(reports, never()).save(any(ChangeReport.class));
    }

    // ---------------------------------------------------------------------
    // Terminal events and error messages
    // ---------------------------------------------------------------------

    @Test
    void aSuccessfulRunEndsWithTheSavedReportOnTheStream() {
        given(source(SourceType.WEBSITE, "Changelog"));
        when(sourceFetcher.fetchAll(any(), any()))
                .thenReturn(fetchOutcome(List.of(comparison("Changelog")), List.of()));
        RunSession session = new RunSession("run-1", "product-1", RunType.STANDARD);
        when(eventStream.open(anyString(), any())).thenReturn(session);
        RecordingSink sink = new RecordingSink();
        session.subscribe(sink);

        orchestrator.startRun("product-1", null);

        assertThat(sink.types()).endsWith(RunEventType.RUN_COMPLETED);
        assertThat(sink.received().getLast().report()).isNotNull();
    }

    @Test
    void anUnexpectedFailureGivesTheUserAGenericReasonAndNeverTheExceptionMessage() {
        given(source(SourceType.WEBSITE, "Changelog"));
        when(sourceFetcher.fetchAll(any(), any()))
                .thenReturn(fetchOutcome(List.of(comparison("Changelog")), List.of()));
        when(provider.generateChangeReport(any(), any()))
                .thenThrow(new IllegalStateException("Bearer sk-ant-api03-secret leaked in a request body"));
        RunSession session = new RunSession("run-1", "product-1", RunType.STANDARD);
        when(eventStream.open(anyString(), any())).thenReturn(session);
        RecordingSink sink = new RecordingSink();
        session.subscribe(sink);

        orchestrator.startRun("product-1", null);

        // An SDK exception message can contain the request body, which here means crawled page content and MCP
        // authorization tokens. The real detail goes to the log and only to the log.
        assertThat(sink.received().getLast().detail())
                .isEqualTo("The analysis failed because of an unexpected error. The server log has the details.")
                .doesNotContain("sk-ant-api03");
    }

    @Test
    void aProductWithNoSourcesAtAllIsToldToAddOneRatherThanGivenAnEmptyReport() {
        given();
        RunSession session = new RunSession("run-1", "product-1", RunType.STANDARD);
        when(eventStream.open(anyString(), any())).thenReturn(session);
        RecordingSink sink = new RecordingSink();
        session.subscribe(sink);

        orchestrator.startRun("product-1", null);

        assertThat(sink.received().getLast().detail())
                .contains("no sources configured")
                .contains("Add at least one website, SaaS app URL, or MCP server");
        verify(provider, never()).generateChangeReport(any(), any());
    }

    @Test
    void aRunWhereEverySourceFailedNamesTheSourcesRatherThanSayingNothingToAnalyse() {
        given(source(SourceType.WEBSITE, "Changelog"), source(SourceType.SAAS_URL, "App"));
        when(sourceFetcher.fetchAll(any(), any())).thenReturn(fetchOutcome(List.of(), List.of(
                new SourceFailure("Changelog", SourceType.WEBSITE, "503"),
                new SourceFailure("App", SourceType.SAAS_URL, "timeout"))));
        RunSession session = new RunSession("run-1", "product-1", RunType.STANDARD);
        when(eventStream.open(anyString(), any())).thenReturn(session);
        RecordingSink sink = new RecordingSink();
        session.subscribe(sink);

        orchestrator.startRun("product-1", null);

        // Calling the provider with nothing but failures would spend money to be told there is nothing to report.
        assertThat(sink.received().getLast().detail())
                .contains("None of this product's sources could be read (Changelog, App)");
        verify(provider, never()).generateChangeReport(any(), any());
    }

    // ---------------------------------------------------------------------
    // What the report records
    // ---------------------------------------------------------------------

    @Test
    void theReportIsAttributedToTheUserWhoTriggeredItRatherThanToTheRunThread() {
        given(source(SourceType.WEBSITE, "Changelog"));
        when(sourceFetcher.fetchAll(any(), any()))
                .thenReturn(fetchOutcome(List.of(comparison("Changelog")), List.of()));

        orchestrator.startRun("product-1", null);

        // CurrentUser reads a thread-local that exists only on the request thread, so the actor has to be captured
        // and handed over. Getting this wrong does not throw - it silently records runs as having no actor.
        assertThat(savedReport().getRunBy()).isEqualTo("dana");
    }

    @Test
    void anMcpSourceIsListedAsIncludedWithNoFetchTimeAtAll() {
        given(source(SourceType.WEBSITE, "Changelog"), source(SourceType.DOCS_MCP, "Docs"));
        when(sourceFetcher.fetchAll(any(), any()))
                .thenReturn(fetchOutcome(List.of(comparison("Changelog")), List.of()));

        orchestrator.startRun("product-1", null);

        // Null is a statement rather than a missing value: the backend never held that source's content and cannot
        // claim when it was current. A timestamp there would be this process's clock posing as a freshness guarantee.
        assertThat(savedReport().getSourcesIncluded())
                .anySatisfy(inclusion -> {
                    assertThat(inclusion.sourceName()).isEqualTo("Docs");
                    assertThat(inclusion.fetchedAt()).isNull();
                })
                .anySatisfy(inclusion -> {
                    assertThat(inclusion.sourceName()).isEqualTo("Changelog");
                    assertThat(inclusion.fetchedAt()).isNotNull();
                });
    }

    @Test
    void aStandardRunDeclaresItsMcpServersToTheModelAsTools() {
        given(source(SourceType.WEBSITE, "Changelog"), source(SourceType.DOCS_MCP, "Docs"));
        when(sourceFetcher.fetchAll(any(), any()))
                .thenReturn(fetchOutcome(List.of(comparison("Changelog")), List.of()));

        orchestrator.startRun("product-1", null);

        assertThat(capturedContext().mcpSources()).extracting(McpSourceRef::name).containsExactly("Docs");
    }

    @Test
    void aStandardRunIsSavedAsAStandardRunWithNoRangeAndNoCaveat() {
        given(source(SourceType.WEBSITE, "Changelog"));
        when(sourceFetcher.fetchAll(any(), any()))
                .thenReturn(fetchOutcome(List.of(comparison("Changelog")), List.of()));

        orchestrator.startRun("product-1", null);

        ChangeReport saved = savedReport();
        assertThat(saved.getRunType()).isEqualTo(RunType.STANDARD);
        assertThat(saved.getRangeFrom()).isNull();
        assertThat(saved.isMcpHistoryLimited()).isFalse();
    }

    // ---------------------------------------------------------------------
    // Compare
    // ---------------------------------------------------------------------

    @Test
    void theRangeIsValidatedBeforeAnythingIsAuditedOrOpened() {
        given(source(SourceType.WEBSITE, "Changelog"));
        doThrow(new com.saasinvestigator.error.BadRequestException("earliest data is from 20 Jun 2026"))
                .when(historyLoader).validateRange(any(), any(), any());

        assertThatThrownBy(() -> orchestrator.startCompare("product-1", JUNE_1, JUNE_30, null))
                .isInstanceOf(com.saasinvestigator.error.BadRequestException.class);

        // A 400 naming the earliest available date is a better answer than a 202 followed seconds later by a failure
        // event the user has to open the live view to read.
        verify(audit, never()).log(any(), anyString(), anyString(), any());
        verify(eventStream, never()).open(anyString(), any());
        verify(executor, never()).submitRun(any());
    }

    @Test
    void aCompareNeverDeclaresMcpServersToTheModel() {
        given(source(SourceType.WEBSITE, "Changelog"), source(SourceType.DOCS_MCP, "Docs"));
        when(historyLoader.load(any(), any(), any(), any()))
                .thenReturn(compareData(List.of(comparison("Changelog")), List.of()));

        orchestrator.startCompare("product-1", JUNE_1, JUNE_30, null);

        // The load-bearing line of the compare flow. Declaring them would have the model report today's state inside
        // a report labelled with two dates in June, and nothing in the output would reveal the substitution.
        assertThat(capturedContext().mcpSources()).isEmpty();
        verify(sourceMapper, never()).decryptAuthToken(any());
        verify(sourceFetcher, never()).fetchAll(any(), any());
    }

    @Test
    void aCompareIsSavedWithItsRangeAndItsMcpCaveat() {
        given(source(SourceType.WEBSITE, "Changelog"), source(SourceType.DOCS_MCP, "Docs"));
        when(historyLoader.load(any(), any(), any(), any())).thenReturn(new HistoryLoader.CompareData(
                List.of(comparison("Changelog")), List.of(), List.of(),
                List.of(new SourceInclusion("Changelog", SourceType.WEBSITE, LAST_RUN)), List.of(), true));

        orchestrator.startCompare("product-1", JUNE_1, JUNE_30, AnalysisDepth.NUCLEAR);

        ChangeReport saved = savedReport();
        assertThat(saved.getRunType()).isEqualTo(RunType.CUSTOM_RANGE);
        assertThat(saved.getRangeFrom()).isEqualTo(JUNE_1);
        assertThat(saved.getRangeTo()).isEqualTo(JUNE_30);
        assertThat(saved.isMcpHistoryLimited()).isTrue();
    }

    @Test
    void aCompareWithASourceMissingFromTheWindowIsPartial() {
        given(source(SourceType.WEBSITE, "Changelog"), source(SourceType.SAAS_URL, "App"));
        when(historyLoader.load(any(), any(), any(), any())).thenReturn(compareData(
                List.of(comparison("Changelog")),
                List.of(new SourceFailure("App", SourceType.SAAS_URL, "no snapshot in range"))));

        orchestrator.startCompare("product-1", JUNE_1, JUNE_30, null);

        verify(metrics).recordRun(anyString(), eq(RunType.CUSTOM_RANGE), any(), eq(RunOutcome.PARTIAL),
                any(Duration.class));
    }

    @Test
    void aCompareWhoseOnlyOmissionIsAnUnchangedSourceIsStillACleanSuccess() {
        given(source(SourceType.WEBSITE, "Changelog"), source(SourceType.SAAS_URL, "App"));
        when(historyLoader.load(any(), any(), any(), any())).thenReturn(new HistoryLoader.CompareData(
                List.of(comparison("Changelog")),
                List.of(),
                List.of(new SourceFailure("App", SourceType.SAAS_URL, "no new capture was taken")),
                List.of(), List.of(), false));

        orchestrator.startCompare("product-1", JUNE_1, JUNE_30, null);

        // "Nothing new was captured for this source in this window" is the system working correctly. Counting it as
        // partial would mark almost every compare partial and make the metric useless.
        verify(metrics).recordRun(anyString(), eq(RunType.CUSTOM_RANGE), any(), eq(RunOutcome.SUCCESS),
                any(Duration.class));
    }

    @Test
    void theCompareTriggerIsAuditedWithItsRangeAsPlainDates() {
        given(source(SourceType.WEBSITE, "Changelog"));
        when(historyLoader.load(any(), any(), any(), any()))
                .thenReturn(compareData(List.of(comparison("Changelog")), List.of()));

        orchestrator.startCompare("product-1", JUNE_1, JUNE_30, AnalysisDepth.SHORT);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(audit).log(eq(AuditAction.PRODUCT_COMPARE_TRIGGERED), eq("saas_product"), eq("product-1"),
                captor.capture());
        assertThat(captor.getValue())
                .containsEntry("productName", "Acme Billing")
                .containsEntry("analysisDepth", "SHORT")
                .containsEntry("rangeFrom", JUNE_1.toString())
                .containsEntry("rangeTo", JUNE_30.toString());
    }

    @Test
    void auditDetailsCarryNamesAndCountsAndNothingThatCouldBeASecret() {
        SourceConfig mcp = source(SourceType.DOCS_MCP, "Docs");
        mcp.setAuthTokenEncrypted("ciphertext");
        given(source(SourceType.WEBSITE, "Changelog"), mcp);
        when(sourceFetcher.fetchAll(any(), any()))
                .thenReturn(fetchOutcome(List.of(comparison("Changelog")), List.of()));

        orchestrator.startRun("product-1", null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(audit).log(eq(AuditAction.PRODUCT_RUN_TRIGGERED), anyString(), anyString(), captor.capture());
        assertThat(captor.getValue()).containsOnlyKeys("productName", "analysisDepth", "sourceCount");
        assertThat(captor.getValue().values().toString()).doesNotContain("ciphertext");
    }
}
