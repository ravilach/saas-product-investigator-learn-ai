package com.saasinvestigator.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saasinvestigator.product.SourceType;
import com.saasinvestigator.report.Change;
import com.saasinvestigator.report.ChangeCategory;
import com.saasinvestigator.report.Confidence;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns a model's response into a {@link GeneratedReport}, and owns the single corrective retry.
 *
 * <p>Both providers ask for JSON using the strongest enforcement they offer - Anthropic's structured output config,
 * OpenAI's JSON schema - and both still route the response through here, because provider-side enforcement covers
 * syntax and this class covers meaning. A syntactically perfect {@code {"overallSummary": "", "changes": []}} is
 * valid JSON and a useless report; a change attributed to a source that does not exist is valid JSON and a wrong
 * report. Those are the failures this class is for.
 *
 * <h2>Two different responses to bad input, on purpose</h2>
 *
 * <p>The asymmetry here is the design, so it is worth stating plainly:
 *
 * <ul>
 *   <li><b>Structural problems retry.</b> Not JSON at all, no object in it, {@code changes} is a string, no
 *       {@code overallSummary} - the response is unusable as a whole, so {@link #parse} throws and
 *       {@link #parseWithRetry} sends {@link PromptBuilder#jsonCorrectionPrompt()} once. Once, not in a loop: a
 *       model told plainly that its output was unparseable usually complies, and a model that fails twice is
 *       failing for a reason a third expensive call will not fix.
 *   <li><b>Field-level problems degrade.</b> An invented category, a missing confidence, a null evidence snippet -
 *       the change's description and evidence are still exactly right and only a badge is a guess, so
 *       {@code ChangeCategory.fromWire}/{@code Confidence.fromWire} absorb it and the report stands. Throwing away
 *       a good seven-change report because the model wrote {@code "price"} instead of {@code "pricing"} would be
 *       strictness for its own sake.
 * </ul>
 *
 * <p>Uses a locally constructed Jackson 2 {@link ObjectMapper}, not the injected one. The injected mapper in Spring
 * Boot 4 is Jackson 3 ({@code tools.jackson}) and belongs to HTTP serialisation; Jackson 2 is on the classpath via
 * the LLM SDKs and jjwt. Parsing a model's output is not HTTP serialisation and must not be coupled to whatever the
 * web layer's mapper is configured to do - a {@code FAIL_ON_UNKNOWN_PROPERTIES} setting added for an API DTO three
 * releases from now should not change how a model's response is read.
 */
@Component
public class ChangeReportJsonParser {

    private static final Logger log = LoggerFactory.getLogger(ChangeReportJsonParser.class);

    /** Longest evidence snippet kept. Enough for a sentence or two; a model quoting a whole page is not evidence. */
    static final int MAX_EVIDENCE_CHARS = 2_000;

    /** Field-level parsing is deliberately lenient, so no configuration is needed or wanted here. */
    private final ObjectMapper mapper = new ObjectMapper();

    private final PromptBuilder promptBuilder;

    public ChangeReportJsonParser(PromptBuilder promptBuilder) {
        this.promptBuilder = promptBuilder;
    }

    /**
     * Parses a response, retrying once through {@code retry} if the first attempt is structurally unusable.
     *
     * <p>This is the method providers call. The retry is driven by a callback rather than by the provider so that
     * the retry <em>policy</em> - how many attempts, what the correction says, what counts as worth retrying - lives
     * in one place, while each provider supplies only the mechanics of sending another message on its own API.
     *
     * @param context the run whose source names are the valid attribution set
     * @param firstResponse the model's first response, exactly as received
     * @param retry sends a correction message to the same conversation and returns the new response
     * @return the parsed report
     * @throws MalformedReportException if the retry also fails, or if {@code retry} itself throws. Providers wrap
     *     this in {@code ProviderUnavailableException}: a model that cannot produce the contract is, for this
     *     application's purposes, a provider that is unavailable.
     */
    public GeneratedReport parseWithRetry(RunContext context, String firstResponse, CorrectionCall retry) {
        try {
            return parse(context, firstResponse);
        } catch (MalformedReportException first) {
            log.warn("Model response was not the required JSON ({}); sending one corrective retry.",
                    first.getMessage());
            String second;
            try {
                second = retry.send(promptBuilder.jsonCorrectionPrompt());
            } catch (RuntimeException e) {
                throw new MalformedReportException(
                        "the response was not valid JSON and the corrective retry could not be sent: "
                                + e.getMessage(), e);
            }
            try {
                GeneratedReport corrected = parse(context, second);
                log.info("Corrective retry produced valid JSON.");
                return corrected;
            } catch (MalformedReportException e) {
                throw new MalformedReportException(
                        "the model did not return the required JSON, even after one corrective retry", e);
            }
        }
    }

    /**
     * Parses a single response with no retry.
     *
     * @param context the run whose source names are the valid attribution set
     * @param raw the model's response, exactly as received
     * @return the parsed report
     * @throws MalformedReportException if the response cannot be read as the required contract
     */
    public GeneratedReport parse(RunContext context, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new MalformedReportException("the response was empty");
        }

        JsonNode root = readObject(raw);

        JsonNode summaryNode = root.get("overallSummary");
        if (summaryNode == null || !summaryNode.isTextual() || summaryNode.asText().isBlank()) {
            // The one field with no sensible default. An empty summary would render as a blank report card that
            // looks like a rendering bug, and every export would have a hole where its only prose belongs - so
            // this is worth a retry even though everything else here degrades gracefully.
            throw new MalformedReportException("\"overallSummary\" was missing, empty, or not a string");
        }

        JsonNode changesNode = root.get("changes");
        if (changesNode != null && !changesNode.isNull() && !changesNode.isArray()) {
            throw new MalformedReportException("\"changes\" was present but was not an array");
        }

        List<Change> changes = new ArrayList<>();
        int dropped = 0;
        if (changesNode != null && changesNode.isArray()) {
            for (JsonNode element : changesNode) {
                if (!element.isObject()) {
                    dropped++;
                    continue;
                }
                Change change = readChange(context, element);
                if (change == null) {
                    dropped++;
                } else {
                    changes.add(change);
                }
            }
        }
        if (dropped > 0) {
            // Logged rather than surfaced: the overall summary still describes the product as a whole, so a
            // partially unattributable changes list is a degraded report, not a failed run. Visible server-side
            // because a provider that starts doing this regularly is worth knowing about.
            log.warn("Discarded {} of {} reported change(s) that could not be attributed to a configured source of "
                            + "\"{}\".",
                    dropped, changes.size() + dropped, context.productName());
        }
        return new GeneratedReport(summaryNode.asText().trim(), changes);
    }

    /**
     * Extracts the JSON object from a response that may have prose or markdown fences around it.
     *
     * <p>A strict parse is tried first, so a compliant response costs nothing. The salvage pass exists because the
     * single most common deviation is not malformed JSON - it is correct JSON wrapped in {@code ```json} fences or
     * preceded by "Here is the analysis:", despite the instruction not to. Recovering from that is worth one
     * substring operation; the alternative is a retry, a second full-price call, and a slower run for something the
     * response already contains.
     */
    private JsonNode readObject(String raw) {
        String text = raw.trim();
        try {
            JsonNode parsed = mapper.readTree(text);
            if (parsed != null && parsed.isObject()) {
                return parsed;
            }
        } catch (JsonProcessingException ignored) {
            // Fall through to salvage.
        }

        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new MalformedReportException("the response contained no JSON object");
        }
        try {
            JsonNode salvaged = mapper.readTree(text.substring(start, end + 1));
            if (salvaged == null || !salvaged.isObject()) {
                throw new MalformedReportException("the response's JSON was not an object");
            }
            log.debug("Recovered a JSON object from a response with {} extra characters around it.",
                    text.length() - (end + 1 - start));
            return salvaged;
        } catch (JsonProcessingException e) {
            throw new MalformedReportException("the response was not valid JSON: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * Reads one element of {@code changes}, or returns {@code null} if it cannot be attributed to a real source.
     *
     * <p>Attribution is the one field-level problem that cannot be shrugged off with a default. Category and
     * confidence are labels on a finding; the source is what makes the finding checkable, and a change filed
     * against a source the product does not have sends a reader looking for evidence on a page that was never
     * consulted. So this is lenient about <em>matching</em> and strict about <em>inventing</em>.
     */
    private Change readChange(RunContext context, JsonNode node) {
        String description = text(node, "description");
        if (description == null || description.isBlank()) {
            return null;
        }

        String claimedSource = text(node, "sourceName");
        Attribution attribution = attribute(context, claimedSource);
        if (attribution == null) {
            log.debug("Change attributed to unknown source \"{}\"; discarding: {}", claimedSource, description);
            return null;
        }

        String evidence = text(node, "evidenceSnippet");
        if (evidence != null) {
            evidence = evidence.isBlank() ? null : trimEvidence(evidence.trim());
        }

        return new Change(
                attribution.name(),
                attribution.type(),
                ChangeCategory.fromWire(text(node, "category")),
                description.trim(),
                Confidence.fromWire(text(node, "confidence")),
                evidence);
    }

    /**
     * Resolves a source name the model wrote to a configured source, tolerating the ways models decorate names.
     *
     * <p>Three passes, narrowest first. Exact case-insensitive match is the expected path. A one-sided containment
     * match handles the common decoration - a model asked to use {@code "Docs"} returning
     * {@code "Docs (docs.example.com)"} - and is only accepted when exactly one configured source matches, so an
     * ambiguous name is never silently filed against an arbitrary source. Finally, a product with exactly one
     * source has only one possible answer, so a badly-named change there is still correctly attributable.
     *
     * @return the configured name and type to store, or {@code null} if the name cannot be resolved
     */
    private static Attribution attribute(RunContext context, String claimed) {
        List<Attribution> known = knownSources(context);
        if (known.isEmpty()) {
            return null;
        }
        if (claimed == null || claimed.isBlank()) {
            return known.size() == 1 ? known.getFirst() : null;
        }

        String wanted = claimed.trim();
        SourceType exact = PromptBuilder.resolveSourceType(context, wanted);
        if (exact != null) {
            for (Attribution candidate : known) {
                if (candidate.name().equalsIgnoreCase(wanted)) {
                    // The configured spelling wins over the model's, so the stored report groups cleanly with
                    // every other report for the same source.
                    return candidate;
                }
            }
        }

        Attribution loose = null;
        String lowered = wanted.toLowerCase(java.util.Locale.ROOT);
        for (Attribution candidate : known) {
            String name = candidate.name().toLowerCase(java.util.Locale.ROOT);
            if (lowered.contains(name) || name.contains(lowered)) {
                if (loose != null) {
                    return null; // Ambiguous: two configured sources match, so guessing would be a coin flip.
                }
                loose = candidate;
            }
        }
        if (loose != null) {
            return loose;
        }
        return known.size() == 1 ? known.getFirst() : null;
    }

    /** Every source the model was told about, in the order it saw them. */
    private static List<Attribution> knownSources(RunContext context) {
        List<Attribution> known = new ArrayList<>();
        for (SourceComparison comparison : context.comparisons()) {
            known.add(new Attribution(comparison.sourceName(), comparison.sourceType()));
        }
        for (McpSourceRef mcp : context.mcpSources()) {
            known.add(new Attribution(mcp.name(), mcp.type()));
        }
        for (McpChangeHistory history : context.mcpHistory()) {
            known.add(new Attribution(history.sourceName(), history.sourceType()));
        }
        for (SourceFailure failure : context.failures()) {
            // Included so a change the model derived from a failed source's own error page - or from the failure
            // note in the prompt - is still attributable rather than discarded as an unknown name.
            known.add(new Attribution(failure.sourceName(), failure.sourceType()));
        }
        return known;
    }

    private static String trimEvidence(String evidence) {
        return evidence.length() <= MAX_EVIDENCE_CHARS
                ? evidence
                : evidence.substring(0, MAX_EVIDENCE_CHARS) + "...";
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        // asText() rather than requiring isTextual(): a model that wrote a number or a boolean where a string
        // belonged still said something, and its rendering is more useful than dropping the field.
        return value.isTextual() ? value.asText() : value.asText(null);
    }

    /** A resolved source name plus its type, both taken from configuration rather than from the model. */
    private record Attribution(String name, SourceType type) {
    }

    /**
     * Sends a correction message on an in-progress provider conversation.
     *
     * <p>Implemented by each provider as a lambda closing over whatever it needs to continue the same exchange -
     * for Anthropic the message list plus the assistant's first reply, for OpenAI the previous response. The parser
     * only needs to know that it can hand over a string and get one back.
     */
    @FunctionalInterface
    public interface CorrectionCall {

        /**
         * @param correctionPrompt the message to send, from {@link PromptBuilder#jsonCorrectionPrompt()}
         * @return the model's new response, exactly as received
         */
        String send(String correctionPrompt);
    }

    /**
     * A model response that could not be read as the required contract.
     *
     * <p>Unchecked because there is nothing a caller can do about it other than fail the run, and every caller is
     * already inside a provider implementation that converts it to
     * {@code ProviderUnavailableException}. Its message is written to be safe to show a user: it says what was
     * wrong with the shape of the response, never what the response contained.
     */
    public static class MalformedReportException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public MalformedReportException(String message) {
            super(message);
        }

        public MalformedReportException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
