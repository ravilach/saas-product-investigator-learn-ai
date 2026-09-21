package com.saasinvestigator.llm;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.errors.BadRequestException;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputItemAddedEvent;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponseTextConfig;
import com.openai.models.responses.Tool;
import com.saasinvestigator.error.ProviderUnavailableException;
import com.saasinvestigator.report.AnalysisDepth;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenAI's implementation of {@link LlmProvider}, using the Responses API.
 *
 * <p>Same contract and the same prompts as {@link AnthropicLlmProvider}; everything different here is the API's
 * shape rather than a different idea about the task. Like its Anthropic counterpart it is constructed per run by
 * {@link LlmProviderResolver} rather than being a Spring bean, because an instance is bound to one resolved API key.
 *
 * <h2>Three API-specific decisions</h2>
 *
 * <p><b>MCP approval is set to never.</b> The Responses API defaults MCP tool calls to requiring approval, which for
 * a server-side batch run means the model asks permission and the run stalls until something answers - and nothing
 * here would. Approval is a safeguard aimed at interactive agents that can take destructive actions; the MCP servers
 * here are read-only sources a user deliberately configured, and the run happens with no human watching the
 * conversation. Turning it off is what makes the run complete at all.
 *
 * <p><b>{@code store} is false.</b> The API keeps responses server-side by default, which would make the corrective
 * retry trivial - just reference {@code previousResponseId}. It would also mean every crawled page of every product,
 * plus MCP tool output, is retained on the provider's side beyond the call. This application's whole secrets posture
 * is about not leaving copies of things lying around, so the retry replays the conversation explicitly instead,
 * exactly as the Anthropic path does.
 *
 * <p><b>Depth maps to reasoning effort as well as output length.</b> {@code AnalysisDepth} sets the output ceiling
 * everywhere, but on this API it can also set how much the model thinks first, which is closer to what a user means
 * by "nuclear" than a bigger output budget alone. See {@link #effortFor}.
 */
public class OpenAiLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiLlmProvider.class);

    /** See {@link AnthropicLlmProvider#FALLBACK_MAX_OUTPUT_TOKENS} - same reasoning, same value. */
    static final long FALLBACK_MAX_OUTPUT_TOKENS = 8_192L;

    /** Output ceiling for a question. */
    static final long ASK_MAX_OUTPUT_TOKENS = 16_384L;

    /** Schema name sent with the strict JSON format. Appears in provider-side errors, so it is worth being legible. */
    private static final String SCHEMA_NAME = "saas_change_report";

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
    public OpenAiLlmProvider(String apiKey,
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
        return LlmProviderType.OPENAI;
    }

    @Override
    public GeneratedReport generateChangeReport(RunContext context, LlmActivityListener listener) {
        OpenAIClient client = client();
        try {
            String first = streamReport(client, context, null, null, listener);
            return parser.parseWithRetry(context, first,
                    correction -> streamReport(client, context, first, correction, LlmActivityListener.NONE));
        } catch (ChangeReportJsonParser.MalformedReportException e) {
            throw new ProviderUnavailableException(
                    "OpenAI returned a response this application could not use: " + e.getMessage(), e);
        } catch (OpenAIException e) {
            throw new ProviderUnavailableException("OpenAI could not complete this analysis: " + reason(e), e);
        } finally {
            client.close();
        }
    }

    @Override
    public String answerQuestion(AskContext context, TokenSink sink) {
        OpenAIClient client = client();
        try {
            ResponseCreateParams params = ResponseCreateParams.builder()
                    .model(properties.openAiModel())
                    .instructions(promptBuilder.askSystemPrompt())
                    .input(promptBuilder.askPrompt(context))
                    .maxOutputTokens(ASK_MAX_OUTPUT_TOKENS)
                    .reasoning(Reasoning.builder().effort(ReasoningEffort.MEDIUM).build())
                    .store(false)
                    .build();

            StringBuilder answer = new StringBuilder();
            consume(client, params, LlmActivityListener.NONE, chunk -> {
                answer.append(chunk);
                sink.onChunk(chunk);
            });
            return answer.toString();
        } catch (OpenAIException e) {
            throw new ProviderUnavailableException("OpenAI could not answer this question: " + reason(e), e);
        } finally {
            client.close();
        }
    }

    /**
     * Runs one report request and returns the accumulated text.
     *
     * <p>When {@code correction} is non-null this is the corrective retry, sent as an explicit three-item
     * conversation - the original prompt, the model's unusable answer, the correction - rather than as a reference to
     * a stored response, for the reason given in this class's documentation.
     */
    private String streamReport(OpenAIClient client,
                               RunContext context,
                               String priorAnswer,
                               String correction,
                               LlmActivityListener listener) {
        String prompt = promptBuilder.runPrompt(context);

        LongFunction<ResponseCreateParams> params = maxTokens -> {
            ResponseCreateParams.Builder builder = ResponseCreateParams.builder()
                    .model(properties.openAiModel())
                    .instructions(promptBuilder.systemPrompt())
                    .maxOutputTokens(maxTokens)
                    .reasoning(Reasoning.builder().effort(effortFor(context.analysisDepth())).build())
                    .text(reportTextConfig())
                    .store(false);

            if (correction == null) {
                builder.input(prompt);
            } else {
                List<ResponseInputItem> conversation = new ArrayList<>(3);
                conversation.add(message(EasyInputMessage.Role.USER, prompt));
                conversation.add(message(EasyInputMessage.Role.ASSISTANT,
                        priorAnswer == null || priorAnswer.isBlank() ? "(no output)" : priorAnswer));
                conversation.add(message(EasyInputMessage.Role.USER, correction));
                builder.inputOfResponse(conversation);
            }

            for (McpSourceRef source : context.mcpSources()) {
                builder.addTool(mcpTool(source));
            }
            return builder.build();
        };

        long wanted = context.analysisDepth().maxOutputTokens();
        try {
            return collect(client, params.apply(wanted), listener);
        } catch (BadRequestException e) {
            if (wanted <= FALLBACK_MAX_OUTPUT_TOKENS) {
                throw e;
            }
            log.warn("OpenAI rejected a {}-token output request for model {} ({}); retrying at {} tokens.",
                    wanted, properties.openAiModel(), reason(e), FALLBACK_MAX_OUTPUT_TOKENS);
            return collect(client, params.apply(FALLBACK_MAX_OUTPUT_TOKENS), listener);
        }
    }

    private String collect(OpenAIClient client, ResponseCreateParams params, LlmActivityListener listener) {
        StringBuilder text = new StringBuilder();
        consume(client, params, listener, text::append);
        return text.toString();
    }

    /**
     * Consumes one response stream, forwarding output text to {@code text} and tool activity to {@code listener}.
     *
     * <p>The MCP details come from {@code response.output_item.added}, not from the {@code mcp_call.in_progress}
     * event, because only the former carries the item itself - and therefore the server label and tool name that
     * make the progress line specific. {@code mcp_call.in_progress} carries an item id and nothing a user could
     * read.
     */
    private void consume(OpenAIClient client,
                         ResponseCreateParams params,
                         LlmActivityListener listener,
                         TokenSink text) {
        try (StreamResponse<ResponseStreamEvent> response = client.responses().createStreaming(params)) {
            response.stream().forEach(event -> {
                event.outputTextDelta().ifPresent(delta -> text.onChunk(delta.delta()));

                event.outputItemAdded()
                        .map(ResponseOutputItemAddedEvent::item)
                        .flatMap(ResponseOutputItem::mcpCall)
                        .ifPresent(call -> listener.onActivity(
                                "Calling " + call.serverLabel() + " MCP tool: " + call.name()));

                event.mcpListToolsInProgress()
                        .ifPresent(unused -> listener.onActivity("Listing the tools an MCP server offers"));

                event.mcpCallFailed()
                        .ifPresent(unused -> listener.onActivity("An MCP tool call returned an error"));

                // A refusal is not a transport failure, so it would otherwise surface as an empty response and
                // then as a JSON parse error two layers away from the actual cause.
                event.refusalDone().ifPresent(refusal -> {
                    throw new ProviderUnavailableException(
                            "OpenAI declined to analyse this product: " + refusal.refusal());
                });

                event.error().ifPresent(error -> {
                    throw new ProviderUnavailableException(
                            "OpenAI reported an error mid-response: " + error.message());
                });
            });
        }
    }

    /**
     * Maps analysis depth to reasoning effort.
     *
     * <p>{@code LOW} rather than {@code MINIMAL} for a short run: the depth names describe how much output a user
     * wants, not permission to do the comparison badly, and a minimal-effort pass over two versions of a pricing
     * page is where a model starts reporting reordered bullet points. {@code HIGH} rather than {@code MAX} for
     * nuclear, because the marginal findings above {@code HIGH} do not currently justify the latency on a run
     * someone is watching a progress bar for.
     */
    static ReasoningEffort effortFor(AnalysisDepth depth) {
        return switch (depth) {
            case SHORT -> ReasoningEffort.LOW;
            case REGULAR -> ReasoningEffort.MEDIUM;
            case NUCLEAR -> ReasoningEffort.HIGH;
        };
    }

    /** One MCP source as a Responses API tool. The configured name becomes the server label the user sees. */
    private static Tool mcpTool(McpSourceRef source) {
        Tool.Mcp.Builder mcp = Tool.Mcp.builder()
                .serverLabel(source.name())
                .serverUrl(source.endpointUrl())
                .requireApproval(Tool.Mcp.RequireApproval.McpToolApprovalSetting.NEVER);
        if (source.hasAuthToken()) {
            mcp.authorization(source.authToken());
        }
        return Tool.ofMcp(mcp.build());
    }

    /** Wraps the shared schema in the Responses API's strict JSON format. */
    private static ResponseTextConfig reportTextConfig() {
        Map<String, JsonValue> schema = new LinkedHashMap<>();
        ReportJsonSchema.asMap().forEach((key, value) -> schema.put(key, JsonValue.from(value)));
        return ResponseTextConfig.builder()
                .format(ResponseFormatTextJsonSchemaConfig.builder()
                        .name(SCHEMA_NAME)
                        .schema(ResponseFormatTextJsonSchemaConfig.Schema.builder()
                                .additionalProperties(schema)
                                .build())
                        .strict(true)
                        .build())
                .build();
    }

    private static ResponseInputItem message(EasyInputMessage.Role role, String content) {
        return ResponseInputItem.ofEasyInputMessage(
                EasyInputMessage.builder().role(role).content(content).build());
    }

    private OpenAIClient client() {
        OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(REQUEST_TIMEOUT);
        // See AnthropicLlmProvider's equivalent: absent means "leave the SDK's default alone".
        properties.openAiBaseUrl().ifPresent(builder::baseUrl);
        return builder.build();
    }

    /** See {@link AnthropicLlmProvider}'s equivalent: the SDK's message can contain the request body. */
    private static String reason(OpenAIException e) {
        if (e instanceof OpenAIServiceException service) {
            return "the provider returned HTTP " + service.statusCode();
        }
        return e.getClass().getSimpleName();
    }
}
