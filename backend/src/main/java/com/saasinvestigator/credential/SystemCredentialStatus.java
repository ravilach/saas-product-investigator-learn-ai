package com.saasinvestigator.credential;

import com.saasinvestigator.llm.LlmProviderType;

/**
 * What {@code GET /api/admin/system-credentials} returns per provider.
 *
 * <p>Unlike {@link CredentialStatus} this reports {@link CredentialSource}, because a system-wide key can
 * arrive from three different channels and "there is a key" is not enough to act on when the question is
 * "why is the key I just set not being used". Every provider gets a row whether configured or not, so the
 * Secrets tab can show a complete picture including the ones with nothing set.
 *
 * @param provider which provider
 * @param configured whether any channel supplied a key
 * @param last4 the last four characters of whichever key is in effect, or {@code null} when none is
 * @param source which channel supplied it, or {@link CredentialSource#NONE}
 */
public record SystemCredentialStatus(LlmProviderType provider, boolean configured, String last4,
                                     CredentialSource source) {

    /**
     * @param provider the provider no channel supplied a key for
     * @return a "nothing available" row
     */
    public static SystemCredentialStatus none(LlmProviderType provider) {
        return new SystemCredentialStatus(provider, false, null, CredentialSource.NONE);
    }
}
