/**
 * Downloadable renderings of a stored change report.
 *
 * <h2>Shape</h2>
 *
 * <p>{@link com.saasinvestigator.export.ReportContent} resolves a stored report into already-formatted content;
 * {@link com.saasinvestigator.export.ReportPdfWriter} and {@link com.saasinvestigator.export.ReportDocxWriter} each
 * lay that same content out in one format; {@link com.saasinvestigator.export.ReportExportService} dispatches between
 * them. The two writers never read a {@code ChangeReport}, which is what keeps the requirement that both formats carry
 * identical content from being a convention somebody has to remember.
 *
 * <h2>What this package does not do</h2>
 *
 * <p>It performs no lookup, no authorization, and no audit logging - the controller has done all three before calling
 * in. It also stores nothing: there is no rendered-document collection and no cache, so a report's history is exactly
 * the reports themselves.
 *
 * <h2>Untrusted text</h2>
 *
 * <p>Every string that reaches a writer originated in a crawled web page or an MCP tool response and passed through a
 * model, so all of it is untrusted. The PDF path escapes into XML; the DOCX path writes text through POI's run API,
 * which encodes on its behalf; and
 * {@link com.saasinvestigator.export.ReportContent#of(java.lang.String, com.saasinvestigator.report.ChangeReport,
 * java.time.Instant)} slugs the product name before it can reach a {@code Content-Disposition} header.
 */
package com.saasinvestigator.export;
