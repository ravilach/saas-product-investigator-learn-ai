package com.saasinvestigator.export;

import com.saasinvestigator.product.SourceType;
import com.saasinvestigator.report.Change;
import com.saasinvestigator.report.ChangeCategory;
import com.saasinvestigator.report.ChangeReport;
import com.saasinvestigator.report.Confidence;
import com.saasinvestigator.report.AnalysisDepth;
import com.saasinvestigator.report.SourceInclusion;
import java.time.Instant;
import java.util.List;

/**
 * Shared report fixtures for the export tests.
 *
 * <p>One builder rather than a per-test literal because the interesting assertions are about what each format does with
 * the <em>same</em> report - identical content in both files is the requirement - and a fixture built twice is a fixture
 * that can differ.
 */
final class ExportFixtures {

    static final Instant RUN_AT = Instant.parse("2026-06-01T09:30:00Z");
    static final Instant EXPORTED_AT = Instant.parse("2026-06-02T14:05:00Z");

    private ExportFixtures() {
    }

    /**
     * @return a standard run with findings in two categories, one of which has no evidence snippet
     */
    static ChangeReport standardReport() {
        ChangeReport report = new ChangeReport(
                "product-1",
                "dana",
                AnalysisDepth.REGULAR,
                List.of(new SourceInclusion("Docs site", SourceType.WEBSITE, RUN_AT),
                        new SourceInclusion("Changelog MCP", SourceType.GENERIC_MCP, null)),
                "Two things changed this week.",
                List.of(
                        new Change("Docs site", SourceType.WEBSITE, ChangeCategory.PRICING,
                                "The Team plan rose from $20 to $25 per seat.", Confidence.HIGH,
                                "Team - $25/seat/month"),
                        new Change("Changelog MCP", SourceType.GENERIC_MCP, ChangeCategory.FEATURE,
                                "Single sign-on is now generally available.", Confidence.MEDIUM, null)));
        report.setId("report-1");
        report.setRunAt(RUN_AT);
        return report;
    }

    /**
     * @return a report with nothing to say, for the empty-state paths both writers have to handle
     */
    static ChangeReport emptyReport() {
        ChangeReport report = new ChangeReport("product-1", "dana", AnalysisDepth.SHORT, List.of(), "  ", List.of());
        report.setId("report-2");
        report.setRunAt(RUN_AT);
        return report;
    }

    /**
     * @return a custom-range compare carrying the MCP history caveat
     */
    static ChangeReport compareReport() {
        ChangeReport report = standardReport()
                .asCustomRange(Instant.parse("2026-05-01T00:00:00Z"), Instant.parse("2026-06-01T00:00:00Z"), true);
        report.setId("report-3");
        report.setRunAt(RUN_AT);
        return report;
    }
}
