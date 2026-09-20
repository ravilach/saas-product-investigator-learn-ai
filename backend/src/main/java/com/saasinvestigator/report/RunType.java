package com.saasinvestigator.report;

/**
 * Which of the two operations produced a change report.
 *
 * <p>Stored on the report because the two are not interchangeable and a reader needs to know which they are
 * looking at: a {@link #STANDARD} report is grounded in data fetched moments before it was written, while a
 * {@link #CUSTOM_RANGE} report is grounded entirely in what was already stored. Presenting them identically would
 * imply the second one went and looked, and it did not.
 */
public enum RunType {

    /**
     * A live run: every source was fetched fresh and compared against what was seen last time.
     *
     * <p>The only kind that writes new snapshots.
     */
    STANDARD,

    /**
     * A compare over a user-chosen date range, answered from history alone.
     *
     * <p>Fetches nothing and crawls nothing - that is the defining property, not an optimisation. For crawled
     * sources it reads the nearest stored snapshot at or before each end of the range. MCP sources have no stored
     * history at all, so their portion is aggregated from earlier reports and the result is flagged with
     * {@code mcpHistoryLimited}.
     *
     * <p>Sets {@code rangeFrom} and {@code rangeTo}; {@link #STANDARD} leaves both null.
     */
    CUSTOM_RANGE
}
