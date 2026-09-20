package com.saasinvestigator.run;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/saas-products/{id}/ask}.
 *
 * <p>Both constraints are validated before a provider is contacted, which is the only place they can usefully be
 * checked. An empty question would produce a paid-for answer to nothing, and an unbounded one would spend the prompt
 * budget on the question and leave no room for the snapshots needed to answer it. Failing at the edge turns both into
 * a {@code 400} with a readable message instead of a confusing answer.
 *
 * @param question the user's question in their own words
 */
public record AskRequest(
        @NotBlank(message = "question must not be blank")
        @Size(max = AskService.MAX_QUESTION_CHARS,
                message = "question must be at most " + AskService.MAX_QUESTION_CHARS + " characters")
        String question) {
}
