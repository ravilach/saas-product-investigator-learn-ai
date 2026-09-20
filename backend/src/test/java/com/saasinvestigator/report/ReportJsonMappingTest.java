package com.saasinvestigator.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.saasinvestigator.product.SourceType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proves the report model serialises correctly under <b>both</b> Jackson generations.
 *
 * <p>This project runs two Jacksons at once, which is not a mistake but is a trap: Spring Boot 4 serialises HTTP
 * with Jackson 3 ({@code tools.jackson}), while jjwt and the LLM SDKs pull in Jackson 2 ({@code com.fasterxml}), and
 * model output is parsed with a Jackson 2 mapper. See {@code docs/decisions/0003-jackson-2-and-3.md}.
 *
 * <p>The specific thing at risk is the {@code @JsonValue}/{@code @JsonCreator} pair on {@link ChangeCategory} and
 * {@link Confidence}: those annotations come from {@code com.fasterxml.jackson.annotation}, a package Jackson 3 also
 * reads. That is the claim the ADR rests on, so it gets a test rather than a comment. If it were false the symptom
 * would be split-brained - categories lowercase in parsed model output and uppercase in API responses - and it would
 * surface as a frontend bug about badge colours, a long way from the cause.
 */
class ReportJsonMappingTest {

    /** Jackson 3, the same generation Spring Boot 4 uses for HTTP responses. */
    private final tools.jackson.databind.ObjectMapper jackson3 = new tools.jackson.databind.ObjectMapper();

    /** Jackson 2, the same generation used to parse structured output back from an LLM. */
    private final com.fasterxml.jackson.databind.ObjectMapper jackson2 =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @Test
    void jackson3WritesCategoriesAndConfidencesLowercase() {
        assertThat(jackson3.writeValueAsString(ChangeCategory.PRICING)).isEqualTo("\"pricing\"");
        assertThat(jackson3.writeValueAsString(Confidence.HIGH)).isEqualTo("\"high\"");
    }

    @Test
    void jackson2WritesCategoriesAndConfidencesLowercase() throws Exception {
        assertThat(jackson2.writeValueAsString(ChangeCategory.PRICING)).isEqualTo("\"pricing\"");
        assertThat(jackson2.writeValueAsString(Confidence.HIGH)).isEqualTo("\"high\"");
    }

    @Test
    void jackson2ParsesAChangeAsAModelWouldEmitIt() throws Exception {
        // Deliberately shaped like real model output, including a category in the wrong case - the path that
        // actually matters, since this is the mapper that reads what the LLM wrote.
        String json = """
                {"sourceName":"Changelog","sourceType":"SAAS_URL","category":"Pricing",
                 "description":"The Team plan went from $20 to $25 per seat.",
                 "confidence":"high","evidenceSnippet":"Team - $25/user/month"}""";

        Change change = jackson2.readValue(json, Change.class);

        assertThat(change.category()).isEqualTo(ChangeCategory.PRICING);
        assertThat(change.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(change.sourceType()).isEqualTo(SourceType.SAAS_URL);
        assertThat(change.evidenceSnippet()).isEqualTo("Team - $25/user/month");
    }

    @Test
    void jackson2SurvivesAnInventedCategoryInModelOutput() throws Exception {
        String json = """
                {"sourceName":"Docs","sourceType":"WEBSITE","category":"billing-change",
                 "description":"Invoices are now issued monthly.","confidence":"quite sure",
                 "evidenceSnippet":"Invoices are issued monthly."}""";

        Change change = jackson2.readValue(json, Change.class);

        // The whole point of the lenient creators: the description and evidence are fine, so the report is fine.
        assertThat(change.category()).isEqualTo(ChangeCategory.OTHER);
        assertThat(change.confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(change.description()).isEqualTo("Invoices are now issued monthly.");
    }

    @Test
    void jackson3WritesAWholeReportWithLowercaseCategoriesAndUppercaseMetadata() {
        ChangeReport report = new ChangeReport("product-1", "admin", AnalysisDepth.NUCLEAR,
                List.of(new SourceInclusion("Changelog", SourceType.SAAS_URL, Instant.parse("2026-09-20T10:00:00Z"))),
                "One pricing change.",
                List.of(new Change("Changelog", SourceType.SAAS_URL, ChangeCategory.PRICING,
                        "Team plan went up.", Confidence.HIGH, "Team - $25/user/month")));

        String json = jackson3.writeValueAsString(report);

        // Categories and confidence are lowercase because the prompt uses that spelling; the enums the backend owns
        // outright stay uppercase. The mixture is intentional, so it is pinned here rather than left to drift.
        assertThat(json).contains("\"category\":\"pricing\"", "\"confidence\":\"high\"");
        assertThat(json).contains("\"analysisDepth\":\"NUCLEAR\"", "\"runType\":\"STANDARD\"",
                "\"sourceType\":\"SAAS_URL\"");
        assertThat(json).contains("\"mcpHistoryLimited\":false");
    }
}
