package com.saasinvestigator.llm;

import com.saasinvestigator.product.SourceType;
import com.saasinvestigator.report.Change;
import java.util.List;

/**
 * Previously-recorded changes for one MCP source, used only by a custom-range compare.
 *
 * <p>This record is the shape of a genuine limitation, and it is worth understanding rather than working around.
 * A crawled source can be rewound: the app stored its text on every past run, so "what did this page look like on
 * 1 June" is a lookup. An MCP server cannot be rewound. Its tools report the state of a Jira project or a doc set
 * <em>now</em>; there is no protocol for asking one what it would have said three months ago, and inventing one by
 * caching every tool response would be a different application.
 *
 * <p>So for MCP sources a compare does the next honest thing: it re-reads the change entries this app itself
 * recorded for that source across the reports in the requested window, and asks the model to fold them into the
 * summary instead of deriving them afresh. That makes the MCP half of a compare a summary of summaries, which is
 * weaker than the crawled half, which is why any report built this way carries {@code mcpHistoryLimited: true}.
 * The caveat is surfaced in the UI rather than buried: a user comparing two dates should know that half the answer
 * is as good as the runs that happened in between, and that a window with no runs in it has nothing to say about
 * its MCP sources at all.
 *
 * @param sourceName the MCP source's name within its product
 * @param sourceType {@code DOCS_MCP}, {@code ATLASSIAN_MCP} or {@code GENERIC_MCP}
 * @param changes every change previously recorded against this source inside the requested window, newest run
 *     first. May be empty, which means "no run in this window reported anything for this source" - a legitimate
 *     and different statement from "nothing changed".
 */
public record McpChangeHistory(String sourceName, SourceType sourceType, List<Change> changes) {

    /** Defensively copies, so an aggregation cannot be mutated after the fact. */
    public McpChangeHistory {
        changes = List.copyOf(changes);
    }

    /**
     * @return {@code true} when no prior run recorded anything for this source in the window
     */
    public boolean isEmpty() {
        return changes.isEmpty();
    }
}
