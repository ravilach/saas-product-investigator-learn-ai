package com.saasinvestigator.error;

/** Thrown when a requested entity does not exist; mapped to HTTP 404. */
public class NotFoundException extends RuntimeException {

    /**
     * @param message human-readable explanation naming what was not found
     */
    public NotFoundException(String message) {
        super(message);
    }

    /**
     * Builds a consistent "&lt;type&gt; not found: &lt;id&gt;" message.
     *
     * @param type the entity type, e.g. {@code "SaaS Product"}
     * @param id the identifier that was looked up
     * @return the exception, ready to throw
     */
    public static NotFoundException of(String type, String id) {
        return new NotFoundException(type + " not found: " + id);
    }
}
