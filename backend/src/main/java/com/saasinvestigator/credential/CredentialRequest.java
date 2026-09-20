package com.saasinvestigator.credential;

import com.saasinvestigator.llm.LlmProviderType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /api/users/me/credentials} and {@code PUT /api/admin/system-credentials}.
 *
 * <p>One record for both because the shape genuinely is identical - {@code { provider, apiKey }} - and the
 * difference between them is entirely in who may call the endpoint and where the key is stored. Two
 * identical records would be two places to change the validation rules.
 *
 * <p><b>No format validation beyond a length floor.</b> It is tempting to check for an {@code sk-ant-}
 * prefix, and it would be wrong: key formats are the provider's to change, and a prefix check rejects
 * tomorrow's perfectly valid key with a message claiming it is malformed. The authoritative test of a key
 * is whether the provider accepts it, which happens on the first run and produces a real error from the
 * real authority.
 *
 * @param provider which provider the key is for
 * @param apiKey the key in plaintext - encrypted immediately on arrival and never stored or logged as-is
 */
public record CredentialRequest(
        @NotNull(message = "provider is required and must be ANTHROPIC or OPENAI") LlmProviderType provider,

        @NotBlank(message = "apiKey is required")
        // 8 is the floor at which last4 masking reveals a minority of the value rather than most of it
        // (see CryptoService.last4), and no provider issues keys anywhere near that short - so this
        // catches a truncated paste without pretending to know the format.
        @Size(min = 8, message = "apiKey looks too short to be a real API key")
        String apiKey) {
}
