package com.saasinvestigator.export;

import com.saasinvestigator.error.ProviderUnavailableException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

/**
 * Renders a {@link ReportContent} as a Word document.
 *
 * <h2>Why not HTML here too</h2>
 *
 * <p>POI can be handed HTML only through a converter that produces a document full of literal formatting, which is
 * precisely what makes a DOCX useless for its actual purpose: someone is going to paste this into their own document
 * and expect the text to adopt <em>their</em> styles. So this writer builds real paragraphs and runs.
 *
 * <p>Headings are bold, sized runs rather than {@code setStyle("Heading1")}. A document created by
 * {@code new XWPFDocument()} has no styles part, so naming a style produces a paragraph with a dangling reference -
 * which Word renders as body text, silently. Explicit runs give the same visual result and actually survive the round
 * trip.
 *
 * <p>The findings are a table rather than a run of prose paragraphs because a table is what a reader can scan and, more
 * to the point, what they can sort and filter after pasting it somewhere else.
 */
@Component
class ReportDocxWriter {

    private static final String FONT = "Calibri";
    private static final String INK = "12241F";
    private static final String MUTED = "7C948F";
    private static final String SEAFOAM = "1F7A6D";
    private static final String WARNING_INK = "6B4A12";

    /**
     * Renders the content.
     *
     * @param content the resolved report content
     * @return the DOCX bytes
     * @throws ProviderUnavailableException if the document cannot be assembled; see the note on
     *     {@link ReportPdfWriter#write} for why this is a 503 and not a 500
     */
    byte[] write(ReportContent content) {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            title(doc, content.productName());
            muted(doc, "Change report · " + content.runTypeLabel());

            meta(doc, "Run at", content.runAtLabel());
            if (content.rangeLabel() != null) {
                meta(doc, "Range compared", content.rangeLabel());
            }
            meta(doc, "Analysis depth", content.depthLabel());
            meta(doc, "Triggered by", content.runByLabel());
            meta(doc, "Changes found", String.valueOf(content.changeCount()));

            if (content.caveat() != null) {
                XWPFParagraph p = doc.createParagraph();
                p.setSpacingBefore(160);
                XWPFRun run = p.createRun();
                run.setFontFamily(FONT);
                run.setFontSize(10d);
                run.setItalic(true);
                run.setColor(WARNING_INK);
                run.setText(content.caveat());
            }

            heading(doc, "Summary");
            // Blank-line-separated blocks become separate paragraphs, matching the PDF; single newlines inside a block
            // become soft line breaks, so a bulleted summary keeps its shape in both formats.
            for (String block : content.overallSummary().split("\n\\s*\n")) {
                if (!block.isBlank()) {
                    body(doc, block.strip());
                }
            }

            heading(doc, "Sources included");
            if (content.sources().isEmpty()) {
                italicMuted(doc, "No sources were recorded for this run.");
            } else {
                for (ReportContent.SourceLine source : content.sources()) {
                    XWPFParagraph p = doc.createParagraph();
                    p.setIndentationLeft(360);
                    XWPFRun name = p.createRun();
                    name.setFontFamily(FONT);
                    name.setFontSize(10d);
                    name.setColor(INK);
                    name.setText("•  " + source.name());
                    XWPFRun detail = p.createRun();
                    detail.setFontFamily(FONT);
                    detail.setFontSize(9d);
                    detail.setColor(MUTED);
                    detail.setText("   (" + source.typeLabel() + " · " + source.fetchedAtLabel() + ")");
                }
            }

            heading(doc, "Changes");
            if (content.sections().isEmpty()) {
                italicMuted(doc, "No changes were identified.");
            } else {
                changesTable(doc, content);
            }

            footer(doc, content);

            doc.write(out);
            return out.toByteArray();
        } catch (IOException | RuntimeException e) {
            throw new ProviderUnavailableException("The Word document could not be generated. Please try again.", e);
        }
    }

    /**
     * Writes one table with a header row and one row per finding.
     *
     * <p>Category is a column rather than a series of sub-headings so that the table stays a single sortable block.
     * Rows are created up front rather than appended, because POI's row-append path requires copying a template row
     * and a table created with a known row count needs none of that.
     */
    private static void changesTable(XWPFDocument doc, ReportContent content) {
        int rows = 1 + content.changeCount();
        XWPFTable table = doc.createTable(rows, 4);
        table.setWidth("100%");

        headerCell(table.getRow(0), 0, "Category");
        headerCell(table.getRow(0), 1, "What changed");
        headerCell(table.getRow(0), 2, "Source");
        headerCell(table.getRow(0), 3, "Confidence");

        int row = 1;
        for (ReportContent.CategorySection section : content.sections()) {
            for (ReportContent.Finding finding : section.findings()) {
                XWPFTableRow r = table.getRow(row++);
                cell(r, 0, section.label(), true);
                String what = finding.evidenceSnippet() == null || finding.evidenceSnippet().isBlank()
                        ? finding.description()
                        // The evidence goes in the same cell as the description rather than a fifth column: a quote is
                        // usually longer than everything else in the row, and its own column would squeeze the rest.
                        : finding.description() + "\n“" + finding.evidenceSnippet() + "”";
                cell(r, 1, what, false);
                cell(r, 2, finding.sourceName(), false);
                cell(r, 3, finding.confidenceLabel(), false);
            }
        }
    }

    private static void headerCell(XWPFTableRow row, int column, String text) {
        XWPFParagraph p = row.getCell(column).getParagraphs().get(0);
        XWPFRun run = p.createRun();
        run.setFontFamily(FONT);
        run.setFontSize(9d);
        run.setBold(true);
        run.setColor(SEAFOAM);
        run.setText(text);
    }

    /**
     * Fills a body cell, honouring embedded newlines.
     *
     * <p>{@code setText} on a single run would render a newline as nothing at all, so each line after the first is
     * added with {@link XWPFRun#addBreak()}. This is why the method writes runs instead of calling
     * {@code cell.setText(...)}.
     */
    private static void cell(XWPFTableRow row, int column, String text, boolean bold) {
        XWPFParagraph p = row.getCell(column).getParagraphs().get(0);
        XWPFRun run = p.createRun();
        run.setFontFamily(FONT);
        run.setFontSize(9d);
        run.setBold(bold);
        run.setColor(INK);
        String[] lines = (text == null ? "" : text).split("\n", -1);
        run.setText(lines[0]);
        for (int i = 1; i < lines.length; i++) {
            run.addBreak();
            run.setText(lines[i], i);
        }
    }

    private static void title(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun run = p.createRun();
        run.setFontFamily(FONT);
        run.setFontSize(20d);
        run.setBold(true);
        run.setColor(INK);
        run.setText(text);
    }

    private static void heading(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(280);
        p.setSpacingAfter(60);
        XWPFRun run = p.createRun();
        run.setFontFamily(FONT);
        run.setFontSize(13d);
        run.setBold(true);
        run.setColor(SEAFOAM);
        run.setText(text);
    }

    private static void body(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun run = p.createRun();
        run.setFontFamily(FONT);
        run.setFontSize(10d);
        run.setColor(INK);
        String[] lines = text.split("\n", -1);
        run.setText(lines[0]);
        for (int i = 1; i < lines.length; i++) {
            run.addBreak();
            run.setText(lines[i], i);
        }
    }

    private static void muted(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun run = p.createRun();
        run.setFontFamily(FONT);
        run.setFontSize(10d);
        run.setColor(MUTED);
        run.setText(text);
    }

    private static void italicMuted(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun run = p.createRun();
        run.setFontFamily(FONT);
        run.setFontSize(10d);
        run.setItalic(true);
        run.setColor(MUTED);
        run.setText(text);
    }

    /**
     * Writes one "key: value" line.
     *
     * <p>Two runs rather than one string, so the label reads as a label. A real two-column table would align the
     * values, but it would also be a table a reader has to get past to reach the one table that carries the findings.
     */
    private static void meta(XWPFDocument doc, String key, String value) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingAfter(0);
        XWPFRun label = p.createRun();
        label.setFontFamily(FONT);
        label.setFontSize(10d);
        label.setColor(MUTED);
        label.setText(key + ": ");
        XWPFRun run = p.createRun();
        run.setFontFamily(FONT);
        run.setFontSize(10d);
        run.setColor(INK);
        run.setText(value);
    }

    /**
     * Writes the export footer as the last paragraph.
     *
     * <p>Not a real page footer: a Word footer lives in a separate part and would repeat on every page, which is more
     * prominence than a provenance line needs. The report id is here for the same reason it is in the PDF - so a
     * document circulating by email can be traced back to the run that produced it.
     */
    private static void footer(XWPFDocument doc, ReportContent content) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(360);
        p.setAlignment(ParagraphAlignment.LEFT);
        XWPFRun run = p.createRun();
        run.setFontFamily(FONT);
        run.setFontSize(8d);
        run.setColor(MUTED);
        String suffix = content.reportId() == null ? "" : " · report " + content.reportId();
        run.setText("Exported " + content.exportedAtLabel() + suffix
                + " · generated by SaaS Product Investigator");
    }
}
