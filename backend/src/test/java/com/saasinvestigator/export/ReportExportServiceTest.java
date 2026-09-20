package com.saasinvestigator.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.saasinvestigator.error.BadRequestException;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link ReportExportService} and {@link ExportFormat} together: the dispatch and the naming.
 *
 * <p>Small, but it protects the one thing a caller relies on and neither writer can guarantee alone - that the filename
 * extension, the {@code Content-Type}, and the bytes all describe the same format. Getting that wrong produces a file a
 * browser saves as {@code .pdf} and refuses to open, with nothing in any log to explain it.
 */
class ReportExportServiceTest {

    private final ReportExportService service =
            new ReportExportService(new ReportPdfWriter(), new ReportDocxWriter());

    @Test
    void namesAndTypesThePdfConsistentlyWithTheBytesItProduced() {
        ReportExport export = service.export("Acme Analytics", ExportFixtures.standardReport(), ExportFormat.PDF);

        assertThat(export.filename()).isEqualTo("acme-analytics-change-report-2026-06-01.pdf");
        assertThat(export.contentType()).isEqualTo("application/pdf");
        assertThat(export.content()).isNotEmpty();
        assertThat(export.size()).isEqualTo(export.content().length);
    }

    @Test
    void namesAndTypesTheDocxConsistentlyWithTheBytesItProduced() {
        ReportExport export = service.export("Acme Analytics", ExportFixtures.standardReport(), ExportFormat.DOCX);

        assertThat(export.filename()).isEqualTo("acme-analytics-change-report-2026-06-01.docx");
        assertThat(export.contentType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(export.content()).isNotEmpty();
    }

    @Test
    void producesDifferentBytesForTheTwoFormatsOfTheSameReport() {
        // Guards against a dispatch that silently sends both formats through one writer - which would still download,
        // still open in a PDF reader, and be entirely wrong for the DOCX request.
        byte[] pdf = service.export("Acme", ExportFixtures.standardReport(), ExportFormat.PDF).content();
        byte[] docx = service.export("Acme", ExportFixtures.standardReport(), ExportFormat.DOCX).content();

        assertThat(pdf).isNotEqualTo(docx);
    }

    // ----- Parameter parsing -----

    @Test
    void acceptsTheFormatParameterInAnyCaseWithSurroundingSpace() {
        assertThat(ExportFormat.fromParam("pdf")).isEqualTo(ExportFormat.PDF);
        assertThat(ExportFormat.fromParam(" PDF ")).isEqualTo(ExportFormat.PDF);
        assertThat(ExportFormat.fromParam("DocX")).isEqualTo(ExportFormat.DOCX);
    }

    @Test
    void rejectsAnUnknownFormatWithAMessageNamingTheTwoValuesTheParameterActuallyAccepts() {
        // The message matters more than the status here: Spring's own enum conversion would name the Java constants,
        // and "PDF" is not what the query parameter is documented to take.
        assertThatThrownBy(() -> ExportFormat.fromParam("xlsx"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("format must be 'pdf' or 'docx'.");
        assertThatThrownBy(() -> ExportFormat.fromParam(null))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> ExportFormat.fromParam(""))
                .isInstanceOf(BadRequestException.class);
    }
}
