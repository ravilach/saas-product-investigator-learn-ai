package com.saasinvestigator.llm;

/**
 * Receives an answer's text as the model generates it.
 *
 * <p>Exists so that {@code POST /api/saas-products/{id}/ask} can stream. Both providers support native streaming,
 * so the chat-style "answer appears as it is written" behaviour is real rather than a client-side typing animation
 * over an already-complete string - which matters beyond aesthetics: a NUCLEAR-length answer can take a while, and
 * a user watching words appear knows the request is alive, where a user watching a spinner does not.
 */
@FunctionalInterface
public interface TokenSink {

    /** Discards everything. Useful in tests that only assert on the returned complete answer. */
    TokenSink NONE = chunk -> {
    };

    /**
     * Called for each chunk of generated text, in order, on the thread reading the provider's stream.
     *
     * <p>Chunks are whatever the provider emits - words, fragments, sometimes single characters - and carry no
     * guarantee of falling on word or sentence boundaries. Concatenating every chunk in order yields exactly the
     * complete answer. Implementations must not throw, for the same reason as {@link LlmActivityListener}.
     *
     * @param chunk the next piece of text, never {@code null}, occasionally empty
     */
    void onChunk(String chunk);
}
