/**
 * Symmetric encryption of secrets at rest.
 *
 * <p>One service, {@link com.saasinvestigator.crypto.CryptoService}, is the only place in the
 * codebase that touches {@code CREDENTIAL_ENCRYPTION_KEY}. Everything stored encrypted - personal
 * and system LLM API keys, per-source MCP auth tokens, the auto-generated JWT signing secret - goes
 * through it, so there is exactly one implementation to audit rather than several.
 */
package com.saasinvestigator.crypto;
