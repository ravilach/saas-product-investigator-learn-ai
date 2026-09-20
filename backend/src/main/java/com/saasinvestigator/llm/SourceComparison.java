package com.saasinvestigator.llm;

import com.saasinvestigator.product.SourceType;
import java.time.Instant;

/**
 * One crawled source's "before" and "after" text, ready to hand to a model.
 *
 * <p>This is the whole of what the backend contributes to a comparison. There is deliberately no diffing here and
 * none anywhere else: the two texts go into the prompt as they are and the model decides what changed and whether
 * it matters. A hand-written differ would find every reordered nav item and miss that a price moved from a table
 * into a footnote.
 *
 * <p>Used by both run types, which is why the field names are {@code prior}/{@code current} rather than
 * {@code before}/{@code after}. For a standard run, {@code prior} is the most recent stored snapshot and
 * {@code current} is text crawled seconds ago. For a custom-range compare, both are stored snapshots - the nearest
 * one at-or-before each chosen date - and nothing is crawled at all. The provider cannot tell the difference and
 * does not need to; what the dates mean is explained to the model by the prompt, which has the {@code RunType}.
 *
 * @param sourceName the source's name within its product, which is also the identity its snapshots are keyed by
 * @param sourceType always a crawled type ({@code WEBSITE} or {@code SAAS_URL}); MCP sources are declared to the
 *     model as tools instead and never appear here
 * @param priorText the earlier state's extracted text, or {@code null} when there is no earlier snapshot. Null on
 *     a product's first ever run, which is a normal state and not an error - the prompt asks for a description of
 *     the current state instead of a comparison.
 * @param priorFetchedAt when {@code priorText} was captured, or {@code null} alongside a null {@code priorText}.
 *     Included in the prompt because "changed since Tuesday" and "changed since March" are different claims.
 * @param currentText the later state's extracted text. Never {@code null}: a source whose fetch failed is carried
 *     as a {@link SourceFailure} instead, so that "unavailable" and "empty" cannot be confused.
 * @param currentFetchedAt when {@code currentText} was captured
 */
public record SourceComparison(
        String sourceName,
        SourceType sourceType,
        String priorText,
        Instant priorFetchedAt,
        String currentText,
        Instant currentFetchedAt) {

    /**
     * @return {@code true} when there is an earlier state to compare against. {@code false} means this is a
     *     first-look source, and the prompt should ask for a baseline description rather than a change list.
     */
    public boolean hasPrior() {
        return priorText != null;
    }
}
