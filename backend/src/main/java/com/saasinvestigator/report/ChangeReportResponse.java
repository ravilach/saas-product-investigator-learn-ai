package com.saasinvestigator.report;

import com.saasinvestigator.product.SourceType;
import java.time.Instant;
import java.util.List;

/**
 * A change report as returned by the API.
 *
 * <p>Almost a mirror of {@link ChangeReport}, and the duplication is worth the cost. A stored report is the one
 * document in this application whose fields might plausibly grow an internal one later - a provider name, a token
 * count, a prompt fingerprint - and an entity serialised straight to JSON would publish each of those the moment it
 * was added. The seam also means the History timeline's shape does not change when the document's does.
 *
 * @param id the report id
 * @param saasProductId the product this is about
 * @param runAt when the run happened
 * @param runBy username of whoever triggered it
 * @param runType live run or custom-range compare
 * @param analysisDepth the depth it ran at
 * @param rangeFrom start of the compared window, or {@code null} for a live run
 * @param rangeTo end of the compared window, or {@code null} for a live run
 * @param mcpHistoryLimited whether part of this report came from aggregated earlier reports rather than a real
 *     before-and-after comparison; surfaced so the UI and both export formats can state the caveat
 * @param sourcesIncluded which sources contributed and as of when
 * @param overallSummary the model's prose summary
 * @param changes every individual finding
 * @param changeCount how many findings there are, so a collapsed timeline row need not count them
 */
public record ChangeReportResponse(
        String id,
        String saasProductId,
        Instant runAt,
        String runBy,
        RunType runType,
        AnalysisDepth analysisDepth,
        Instant rangeFrom,
        Instant rangeTo,
        boolean mcpHistoryLimited,
        List<SourceInclusionResponse> sourcesIncluded,
        String overallSummary,
        List<ChangeResponse> changes,
        int changeCount) {

    /**
     * @param report the stored report
     * @return its API representation
     */
    public static ChangeReportResponse from(ChangeReport report) {
        return new ChangeReportResponse(
                report.getId(),
                report.getSaasProductId(),
                report.getRunAt(),
                report.getRunBy(),
                report.getRunType(),
                report.getAnalysisDepth(),
                report.getRangeFrom(),
                report.getRangeTo(),
                report.isMcpHistoryLimited(),
                report.getSourcesIncluded().stream().map(SourceInclusionResponse::from).toList(),
                report.getOverallSummary(),
                report.getChanges().stream().map(ChangeResponse::from).toList(),
                report.getChanges().size());
    }

    /**
     * One finding.
     *
     * <p>{@code category} and {@code confidence} are serialised as their {@code wireName()} - lower case - because
     * those are the values the model is asked to produce and the values the frontend styles its badges on. Two
     * spellings of the same seven categories, one in the prompt and one in the API, is a mapping table nobody needs.
     *
     * @param sourceName which source this was found in
     * @param sourceType that source's kind
     * @param category the kind of change
     * @param description what changed
     * @param confidence how sure the model is
     * @param evidenceSnippet a short supporting quote, or {@code null} when the change is an absence
     */
    public record ChangeResponse(
            String sourceName,
            SourceType sourceType,
            String category,
            String description,
            String confidence,
            String evidenceSnippet) {

        /**
         * @param change the stored finding
         * @return its API representation
         */
        public static ChangeResponse from(Change change) {
            return new ChangeResponse(
                    change.sourceName(),
                    change.sourceType(),
                    change.category() == null ? null : change.category().wireName(),
                    change.description(),
                    change.confidence() == null ? null : change.confidence().wireName(),
                    change.evidenceSnippet());
        }
    }

    /**
     * One source that contributed to a report.
     *
     * @param sourceName the source's name
     * @param sourceType the source's kind
     * @param fetchedAt when its content was captured, or {@code null} for an MCP source - the backend never held
     *     that content and so cannot say when it was current
     */
    public record SourceInclusionResponse(String sourceName, SourceType sourceType, Instant fetchedAt) {

        /**
         * @param inclusion the stored inclusion
         * @return its API representation
         */
        public static SourceInclusionResponse from(SourceInclusion inclusion) {
            return new SourceInclusionResponse(inclusion.sourceName(), inclusion.sourceType(),
                    inclusion.fetchedAt());
        }
    }
}
