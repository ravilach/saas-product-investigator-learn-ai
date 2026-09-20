package com.saasinvestigator.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.saasinvestigator.product.SourceType;
import com.saasinvestigator.report.Change;
import com.saasinvestigator.report.ChangeCategory;
import com.saasinvestigator.report.ChangeReport;
import com.saasinvestigator.report.Confidence;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link ReportPdfWriter} by rendering real PDFs and reading the text back out of them.
 *
 * <h2>Why the text is extracted rather than the HTML inspected</h2>
 *
 * <p>Asserting on the generated markup would pass while producing a file nobody can open. openhtmltopdf parses strict
 * XHTML, and the single most likely way for this writer to break is an unescaped character in text that a model
 * extracted from a crawled web page - which is every string in a report. A test that renders and then reads confirms
 * both that the parse succeeded and that the content survived it. PDFBox is already on the classpath as
 * openhtmltopdf's own renderer, so this costs no new dependency.
 */
class ReportPdfWriterTest {

    private final ReportPdfWriter writer = new ReportPdfWriter();

    // ----- Content -----

    @Test
    void writesAPdfContainingTheProductNameSummaryFindingsAndFooter() throws Exception {
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
                .contains("High confidence")
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

    // ----- Untrusted text -----

    @Test
    void rendersCrawledTextContainingXmlMetacharactersInsteadOfFailingToParseIt() throws Exception {
        // The realistic failure: a pricing page saying "Team & Enterprise <beta>" ends up in a finding, and an
        // unescaped ampersand is a fatal XHTML parse error rather than something a browser would forgive. Reaching
        // the assertions at all is most of the point here - a regression throws rather than returning wrong text.
        ChangeReport report = ExportFixtures.standardReport();
        report.setChanges(List.of(new Change("Docs & Guides <v2>", SourceType.WEBSITE, ChangeCategory.PRICING,
                "Plans \"Team\" & <Enterprise> cost 5 > 4", Confidence.HIGH, "<td>$25 & up</td>")));
        report.setOverallSummary("Pricing & packaging \"changed\" <again>");

        String text = render("Acme & Sons <EU>", report);

        // Short fragments, because PDF text extraction reflows a rendered line and a long expected string would be
        // asserting on where the layout engine chose to wrap.
        assertThat(text)
                .contains("Acme & Sons <EU>")
                .contains("& packaging \"changed\" <again>")
                .contains("<Enterprise>")
                .contains("<td>$25 & up</td>");
    }

    @Test
    void escapesAllFiveXmlMetacharactersIncludingBothQuoteFormsSoAttributesAreSafeToo() {
        assertThat(ReportPdfWriter.xml("a & b < c > d \" e ' f"))
                .isEqualTo("a &amp; b &lt; c &gt; d &quot; e &#39; f");
    }

    @Test
    void dropsControlCharactersThatXmlCannotRepresentEvenWhenEscaped() {
        // XML 1.0 has no representation for 0x0B, escaped or not, so a stray vertical tab in crawled page text would
        // fail the parse. Tabs and newlines are legal and are kept.
        assertThat(ReportPdfWriter.xml("before\u000Bafter\tkept\nkept")).isEqualTo("beforeafter\tkept\nkept");
    }

    @Test
    void treatsNullAsEmptyRatherThanWritingTheWordNullIntoTheDocument() {
        assertThat(ReportPdfWriter.xml(null)).isEmpty();
    }

    // ----- Shape -----

    @Test
    void producesBytesThatBeginWithThePdfMagicNumberSoABrowserRecognisesTheDownload() {
        byte[] pdf = writer.write(content("Acme Analytics", ExportFixtures.standardReport()));

        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(pdf.length).isGreaterThan(1000);
    }

    // ----- Helpers -----

    private String render(String productName, ChangeReport report) throws Exception {
        byte[] pdf = writer.write(content(productName, report));
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static ReportContent content(String productName, ChangeReport report) {
        return ReportContent.of(productName, report, ExportFixtures.EXPORTED_AT);
    }
}
