package com.saasinvestigator.export;

import com.saasinvestigator.error.BadRequestException;
import java.util.Locale;

/** The two download formats a report can be exported as. */
public enum ExportFormat {

    /** A paginated, styled document for reading and archiving. */
    PDF("pdf", "application/pdf"),

    /**
     * An editable document, for pasting a report's findings into a larger internal write-up.
     *
     * <p>The reason both formats exist rather than just PDF: a change report is very often an input to somebody
     * else's document, and the alternative to DOCX is copying text out of a PDF and losing every heading.
     */
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final String param;
    private final String contentType;

    ExportFormat(String param, String contentType) {
        this.param = param;
        this.contentType = contentType;
    }

    /** @return the value of {@code ?format=}, which is also the filename extension */
    public String param() {
        return param;
    }

    /** @return the MIME type to send as {@code Content-Type} */
    public String contentType() {
        return contentType;
    }

    /**
     * Parses the {@code ?format=} query parameter.
     *
     * <p>Case-insensitive, and rejected with a message that lists the valid values. Spring's own enum conversion
     * would do most of this, but its rejection message names the Java enum constants, and {@code PDF} is not what the
     * parameter is documented to accept.
     *
     * @param value the raw parameter value
     * @return the matching format
     * @throws BadRequestException if the value is missing or is not one of the two supported formats
     */
    public static ExportFormat fromParam(String value) {
        String normalised = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        for (ExportFormat format : values()) {
            if (format.param.equals(normalised)) {
                return format;
            }
        }
        throw new BadRequestException("format must be 'pdf' or 'docx'.");
    }
}
