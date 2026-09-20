package com.saasinvestigator.error;

/**
 * Thrown when a request is structurally valid but semantically wrong; mapped to HTTP 400.
 *
 * <p>The message is shown directly to the user, so it must name exactly what is wrong - "fromDate
 * 2026-05-01 is after toDate 2026-04-01", not "invalid range".
 */
public class BadRequestException extends RuntimeException {

    /**
     * @param message human-readable explanation of precisely what was wrong
     */
    public BadRequestException(String message) {
        super(message);
    }
}
