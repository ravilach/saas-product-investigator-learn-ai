package com.saasinvestigator.error;

import java.time.Instant;

/**
 * The single JSON error shape every failed request returns, regardless of which layer failed.
 *
 * <p>Keeping one shape means the frontend has exactly one error renderer to write rather than
 * guessing at whatever a given framework default happens to produce.
 *
 * @param error a short, stable, machine-readable code (e.g. {@code NOT_FOUND}); safe to branch on
 * @param message a human-readable, actionable explanation - never a raw exception message or stack
 *     trace, both of which can leak internals
 * @param timestamp when the failure was mapped to a response
 * @param path the request path that failed, to make logs and bug reports correlatable
 */
public record ApiErrorResponse(String error, String message, Instant timestamp, String path) {

    /**
     * Convenience factory that stamps the current time.
     *
     * @param error short machine-readable code
     * @param message human-readable explanation
     * @param path the failing request path
     * @return a populated error response
     */
    public static ApiErrorResponse of(String error, String message, String path) {
        return new ApiErrorResponse(error, message, Instant.now(), path);
    }
}
