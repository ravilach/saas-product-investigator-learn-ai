package com.saasinvestigator.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests the JSON Schema both providers enforce.
 *
 * <p>Two things make this worth a test rather than a code read. First, the schema and the prose contract in
 * {@link PromptBuilder#systemPrompt()} describe the same shape twice and must stay in step; the enum lists are the
 * part most likely to drift, and they drift silently. Second, both providers' strict modes have requirements that look
 * like boilerplate and are not - {@code additionalProperties: false} and every property listed in {@code required} -
 * so a request that omits them is rejected at the API with an error about the schema rather than about the report.
 */
class ReportJsonSchemaTest {

    private final Map<String, Object> schema = ReportJsonSchema.asMap();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object node) {
        return (Map<String, Object>) node;
    }

    private Map<String, Object> changeSchema() {
        return map(map(map(schema.get("properties")).get("changes")).get("items"));
    }

    @Test
    void theTopLevelIsAClosedObjectRequiringBothFields() {
        assertThat(schema).containsEntry("type", "object").containsEntry("additionalProperties", false);
        assertThat(schema.get("required")).isEqualTo(List.of("overallSummary", "changes"));
    }

    @Test
    void everyDeclaredPropertyOfAChangeIsAlsoRequired() {
        Map<String, Object> change = changeSchema();
        Map<String, Object> properties = map(change.get("properties"));

        // Both strict modes require this, and the failure is not obvious from the error: a property declared but not
        // required is rejected by the API, so the run fails before the model is ever asked anything.
        assertThat(change.get("required")).asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
                .containsExactlyInAnyOrderElementsOf(properties.keySet());
        assertThat(change).containsEntry("additionalProperties", false);
    }

    @Test
    void theEnumsAreGeneratedFromTheJavaEnumsRatherThanRestatedAsLiterals() {
        Map<String, Object> properties = map(changeSchema().get("properties"));

        // Generated, so adding a ChangeCategory value cannot leave the schema behind. A literal list here would
        // reject the new value at the API, which is at least loud - but a literal list that was updated and a prompt
        // that was not is silent, which is why PromptBuilderTest asserts the same names appear in the prose.
        assertThat(map(properties.get("category")).get("enum")).isEqualTo(PromptBuilder.categoryWireNames());
        assertThat(map(properties.get("confidence")).get("enum")).isEqualTo(PromptBuilder.confidenceWireNames());
    }

    @Test
    void evidenceSnippetIsNullableRatherThanOptional() {
        Map<String, Object> evidence = map(map(changeSchema().get("properties")).get("evidenceSnippet"));

        // The distinction matters because strict mode requires every property to be present. A change that is the
        // absence of something has nothing to quote, and a non-nullable string there would force the model to invent
        // a quote rather than admit it has none.
        assertThat(evidence.get("type")).isEqualTo(List.of("string", "null"));
    }

    @Test
    void theArrayOfChangesIsExplicitlyAllowedToBeEmpty() {
        Map<String, Object> changes = map(map(schema.get("properties")).get("changes"));

        assertThat(changes).containsEntry("type", "array");
        assertThat((String) changes.get("description")).contains("May be empty");
    }
}
