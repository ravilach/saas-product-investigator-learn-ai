package com.saasinvestigator.credential;

import com.saasinvestigator.llm.LlmProviderType;

/**
 * The body of {@code PUT /api/users/me/preferred-provider}.
 *
 * <p>{@code provider} is deliberately <b>not</b> {@code @NotNull}: sending {@code { "provider": null }}
 * clears the preference and falls the user back to the system-wide default, which is a thing they need to
 * be able to do and which otherwise needs a second endpoint to express.
 *
 * <p>Setting a preference for a provider the caller has no key for is also allowed, per the build prompt -
 * it simply has no effect until they add the matching key. Rejecting it would force a particular order of
 * operations on the Account Settings screen for no benefit.
 *
 * @param provider the preferred provider, or {@code null} to clear the preference
 */
public record PreferredProviderRequest(LlmProviderType provider) {
}
