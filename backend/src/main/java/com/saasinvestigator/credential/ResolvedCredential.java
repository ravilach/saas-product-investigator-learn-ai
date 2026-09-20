package com.saasinvestigator.credential;

import com.saasinvestigator.llm.LlmProviderType;

/**
 * A decrypted API key together with which provider it is for and where it came from.
 *
 * <p><b>This type carries a live secret in plaintext.</b> It exists only to travel from a credential
 * service to the provider SDK call that uses it, and must never be returned from a controller, put in an
 * audit {@code details} map, or logged. The masked shapes for display are {@link CredentialStatus} and
 * {@link SystemCredentialStatus}, which hold a {@code last4} and no key at all.
 *
 * @param provider which provider this key authenticates against
 * @param apiKey the decrypted key - see the warning above
 * @param source which channel supplied it, for error messages and the Admin Console
 */
public record ResolvedCredential(LlmProviderType provider, String apiKey, CredentialSource source) {

    /**
     * Redacts the key.
     *
     * <p>A record's generated {@code toString} includes every component, so without this override the key
     * would be printed in full by any log statement, debugger inspection, or exception message that
     * happened to interpolate one of these. That is not a hypothetical: {@code log.debug("resolved {}",
     * credential)} is the most natural line anyone would write while debugging provider resolution.
     *
     * @return the provider and source, never the key
     */
    @Override
    public String toString() {
        return "ResolvedCredential[provider=" + provider + ", source=" + source + ", apiKey=***]";
    }
}
