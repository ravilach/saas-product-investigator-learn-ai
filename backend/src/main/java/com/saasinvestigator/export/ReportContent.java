package com.saasinvestigator.export;

import com.saasinvestigator.report.Change;
import com.saasinvestigator.report.ChangeCategory;
import com.saasinvestigator.report.ChangeReport;
import com.saasinvestigator.report.RunType;
import com.saasinvestigator.report.SourceInclusion;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Everything an export says, resolved once and rendered twice.
 *
 * <h2>Why this type exists</h2>
 *
 * <p>The requirement is that the PDF and the DOCX contain <b>identical content</b>. Two writers reading the stored
 * report directly would satisfy that on the day they were written and drift the first time one of them gained a field
 * or changed a date format - and nothing would notice, because nobody opens both files for the same report. With this
 * record in between, the two writers differ only in how they lay out a structure neither of them composes: a field
 * added here appears in both, and a field added to only one is visibly a field that came from nowhere.
 *
 * <p>Everything here is already-formatted text, including dates. Formatting is a content decision - "1 June 2026" and
 * "2026-06-01T00:00:00Z" are different documents - so it belongs on this side of the seam rather than in each writer.
 *
 * @param productName the product the report is about
 * @param reportId the report's id, printed in the footer so a document can be traced back to its record
 * @param runTypeLabel {@code "Standard run"} or {@code "Custom range compare"}
 * @param runAtLabel when the run happened, formatted
 * @param rangeLabel the compared window, or {@code null} for a standard run
 * @param depthLabel the analysis depth, formatted
 * @param runByLabel who triggered the run
 * @param overallSummary the model's prose summary
 * @param sources which sources contributed and as of when
 * @param sections the findings, grouped by category in {@link ChangeCategory} declaration order
 * @param changeCount total findings across all sections
 * @param caveat the {@code mcpHistoryLimited} warning, or {@code null} when the report carries no caveat
 * @param exportedAtLabel when this file was generated, formatted
 * @param fileBaseName the download filename without its extension
 */
public record ReportContent(
        String productName,
        String reportId,
        String runTypeLabel,
        String runAtLabel,
        String rangeLabel,
        String depthLabel,
        String runByLabel,
        String overallSummary,
        List<SourceLine> sources,
        List<CategorySection> sections,
        int changeCount,
        String caveat,
        String exportedAtLabel,
        String fileBaseName) {

    /** How a timestamp reads in an export: unambiguous month name, explicit zone, no seconds. */
    private static final DateTimeFormatter MINUTE =
            DateTimeFormatter.ofPattern("d MMMM yyyy 'at' HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    /** Day granularity, for the two ends of a compare window the user chose with a date picker. */
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    /** For the filename, where a sortable form is more useful than a readable one. */
    private static final DateTimeFormatter FILE_DAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    /**
     * The exact wording of the caveat, in one place because it appears in the PDF, the DOCX, and the UI.
     *
     * <p>It says what was substituted and why rather than only that something was, because a reader who cannot tell
     * which parts of a report are weaker evidence will assume all of it is the stronger kind.
     */
    static final String MCP_HISTORY_CAVEAT =
            "Part of this comparison could not be made from stored content. This product includes MCP sources, whose "
            + "content is never stored by this application, so for the chosen date range their portion of this report "
            + "was assembled from what earlier reports said about them rather than from a direct before-and-after "
            + "comparison.";

    /**
     * Builds the content of an export.
     *
     * @param productName the product's current name
     * @param report the report being exported
     * @param exportedAt when this export is being generated
     * @return the resolved content, ready for either writer
     */
    public static ReportContent of(String productName, ChangeReport report, Instant exportedAt) {
        List<SourceLine> sources = new ArrayList<>();
        for (SourceInclusion inclusion : report.getSourcesIncluded()) {
            sources.add(new SourceLine(
                    inclusion.sourceName(),
                    label(inclusion.sourceType() == null ? null : inclusion.sourceType().name()),
                    // An MCP source has no fetch time, and saying so is the point: the backend never held its
                    // content and so cannot claim when it was current.
                    inclusion.fetchedAt() == null
                            ? "consulted live, not stored"
                            : MINUTE.format(inclusion.fetchedAt())));
        }

        List<CategorySection> sections = new ArrayList<>();
        for (Map.Entry<ChangeCategory, List<Change>> group : report.changesByCategory().entrySet()) {
            List<Finding> findings = new ArrayList<>();
            for (Change change : group.getValue()) {
                findings.add(new Finding(
                        change.sourceName(),
                        change.description(),
                        change.confidence() == null ? "unknown" : label(change.confidence().name()),
                        change.evidenceSnippet()));
            }
            sections.add(new CategorySection(group.getKey(), label(group.getKey().name()), findings));
        }

        boolean custom = report.getRunType() == RunType.CUSTOM_RANGE;
        return new ReportContent(
                productName,
                report.getId(),
                custom ? "Custom range compare" : "Standard run",
                report.getRunAt() == null ? "unknown" : MINUTE.format(report.getRunAt()),
                custom && report.getRangeFrom() != null && report.getRangeTo() != null
                        ? DAY.format(report.getRangeFrom()) + " to " + DAY.format(report.getRangeTo())
                        : null,
                report.getAnalysisDepth() == null ? "unknown" : label(report.getAnalysisDepth().name()),
                report.getRunBy() == null ? "unknown" : report.getRunBy(),
                report.getOverallSummary() == null || report.getOverallSummary().isBlank()
                        ? "No summary was recorded for this run."
                        : report.getOverallSummary(),
                List.copyOf(sources),
                List.copyOf(sections),
                report.getChanges().size(),
                report.isMcpHistoryLimited() ? MCP_HISTORY_CAVEAT : null,
                MINUTE.format(exportedAt),
                fileBaseName(productName, report, exportedAt));
    }

    /**
     * Builds the download filename's stem.
     *
     * <p>Non-alphanumerics collapse to single hyphens because this string ends up in a {@code Content-Disposition}
     * header, where a quote or a newline in a product name is a header-injection bug rather than a cosmetic problem.
     */
    private static String fileBaseName(String productName, ChangeReport report, Instant exportedAt) {
        String slug = (productName == null ? "report" : productName)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (slug.isEmpty()) {
            slug = "report";
        }
        Instant when = report.getRunAt() == null ? exportedAt : report.getRunAt();
        return slug + "-change-report-" + FILE_DAY.format(when);
    }

    /** Turns an enum constant into prose: {@code CUSTOM_RANGE} to {@code "Custom range"}. */
    private static String label(String enumName) {
        if (enumName == null || enumName.isEmpty()) {
            return "unknown";
        }
        String spaced = enumName.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    /**
     * One entry in the "sources included" list.
     *
     * @param name the source's name
     * @param typeLabel its kind, in prose
     * @param fetchedAtLabel when its content was captured, or a phrase explaining why there is no such time
     */
    public record SourceLine(String name, String typeLabel, String fetchedAtLabel) {
    }

    /**
     * All findings of one category.
     *
     * @param category the category itself, kept so a writer can look up its badge colour
     * @param label the category in prose
     * @param findings its findings, in the order the model reported them
     */
    public record CategorySection(ChangeCategory category, String label, List<Finding> findings) {
    }

    /**
     * One finding as it appears in an export.
     *
     * @param sourceName which source it was found in
     * @param description what changed
     * @param confidenceLabel how sure the model is, in prose
     * @param evidenceSnippet the supporting quote, or {@code null} when the change is an absence and there is
     *     nothing to quote
     */
    public record Finding(String sourceName, String description, String confidenceLabel, String evidenceSnippet) {
    }
}
