package com.saasinvestigator.llm;

import com.saasinvestigator.product.SourceType;

/**
 * One MCP source, with its token decrypted, ready to be declared to a provider as a remote MCP tool.
 *
 * <p>The asymmetry with {@link SourceComparison} is the core of how this app treats its two families of source.
 * Crawled sources are fetched by the backend and arrive as text. MCP sources are handed to the model as tools it
 * calls itself, which is why this record carries an endpoint and a credential rather than content: the backend
 * never calls an MCP server, and could not usefully pre-fetch one when it does not know what tools that server
 * exposes.
 *
 * <p>This is the one place in the application where an MCP {@code authToken} exists in plaintext, and it lives
 * only for the duration of a single provider call. {@code SourceConfigMapper.decryptAuthToken} produces it, this
 * record carries it, and the provider puts it in one outbound request. Nothing persists it and nothing logs it -
 * which is why {@link #toString()} is overridden. A record's generated {@code toString} prints every component, so
 * one {@code log.debug("sources: {}", mcpSources)} would put live credentials in a logfile.
 *
 * @param name the source's name within its product. Also used as the MCP server label sent to the provider, so it
 *     appears in tool-call events and therefore in the SSE {@code detail} strings the user watches.
 * @param type {@code DOCS_MCP}, {@code ATLASSIAN_MCP} or {@code GENERIC_MCP}. Drives how much the prompt says
 *     about the server: the first two get a hint about what to look for, {@code GENERIC_MCP} deliberately gets
 *     none - see {@link PromptBuilder}.
 * @param endpointUrl the MCP server URL
 * @param authToken the decrypted bearer token, or {@code null} for a server that needs none
 */
public record McpSourceRef(String name, SourceType type, String endpointUrl, String authToken) {

    /**
     * @return {@code true} if this server should be called with a credential
     */
    public boolean hasAuthToken() {
        return authToken != null && !authToken.isBlank();
    }

    /**
     * Redacts the token.
     *
     * <p>Overridden rather than left to the record default because the default would print the live credential.
     * The token's presence is worth knowing in a log line; its value never is.
     */
    @Override
    public String toString() {
        return "McpSourceRef[name=" + name
                + ", type=" + type
                + ", endpointUrl=" + endpointUrl
                + ", authToken=" + (hasAuthToken() ? "***" : "none")
                + "]";
    }
}
