package com.saasinvestigator.error;

/**
 * Thrown when no LLM provider can be resolved for a run or ask, or the resolved provider's call
 * failed outright; mapped to HTTP 503.
 *
 * <p>This exists as its own type rather than a generic 500 so the "nobody has configured a
 * credential yet" case - the single most likely first-run failure - produces a message that tells
 * the user what to actually do about it instead of a NullPointerException.
 */
public class ProviderUnavailableException extends RuntimeException {

    /**
     * @param message human-readable explanation, ideally naming where to configure a credential
     */
    public ProviderUnavailableException(String message) {
        super(message);
    }

    /**
     * @param message human-readable explanation
     * @param cause the underlying provider/transport failure, logged but never returned to clients
     */
    public ProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
