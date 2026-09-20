package com.saasinvestigator.product;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * One source as submitted by a client, inside a create-or-update SaaS Product request.
 *
 * <p>The only field here that differs in kind from {@link SourceConfig}'s is {@code authToken}: this record
 * carries the <b>plaintext</b> token a user typed, and {@link SourceConfigMapper} is the one thing that turns
 * it into the stored ciphertext. Requests carry plaintext, documents carry ciphertext, responses carry a
 * {@code last4} - three types, three states, no field anywhere that could hold the wrong one.
 *
 * <p><b>{@code authToken}'s three meanings are load-bearing</b>, because a source edit form submits every
 * field including ones the user did not touch:
 *
 * <table border="1">
 *   <caption>How {@code authToken} is interpreted on update</caption>
 *   <tr><th>Value</th><th>Meaning</th></tr>
 *   <tr><td>{@code null} / absent</td><td>Leave the stored token exactly as it is</td></tr>
 *   <tr><td>{@code ""} (empty)</td><td>Remove the stored token - this source now needs none</td></tr>
 *   <tr><td>anything else</td><td>Replace the stored token with this value</td></tr>
 * </table>
 *
 * <p>The alternative - a sentinel string the client echoes back to mean "unchanged" - was rejected because
 * the sentinel is then a value a real token could theoretically equal, and because it makes the client
 * responsible for a protocol it can get wrong silently. Absent-means-unchanged needs no agreement beyond
 * ordinary JSON semantics.
 *
 * @param type which kind of source this is
 * @param name a label, unique within the product; identifies this source in reports and snapshots
 * @param endpointUrl the MCP server URL, or the crawl's starting URL
 * @param authToken the plaintext credential, or {@code null} to leave unchanged, or {@code ""} to clear.
 *     Encrypted immediately on arrival and never stored or logged in this form.
 * @param maxDepth crawl depth override, or {@code null} for the current default. Crawled types only.
 * @param maxPages page-count override, or {@code null} for the current default. Crawled types only.
 */
public record SourceConfigRequest(
        @NotNull(message = "type is required and must be one of DOCS_MCP, ATLASSIAN_MCP, GENERIC_MCP, "
                + "WEBSITE, SAAS_URL")
        SourceType type,

        @NotBlank(message = "each source needs a name")
        @Size(max = 120, message = "a source name must be 120 characters or fewer")
        String name,

        @NotBlank(message = "each source needs an endpointUrl")
        String endpointUrl,

        String authToken,

        Integer maxDepth,

        Integer maxPages) {
}
