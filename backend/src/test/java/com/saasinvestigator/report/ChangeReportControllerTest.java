package com.saasinvestigator.report;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saasinvestigator.error.GlobalExceptionHandler;
import com.saasinvestigator.error.NotFoundException;
import com.saasinvestigator.export.ReportExport;
import com.saasinvestigator.export.ReportExportService;
import com.saasinvestigator.product.SaasProduct;
import com.saasinvestigator.product.SaasProductService;
import com.saasinvestigator.product.SourceType;
import com.saasinvestigator.testsupport.MethodSecuritySliceConfig;
import com.saasinvestigator.testsupport.TestPrincipals;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tests for {@link ChangeReportController} - the history list and the export download.
 *
 * <h2>The two assertions that matter most</h2>
 *
 * <p><b>A report belongs to exactly one product, and the URL says which.</b> Looking a report up by id alone would let
 * {@code /api/saas-products/product-A/reports/report-of-product-B/export} succeed, serving one product's findings under
 * another's name in the header of a file somebody then circulates. The ownership filter is what prevents that, and it
 * looks like a redundant condition to anybody tidying the method.
 *
 * <p><b>An unknown product is a 404, not an empty page.</b> The history query filters by product id anyway, so without
 * the explicit lookup a mistyped id returns {@code []} - which reads as "this product has never been run", the wrong
 * answer to a typo.
 *
 * <p>Both roles may read and export. That is the point of {@code READ_ONLY}: full visibility, no ability to change what
 * is tracked. There is also no verb here but {@code GET} - a report is the record of what a model saw at a moment in
 * time, and an endpoint that could edit one would make the history a claim rather than a record.
 */
@WebMvcTest(ChangeReportController.class)
@Import({MethodSecuritySliceConfig.class, GlobalExceptionHandler.class})
class ChangeReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SaasProductService products;
    @MockitoBean
    private ChangeReportRepository reports;
    @MockitoBean
    private ReportExportService exportService;

    // ----- History -----

    @Test
    void returnsTheHistoryNewestFirstInTheSharedPageEnvelope() throws Exception {
        when(products.require("product-1")).thenReturn(product());
        when(reports.findBySaasProductIdOrderByRunAtDesc(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(report())));

        mockMvc.perform(get("/api/saas-products/product-1/reports").with(TestPrincipals.readOnly()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("report-1"))
                .andExpect(jsonPath("$.content[0].analysisDepth").value("REGULAR"))
                // The list carries whole reports rather than summaries, so the timeline can expand a row in place
                // instead of issuing one request per row somebody opens.
                .andExpect(jsonPath("$.content[0].changes[0].category").value("pricing"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.pageable").doesNotExist());
    }

    @Test
    void returnsA404ForAnUnknownProductRatherThanAnEmptyHistory() throws Exception {
        when(products.require("missing")).thenThrow(new NotFoundException("No SaaS product with id missing"));

        mockMvc.perform(get("/api/saas-products/missing/reports").with(TestPrincipals.readOnly()))
                .andExpect(status().isNotFound());

        verify(reports, never()).findBySaasProductIdOrderByRunAtDesc(anyString(), any(Pageable.class));
    }

    @Test
    void rejectsANonsensicalPageBeforeQueryingTheDatabase() throws Exception {
        mockMvc.perform(get("/api/saas-products/product-1/reports").param("size", "0")
                        .with(TestPrincipals.readOnly()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", Matchers.containsString("size must be at least 1")));

        verifyNoInteractions(reports);
    }

    @Test
    void anonymousCallerCannotReadAProductsHistory() throws Exception {
        mockMvc.perform(get("/api/saas-products/product-1/reports")).andExpect(status().isForbidden());

        verifyNoInteractions(products);
        verifyNoInteractions(reports);
    }

    // ----- Export -----

    @Test
    void servesAPdfAsAnAttachmentNamedAfterTheProductAndTheRunDate() throws Exception {
        when(products.require("product-1")).thenReturn(product());
        when(reports.findById("report-1")).thenReturn(Optional.of(report()));
        when(exportService.export(anyString(), any(), any()))
                .thenReturn(new ReportExport("acme-analytics-change-report-2026-06-01.pdf", "application/pdf",
                        "%PDF-1.7 pretend".getBytes(StandardCharsets.US_ASCII)));

        mockMvc.perform(get("/api/saas-products/product-1/reports/report-1/export")
                        .param("format", "pdf").with(TestPrincipals.readOnly()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                // attachment, not inline: a PDF rendered in the browser tab loses the filename the user needs to
                // recognise it later, and the filename is where the product and the run date are recorded.
                .andExpect(header().string("Content-Disposition",
                        Matchers.containsString("attachment")))
                .andExpect(header().string("Content-Disposition",
                        Matchers.containsString("acme-analytics-change-report-2026-06-01.pdf")))
                .andExpect(header().string("Content-Length", "16"));
    }

    @Test
    void refusesToExportAReportThatBelongsToADifferentProduct() throws Exception {
        // The report exists and the product exists; only the pairing is wrong. Without the ownership filter this
        // succeeds and serves product B's findings under product A's name.
        when(products.require("product-1")).thenReturn(product());
        ChangeReport otherProductsReport = report();
        otherProductsReport.setSaasProductId("product-2");
        when(reports.findById("report-1")).thenReturn(Optional.of(otherProductsReport));

        mockMvc.perform(get("/api/saas-products/product-1/reports/report-1/export")
                        .param("format", "pdf").with(TestPrincipals.readOnly()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", Matchers.containsString("report-1")));

        verifyNoInteractions(exportService);
    }

    @Test
    void returnsA404ForAReportIdThatDoesNotExist() throws Exception {
        when(products.require("product-1")).thenReturn(product());
        when(reports.findById("nope")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/saas-products/product-1/reports/nope/export")
                        .param("format", "docx").with(TestPrincipals.readOnly()))
                .andExpect(status().isNotFound());

        verifyNoInteractions(exportService);
    }

    @Test
    void rejectsAnUnsupportedFormatWithA400NamingTheTwoItAccepts() throws Exception {
        when(products.require("product-1")).thenReturn(product());
        when(reports.findById("report-1")).thenReturn(Optional.of(report()));

        mockMvc.perform(get("/api/saas-products/product-1/reports/report-1/export")
                        .param("format", "xlsx").with(TestPrincipals.readOnly()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", Matchers.containsString("'pdf' or 'docx'")));

        verifyNoInteractions(exportService);
    }

    @Test
    void rejectsAMissingFormatParameterRatherThanGuessingOne() throws Exception {
        mockMvc.perform(get("/api/saas-products/product-1/reports/report-1/export")
                        .with(TestPrincipals.readOnly()))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(exportService);
    }

    @Test
    void anonymousCallerCannotDownloadAReport() throws Exception {
        mockMvc.perform(get("/api/saas-products/product-1/reports/report-1/export").param("format", "pdf"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(exportService);
    }

    // ----- Helpers -----

    private static SaasProduct product() {
        SaasProduct product = new SaasProduct("Acme Analytics", null, List.of(), "admin");
        product.setId("product-1");
        return product;
    }

    private static ChangeReport report() {
        ChangeReport report = new ChangeReport("product-1", "dana", AnalysisDepth.REGULAR,
                List.of(new SourceInclusion("Docs site", SourceType.WEBSITE, Instant.parse("2026-06-01T09:30:00Z"))),
                "One thing changed.",
                List.of(new Change("Docs site", SourceType.WEBSITE, ChangeCategory.PRICING,
                        "The Team plan rose to $25 per seat.", Confidence.HIGH, "Team - $25/seat/month")));
        report.setId("report-1");
        report.setRunAt(Instant.parse("2026-06-01T09:30:00Z"));
        return report;
    }
}
