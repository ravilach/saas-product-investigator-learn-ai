package com.saasinvestigator.product;

/**
 * The five kinds of data source a SaaS Product can be watched through.
 *
 * <p>The distinction that matters most is not which of the five a source is, but which of two families it belongs
 * to - see {@link #isMcp()} and {@link #isCrawled()}. The two families are handled in completely different ways:
 *
 * <ul>
 *   <li><b>MCP sources</b> are declared to the LLM as remote MCP tools and the model calls them itself. The
 *       backend never fetches their content, which is also why they have no historical state of their own to
 *       compare against (see {@code mcpHistoryLimited} on a change report).
 *   <li><b>Crawled sources</b> are fetched by the backend, turned into text, stored as a snapshot, and passed into
 *       the prompt as context. These are the ones {@code maxDepth}/{@code maxPages} apply to.
 * </ul>
 *
 * <p>A product may hold any number of sources of any type in any combination - three separate Docs MCP servers for
 * three doc sets is a supported configuration, not an edge case. Nothing here or in the UI caps the count.
 *
 * <p>Adding a sixth type is the subject of the {@code add-source-connector} skill; it touches more than this enum.
 */
public enum SourceType {

    /** An MCP server exposing documentation search/read tools. */
    DOCS_MCP(true),

    /**
     * Atlassian's own MCP server, which exposes Jira <em>and</em> Confluence tools.
     *
     * <p>Deliberately not named {@code JIRA_MCP}: treating it as Jira-only would mean prompting the model past
     * half of what the server offers.
     */
    ATLASSIAN_MCP(true),

    /**
     * Any other MCP server.
     *
     * <p>Its tool shape is unknown at configuration time, so the prompt for this type carries no domain-specific
     * hints - the model is told to use its judgement about which of the server's tools bear on recent changes. A
     * "check for new issues" style hint here would be a guess about a server nobody has described.
     */
    GENERIC_MCP(true),

    /** A URL that is crawled breadth-first, same-origin, for its visible text. */
    WEBSITE(false),

    /**
     * A specific page of a SaaS app, typically a changelog or release-notes page.
     *
     * <p>Crawled identically to {@link #WEBSITE}, with the same defaults and limits. It exists as a separate type
     * because the distinction is meaningful to the person configuring it and shows up in reports and metrics,
     * not because the fetching differs.
     */
    SAAS_URL(false);

    private final boolean mcp;

    SourceType(boolean mcp) {
        this.mcp = mcp;
    }

    /**
     * @return {@code true} if this source is reached by the LLM as a remote MCP tool rather than fetched by the
     *     backend
     */
    public boolean isMcp() {
        return mcp;
    }

    /**
     * @return {@code true} if the backend fetches this source's content itself, which also means
     *     {@code maxDepth}/{@code maxPages} apply to it and each run produces a snapshot for it
     */
    public boolean isCrawled() {
        return !mcp;
    }
}
