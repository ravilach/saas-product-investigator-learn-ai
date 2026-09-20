package com.saasinvestigator.report;

import com.saasinvestigator.common.PageResponse;
import com.saasinvestigator.common.Paging;
import com.saasinvestigator.error.NotFoundException;
import com.saasinvestigator.export.ExportFormat;
import com.saasinvestigator.export.ReportExport;
import com.saasinvestigator.export.ReportExportService;
import com.saasinvestigator.product.SaasProduct;
import com.saasinvestigator.product.SaasProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A product's history of change reports, and the two file formats it can leave the application as.
 *
 * <h2>Nested under the product on purpose</h2>
 *
 * <p>A report is meaningless without the product it is about - it has no name of its own, only a date and a list of
 * things that changed - so its address includes the product's. The practical consequence is the ownership check below:
 * every path here resolves the product first and then insists the report belongs to it, so a report id guessed or
 * copied from another product yields a 404 rather than somebody else's findings.
 *
 * <h2>Both roles, read-only</h2>
 *
 * <p>{@code isAuthenticated()} rather than {@code hasRole('ADMIN')}, for the reason set out on
 * {@link com.saasinvestigator.run.RunController}: reading and exporting history is the point of the application. And
 * there is no verb here but {@code GET}. A report is the record of what a model saw at a moment in time; an endpoint
 * that could edit or delete one would make the history a claim rather than a record. Reports are removed only as part
 * of deleting their product, which is a decision about the product rather than about its past.
 */
@RestController
@RequestMapping("/api/saas-products/{productId}/reports")
@PreAuthorize("isAuthenticated()")
@Tag(name = "Reports", description = "Change report history and PDF/DOCX export")
public class ChangeReportController {

    private final SaasProductService products;
    private final ChangeReportRepository reports;
    private final ReportExportService exportService;

    /**
     * @param products resolves the product and 404s when it does not exist
     * @param reports the stored reports
     * @param exportService renders a report as PDF or DOCX
     */
    public ChangeReportController(SaasProductService products, ChangeReportRepository reports,
                                 ReportExportService exportService) {
        this.products = products;
        this.reports = reports;
        this.exportService = exportService;
    }

    /**
     * Returns a page of the product's reports, newest first.
     *
     * <p>Each entry carries its full list of changes rather than a summary. The History timeline shows a collapsed row
     * per run and expands in place, so fetching the detail separately would be one request per row the user opens, for
     * data that was already one document in Mongo. If a report ever grows large enough for that to hurt, the fix is a
     * smaller page size, not a second endpoint.
     *
     * @param productId the product whose history to read
     * @param page zero-based page number
     * @param size page size, capped by {@link Paging#MAX_PAGE_SIZE}
     * @return the page of reports
     * @throws NotFoundException if no product has that id
     */
    @GetMapping
    @Operation(summary = "List a product's change reports, newest first")
    public PageResponse<ChangeReportResponse> list(
            @PathVariable String productId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + Paging.DEFAULT_PAGE_SIZE) int size) {

        // Resolved even though the query below filters by id anyway: without it, a bad product id would return an
        // empty page, which reads as "this product has never been run" - the wrong answer to a typo.
        products.require(productId);

        return PageResponse.from(
                reports.findBySaasProductIdOrderByRunAtDesc(productId, Paging.of(page, size)),
                ChangeReportResponse::from);
    }

    /**
     * Downloads one report as a PDF or a Word document.
     *
     * <p>Generated on demand; nothing is pre-rendered or cached, for the reasons on
     * {@link ReportExportService}. The response is a complete byte array with a {@code Content-Length}, so a browser
     * shows a real progress bar and a failed render becomes a 503 with a JSON body rather than a truncated file that
     * opens to a blank page.
     *
     * @param productId the product the report belongs to
     * @param reportId the report to export
     * @param format {@code pdf} or {@code docx}; anything else is a 400 naming both
     * @return the file, with a {@code Content-Disposition} that names it after the product and the run date
     * @throws NotFoundException if the product does not exist, or the report does not exist or belongs to a different
     *     product - the same response either way, because distinguishing them would confirm that a report id exists
     */
    @GetMapping("/{reportId}/export")
    @Operation(summary = "Download a change report as PDF or DOCX")
    public ResponseEntity<byte[]> export(
            @PathVariable String productId,
            @PathVariable String reportId,
            @RequestParam String format) {

        SaasProduct product = products.require(productId);
        ChangeReport report = reports.findById(reportId)
                .filter(r -> productId.equals(r.getSaasProductId()))
                .orElseThrow(() -> new NotFoundException("No report " + reportId + " for this product."));

        ReportExport export = exportService.export(product.getName(), report, ExportFormat.fromParam(format));

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(export.contentType()))
                .contentLength(export.size())
                // Built rather than concatenated: ContentDisposition emits the RFC 6266 filename* form, so a product
                // name that survived slugging into pure ASCII still gets a correctly quoted header either way.
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(export.filename()).build().toString())
                .body(export.content());
    }
}
