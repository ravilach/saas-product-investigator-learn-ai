package com.saasinvestigator.export;

import com.saasinvestigator.report.ChangeReport;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * Turns a stored report into a downloadable file.
 *
 * <h2>Nothing is cached</h2>
 *
 * <p>Every export is generated from the stored report at the moment it is requested. Caching the rendered bytes would
 * buy nothing worth having: a report never changes after it is written, so a cache could never be wrong - but exports
 * are rare, a few pages take milliseconds, and the product name in the header <em>can</em> change, which is exactly the
 * kind of field a cache would quietly serve stale. Rendering on demand also means the renderer holds no state between
 * requests and both writers are trivially safe to share as singletons.
 *
 * <p>This service does not look anything up. It is handed the product name and the report by the controller, which has
 * already established that the report belongs to that product - keeping the ownership check on one side of the seam
 * rather than duplicated on both.
 */
@Service
public class ReportExportService {

    private final ReportPdfWriter pdfWriter;
    private final ReportDocxWriter docxWriter;

    /**
     * @param pdfWriter renders the PDF
     * @param docxWriter renders the Word document
     */
    ReportExportService(ReportPdfWriter pdfWriter, ReportDocxWriter docxWriter) {
        this.pdfWriter = pdfWriter;
        this.docxWriter = docxWriter;
    }

    /**
     * Generates an export.
     *
     * @param productName the product's current name, used in the heading and the filename
     * @param report the report to export
     * @param format which format to generate
     * @return the file, its name, and its content type
     */
    public ReportExport export(String productName, ChangeReport report, ExportFormat format) {
        ReportContent content = ReportContent.of(productName, report, Instant.now());
        byte[] bytes = switch (format) {
            case PDF -> pdfWriter.write(content);
            case DOCX -> docxWriter.write(content);
        };
        return ReportExport.of(content.fileBaseName(), format, bytes);
    }
}
