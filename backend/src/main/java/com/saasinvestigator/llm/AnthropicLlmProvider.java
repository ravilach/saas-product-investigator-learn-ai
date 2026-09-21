package com.saasinvestigator.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.models.beta.AnthropicBeta;
import com.anthropic.models.beta.messages.BetaJsonOutputFormat;
import com.anthropic.models.beta.messages.BetaMcpToolset;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaRawContentBlockDeltaEvent;
import com.anthropic.models.beta.messages.BetaRawContentBlockStartEvent;
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent;
import com.anthropic.models.beta.messages.BetaRequestMcpServerUrlDefinition;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.anthropic.models.messages.RawContentBlockDeltaEvent;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.ThinkingConfigDisabled;
import com.saasinvestigator.error.ProviderUnavailableException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Anthropic's implementation of {@link LlmProvider}, using the Messages API.
 *
 * <p>Not a Spring bean, and deliberately so: an instance is bound to one API key, and which key that is depends on
 * who triggered the run - their own BYOK key if they have one, the system key otherwise. A singleton would have to
 * either hold every key or look one up mid-call; {@link LlmProviderResolver} constructs an instance per run instead,
 * so the key's lifetime is the run's lifetime.
 *
 * <h2>Three things this class does that are not obvious</h2>
 *
 * <p><b>The run path streams even though nothing streams to the user.</b> A run's output is a JSON object that is
 * parsed all at once, so streaming buys nothing for the report itself - but MCP tool calls only become visible as
 * they happen on the event stream. Without streaming, the live execution view could not say
 * {@code "Calling Docs MCP tool: search_docs"} until after the whole analysis finished, which is exactly when it
 * stops being useful. The text deltas are accumulated and parsed at the end.
 *
 * <p><b>MCP takes two declarations, not one.</b> {@code mcpServers} tells the API where the servers are;
 * {@code tools} with a {@link BetaMcpToolset} per server is what actually exposes their tools to the model. Declaring
 * only the first produces a request that succeeds and a model that never calls anything - a silent no-op that looks
 * like "the MCP server had no changes". Both halves plus the {@code MCP_CLIENT_2025_11_20} beta are required, which
 * is also why the run path uses the beta namespace while the ask path does not.
 *
 * <p><b>Extended thinking is on for questions and off for reports.</b> Not a judgment that reports need less
 * thought: a report may need a corrective retry, and a retry replays the assistant's previous turn. An assistant
 * turn that contained thinking and server-side MCP tool-use blocks cannot be faithfully replayed as text, and
 * replaying it unfaithfully is a request the API can reject. The ask path has no retry, no tools, and no JSON
 * contract, so it has none of that risk and gets adaptive thinking.
 */
public class AnthropicLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(AnthropicLlmProvider.class);

    /**
     * Output ceiling retried with when the configured model rejects the requested one.
     *
     * <p>{@code AnalysisDepth.NUCLEAR} asks for 64k output tokens, which current models accept and older or smaller
     * ones do not. Since the model name is an env var anyone can point at anything, a rejection is a configuration
     * outcome rather than a bug, and a truncated-but-delivered NUCLEAR report beats a failed run with a 400 in the
     * log.
     */
    static final long FALLBACK_MAX_OUTPUT_TOKENS = 8_192L;

    /** Output ceiling for a question. Prose answers are short; this is headroom, not a target. */
    static final long ASK_MAX_OUTPUT_TOKENS = 16_384L;

    /**
     * Per-request ceiling. A NUCLEAR run over many sources with MCP tool calls genuinely takes minutes, and the
     * SDK's default would give up part-way through work the user is watching progress for.
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(20);

    private final String apiKey;
    private final LlmProperties properties;
    private final PromptBuilder promptBuilder;
    private final ChangeReportJsonParser parser;

    /**
     * @param apiKey the resolved key for this call; never logged, never stored
     * @param properties model names and prompt budget
     * @param promptBuilder the shared prompt source
     * @param parser the shared response parser, including its corrective retry
     */
    public AnthropicLlmProvider(String apiKey,
                                LlmProperties properties,
                                PromptBuilder promptBuilder,
                                ChangeReportJsonParser parser) {
        this.apiKey = apiKey;
        this.properties = properties;
        this.promptBuilder = promptBuilder;
        this.parser = parser;
    }

    @Override
    public LlmProviderType type() {
        return LlmProviderType.ANTHROPIC;
    }

    @Override
    public GeneratedReport generateChangeReport(RunContext context, LlmActivityListener listener) {
        AnthropicClient client = client();
        try {
            String first = streamReport(client, context, null, null, listener);
            return parser.parseWithRetry(context, first,
                    correction -> streamReport(client, context, first, correction, LlmActivityListener.NONE));
        } catch (ChangeReportJsonParser.MalformedReportException e) {
            throw new ProviderUnavailableException(
                    "Anthropic returned a response this application could not use: " + e.getMessage(), e);
        } catch (AnthropicException e) {
            throw new ProviderUnavailableException("Anthropic could not complete this analysis: " + reason(e), e);
        } finally {
            client.close();
        }
    }

    @Override
    public String answerQuestion(AskContext context, TokenSink sink) {
        AnthropicClient client = client();
        try {
            String system = promptBuilder.askSystemPrompt();
            String question = promptBuilder.askPrompt(context);

            // Adaptive thinking first; a model that rejects it - anything pre-4.6, which an env var can still
            // select - falls back to the same question with thinking off rather than failing the request.
            com.anthropic.models.messages.MessageCreateParams thinking =
                    askParams(system, question, ASK_MAX_OUTPUT_TOKENS, true);
            com.anthropic.models.messages.MessageCreateParams plain =
                    askParams(system, question, FALLBACK_MAX_OUTPUT_TOKENS, false);

            StringBuilder answer = new StringBuilder();
            try {
                streamAnswer(client, thinking, sink, answer);
            } catch (BadRequestException e) {
                log.warn("Anthropic rejected the question request ({}); retrying without extended thinking.",
                        reason(e));
                answer.setLength(0);
                streamAnswer(client, plain, sink, answer);
            }
            return answer.toString();
        } catch (AnthropicException e) {
            throw new ProviderUnavailableException("Anthropic could not answer this question: " + reason(e), e);
        } finally {
            client.close();
        }
    }

    /**
     * Runs one report request, streaming it, and returns the accumulated text.
     *
     * <p>When {@code priorAnswer} and {@code correction} are both non-null this is the corrective retry: the same
     * prompt, the model's unusable answer replayed as text, and the correction appended. Rebuilding from scratch
     * rather than mutating the first request's builder is what keeps the two calls honestly identical apart from
     * those two messages.
     */
    private String streamReport(AnthropicClient client,
                                RunContext context,
                                String priorAnswer,
                                String correction,
                                LlmActivityListener listener) {
        LongFunction<MessageCreateParams> params = maxTokens -> {
            MessageCreateParams.Builder builder = MessageCreateParams.builder()
                    .model(properties.anthropicModel())
                    .maxTokens(maxTokens)
                    .system(promptBuilder.systemPrompt())
                    .addUserMessage(promptBuilder.runPrompt(context))
                    .outputConfig(reportOutputConfig());
            declareMcpSources(builder, context);
            if (correction != null) {
                builder.addAssistantMessage(priorAnswer == null || priorAnswer.isBlank()
                        ? "(no output)"
                        : priorAnswer);
                builder.addUserMessage(correction);
            }
            return builder.build();
        };

        long wanted = context.analysisDepth().maxOutputTokens();
        try {
            return streamReportOnce(client, params.apply(wanted), listener);
        } catch (BadRequestException e) {
            if (wanted <= FALLBACK_MAX_OUTPUT_TOKENS) {
                throw e;
            }
            log.warn("Anthropic rejected a {}-token output request for model {} ({}); retrying at {} tokens.",
                    wanted, properties.anthropicModel(), reason(e), FALLBACK_MAX_OUTPUT_TOKENS);
            return streamReportOnce(client, params.apply(FALLBACK_MAX_OUTPUT_TOKENS), listener);
        }
    }

    /** Consumes one beta stream: text deltas accumulate, MCP tool activity is reported as it arrives. */
    private String streamReportOnce(AnthropicClient client,
                                    MessageCreateParams params,
                                    LlmActivityListener listener) {
        StringBuilder text = new StringBuilder();
        try (StreamResponse<BetaRawMessageStreamEvent> response = client.beta().messages().createStreaming(params)) {
            response.stream().forEach(event -> {
                event.contentBlockStart()
                        .map(BetaRawContentBlockStartEvent::contentBlock)
                        .ifPresent(block -> {
                            block.mcpToolUse().ifPresent(use -> listener.onActivity(
                                    "Calling " + use.serverName() + " MCP tool: " + use.name()));
                            block.mcpToolResult()
                                    .filter(result -> result.isError())
                                    .ifPresent(result -> listener.onActivity("An MCP tool call returned an error"));
                        });
                event.contentBlockDelta()
                        .map(BetaRawContentBlockDeltaEvent::delta)
                        .flatMap(delta -> delta.text())
                        .ifPresent(textDelta -> text.append(textDelta.text()));
            });
        }
        return text.toString();
    }

    /** Consumes one non-beta stream, forwarding text deltas only - thinking deltas are not the answer. */
    private void streamAnswer(AnthropicClient client,
                              com.anthropic.models.messages.MessageCreateParams params,
                              TokenSink sink,
                              StringBuilder answer) {
        try (StreamResponse<RawMessageStreamEvent> response = client.messages().createStreaming(params)) {
            response.stream().forEach(event -> event.contentBlockDelta()
                    .map(RawContentBlockDeltaEvent::delta)
                    .flatMap(delta -> delta.text())
                    .ifPresent(textDelta -> {
                        String chunk = textDelta.text();
                        answer.append(chunk);
                        sink.onChunk(chunk);
                    }));
        }
    }

    private com.anthropic.models.messages.MessageCreateParams askParams(String system,
                                                                        String question,
                                                                        long maxTokens,
                                                                        boolean adaptiveThinking) {
        com.anthropic.models.messages.MessageCreateParams.Builder builder =
                com.anthropic.models.messages.MessageCreateParams.builder()
                        .model(properties.anthropicModel())
                        .maxTokens(maxTokens)
                        .system(system)
                        .addUserMessage(question);
        if (adaptiveThinking) {
            builder.thinking(ThinkingConfigAdaptive.builder().build());
        } else {
            builder.thinking(ThinkingConfigDisabled.builder().build());
        }
        return builder.build();
    }

    /**
     * Declares every MCP source twice, as both halves of the API's MCP support.
     *
     * <p>The source's configured name is used as the server label, which is why it turns up verbatim in the tool-call
     * events and therefore in the progress line a user reads. The auth token is passed straight to the request and
     * never logged; it exists in memory only inside the {@link McpSourceRef} for the duration of this call.
     */
    private void declareMcpSources(MessageCreateParams.Builder builder, RunContext context) {
        for (McpSourceRef source : context.mcpSources()) {
            BetaRequestMcpServerUrlDefinition.Builder definition = BetaRequestMcpServerUrlDefinition.builder()
                    .name(source.name())
                    .url(source.endpointUrl());
            if (source.hasAuthToken()) {
                definition.authorizationToken(source.authToken());
            }
            builder.addMcpServer(definition.build());
            builder.addTool(BetaMcpToolset.builder().mcpServerName(source.name()).build());
        }
        if (!context.mcpSources().isEmpty()) {
            builder.addBeta(AnthropicBeta.MCP_CLIENT_2025_11_20);
        }
    }

    /** Wraps the shared schema in Anthropic's structured-output config. */
    private static BetaOutputConfig reportOutputConfig() {
        Map<String, JsonValue> schema = new LinkedHashMap<>();
        ReportJsonSchema.asMap().forEach((key, value) -> schema.put(key, JsonValue.from(value)));
        return BetaOutputConfig.builder()
                .format(BetaJsonOutputFormat.builder()
                        .schema(BetaJsonOutputFormat.Schema.builder().additionalProperties(schema).build())
                        .build())
                .build();
    }

    private AnthropicClient client() {
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(REQUEST_TIMEOUT);
        // Only touched when an override is configured, so an unset value leaves the SDK's own default in place
        // rather than replacing it with this application's idea of what that default is.
        properties.anthropicBaseUrl().ifPresent(builder::baseUrl);
        return builder.build();
    }

    /**
     * A short, safe description of an SDK failure.
     *
     * <p>The SDK's own message can contain the request body, which for this application means the prompt - which
     * means crawled page content, and for an MCP source the authorization token. None of that may reach a user or a
     * log line, so only the exception's class and status are used. The full detail is still available by attaching
     * the cause, which stays server-side.
     */
    private static String reason(AnthropicException e) {
        if (e instanceof com.anthropic.errors.AnthropicServiceException service) {
            return "the provider returned HTTP " + service.statusCode();
        }
        return e.getClass().getSimpleName();
    }
}
