package com.saasinvestigator.error;

/** Thrown when a request collides with existing state (duplicate username/email); HTTP 409. */
public class ConflictException extends RuntimeException {

    /**
     * @param message human-readable explanation of the collision
     */
    public ConflictException(String message) {
        super(message);
    }
}
