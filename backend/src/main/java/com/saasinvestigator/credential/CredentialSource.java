package com.saasinvestigator.credential;

/**
 * Which channel supplied the system-wide API key currently in use for a provider.
 *
 * <p>Reported by {@code GET /api/admin/system-credentials} alongside a {@code last4}. The reason this is
 * worth returning at all: when a run fails with "invalid API key", the first question is <em>which</em> key
 * was used. Three channels can supply one, and an admin who has just pasted a key into the Admin Console
 * has no other way to tell whether it took effect or whether a stale {@code ANTHROPIC_API_KEY} in the
 * container's environment is still winning. (It is not - see {@link SystemCredentialService} - but knowing
 * that from the UI beats trusting it.)
 *
 * <p>Declaration order is the resolution order, highest priority first.
 */
public enum CredentialSource {

    /**
     * The initiating user's own stored key, used because they set a {@code preferredLlmProvider} they have a
     * credential for.
     *
     * <p>Never appears in {@code GET /api/admin/system-credentials}, which reports only system-wide
     * channels - a personal key is not a system credential, and listing it there would imply an admin could
     * see or manage it. It is a member of this enum anyway so that error messages and run metadata have one
     * vocabulary for "where did the key that was used come from", which is the question being asked whether
     * the answer is personal or system-wide.
     */
    PERSONAL,

    /** An admin set an explicit key from the Admin Console; stored encrypted in {@code system_llm_credentials}. */
    OVERRIDE,

    /** Supplied externally as {@code ANTHROPIC_API_KEY} / {@code OPENAI_API_KEY} - env var, properties, K8s Secret, ECS secret. */
    ENV_VAR,

    /** Read from the bind-mounted host credential file. Anthropic only; see {@link HostCredentialLoader}. */
    HOST_MOUNT,

    /** No key is available from any channel. A run or ask using this provider will fail with an actionable message. */
    NONE
}
