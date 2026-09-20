package com.saasinvestigator.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON Schema for a change report, as plain Java maps, shared by both providers.
 *
 * <p>Both vendors take a JSON Schema for structured output and both take it as a nested object, so the schema itself
 * is vendor-neutral even though the wrapper around it is not: Anthropic wants it inside a
 * {@code BetaJsonOutputFormat}, OpenAI inside a {@code ResponseFormatJsonSchema}. Keeping the schema here and the
 * wrapping in each provider means the two cannot drift into enforcing subtly different contracts - which would show
 * up as "the OpenAI reports have no evidence snippets" and take a while to trace back to a schema copy-paste.
 *
 * <p>Deliberately plain {@code Map}/{@code List} rather than a generated schema from a Java class. Both SDKs can
 * derive a schema from an annotated type, but the shape wanted here is not quite any Java type in this codebase:
 * {@code sourceType} is resolved from configuration rather than asked for (see
 * {@link PromptBuilder#resolveSourceType}), {@code evidenceSnippet} is deliberately nullable, and
 * {@code confidence}/{@code category} are constrained to the lowercase wire names rather than the Java enum
 * constants. A generated schema would have to be fought on all four points.
 *
 * <p>This is enforcement, not instruction: the prompt in {@link PromptBuilder#systemPrompt()} describes the same
 * shape in prose, because a schema tells a model what is structurally legal and a prompt tells it what to put there.
 * The two must be kept in step, and that is the cost of having both.
 */
final class ReportJsonSchema {

    private ReportJsonSchema() {
    }

    /**
     * @return the schema for the object a model must return, as nested {@code Map}s and {@code List}s that either
     *     SDK will serialise directly
     */
    static Map<String, Object> asMap() {
        return object(
                Map.of(
                        "overallSummary", Map.of(
                                "type", "string",
                                "description", "A few sentences to a few paragraphs describing what changed "
                                        + "overall and why it matters, written for someone who has not read the "
                                        + "sources."),
                        "changes", Map.of(
                                "type", "array",
                                "description", "Every substantive change found. May be empty: nothing changed is a "
                                        + "valid result.",
                                "items", changeSchema())),
                List.of("overallSummary", "changes"));
    }

    private static Map<String, Object> changeSchema() {
        return object(
                Map.of(
                        "sourceName", Map.of(
                                "type", "string",
                                "description", "The name of the source this change was found in, exactly as given "
                                        + "in the prompt."),
                        "category", Map.of(
                                "type", "string",
                                "enum", PromptBuilder.categoryWireNames(),
                                "description", "The kind of change."),
                        "description", Map.of(
                                "type", "string",
                                "description", "What changed, in prose."),
                        "confidence", Map.of(
                                "type", "string",
                                "enum", PromptBuilder.confidenceWireNames(),
                                "description", "How certain this change is real and correctly described."),
                        "evidenceSnippet", Map.of(
                                // Nullable rather than optional, because both providers' strict modes require
                                // every declared property to be present. A change that is the absence of something
                                // has nothing to quote, and forcing a string there would produce an invented quote.
                                "type", List.of("string", "null"),
                                "description", "A short verbatim quote from the source supporting this change, or "
                                        + "null when the change is that something is no longer present.")),
                List.of("sourceName", "category", "description", "confidence", "evidenceSnippet"));
    }

    /** Assembles one object schema with {@code additionalProperties: false}, which both strict modes require. */
    private static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }
}
