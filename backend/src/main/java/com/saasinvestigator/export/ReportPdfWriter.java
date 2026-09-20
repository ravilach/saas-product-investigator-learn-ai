package com.saasinvestigator.export;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.saasinvestigator.error.ProviderUnavailableException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.springframework.stereotype.Component;

/**
 * Renders a {@link ReportContent} as a PDF.
 *
 * <h2>Why HTML in the middle</h2>
 *
 * <p>openhtmltopdf lays out a document from XHTML and CSS, so the styling is the app's own design tokens written as a
 * stylesheet rather than a second set of layout code expressed in drawing primitives. The practical benefit is that
 * the PDF looks like the screen without anybody maintaining that resemblance by hand.
 *
 * <h2>The one trap: this is XML, not HTML</h2>
 *
 * <p>openhtmltopdf parses <b>strict XHTML</b>. An unescaped ampersand, a bare {@code <br>}, or a {@code &nbsp;} is a
 * fatal parse error rather than something a browser would quietly forgive - and every string in a report is untrusted
 * text that a model extracted from a crawled web page, so ampersands and angle brackets are not hypothetical. Hence:
 *
 * <ul>
 *   <li>every interpolated value goes through {@link #xml(String)}, which escapes {@code & < > " '};</li>
 *   <li>every void element is written self-closed ({@code <br />}, {@code <meta ... />});</li>
 *   <li>no named entities beyond the five XML built-ins - {@code &#160;} is written numerically.</li>
 * </ul>
 *
 * <p>Escaping is also what keeps a crawled page from injecting markup into the document: a page containing
 * {@code <style>} cannot restyle somebody's export.
 */
@Component
class ReportPdfWriter {

    /**
     * The stylesheet, in the light-theme palette.
     *
     * <p>Light theme only, deliberately: a dark-mode PDF is a page of ink when printed, and an export is the one
     * artefact in this application that leaves the screen. The colours are the same tokens the frontend uses, so the
     * document is recognisably from the same product.
     */
    private static final String CSS = """
            @page { size: A4; margin: 18mm 16mm 20mm 16mm; }
            body { font-family: 'Helvetica', sans-serif; font-size: 10pt; color: #12241F; line-height: 1.5; }
            h1 { font-size: 19pt; margin: 0 0 2mm 0; color: #12241F; }
            h2 { font-size: 12pt; margin: 8mm 0 2mm 0; color: #1F7A6D;
                 border-bottom: 0.4mm solid #D6E9E6; padding-bottom: 1.5mm; }
            .subtitle { font-size: 10pt; color: #7C948F; margin: 0 0 6mm 0; }
            table.meta { width: 100%; border-collapse: collapse; margin: 0 0 6mm 0; }
            table.meta td { padding: 1.2mm 0; vertical-align: top; }
            table.meta td.key { width: 34mm; color: #7C948F; }
            .summary { background-color: #F4FBFA; border-left: 1mm solid #2F9E8F;
                       padding: 4mm 5mm; margin: 0 0 4mm 0; }
            .caveat { background-color: #FAF3E6; border-left: 1mm solid #C98A2F;
                      padding: 4mm 5mm; margin: 0 0 6mm 0; color: #6B4A12; }
            .badge { font-size: 9pt; padding: 1mm 2.5mm; border-radius: 2mm; }
            .count { color: #7C948F; font-size: 9pt; }
            .finding { margin: 0 0 4mm 0; padding: 0 0 0 4mm; border-left: 0.5mm solid #D6E9E6; }
            .finding .what { margin: 0 0 1.5mm 0; }
            .finding .attr { color: #7C948F; font-size: 9pt; }
            .evidence { font-family: 'Courier', monospace; font-size: 8.5pt; color: #4A5C58;
                        background-color: #F4FBFA; padding: 2.5mm 3mm; margin: 1.5mm 0 0 0; }
            ul.sources { margin: 0; padding: 0 0 0 5mm; }
            ul.sources li { margin: 0 0 1.5mm 0; }
            .empty { color: #7C948F; font-style: italic; }
            .footer { margin-top: 10mm; padding-top: 3mm; border-top: 0.4mm solid #D6E9E6;
                      color: #7C948F; font-size: 8.5pt; }
            """;

    /**
     * Renders the content.
     *
     * @param content the resolved report content
     * @return the PDF bytes
     * @throws ProviderUnavailableException if rendering fails. Mapped to 503 rather than 500 because the report itself
     *     is intact and stored - only this rendering of it failed - so the honest message is "try again", not "your
     *     data is broken".
     */
    byte[] write(ReportContent content) {
        String html = html(content);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            // No useFastMode() call: it is deprecated in this version because fast mode is now the only renderer, so
            // calling it would be asking for a default and getting a deprecation warning for the trouble.
            // No base URI: the document references no external image, font, or stylesheet, and leaving the base URI
            // null means a URL that somehow appeared in the markup cannot be resolved and fetched during rendering.
            builder.withHtmlContent(html, null);
            builder.withProducer("SaaS Product Investigator");
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (IOException | RuntimeException e) {
            throw new ProviderUnavailableException("The PDF could not be generated. Please try again.", e);
        }
    }

    /** Builds the whole document. Returns strict XHTML; see the class comment on why that matters. */
    private String html(ReportContent content) {
        StringBuilder b = new StringBuilder(4096);
        b.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        b.append("<html xmlns=\"http://www.w3.org/1999/xhtml\"><head>");
        b.append("<meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\" />");
        b.append("<title>").append(xml(content.productName())).append(" - change report</title>");
        b.append("<style>").append(CSS).append("</style>");
        b.append("</head><body>");

        b.append("<h1>").append(xml(content.productName())).append("</h1>");
        b.append("<p class=\"subtitle\">Change report &#183; ").append(xml(content.runTypeLabel())).append("</p>");

        b.append("<table class=\"meta\">");
        metaRow(b, "Run at", content.runAtLabel());
        if (content.rangeLabel() != null) {
            metaRow(b, "Range compared", content.rangeLabel());
        }
        metaRow(b, "Analysis depth", content.depthLabel());
        metaRow(b, "Triggered by", content.runByLabel());
        metaRow(b, "Changes found", String.valueOf(content.changeCount()));
        b.append("</table>");

        if (content.caveat() != null) {
            b.append("<div class=\"caveat\">").append(xml(content.caveat())).append("</div>");
        }

        b.append("<h2>Summary</h2>");
        b.append("<div class=\"summary\">").append(paragraphs(content.overallSummary())).append("</div>");

        b.append("<h2>Sources included</h2>");
        if (content.sources().isEmpty()) {
            b.append("<p class=\"empty\">No sources were recorded for this run.</p>");
        } else {
            b.append("<ul class=\"sources\">");
            for (ReportContent.SourceLine source : content.sources()) {
                b.append("<li>").append(xml(source.name()))
                        .append(" <span class=\"attr count\">(").append(xml(source.typeLabel()))
                        .append(" &#183; ").append(xml(source.fetchedAtLabel())).append(")</span></li>");
            }
            b.append("</ul>");
        }

        b.append("<h2>Changes</h2>");
        if (content.sections().isEmpty()) {
            b.append("<p class=\"empty\">No changes were identified.</p>");
        } else {
            for (ReportContent.CategorySection section : content.sections()) {
                CategoryPalette.Badge badge = CategoryPalette.of(section.category());
                b.append("<p><span class=\"badge\" style=\"background-color: ").append(badge.background())
                        .append("; color: ").append(badge.foreground()).append(";\">")
                        .append(xml(section.label())).append("</span> <span class=\"count\">")
                        .append(section.findings().size()).append("</span></p>");
                for (ReportContent.Finding finding : section.findings()) {
                    b.append("<div class=\"finding\">");
                    b.append("<p class=\"what\">").append(xml(finding.description())).append("</p>");
                    b.append("<p class=\"attr\">").append(xml(finding.sourceName()))
                            .append(" &#183; ").append(xml(finding.confidenceLabel())).append(" confidence</p>");
                    if (finding.evidenceSnippet() != null && !finding.evidenceSnippet().isBlank()) {
                        b.append("<div class=\"evidence\">").append(xml(finding.evidenceSnippet())).append("</div>");
                    }
                    b.append("</div>");
                }
            }
        }

        b.append("<div class=\"footer\">Exported ").append(xml(content.exportedAtLabel()));
        if (content.reportId() != null) {
            b.append(" &#183; report ").append(xml(content.reportId()));
        }
        b.append(" &#183; generated by SaaS Product Investigator</div>");

        b.append("</body></html>");
        return b.toString();
    }

    private static void metaRow(StringBuilder b, String key, String value) {
        b.append("<tr><td class=\"key\">").append(xml(key)).append("</td><td>")
                .append(xml(value)).append("</td></tr>");
    }

    /**
     * Turns text containing blank lines into paragraphs.
     *
     * <p>The model's summary is prose and often multi-paragraph. Without this it would render as one wall of text,
     * because XHTML collapses newlines like any other whitespace.
     */
    private static String paragraphs(String text) {
        StringBuilder b = new StringBuilder();
        for (String block : text.split("\n\\s*\n")) {
            if (!block.isBlank()) {
                // Single newlines inside a block become <br />, so a bulleted summary keeps its line breaks.
                b.append("<p>").append(xml(block.strip()).replace("\n", "<br />")).append("</p>");
            }
        }
        return b.isEmpty() ? "<p></p>" : b.toString();
    }

    /**
     * Escapes text for XML.
     *
     * <p>All five built-ins, including the two quote forms, because interpolated values land in attribute position as
     * well as in text nodes and a single helper that is safe in both places is one fewer thing to get wrong. Control
     * characters are dropped: XML 1.0 has no way to represent them, escaped or not, and a stray {@code 0x0B} in
     * crawled page text would otherwise fail the parse.
     */
    static String xml(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> {
                    if (c == '\n' || c == '\t' || c >= 0x20) {
                        b.append(c);
                    }
                }
            }
        }
        return b.toString();
    }
}
