package com.saasinvestigator.export;

/**
 * A generated export, ready to be written to an HTTP response.
 *
 * <p>Bytes rather than a stream because both writers build their whole document in memory anyway - PDF layout needs
 * the full content to paginate, and POI's {@code XWPFDocument} is an in-memory model - so pretending to stream would
 * add a seam without adding the property that makes streaming worth having. A change report is a few pages; if that
 * ever stops being true, the size limit belongs on the number of changes the model may return, not here.
 *
 * @param filename the download filename, including its extension
 * @param contentType the MIME type to send
 * @param content the document bytes
 */
public record ReportExport(String filename, String contentType, byte[] content) {

    /**
     * @param baseName the filename without an extension
     * @param format the format that was generated
     * @param content the document bytes
     * @return an export whose filename and content type both come from {@code format}, so the two cannot disagree
     */
    static ReportExport of(String baseName, ExportFormat format, byte[] content) {
        return new ReportExport(baseName + "." + format.param(), format.contentType(), content);
    }

    /** @return the document's size in bytes, for the {@code Content-Length} header and for logging */
    public int size() {
        return content.length;
    }
}
