package com.saasinvestigator.run;

import com.saasinvestigator.audit.AuditAction;
import com.saasinvestigator.audit.AuditService;
import com.saasinvestigator.error.NotFoundException;
import com.saasinvestigator.error.ProviderUnavailableException;
import com.saasinvestigator.llm.GeneratedReport;
import com.saasinvestigator.llm.LlmActivityListener;
import com.saasinvestigator.llm.LlmProviderResolver;
import com.saasinvestigator.llm.McpSourceRef;
import com.saasinvestigator.llm.RunContext;
import com.saasinvestigator.llm.SourceFailure;
import com.saasinvestigator.product.SaasProduct;
import com.saasinvestigator.product.SaasProductRepository;
import com.saasinvestigator.product.SourceConfig;
import com.saasinvestigator.product.SourceConfigMapper;
import com.saasinvestigator.report.AnalysisDepth;
import com.saasinvestigator.report.ChangeReport;
import com.saasinvestigator.report.ChangeReportRepository;
import com.saasinvestigator.report.RunType;
import com.saasinvestigator.report.SourceInclusion;
import com.saasinvestigator.security.AuthenticatedUser;
import com.saasinvestigator.security.CurrentUser;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs the two analysis flows: a standard run that fetches everything, and a custom-range compare that fetches
 * nothing.
 *
 * <h2>The shape of a request</h2>
 *
 * <p>Every entry point here does the same four things on the request thread and then gets out of the way: validate
 * enough to reject a bad request with a real status code, write the audit entry, open a {@link RunSession}, and hand
 * the work to {@link RunExecutor}. The caller gets a {@code runId} in a {@code 202} and watches the rest over SSE.
 *
 * <p>Validation deliberately happens on the request thread even where it costs a database read. The alternative -
 * accept everything, fail asynchronously - turns "you picked a date before this product had any data" into a
 * {@code 202} followed by a failure event the user has to open the live view to read. A {@code 400} with the
 * earliest available date in it is a better answer to the same question.
 *
 * <h2>The actor is captured, not looked up</h2>
 *
 * <p>{@link CurrentUser} reads a thread-local that exists only on the request thread, so the run task cannot use it
 * and must be handed the {@link AuthenticatedUser} explicitly. Audit entries written from a run thread use
 * {@link AuditService#logAs} for the same reason. Getting this wrong does not throw - it silently records runs as
 * having no actor, which is worse.
 *
 * <h2>Where the comparison logic is not</h2>
 *
 * <p>Nothing in this class compares anything. It gathers material, hands it to a provider, and stores what comes
 * back. Every decision about what counts as a meaningful change lives in the prompt; see
 * {@code com.saasinvestigator.llm.PromptBuilder} and {@code docs/ARCHITECTURE.md}.
 */
@Service
public class RunOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(RunOrchestrator.class);

    /** The audit {@code targetType} for everything about a product, matching the other product actions. */
    private static final String AUDIT_TARGET = "saas_product";

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneOffset.UTC);

    private final SaasProductRepository products;
    private final ChangeReportRepository reports;
    private final SourceFetcher sourceFetcher;
    private final HistoryLoader historyLoader;
    private final LlmProviderResolver providers;
    private final SourceConfigMapper sourceMapper;
    private final RunEventStream eventStream;
    private final RunExecutor executor;
    private final RunMetrics metrics;
    private final RunRecorder recorder;
    private final AuditService audit;

    public RunOrchestrator(SaasProductRepository products,
                           ChangeReportRepository reports,
                           SourceFetcher sourceFetcher,
                           HistoryLoader historyLoader,
                           LlmProviderResolver providers,
                           SourceConfigMapper sourceMapper,
                           RunEventStream eventStream,
                           RunExecutor executor,
                           RunMetrics metrics,
                           RunRecorder recorder,
                           AuditService audit) {
        this.products = products;
        this.reports = reports;
        this.sourceFetcher = sourceFetcher;
        this.historyLoader = historyLoader;
        this.providers = providers;
        this.sourceMapper = sourceMapper;
        this.eventStream = eventStream;
        this.executor = executor;
        this.metrics = metrics;
        this.recorder = recorder;
        this.audit = audit;
    }

    /**
     * Starts a standard run: fetch every source now, compare against what was stored last time.
     *
     * @param productId the product to run
     * @param requestedDepth the depth asked for, or {@code null} to use the default
     * @return the {@code runId} to subscribe to
     * @throws NotFoundException if the product does not exist
     * @throws ProviderUnavailableException if the server is already at its run limit
     */
    public String startRun(String productId, AnalysisDepth requestedDepth) {
        SaasProduct product = require(productId);
        AnalysisDepth depth = AnalysisDepth.orDefault(requestedDepth);
        AuthenticatedUser actor = CurrentUser.require();

        audit.log(AuditAction.PRODUCT_RUN_TRIGGERED, AUDIT_TARGET, productId,
                details(product, depth, Map.of("sourceCount", product.getSources().size())));

        RunSession session = eventStream.open(productId, RunType.STANDARD);
        start(session, () -> runStandard(session, product, depth, actor));
        return session.runId();
    }

    /**
     * Starts a custom-range compare: reason over stored snapshots between two dates, fetching nothing.
     *
     * @param productId the product to compare
     * @param from start of the window
     * @param to end of the window
     * @param requestedDepth the depth asked for, or {@code null} to use the default
     * @return the {@code runId} to subscribe to
     * @throws NotFoundException if the product does not exist
     * @throws com.saasinvestigator.error.BadRequestException if the range is inverted, in the future, or predates
     *     all stored data for this product
     * @throws ProviderUnavailableException if the server is already at its run limit
     */
    public String startCompare(String productId, Instant from, Instant to, AnalysisDepth requestedDepth) {
        SaasProduct product = require(productId);
        AnalysisDepth depth = AnalysisDepth.orDefault(requestedDepth);
        AuthenticatedUser actor = CurrentUser.require();

        historyLoader.validateRange(product, from, to);

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("rangeFrom", from.toString());
        extra.put("rangeTo", to.toString());
        audit.log(AuditAction.PRODUCT_COMPARE_TRIGGERED, AUDIT_TARGET, productId, details(product, depth, extra));

        RunSession session = eventStream.open(productId, RunType.CUSTOM_RANGE);
        start(session, () -> runCompare(session, product, from, to, depth, actor));
        return session.runId();
    }

    /**
     * Submits the task, discarding the session if the server is too busy to accept it.
     *
     * <p>Without the discard, a rejected run would leave a session that never produces an event and never expires
     * until {@link RunEventStream#MAX_SESSION_AGE} - and a client that was handed its id would sit on an open
     * stream waiting for a run that was never scheduled.
     */
    private void start(RunSession session, Runnable task) {
        try {
            executor.submitRun(task);
        } catch (RuntimeException e) {
            eventStream.discard(session.runId());
            throw e;
        }
    }

    /**
     * The standard run, on a run thread.
     *
     * <p>Wrapped so that every exit path publishes a terminal event and, exactly once, records both the run metric
     * and the durable {@link RunRecord}. A run that threw without publishing would leave a live view frozen on its
     * last step forever, which looks identical to a run that is still working; a run that threw without recording
     * would be missing from the Admin Console precisely when it failed, which is when someone is looking.
     */
    private void runStandard(RunSession session, SaasProduct product, AnalysisDepth depth, AuthenticatedUser actor) {
        Instant startedAt = Instant.now();
        RunOutcome outcome = RunOutcome.FAILURE;
        String reportId = null;
        String failureReason = null;
        try {
            // Read before anything is written: after this run persists its own report, "the previous run" would be
            // this one, and the model would be told to look for changes since a moment that has not happened yet.
            Instant lastRunAt = reports.findFirstBySaasProductIdOrderByRunAtDesc(product.getId())
                    .map(ChangeReport::getRunAt)
                    .orElse(null);

            SourceFetcher.FetchOutcome fetched = sourceFetcher.fetchAll(product, session);

            List<McpSourceRef> mcpSources = mcpSourceRefs(product);
            RunContext context = new RunContext(
                    product.getName(),
                    product.getDescription(),
                    RunType.STANDARD,
                    depth,
                    lastRunAt,
                    null,
                    null,
                    fetched.comparisons(),
                    mcpSources,
                    List.of(),
                    fetched.failures());

            ChangeReport report = analyse(session, product, context, actor,
                    inclusions(fetched.inclusions(), product), null);

            reportId = report.getId();
            outcome = fetched.failures().isEmpty() ? RunOutcome.SUCCESS : RunOutcome.PARTIAL;
            session.completed(report);
        } catch (RunFailedException e) {
            failureReason = e.getMessage();
            session.failed(failureReason);
        } catch (ProviderUnavailableException e) {
            log.warn("Run {} of product '{}' failed: {}", session.runId(), product.getName(), e.getMessage());
            failureReason = e.getMessage();
            session.failed(failureReason);
        } catch (RuntimeException e) {
            log.error("Run {} of product '{}' failed unexpectedly.", session.runId(), product.getName(), e);
            failureReason = GENERIC_FAILURE;
            session.failed(failureReason);
        } finally {
            Duration took = Duration.between(startedAt, Instant.now());
            metrics.recordRun(product.getName(), RunType.STANDARD, depth, outcome, took);
            recorder.record(product, RunType.STANDARD, depth, outcome, actor.username(), startedAt, took,
                    reportId, failureReason);
        }
    }

    /** The custom-range compare, on a run thread. Identical bookkeeping, different material. */
    private void runCompare(RunSession session,
                            SaasProduct product,
                            Instant from,
                            Instant to,
                            AnalysisDepth depth,
                            AuthenticatedUser actor) {
        Instant startedAt = Instant.now();
        RunOutcome outcome = RunOutcome.FAILURE;
        String reportId = null;
        String failureReason = null;
        try {
            HistoryLoader.CompareData data = historyLoader.load(product, from, to, session);

            RunContext context = new RunContext(
                    product.getName(),
                    product.getDescription(),
                    RunType.CUSTOM_RANGE,
                    depth,
                    null,
                    from,
                    to,
                    data.comparisons(),
                    // Empty, and this is the load-bearing line of the compare flow: declaring MCP servers as tools
                    // here would have the model report today's state inside a report labelled with two past dates.
                    List.of(),
                    data.mcpHistory(),
                    data.promptFailures());

            ChangeReport report = analyse(session, product, context, actor,
                    inclusions(data.inclusions(), product),
                    saved -> saved.asCustomRange(from, to, data.mcpHistoryLimited()));

            reportId = report.getId();
            outcome = data.unavailable().isEmpty() ? RunOutcome.SUCCESS : RunOutcome.PARTIAL;
            session.completed(report);
        } catch (RunFailedException e) {
            failureReason = e.getMessage();
            session.failed(failureReason);
        } catch (ProviderUnavailableException e) {
            log.warn("Compare {} of product '{}' failed: {}", session.runId(), product.getName(), e.getMessage());
            failureReason = e.getMessage();
            session.failed(failureReason);
        } catch (RuntimeException e) {
            log.error("Compare {} of product '{}' failed unexpectedly.", session.runId(), product.getName(), e);
            failureReason = GENERIC_FAILURE;
            session.failed(failureReason);
        } finally {
            Duration took = Duration.between(startedAt, Instant.now());
            metrics.recordRun(product.getName(), RunType.CUSTOM_RANGE, depth, outcome, took);
            recorder.record(product, RunType.CUSTOM_RANGE, depth, outcome, actor.username(), startedAt, took,
                    reportId, failureReason);
        }
    }

    /**
     * The half both flows share: resolve a provider, run the analysis, persist the report.
     *
     * <h2>Why the step boundaries fall where they do</h2>
     *
     * <p>A model produces its comparison and its summary in a single call, so the backend cannot observe the moment
     * one becomes the other. Rather than invent a boundary, the steps are drawn where this process genuinely changes
     * what it is doing: {@code Comparing} covers the provider call, and {@code Summarizing} covers turning the
     * findings into the stored report. Both details are counts of real things, and the elapsed-second counter on
     * every event is what makes a long, quiet {@code Comparing} step legible as progress rather than as a hang.
     *
     * <p>{@code Consulting MCP tools} runs concurrently with {@code Comparing} for a standard run, because the model
     * calls those servers from inside the same request. Its progress lines are the actual tool names as the provider
     * stream reports them, which is the whole reason both providers stream a response that is not displayed
     * incrementally.
     *
     * @param inclusions the {@code sourcesIncluded} entries for this run
     * @param decorate applied to the report before saving, to stamp a compare's range; {@code null} for a run
     */
    private ChangeReport analyse(RunSession session,
                                 SaasProduct product,
                                 RunContext context,
                                 AuthenticatedUser actor,
                                 List<SourceInclusion> inclusions,
                                 ReportDecorator decorate) {
        if (context.hasNothingToAnalyse()) {
            throw new RunFailedException(nothingToAnalyse(context));
        }

        LlmProviderResolver.ResolvedProvider resolved = providers.resolve(actor.userId());
        boolean consultsMcp = !context.mcpSources().isEmpty();
        if (consultsMcp) {
            session.stepStarted(RunStep.CONSULTING_MCP_TOOLS, context.mcpSources().size()
                    + (context.mcpSources().size() == 1 ? " MCP server is" : " MCP servers are")
                    + " available to the model for this analysis");
        }

        session.stepStarted(RunStep.COMPARING, comparingIntro(context, resolved));

        AtomicInteger toolActivity = new AtomicInteger();
        RunStep activityStep = consultsMcp ? RunStep.CONSULTING_MCP_TOOLS : RunStep.COMPARING;
        LlmActivityListener listener = detail -> {
            toolActivity.incrementAndGet();
            session.stepProgress(activityStep, detail);
        };

        GeneratedReport generated = resolved.provider().generateChangeReport(context, listener);

        if (consultsMcp) {
            int calls = toolActivity.get();
            session.stepCompleted(RunStep.CONSULTING_MCP_TOOLS, calls == 0
                    ? "The model did not need to call any MCP tool"
                    : calls + (calls == 1 ? " MCP interaction" : " MCP interactions"));
        }
        int found = generated.changes().size();
        session.stepCompleted(RunStep.COMPARING,
                found == 0 ? "No changes detected" : found + (found == 1 ? " change detected" : " changes detected"));

        session.stepStarted(RunStep.SUMMARIZING, "Summarizing " + found
                + (found == 1 ? " detected change..." : " detected changes..."));

        ChangeReport report = new ChangeReport(product.getId(), actor.username(), context.analysisDepth(),
                inclusions, generated.overallSummary(), generated.changes());
        if (decorate != null) {
            decorate.apply(report);
        }
        ChangeReport saved = reports.save(report);

        session.stepCompleted(RunStep.SUMMARIZING, "Report saved");
        log.info("{} {} of product '{}' produced {} change(s) via {} (key source: {}).",
                context.runType(), session.runId(), product.getName(), found, resolved.type(),
                resolved.credentialSource());
        return saved;
    }

    /**
     * Decrypts each MCP source's token and wraps it for the provider.
     *
     * <p>The one place in a run where a plaintext {@code authToken} exists. It lives in the {@link McpSourceRef}
     * list for the duration of the provider call and nowhere else: not in the session, not in the report, not in the
     * audit entry, and - because {@code McpSourceRef} overrides {@code toString()} - not in a log line either.
     */
    private List<McpSourceRef> mcpSourceRefs(SaasProduct product) {
        List<SourceConfig> sources = product.mcpSources();
        List<McpSourceRef> refs = new ArrayList<>(sources.size());
        for (SourceConfig source : sources) {
            refs.add(new McpSourceRef(source.getName(), source.getType(), source.getEndpointUrl(),
                    sourceMapper.decryptAuthToken(source).orElse(null)));
        }
        return refs;
    }

    /**
     * Adds one {@code sourcesIncluded} entry per MCP source to the crawled ones.
     *
     * <p>MCP entries carry a {@code null} {@code fetchedAt}, which is not a missing value but a statement: the
     * backend never held that source's content and therefore cannot claim when it was current. A timestamp there
     * would be this process's clock masquerading as a data freshness guarantee.
     */
    private static List<SourceInclusion> inclusions(List<SourceInclusion> crawled, SaasProduct product) {
        List<SourceInclusion> all = new ArrayList<>(crawled);
        for (SourceConfig source : product.mcpSources()) {
            all.add(new SourceInclusion(source.getName(), source.getType(), null));
        }
        return all;
    }

    private SaasProduct require(String productId) {
        return products.findById(productId)
                .orElseThrow(() -> new NotFoundException("No SaaS product with id " + productId));
    }

    /** Audit details for a trigger. Carries names and counts only - never a URL's credential or a token. */
    private static Map<String, Object> details(SaasProduct product, AnalysisDepth depth, Map<String, Object> extra) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("productName", product.getName());
        details.put("analysisDepth", depth.name());
        details.putAll(extra);
        return details;
    }

    private static String comparingIntro(RunContext context, LlmProviderResolver.ResolvedProvider resolved) {
        int sources = context.comparisons().size() + context.mcpSources().size() + context.mcpHistory().size();
        String what = context.runType() == RunType.CUSTOM_RANGE
                ? "Comparing " + sources + (sources == 1 ? " source" : " sources") + " between "
                        + DAY.format(context.rangeFrom()) + " and " + DAY.format(context.rangeTo())
                : "Analyzing " + sources + (sources == 1 ? " source" : " sources")
                        + (context.hasAnyPriorState() ? " against prior snapshots..." : " as a first baseline...");
        return what + " (" + resolved.type() + ", " + context.analysisDepth().name().toLowerCase() + " depth)";
    }

    /** The user-facing reason when a run has no material at all. Names the cause rather than just the effect. */
    private static String nothingToAnalyse(RunContext context) {
        if (context.failures().isEmpty()) {
            return "This product has no sources configured, so there is nothing to analyse. "
                    + "Add at least one website, SaaS app URL, or MCP server to it.";
        }
        String names = context.failures().stream().map(SourceFailure::sourceName).reduce((a, b) -> a + ", " + b)
                .orElse("");
        return "None of this product's sources could be read (" + names + "), so no report was produced.";
    }

    private static final String GENERIC_FAILURE =
            "The analysis failed because of an unexpected error. The server log has the details.";

    /**
     * Carries a user-facing failure reason out of the analysis and into the terminal event.
     *
     * <p>Private and deliberately not part of the error hierarchy in {@code error/}: those exceptions exist to map
     * to HTTP status codes, and this one can never reach an HTTP response - by the time it is thrown, the request
     * that started the run has long since returned {@code 202}. Its message is written to be read on screen.
     */
    private static final class RunFailedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private RunFailedException(String message) {
            super(message);
        }
    }

    /** Applies run-type-specific fields to a report before it is saved. */
    @FunctionalInterface
    private interface ReportDecorator {
        void apply(ChangeReport report);
    }
}
