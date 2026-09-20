package com.saasinvestigator.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.saasinvestigator.product.SourceType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests {@link ChangeReport}'s two pieces of behaviour: becoming a custom-range report, and grouping findings. */
class ChangeReportTest {

    private static final Instant FROM = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    void aFreshReportIsAStandardRunWithNoRangeAndNoCaveat() {
        ChangeReport report = report();

        assertThat(report.getRunType()).isEqualTo(RunType.STANDARD);
        assertThat(report.getRangeFrom()).isNull();
        assertThat(report.getRangeTo()).isNull();
        assertThat(report.isMcpHistoryLimited()).isFalse();
        assertThat(report.getRunAt()).isNotNull();
    }

    @Test
    void asCustomRangeSetsTheTypeAndBothBoundsTogether() {
        // The reason this is one method rather than a wider constructor: the range and the runType that makes it
        // meaningful cannot end up disagreeing, and no call site can pass null, null by accident.
        ChangeReport report = report().asCustomRange(FROM, TO, true);

        assertThat(report.getRunType()).isEqualTo(RunType.CUSTOM_RANGE);
        assertThat(report.getRangeFrom()).isEqualTo(FROM);
        assertThat(report.getRangeTo()).isEqualTo(TO);
        assertThat(report.isMcpHistoryLimited()).isTrue();
    }

    @Test
    void aCustomRangeOverCrawledSourcesOnlyCarriesNoCaveat() {
        // mcpHistoryLimited is about MCP sources specifically, not about custom-range compares in general - a compare
        // over crawled sources alone is a real before-and-after against stored snapshots.
        assertThat(report().asCustomRange(FROM, TO, false).isMcpHistoryLimited()).isFalse();
    }

    @Test
    void asCustomRangeReturnsTheSameInstanceForChaining() {
        ChangeReport report = report();
        assertThat(report.asCustomRange(FROM, TO, false)).isSameAs(report);
    }

    @Test
    void findingsAreGroupedByCategoryInEnumDeclarationOrder() {
        ChangeReport report = reportWith(
                change(ChangeCategory.OTHER, "Something else"),
                change(ChangeCategory.PRICING, "Team plan went up"),
                change(ChangeCategory.FEATURE, "SSO added"),
                change(ChangeCategory.PRICING, "Free tier limit lowered"));

        var grouped = report.changesByCategory();

        // Declaration order, not insertion order: the History view and both exporters render these in sequence, and
        // a category jumping position between two reports of the same product reads as a layout bug.
        assertThat(grouped.keySet())
                .containsExactly(ChangeCategory.FEATURE, ChangeCategory.PRICING, ChangeCategory.OTHER);
        assertThat(grouped.get(ChangeCategory.PRICING)).hasSize(2);
    }

    @Test
    void categoriesWithNoFindingsAreAbsentRatherThanEmpty() {
        // So a consumer can iterate the map directly without having to skip empty groups, which is how an export
        // ends up with a "Deprecation" heading and nothing under it.
        var grouped = reportWith(change(ChangeCategory.FEATURE, "SSO added")).changesByCategory();

        assertThat(grouped).containsOnlyKeys(ChangeCategory.FEATURE);
    }

    @Test
    void aReportWithNoFindingsGroupsToAnEmptyMap() {
        // A legitimate outcome, not an error: a quiet week produces a summary and no changes.
        assertThat(report().changesByCategory()).isEmpty();
    }

    @Test
    void theFindingsListIsCopiedOnConstruction() {
        List<Change> mutable = new ArrayList<>(List.of(change(ChangeCategory.FEATURE, "One")));
        ChangeReport report = new ChangeReport("product-1", "admin", AnalysisDepth.REGULAR, List.of(),
                "summary", mutable);

        mutable.add(change(ChangeCategory.PRICING, "Two"));

        assertThat(report.getChanges()).hasSize(1);
    }

    @Test
    void nullListsBecomeEmptyRatherThanNullFields() {
        ChangeReport report = new ChangeReport("product-1", "admin", AnalysisDepth.REGULAR, null, "summary", null);

        assertThat(report.getChanges()).isEmpty();
        assertThat(report.getSourcesIncluded()).isEmpty();

        report.setChanges(null);
        report.setSourcesIncluded(null);
        assertThat(report.getChanges()).isEmpty();
        assertThat(report.getSourcesIncluded()).isEmpty();
    }

    @Test
    void anMcpSourceInclusionMayCarryNoFetchedAt() {
        // The backend never holds MCP content, so it cannot claim a freshness for it. Null here is the honest value,
        // and both exporters have to render it as such rather than as an epoch date.
        SourceInclusion mcp = new SourceInclusion("Docs", SourceType.DOCS_MCP, null);

        assertThat(mcp.fetchedAt()).isNull();
        assertThat(mcp.sourceType().isMcp()).isTrue();
    }

    private static ChangeReport report() {
        return reportWith();
    }

    private static ChangeReport reportWith(Change... changes) {
        return new ChangeReport("product-1", "admin", AnalysisDepth.REGULAR,
                List.of(new SourceInclusion("Changelog", SourceType.SAAS_URL, Instant.parse("2026-09-20T10:00:00Z"))),
                "A summary.", List.of(changes));
    }

    private static Change change(ChangeCategory category, String description) {
        return new Change("Changelog", SourceType.SAAS_URL, category, description, Confidence.HIGH, "evidence");
    }
}
