package com.saasinvestigator.product;

/**
 * One source as returned by the API.
 *
 * <p>This record exists to make leaking a token structurally impossible rather than a thing to remember:
 * there is no field on it that could hold one. Returning {@link SourceConfig} directly would serialise
 * {@code authTokenEncrypted}, and while ciphertext is not plaintext, handing every reader of a product the
 * ciphertext of its credentials is a needless gift to anyone who later obtains the encryption key.
 *
 * <p>The effective crawl limits are reported as well as the raw overrides. A user who left {@code maxPages}
 * blank and wants to know what will actually happen would otherwise have to know the system default, find
 * where it is configured, and check whether an admin has changed it.
 *
 * @param type which kind of source this is
 * @param name the label, unique within its product
 * @param endpointUrl the MCP server URL or crawl starting URL
 * @param authTokenConfigured whether a credential is stored for this source
 * @param authTokenLast4 the last four characters of the stored token, or {@code null} when none is stored
 * @param maxDepth the explicit depth override, or {@code null} if this source follows the default
 * @param maxPages the explicit page-count override, or {@code null} if this source follows the default
 * @param effectiveMaxDepth the depth that will actually be used, defaults and ceilings applied; {@code null}
 *     for MCP sources, which are not crawled
 * @param effectiveMaxPages the page count that will actually be used; {@code null} for MCP sources
 */
public record SourceConfigResponse(
        SourceType type,
        String name,
        String endpointUrl,
        boolean authTokenConfigured,
        String authTokenLast4,
        Integer maxDepth,
        Integer maxPages,
        Integer effectiveMaxDepth,
        Integer effectiveMaxPages) {
}
