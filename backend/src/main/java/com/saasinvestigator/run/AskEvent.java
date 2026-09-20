package com.saasinvestigator.run;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One event in a streamed answer to an ad-hoc question.
 *
 * <p>A separate shape from {@link RunEvent} rather than a reuse of it, because an ask has no steps, no report, and
 * no elapsed-time counter worth showing - it has text arriving. Forcing it through the run event shape would mean a
 * stream of events with four null fields and a client that has to know which ones to ignore.
 *
 * <p>The {@code done} event repeats the whole answer even though the client has just received every chunk of it.
 * That is deliberate: it gives the client a single authoritative string to store, copy, or re-render without
 * trusting its own concatenation, and it is the unambiguous signal that the answer is complete rather than
 * truncated by a dropped connection.
 *
 * @param type {@code chunk}, {@code done}, or {@code error} - also the SSE event name
 * @param text the next piece of generated text, on a {@code chunk} event only
 * @param answer the complete answer, on a {@code done} event only
 * @param message a user-facing reason, on an {@code error} event only
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AskEvent(String type, String text, String answer, String message) {

    /** SSE event name for a piece of generated text. */
    public static final String CHUNK = "chunk";

    /** SSE event name for the terminal success event. */
    public static final String DONE = "done";

    /** SSE event name for the terminal failure event. */
    public static final String ERROR = "error";

    /**
     * @param text the next piece of generated text
     * @return a chunk event
     */
    public static AskEvent chunk(String text) {
        return new AskEvent(CHUNK, text, null, null);
    }

    /**
     * @param answer the complete answer
     * @return the terminal success event
     */
    public static AskEvent done(String answer) {
        return new AskEvent(DONE, null, answer, null);
    }

    /**
     * @param message a user-facing reason; never an internal exception message
     * @return the terminal failure event
     */
    public static AskEvent error(String message) {
        return new AskEvent(ERROR, null, null, message);
    }
}
