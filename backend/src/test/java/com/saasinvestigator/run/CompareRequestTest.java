package com.saasinvestigator.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.saasinvestigator.report.AnalysisDepth;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Tests the day-to-instant conversion, which is deliberately asymmetric.
 *
 * <p>{@code fromDate} resolves to the start of its day and {@code toDate} to the <em>end</em> of its day. Written the
 * obvious symmetric way - both at midnight - the code would look cleaner and be wrong by one day at the far end: every
 * snapshot taken during the last day the user actually named would fall outside the window. That failure has no error
 * message and no log line; it presents as a compare that says nothing changed on the day something did, which a user
 * would read as a fault in the analysis rather than in the range.
 *
 * <p>The zone is asserted for a related reason. A calendar control has no opinion about time of day, so somebody has to
 * pick one, and if the frontend picked it then "1 June to 15 June" from Auckland and the same request from Los Angeles
 * would cover different windows from identical user input. UTC here, once, matches how snapshots, reports and audit
 * entries are all stamped.
 */
class CompareRequestTest {

    private static CompareRequest range(String from, String to) {
        return new CompareRequest(LocalDate.parse(from), LocalDate.parse(to), null);
    }

    @Test
    void theWindowStartsAtMidnightUtcOnTheFirstDay() {
        assertThat(range("2026-06-01", "2026-06-30").fromInstant())
                .isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
    }

    @Test
    void theWindowEndsAtTheLastInstantOfTheLastDayRatherThanAtItsMidnight() {
        // Ending at 2026-06-30T00:00:00Z would silently exclude everything captured on 30 June - including the run the
        // user just did, which is the most likely reason they picked today as the end.
        assertThat(range("2026-06-01", "2026-06-30").toInstant())
                .isEqualTo(Instant.parse("2026-06-30T23:59:59.999Z"));
    }

    @Test
    void aSingleDayRangeCoversThatWholeDayRatherThanZeroTime() {
        CompareRequest oneDay = range("2026-06-15", "2026-06-15");

        // A user who picks the same date twice means "that day". Symmetric conversion would make this an empty window
        // and produce a report about nothing.
        assertThat(oneDay.fromInstant()).isEqualTo(Instant.parse("2026-06-15T00:00:00Z"));
        assertThat(oneDay.toInstant()).isEqualTo(Instant.parse("2026-06-15T23:59:59.999Z"));
        assertThat(oneDay.fromInstant()).isBefore(oneDay.toInstant());
    }

    @Test
    void theEndOfTheWindowNeverSpillsIntoTheFollowingDay() {
        // Built as "start of the next day minus a millisecond", so the assertion worth making is that the millisecond
        // is actually subtracted: 2026-07-01T00:00:00Z would include captures from a day outside the range.
        assertThat(range("2026-06-01", "2026-06-30").toInstant())
                .isBefore(Instant.parse("2026-07-01T00:00:00Z"));
    }

    @Test
    void aMonthBoundaryIsHandledByTheCalendarRatherThanByArithmeticOnDays() {
        // plusDays on a LocalDate rolls the month and the year; adding 86_400_000 milliseconds to an Instant would too,
        // but would also be wrong across a leap second and unreadable either way.
        assertThat(range("2026-02-01", "2026-02-28").toInstant())
                .isEqualTo(Instant.parse("2026-02-28T23:59:59.999Z"));
        assertThat(range("2026-12-31", "2026-12-31").toInstant())
                .isEqualTo(Instant.parse("2026-12-31T23:59:59.999Z"));
    }

    @Test
    void depthIsOptionalAndCarriedThroughWhenGiven() {
        assertThat(range("2026-06-01", "2026-06-30").analysisDepth()).isNull();
        assertThat(new CompareRequest(LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"),
                AnalysisDepth.NUCLEAR).analysisDepth()).isEqualTo(AnalysisDepth.NUCLEAR);
    }
}
