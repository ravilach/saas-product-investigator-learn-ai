package com.saasinvestigator.credential;

import com.saasinvestigator.llm.LlmProviderType;

/**
 * What {@code GET /api/users/me/credentials} returns per provider the caller has configured.
 *
 * <p>{@code configured} is always {@code true} in that response - a provider with no stored key simply is
 * not listed. The field is here anyway because the frontend's Account Settings screen renders one row per
 * provider in {@link LlmProviderType}, filling in the unconfigured ones itself, and a uniform shape is
 * easier to render than two.
 *
 * @param provider which provider
 * @param configured whether a key is stored
 * @param last4 the last four characters of the key, or {@code "****"}; never more than that
 */
public record CredentialStatus(LlmProviderType provider, boolean configured, String last4) {

    /**
     * @param provider the provider with no stored key
     * @return a "nothing stored" row
     */
    public static CredentialStatus notConfigured(LlmProviderType provider) {
        return new CredentialStatus(provider, false, null);
    }
}
