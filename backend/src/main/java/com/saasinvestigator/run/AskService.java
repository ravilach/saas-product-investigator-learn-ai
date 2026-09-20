package com.saasinvestigator.run;

import com.saasinvestigator.audit.AuditAction;
import com.saasinvestigator.audit.AuditService;
import com.saasinvestigator.error.NotFoundException;
import com.saasinvestigator.error.ProviderUnavailableException;
import com.saasinvestigator.llm.AskContext;
import com.saasinvestigator.llm.LlmProviderResolver;
import com.saasinvestigator.product.SaasProduct;
import com.saasinvestigator.product.SaasProductRepository;
import com.saasinvestigator.product.SourceConfig;
import com.saasinvestigator.report.ChangeReport;
import com.saasinvestigator.report.ChangeReportRepository;
import com.saasinvestigator.security.AuthenticatedUser;
import com.saasinvestigator.security.CurrentUser;
import com.saasinvestigator.snapshot.SnapshotRepository;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Answers a free-text question about one product from what is already stored, streaming the answer as it is
 * written.
 *
 * <h2>What an ask deliberately does not do</h2>
 *
 * <p>It fetches nothing, crawls nothing, calls no MCP server, and persists nothing. That is four restrictions with
 * one reason behind them: this endpoint sits behind a query bar that a user will type into repeatedly and casually,
 * and every one of those things would make a casual question expensive, slow, or a source of new database rows
 * nobody asked for. A question is a view over stored data - the same data the last run produced - so asking one
 * twice costs exactly one provider call more and changes nothing.
 *
 * <p>The honest consequence is that an answer can be stale, and the prompt says so: each snapshot's capture date is
 * passed through so the model can answer "as of 3 June" rather than implying it just looked. A user who needs a
 * current answer runs the product; that is what the Run button is for.
 *
 * <h2>Why the response is SSE and not JSON</h2>
 *
 * <p>Both providers stream, so words can appear as they are generated instead of after a silent wait. That is not
 * only an aesthetic: a thorough answer takes long enough that a spinner is genuinely ambiguous between "working" and
 * "broken", and text arriving resolves that ambiguity without a progress bar that would have nothing real to
 * measure.
 */
@Service
public class AskService {

    private static final Logger log = LoggerFactory.getLogger(AskService.class);

    /** The audit {@code targetType} for everything about a product. */
    private static final String AUDIT_TARGET = "saas_product";

    /**
     * How long an answer's stream may stay open.
     *
     * <p>Shorter than a run's, because an ask has a much smaller output budget and no crawling in front of it - and
     * because a question that has produced nothing after this long is not going to.
     */
    private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(5);

    /**
     * The longest question accepted.
     *
     * <p>A guard rather than a rule about how people should write. The question is pasted verbatim into a prompt, so
     * without a bound, one request could spend the entire prompt budget on the question and leave no room for the
     * material needed to answer it.
     */
    static final int MAX_QUESTION_CHARS = 2_000;

    private final SaasProductRepository products;
    private final SnapshotRepository snapshots;
    private final ChangeReportRepository reports;
    private final LlmProviderResolver providers;
    private final RunExecutor executor;
    private final RunMetrics metrics;
    private final AuditService audit;

    public AskService(SaasProductRepository products,
                      SnapshotRepository snapshots,
                      ChangeReportRepository reports,
                      LlmProviderResolver providers,
                      RunExecutor executor,
                      RunMetrics metrics,
                      AuditService audit) {
        this.products = products;
        this.snapshots = snapshots;
        this.reports = reports;
        this.providers = providers;
        this.executor = executor;
        this.metrics = metrics;
        this.audit = audit;
    }

    /**
     * Starts answering a question, returning the stream the answer will arrive on.
     *
     * <p>The product lookup and the audit entry happen on the request thread, so a question about a product that
     * does not exist is a {@code 404} rather than an {@code error} event inside a {@code 200}. Everything after that
     * happens on an ask thread.
     *
     * @param productId the product being asked about
     * @param question the user's question, verbatim
     * @return an emitter that will receive {@code chunk} events, then one {@code done} or {@code error}
     * @throws NotFoundException if the product does not exist
     * @throws ProviderUnavailableException if the server is already answering as many questions as it will
     */
    public SseEmitter ask(String productId, String question) {
        SaasProduct product = products.findById(productId)
                .orElseThrow(() -> new NotFoundException("No SaaS product with id " + productId));
        AuthenticatedUser actor = CurrentUser.require();

        // The question itself is recorded: it is the user's own words about their own product, carries no
        // credential, and is the only thing that makes an ask entry in the audit log worth reading. Truncated
        // because an audit entry is a record of an action, not a copy of an essay.
        audit.log(AuditAction.PRODUCT_ASK_SUBMITTED, AUDIT_TARGET, productId, Map.of(
                "productName", product.getName(),
                "question", question.length() > 500 ? question.substring(0, 500) + "…" : question));

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
        try {
            executor.submitAsk(() -> answer(emitter, product, question, actor));
        } catch (RuntimeException e) {
            // Nothing has been written to the emitter yet, so completing it here is clean - and the exception still
            // propagates, so the client gets a 503 rather than an empty 200 with an error event in it.
            emitter.complete();
            throw e;
        }
        return emitter;
    }

    /** The body, on an ask thread. Always finishes the emitter, whichever way it goes. */
    private void answer(SseEmitter emitter, SaasProduct product, String question, AuthenticatedUser actor) {
        boolean succeeded = false;
        try {
            AskContext context = buildContext(product, question);
            LlmProviderResolver.ResolvedProvider resolved = providers.resolve(actor.userId());

            String complete = resolved.provider().answerQuestion(context, chunk -> send(emitter, AskEvent.chunk(chunk)));

            send(emitter, AskEvent.done(complete));
            emitter.complete();
            succeeded = true;
            log.info("Answered a question about '{}' for {} via {}.",
                    product.getName(), actor.username(), resolved.type());
        } catch (ClientGoneException e) {
            // Somebody closed the tab. Expected, not exceptional: debug, no stack trace, nothing to send.
            log.debug("Question about '{}' abandoned by the client.", product.getName());
            emitter.complete();
        } catch (ProviderUnavailableException e) {
            log.warn("Question about '{}' could not be answered: {}", product.getName(), e.getMessage());
            fail(emitter, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Question about '{}' failed unexpectedly.", product.getName(), e);
            fail(emitter, "The question could not be answered because of an unexpected error.");
        } finally {
            metrics.recordAsk(product.getName(), succeeded);
        }
    }

    /**
     * Gathers the latest stored state of every crawled source, plus the most recent report's summary.
     *
     * <p>MCP sources contribute nothing here, and cannot: the backend never held their content. A question whose
     * answer lives only in a Jira project is one this endpoint will say it cannot answer, which is the correct
     * outcome and a better one than calling the server live - that would make a question as slow and as expensive as
     * a run, and would answer about now while every other part of the same answer was about the last run.
     */
    private AskContext buildContext(SaasProduct product, String question) {
        List<AskContext.SourceExcerpt> excerpts = new ArrayList<>();
        for (SourceConfig source : product.crawledSources()) {
            snapshots.findFirstBySaasProductIdAndSourceNameOrderByFetchedAtDesc(product.getId(), source.getName())
                    .ifPresent(snapshot -> excerpts.add(new AskContext.SourceExcerpt(
                            snapshot.getSourceName(),
                            snapshot.getSourceType(),
                            snapshot.getRawContent(),
                            snapshot.getFetchedAt())));
        }
        excerpts.sort((left, right) -> right.fetchedAt().compareTo(left.fetchedAt()));

        Optional<ChangeReport> latest = reports.findFirstBySaasProductIdOrderByRunAtDesc(product.getId());
        return new AskContext(
                product.getName(),
                question,
                excerpts,
                latest.map(ChangeReport::getOverallSummary).orElse(null),
                latest.map(ChangeReport::getRunAt).orElse(null));
    }

    /**
     * Sends one event, converting a disconnected client into an unchecked failure.
     *
     * <p>Wrapped rather than ignored so that a client closing the tab mid-answer stops the provider call instead of
     * paying for an answer nobody will read: the exception propagates out through the provider's streaming callback
     * and ends the request.
     */
    private static void send(SseEmitter emitter, AskEvent event) {
        try {
            emitter.send(SseEmitter.event().name(event.type()).data(event, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            throw new ClientGoneException(e);
        }
    }

    private static void fail(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().name(AskEvent.ERROR)
                    .data(AskEvent.error(message), MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (IOException | IllegalStateException | ClientGoneException e) {
            // Already gone. Complete so the container releases the async context either way.
            emitter.complete();
        }
    }

    /** Signals that the client hung up. Not an error worth a stack trace - it is how streams normally end early. */
    private static final class ClientGoneException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private ClientGoneException(Throwable cause) {
            super("The client disconnected before the answer was complete", cause);
        }
    }
}
