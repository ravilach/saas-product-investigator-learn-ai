package com.saasinvestigator.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.saasinvestigator.report.ChangeReport;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link ReportDocxWriter} by writing real documents and reading them back with POI's own extractor.
 *
 * <p>Reading back rather than inspecting the model it just built, for the same reason as the PDF test: a document that
 * assembles cleanly can still be one Word refuses to open. Round-tripping through {@code XWPFDocument} proves the parts
 * and relationships are coherent, which is the failure mode POI makes easy to produce.
 *
 * <p>The class deliberately asserts the same content the PDF test does. That overlap is the requirement - both formats
 * carry identical content - and duplicating the expectations is how a divergence gets caught, since nobody opens both
 * files for the same report.
 */
class ReportDocxWriterTest {

    private final ReportDocxWriter writer = new ReportDocxWriter();

    // ----- Content -----

    @Test
    void writesADocumentContainingTheProductNameSummaryFindingsAndFooter() throws Exception {
        String text = render("Acme Analytics", ExportFixtures.standardReport());

        assertThat(text)
                .contains("Acme Analytics")
                .contains("Standard run")
                .contains("1 June 2026 at 09:30 UTC")
                .contains("Regular")
                .contains("dana")
                .contains("Two things changed this week.")
                .contains("Docs site")
                .contains("consulted live, not stored")
                .contains("Pricing")
                .contains("$25 per seat")
                .contains("Team - $25/seat/month")
                .contains("High")
                .contains("Feature")
                .contains("Single sign-on")
                .contains("Exported 2 June 2026 at 14:05 UTC")
                .contains("report-1");
    }

    @Test
    void statesTheComparedWindowAndTheMcpCaveatForACustomRangeReport() throws Exception {
        String text = render("Acme Analytics", ExportFixtures.compareReport());

        assertThat(text)
                .contains("Custom range compare")
                .contains("1 May 2026 to 1 June 2026")
                .contains("never stored by this application");
    }

    @Test
    void saysSoExplicitlyWhenThereIsNothingToReportRatherThanLeavingABlankSection() throws Exception {
        String text = render("Acme Analytics", ExportFixtures.emptyReport());

        assertThat(text)
                .contains("No summary was recorded for this run.")
                .contains("No sources were recorded for this run.")
                .contains("No changes were identified.");
    }

    @Test
    void buildsTheFindingsTableWithAHeaderRowAndOneRowPerFinding() throws Exception {
        byte[] docx = writer.write(content("Acme Analytics", ExportFixtures.standardReport()));

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docx))) {
            assertThat(document.getTables()).singleElement().satisfies(table -> {
                // One header row plus the report's two findings. Asserted on the structure rather than on extracted
                // text because a table is what makes the findings sortable after somebody pastes them elsewhere, and
                // text extraction would pass just as well on a list of paragraphs.
                assertThat(table.getNumberOfRows()).isEqualTo(3);
                assertThat(table.getRow(0).getCell(0).getText()).isEqualTo("Category");
                assertThat(table.getRow(0).getCell(3).getText()).isEqualTo("Confidence");
                // Feature first: the rows follow the ChangeCategory declaration order the content seam imposes, not the
                // order the model happened to emit its findings in.
                assertThat(table.getRow(1).getCell(0).getText()).isEqualTo("Feature");
                assertThat(table.getRow(1).getCell(2).getText()).isEqualTo("Changelog MCP");
                assertThat(table.getRow(2).getCell(0).getText()).isEqualTo("Pricing");
                assertThat(table.getRow(2).getCell(2).getText()).isEqualTo("Docs site");
            });
        }
    }

    // ----- Untrusted text -----

    @Test
    void writesCrawledTextContainingMarkupCharactersAsLiteralTextRatherThanAsStructure() throws Exception {
        ChangeReport report = ExportFixtures.standardReport();
        report.setOverallSummary("Pricing & packaging \"changed\" <again>");

        String text = render("Acme & Sons <EU>", report);

        assertThat(text)
                .contains("Acme & Sons <EU>")
                .contains("Pricing & packaging \"changed\" <again>");
    }

    // ----- Shape -----

    @Test
    void producesBytesThatBeginWithTheZipMagicNumberBecauseADocxIsAZipArchive() {
        byte[] docx = writer.write(content("Acme Analytics", ExportFixtures.standardReport()));

        assertThat(new String(docx, 0, 2, StandardCharsets.US_ASCII)).isEqualTo("PK");
        assertThat(docx.length).isGreaterThan(1000);
    }

    // ----- Helpers -----

    private String render(String productName, ChangeReport report) throws Exception {
        byte[] docx = writer.write(content(productName, report));
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docx));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    private static ReportContent content(String productName, ChangeReport report) {
        return ReportContent.of(productName, report, ExportFixtures.EXPORTED_AT);
    }
}
