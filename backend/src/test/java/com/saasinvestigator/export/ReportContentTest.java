package com.saasinvestigator.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.saasinvestigator.report.ChangeCategory;
import com.saasinvestigator.report.ChangeReport;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link ReportContent}, the seam that makes "the PDF and the DOCX contain identical content" a structural fact
 * rather than a convention.
 *
 * <p>Everything asserted here is a decision that a reader of the record alone could not verify: that a blank summary is
 * replaced rather than rendered as an empty section, that an MCP source's missing fetch time becomes an explanation
 * instead of the word "null", that the caveat appears only when the flag is set, and that the filename can never carry a
 * character that would break a {@code Content-Disposition} header. The last of those is the reason this class exists at
 * all - a product named {@code Acme "Pro"} is a header-injection bug waiting for someone to click Export.
 */
class ReportContentTest {

    // ----- Labels -----

    @Test
    void formatsEnumNamesAsProseSoTheExportDoesNotShoutItsInternalConstants() {
        ReportContent content = ReportContent.of("Acme", ExportFixtures.compareReport(), ExportFixtures.EXPORTED_AT);

        assertThat(content.runTypeLabel()).isEqualTo("Custom range compare");
        assertThat(content.depthLabel()).isEqualTo("Regular");
        // Feature before Pricing even though the fixture lists the pricing change first: sections follow the
        // ChangeCategory declaration order, so two runs of the same product put their headings in the same places and a
        // reader comparing two exports side by side is not also comparing two layouts.
        assertThat(content.sections())
                .extracting(ReportContent.CategorySection::label)
                .containsExactly("Feature", "Pricing");
    }

    @Test
    void formatsDatesWithAnExplicitZoneSoTheSameFileReadsTheSameEverywhere() {
        ReportContent content = ReportContent.of("Acme", ExportFixtures.standardReport(),
                ExportFixtures.EXPORTED_AT);

        assertThat(content.runAtLabel()).isEqualTo("1 June 2026 at 09:30 UTC");
        assertThat(content.exportedAtLabel()).isEqualTo("2 June 2026 at 14:05 UTC");
    }

    @Test
    void labelsTheComparedWindowOnlyForACustomRangeRun() {
        assertThat(ReportContent.of("Acme", ExportFixtures.standardReport(), ExportFixtures.EXPORTED_AT).rangeLabel())
                .isNull();
        assertThat(ReportContent.of("Acme", ExportFixtures.compareReport(), ExportFixtures.EXPORTED_AT).rangeLabel())
                .isEqualTo("1 May 2026 to 1 June 2026");
    }

    // ----- Sources -----

    @Test
    void explainsWhyAnMcpSourceHasNoFetchTimeRatherThanShowingAnEmptyColumn() {
        ReportContent content = ReportContent.of("Acme", ExportFixtures.standardReport(),
                ExportFixtures.EXPORTED_AT);

        assertThat(content.sources())
                .extracting(ReportContent.SourceLine::name, ReportContent.SourceLine::fetchedAtLabel)
                .containsExactly(
                        tuple("Docs site", "1 June 2026 at 09:30 UTC"),
                        tuple("Changelog MCP", "consulted live, not stored"));
    }

    // ----- Groupings and counts -----

    @Test
    void groupsFindingsByCategoryAndKeepsEveryOneOfThem() {
        ReportContent content = ReportContent.of("Acme", ExportFixtures.standardReport(),
                ExportFixtures.EXPORTED_AT);

        assertThat(content.changeCount()).isEqualTo(2);
        assertThat(content.sections()).hasSize(2);
        assertThat(content.sections().get(1).category()).isEqualTo(ChangeCategory.PRICING);
        assertThat(content.sections().get(1).findings())
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.description()).contains("$25 per seat");
                    assertThat(finding.confidenceLabel()).isEqualTo("High");
                    assertThat(finding.evidenceSnippet()).isEqualTo("Team - $25/seat/month");
                });
        // A finding the model gave no quotable evidence for keeps a null snippet rather than an empty string, so each
        // writer can choose to omit the line entirely instead of printing an empty pair of quotation marks.
        assertThat(content.sections().get(0).category()).isEqualTo(ChangeCategory.FEATURE);
        assertThat(content.sections().get(0).findings().get(0).evidenceSnippet()).isNull();
    }

    // ----- Empty and caveat states -----

    @Test
    void substitutesASentenceForABlankSummarySoNeitherFormatRendersAnEmptyHeading() {
        ReportContent content = ReportContent.of("Acme", ExportFixtures.emptyReport(), ExportFixtures.EXPORTED_AT);

        assertThat(content.overallSummary()).isEqualTo("No summary was recorded for this run.");
        assertThat(content.sections()).isEmpty();
        assertThat(content.sources()).isEmpty();
        assertThat(content.changeCount()).isZero();
    }

    @Test
    void carriesTheMcpCaveatOnlyWhenTheReportSaysItsHistoryWasLimited() {
        assertThat(ReportContent.of("Acme", ExportFixtures.standardReport(), ExportFixtures.EXPORTED_AT).caveat())
                .isNull();
        assertThat(ReportContent.of("Acme", ExportFixtures.compareReport(), ExportFixtures.EXPORTED_AT).caveat())
                .isEqualTo(ReportContent.MCP_HISTORY_CAVEAT);
    }

    @Test
    void theCaveatExplainsWhatWasSubstitutedNotMerelyThatSomethingWas() {
        // A caveat that says only "results may be incomplete" tells a reader nothing they can act on. This asserts the
        // two facts that make it useful: that MCP content is never stored, and what was used instead.
        assertThat(ReportContent.MCP_HISTORY_CAVEAT)
                .contains("never stored")
                .contains("what earlier reports said");
    }

    // ----- Filename -----

    @Test
    void slugsTheProductNameSoAQuoteOrNewlineCannotReachTheContentDispositionHeader() {
        ChangeReport report = ExportFixtures.standardReport();

        String base = ReportContent.of("Acme \"Pro\"\n Suite / EU", report, ExportFixtures.EXPORTED_AT)
                .fileBaseName();

        assertThat(base).isEqualTo("acme-pro-suite-eu-change-report-2026-06-01");
        assertThat(base).matches("[a-z0-9-]+");
    }

    @Test
    void fallsBackToAUsableFilenameWhenTheProductNameHasNoUsableCharacters() {
        assertThat(ReportContent.of("！！！", ExportFixtures.standardReport(), ExportFixtures.EXPORTED_AT)
                .fileBaseName())
                .isEqualTo("report-change-report-2026-06-01");
    }

    @Test
    void datesTheFilenameByTheRunRatherThanByTheExportSoTwoDownloadsOfOneReportAgree() {
        ChangeReport report = ExportFixtures.standardReport();

        String first = ReportContent.of("Acme", report, ExportFixtures.EXPORTED_AT).fileBaseName();
        String later = ReportContent.of("Acme", report, Instant.parse("2027-01-01T00:00:00Z")).fileBaseName();

        assertThat(first).isEqualTo(later).endsWith("2026-06-01");
    }

    @Test
    void returnsImmutableCollectionsSoNoWriterCanEditTheContentTheOtherWillRender() {
        ReportContent content = ReportContent.of("Acme", ExportFixtures.standardReport(),
                ExportFixtures.EXPORTED_AT);

        assertThat(content.sections()).isInstanceOf(List.class);
        assertThatThrownBy(() -> content.sections().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> content.sources().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
