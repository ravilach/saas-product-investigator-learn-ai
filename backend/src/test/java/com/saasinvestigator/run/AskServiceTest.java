package com.saasinvestigator.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saasinvestigator.audit.AuditAction;
import com.saasinvestigator.audit.AuditService;
import com.saasinvestigator.credential.CredentialSource;
import com.saasinvestigator.error.NotFoundException;
import com.saasinvestigator.error.ProviderUnavailableException;
import com.saasinvestigator.llm.AskContext;
import com.saasinvestigator.llm.LlmProvider;
import com.saasinvestigator.llm.LlmProviderResolver;
import com.saasinvestigator.llm.LlmProviderType;
import com.saasinvestigator.llm.TokenSink;
import com.saasinvestigator.product.SaasProduct;
import com.saasinvestigator.product.SaasProductRepository;
import com.saasinvestigator.product.SourceConfig;
import com.saasinvestigator.product.SourceType;
import com.saasinvestigator.report.AnalysisDepth;
import com.saasinvestigator.report.ChangeReport;
import com.saasinvestigator.report.ChangeReportRepository;
import com.saasinvestigator.security.AuthenticatedUser;
import com.saasinvestigator.snapshot.Snapshot;
import com.saasinvestigator.snapshot.SnapshotRepository;
import com.saasinvestigator.user.Role;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Tests the four things an ask must not do, and the one thing it must.
 *
 * <p>The restrictions are the substance: an ask fetches nothing, crawls nothing, calls no MCP server and persists
 * nothing. Each of those is a one-line temptation away from being violated - a call to the fetcher here would make
 * answers fresher, and saving the answer would make the history richer - and each would change what the endpoint
 * costs and means. A query bar is typed into casually and repeatedly, so an ask that crawled would turn an idle
 * question into minutes of somebody else's bandwidth, and an ask that persisted would fill the history timeline with
 * rows no user asked to create. The assertions below are mostly {@code never()} verifications for that reason.
 *
 * <p>The one thing it must do is finish its stream on every path, including the path where the user closed the tab.
 * A stream left open holds a servlet async context until the container times it out, so "the client hung up" has to
 * be handled as a normal ending rather than as an error.
 *
 * <p>The executor is stubbed to capture the submitted task rather than to run it, because two tests need to act on
 * the returned emitter before the answer is produced.
 */
class AskServiceTest {

    private static final AuthenticatedUser DANA =
            new AuthenticatedUser("id-dana", "dana", "Dana Reyes", Role.READ_ONLY);
    private static final Instant JUNE_1 = Instant.parse("2026-06-01T10:00:00Z");
    private static final Instant JUNE_20 = Instant.parse("2026-06-20T10:00:00Z");

    private SaasProductRepository products;
    private SnapshotRepository snapshots;
    private ChangeReportRepository reports;
    private LlmProviderResolver providers;
    private RunExecutor executor;
    private RunMetrics metrics;
    private AuditService audit;
    private LlmProvider provider;
    private AskService service;

    /** The task the service handed to the ask pool, so a test can choose when - or whether - it runs. */
    private final AtomicReference<Runnable> submitted = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        products = mock(SaasProductRepository.class);
        snapshots = mock(SnapshotRepository.class);
        reports = mock(ChangeReportRepository.class);
        providers = mock(LlmProviderResolver.class);
        executor = mock(RunExecutor.class);
        metrics = mock(RunMetrics.class);
        audit = mock(AuditService.class);
        provider = mock(LlmProvider.class);

        service = new AskService(products, snapshots, reports, providers, executor, metrics, audit);

        doAnswer(call -> {
            submitted.set(call.getArgument(0, Runnable.class));
            return null;
        }).when(executor).submitAsk(any());

        lenient().when(providers.resolve(anyString())).thenReturn(
                new LlmProviderResolver.ResolvedProvider(provider, CredentialSource.PERSONAL));
        lenient().when(provider.type()).thenReturn(LlmProviderType.ANTHROPIC);
        lenient().when(provider.answerQuestion(any(), any())).thenReturn("Bulk export shipped on 3 June.");
        lenient().when(snapshots.findFirstBySaasProductIdAndSourceNameOrderByFetchedAtDesc(anyString(), anyString()))
                .thenReturn(Optional.empty());
        lenient().when(reports.findFirstBySaasProductIdOrderByRunAtDesc(anyString())).thenReturn(Optional.empty());

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

    private static SourceConfig source(SourceType type, String name) {
        return new SourceConfig(type, name, "https://example.com/" + name.toLowerCase(), null);
    }

    private SaasProduct given(SourceConfig... sources) {
        SaasProduct product = new SaasProduct("Acme Billing", "Billing SaaS", List.of(sources), "admin");
        product.setId("product-1");
        when(products.findById("product-1")).thenReturn(Optional.of(product));
        return product;
    }

    private void storedSnapshot(String sourceName, SourceType type, String text, Instant fetchedAt) {
        Snapshot snapshot = new Snapshot("product-1", sourceName, type, text,
                List.of("https://example.com/" + sourceName.toLowerCase()));
        snapshot.setFetchedAt(fetchedAt);
        when(snapshots.findFirstBySaasProductIdAndSourceNameOrderByFetchedAtDesc("product-1", sourceName))
                .thenReturn(Optional.of(snapshot));
    }

    /** Runs the captured ask task, which is everything that would happen on the ask pool thread. */
    private void runAnswer() {
        Runnable task = submitted.get();
        assertThat(task).as("no task was submitted to the ask pool").isNotNull();
        task.run();
    }

    private AskContext capturedContext() {
        ArgumentCaptor<AskContext> captor = ArgumentCaptor.forClass(AskContext.class);
        verify(provider).answerQuestion(captor.capture(), any());
        return captor.getValue();
    }

    // ---------------------------------------------------------------------
    // What happens on the request thread
    // ---------------------------------------------------------------------

    @Test
    void aQuestionAboutAProductThatDoesNotExistIsA404RatherThanAnErrorEventInsideA200() {
        when(products.findById("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.ask("nope", "What changed?"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("No SaaS product with id nope");

        // The lookup is deliberately on the request thread: once the response is a 200 with an open stream, there is
        // no status code left to say "that product is gone".
        verify(executor, never()).submitAsk(any());
        verify(audit, never()).log(any(), anyString(), anyString(), any());
    }

    @Test
    void theQuestionIsAuditedWithTheUsersOwnWording() {
        given(source(SourceType.WEBSITE, "Changelog"));

        service.ask("product-1", "Did pricing change this month?");

        verify(audit).log(eq(AuditAction.PRODUCT_ASK_SUBMITTED), eq("saas_product"), eq("product-1"),
                eq(Map.of("productName", "Acme Billing", "question", "Did pricing change this month?")));
    }

    @Test
    void aVeryLongQuestionIsTruncatedInTheAuditEntryRatherThanStoredWhole() {
        given(source(SourceType.WEBSITE, "Changelog"));
        String essay = "a".repeat(900);

        service.ask("product-1", essay);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(audit).log(any(), anyString(), anyString(), captor.capture());
        // An audit entry is a record that an action happened, not a copy of its input. 500 characters is enough to
        // recognise the question later; the rest would only grow a collection that is never pruned.
        assertThat((String) captor.getValue().get("question")).hasSize(501).endsWith("…");
    }

    @Test
    void aRejectedQuestionPropagatesSoTheClientGetsA503RatherThanAnEmptyStream() {
        given(source(SourceType.WEBSITE, "Changelog"));
        doThrow(new ProviderUnavailableException("too many questions")).when(executor).submitAsk(any());

        assertThatThrownBy(() -> service.ask("product-1", "What changed?"))
                .isInstanceOf(ProviderUnavailableException.class);

        // Nothing was answered, so nothing is counted. The metric means "a question reached the model".
        verify(metrics, never()).recordAsk(anyString(), anyBoolean());
    }

    // ---------------------------------------------------------------------
    // The four things an ask does not do
    // ---------------------------------------------------------------------

    @Test
    void answeringAQuestionPersistsNothingAtAll() {
        given(source(SourceType.WEBSITE, "Changelog"));
        storedSnapshot("Changelog", SourceType.WEBSITE, "changelog text", JUNE_20);

        service.ask("product-1", "What changed?");
        runAnswer();

        // Asking the same question twice costs one more provider call and changes nothing else. If an ask wrote a
        // report, the history timeline would fill with rows the user never asked to create, and the next run's
        // "changes since the last run" would be measured from a question rather than from a run.
        verify(reports, never()).save(any(ChangeReport.class));
        verify(snapshots, never()).save(any(Snapshot.class));
    }

    @Test
    void anMcpSourceContributesNothingBecauseItsContentWasNeverStoredHere() {
        given(source(SourceType.WEBSITE, "Changelog"), source(SourceType.ATLASSIAN_MCP, "Jira"));
        storedSnapshot("Changelog", SourceType.WEBSITE, "changelog text", JUNE_20);

        service.ask("product-1", "What is in the Jira backlog?");
        runAnswer();

        // Calling the MCP server live would make a question as slow and as expensive as a run, and would answer about
        // today while every other part of the same answer was about the last run.
        assertThat(capturedContext().snapshots())
                .extracting(AskContext.SourceExcerpt::sourceName)
                .containsExactly("Changelog");
        verify(snapshots, never())
                .findFirstBySaasProductIdAndSourceNameOrderByFetchedAtDesc("product-1", "Jira");
    }

    @Test
    void aSourceWithNoStoredSnapshotIsLeftOutRatherThanIncludedEmpty() {
        given(source(SourceType.WEBSITE, "Changelog"), source(SourceType.SAAS_URL, "App"));
        storedSnapshot("Changelog", SourceType.WEBSITE, "changelog text", JUNE_20);

        service.ask("product-1", "What changed?");
        runAnswer();

        // An empty excerpt would read to the model as "this source says nothing", which is a claim about the product
        // rather than about this system's data.
        assertThat(capturedContext().snapshots()).hasSize(1);
    }

    // ---------------------------------------------------------------------
    // What the model is given
    // ---------------------------------------------------------------------

    @Test
    void eachExcerptCarriesItsCaptureDateSoTheAnswerCanSayAsOfWhen() {
        given(source(SourceType.WEBSITE, "Changelog"));
        storedSnapshot("Changelog", SourceType.WEBSITE, "changelog text", JUNE_20);

        service.ask("product-1", "What changed?");
        runAnswer();

        // The honest consequence of never fetching: an answer can be stale, and the only way it can say so is if the
        // date travels with the text.
        assertThat(capturedContext().snapshots()).singleElement().satisfies(excerpt -> {
            assertThat(excerpt.fetchedAt()).isEqualTo(JUNE_20);
            assertThat(excerpt.text()).isEqualTo("changelog text");
            assertThat(excerpt.sourceType()).isEqualTo(SourceType.WEBSITE);
        });
    }

    @Test
    void theFreshestExcerptIsListedFirstBecauseThePromptBudgetIsSpentFromTheTop() {
        given(source(SourceType.WEBSITE, "Stale"), source(SourceType.SAAS_URL, "Fresh"));
        storedSnapshot("Stale", SourceType.WEBSITE, "old text", JUNE_1);
        storedSnapshot("Fresh", SourceType.SAAS_URL, "new text", JUNE_20);

        service.ask("product-1", "What changed?");
        runAnswer();

        assertThat(capturedContext().snapshots())
                .extracting(AskContext.SourceExcerpt::sourceName)
                .containsExactly("Fresh", "Stale");
    }

    @Test
    void theLatestReportsSummaryIsHandedOverSoAnAnswerCanBuildOnItRatherThanRederiveIt() {
        given(source(SourceType.WEBSITE, "Changelog"));
        storedSnapshot("Changelog", SourceType.WEBSITE, "changelog text", JUNE_20);
        ChangeReport latest = new ChangeReport("product-1", "dana", AnalysisDepth.REGULAR, List.of(),
                "Pricing page gained an enterprise tier.", List.of());
        when(reports.findFirstBySaasProductIdOrderByRunAtDesc("product-1")).thenReturn(Optional.of(latest));

        service.ask("product-1", "What changed?");
        runAnswer();

        AskContext context = capturedContext();
        assertThat(context.latestReportSummary()).isEqualTo("Pricing page gained an enterprise tier.");
        assertThat(context.latestReportAt()).isEqualTo(latest.getRunAt());
    }

    @Test
    void aProductThatHasNeverBeenRunCarriesNoSummaryRatherThanAPlaceholderOne() {
        given(source(SourceType.WEBSITE, "Changelog"));

        service.ask("product-1", "What changed?");
        runAnswer();

        AskContext context = capturedContext();
        assertThat(context.latestReportSummary()).isNull();
        assertThat(context.latestReportAt()).isNull();
    }

    @Test
    void theQuestionReachesTheModelVerbatim() {
        given(source(SourceType.WEBSITE, "Changelog"));

        service.ask("product-1", "  Did the free tier change?  ");
        runAnswer();

        // Not trimmed, not normalised, not rewritten. Whatever the user typed is what gets answered.
        assertThat(capturedContext().question()).isEqualTo("  Did the free tier change?  ");
        assertThat(capturedContext().productName()).isEqualTo("Acme Billing");
    }

    @Test
    void theAnswerUsesTheAskingUsersOwnCredentialRatherThanAnyRunsCredential() {
        given(source(SourceType.WEBSITE, "Changelog"));

        service.ask("product-1", "What changed?");
        runAnswer();

        // The actor is captured on the request thread, because CurrentUser reads a thread-local that does not exist on
        // an ask pool thread. Getting this wrong resolves the system credential silently instead of failing.
        verify(providers).resolve("id-dana");
    }

    // ---------------------------------------------------------------------
    // Streaming, and the client hanging up
    // ---------------------------------------------------------------------

    @Test
    void everyChunkTheProviderEmitsIsForwardedToTheStreamAsItArrives() {
        given(source(SourceType.WEBSITE, "Changelog"));
        when(provider.answerQuestion(any(), any())).thenAnswer(call -> {
            TokenSink sink = call.getArgument(1);
            sink.onChunk("Bulk ");
            sink.onChunk("export ");
            sink.onChunk("shipped.");
            return "Bulk export shipped.";
        });

        service.ask("product-1", "What changed?");
        runAnswer();

        // Chunk by chunk rather than in one block at the end, which is the difference between a chat-style answer and
        // a spinner followed by a wall of text.
        verify(metrics).recordAsk("Acme Billing", true);
    }

    @Test
    void aClientThatHangsUpMidAnswerStopsTheProviderCallInsteadOfPayingForTheRest() {
        given(source(SourceType.WEBSITE, "Changelog"));
        AtomicReference<Throwable> thrownAtProvider = new AtomicReference<>();
        when(provider.answerQuestion(any(), any())).thenAnswer(call -> {
            TokenSink sink = call.getArgument(1);
            try {
                sink.onChunk("first");
            } catch (RuntimeException e) {
                thrownAtProvider.set(e);
                throw e;
            }
            return "never completed";
        });

        SseEmitter emitter = service.ask("product-1", "What changed?");
        emitter.complete(); // stands in for the browser closing the tab
        runAnswer();

        // The failure has to travel back out through the streaming callback: swallowing it here would leave the
        // provider generating - and billing for - an answer with nowhere to go.
        assertThat(thrownAtProvider.get())
                .isNotNull()
                .hasMessageContaining("The client disconnected before the answer was complete");
    }

    @Test
    void anAbandonedQuestionIsNotCountedAsAnsweredAndDoesNotThrowOutOfThePool() {
        given(source(SourceType.WEBSITE, "Changelog"));
        when(provider.answerQuestion(any(), any())).thenAnswer(call -> {
            call.getArgument(1, TokenSink.class).onChunk("first");
            return "never completed";
        });

        SseEmitter emitter = service.ask("product-1", "What changed?");
        emitter.complete();

        // An exception escaping here would be logged by the pool as an uncaught failure on every closed tab, which is
        // noise rather than signal - closing a tab is how streams normally end early.
        runAnswer();

        verify(metrics).recordAsk("Acme Billing", false);
    }

    // ---------------------------------------------------------------------
    // Failure is recorded, not just logged
    // ---------------------------------------------------------------------

    @Test
    void aProviderThatIsUnavailableIsCountedAsAFailedQuestion() {
        given(source(SourceType.WEBSITE, "Changelog"));
        when(provider.answerQuestion(any(), any()))
                .thenThrow(new ProviderUnavailableException("no API key is configured"));

        service.ask("product-1", "What changed?");
        runAnswer();

        verify(metrics).recordAsk("Acme Billing", false);
    }

    @Test
    void anUnexpectedFailureIsStillCountedRatherThanLeavingTheMetricSilent() {
        given(source(SourceType.WEBSITE, "Changelog"));
        when(provider.answerQuestion(any(), any())).thenThrow(new IllegalStateException("boom"));

        service.ask("product-1", "What changed?");
        runAnswer();

        // Recorded from a finally block, for the same reason as a run's: a query bar that fails every time should not
        // look like a query bar nobody is using.
        verify(metrics).recordAsk("Acme Billing", false);
    }
}
