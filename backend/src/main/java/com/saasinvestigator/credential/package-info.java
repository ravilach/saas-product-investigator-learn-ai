/**
 * LLM API key storage and resolution - personal, system-wide, and the channels each can come from.
 *
 * <p>The one idea here: <b>a key goes in as plaintext, is encrypted immediately, and only ever comes back
 * out as either a four-character tail or a {@link com.saasinvestigator.credential.ResolvedCredential} handed
 * straight to a provider SDK.</b> No endpoint in this package can return a stored key, which is why none of
 * them needs to be careful about it.
 *
 * <p>Two stores, because they answer two different questions:
 *
 * <ul>
 *   <li>{@code user_llm_credentials} - "my key", one per user per provider. Available to every authenticated
 *       user including READ_ONLY, since bringing your own key is how someone runs a report on a deployment
 *       whose admin has not configured one.
 *   <li>{@code system_llm_credentials} - "the deployment's key", one per provider, admin-only. The fallback
 *       for every user who has not brought their own, which is everyone on a fresh install.
 * </ul>
 *
 * <p>The system-wide key resolves through three channels in a fixed order -
 * {@link com.saasinvestigator.credential.CredentialSource#OVERRIDE} &gt;
 * {@link com.saasinvestigator.credential.CredentialSource#ENV_VAR} &gt;
 * {@link com.saasinvestigator.credential.CredentialSource#HOST_MOUNT} - the same
 * <em>Admin Console override &gt; explicit external config &gt; automatic fallback</em> shape used for the
 * JWT signing secret and for per-source MCP tokens. Three channels exist side by side because they serve
 * different situations, not because one is a better version of another: the env var and Kubernetes/ECS secret
 * paths are for headless provisioning where nobody is present to click through a UI, the host-mounted file is
 * for a throwaway local container, and the Admin Console is for an operator fixing a key on a deployment
 * whose environment they cannot conveniently change.
 *
 * <p>Deciding <em>which provider</em> a given run uses is not in this package - that is provider resolution,
 * and it lives with the {@code llm} package which owns the run. What this package answers is the narrower
 * question: given a provider and a user, is there a key, and where did it come from.
 *
 * <p><b>Invariants that hold across every class here:</b>
 *
 * <ul>
 *   <li>Nothing persists a plaintext key. {@code CryptoService.encrypt} is called before any {@code save}.
 *   <li>No audit {@code details} map contains a key, a {@code last4}, or a length - only the provider name.
 *   <li>A value that cannot be decrypted is treated as absent, logged at ERROR, and resolution continues to
 *       the next channel. Refusing to work because a stale ciphertext exists would turn a rotated
 *       {@code CREDENTIAL_ENCRYPTION_KEY} into an outage instead of a re-paste.
 *   <li>{@code ResolvedCredential.toString} is overridden to redact the key, because a record's generated
 *       one would not be.
 * </ul>
 */
package com.saasinvestigator.credential;
