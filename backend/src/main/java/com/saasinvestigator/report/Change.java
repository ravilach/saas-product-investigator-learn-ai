package com.saasinvestigator.report;

import com.saasinvestigator.product.SourceType;

/**
 * One change the model identified, embedded in a {@link ChangeReport}'s {@code changes} array.
 *
 * <p>Every field except {@code description} exists to make the description checkable. A bare list of sentences
 * about what changed is an assertion the reader has to take on faith; the same list with a source, a category, a
 * stated confidence, and a quoted snippet is something they can verify or dismiss in a few seconds. That is the
 * difference between a report that gets read and one that gets skimmed once and then ignored.
 *
 * <p>A record for the same reason as {@link SourceInclusion}: a finding is a statement about a moment, and nothing
 * should be able to edit one after the fact.
 *
 * @param sourceName which configured source this was found in
 * @param sourceType that source's type, copied so the finding survives the source being edited or removed
 * @param category the kind of change, for badges and filtering
 * @param description what changed, in prose, written for someone who has not read the source
 * @param confidence how sure the model is - see {@link Confidence} on why three levels and not a number
 * @param evidenceSnippet a short quote from the source that supports the description. The single most valuable
 *     field here: it is what turns "the model says pricing changed" into something the reader can check without
 *     leaving the page, and its absence is the usual reason a summary cannot be trusted. May be {@code null} when
 *     the change is an absence rather than a presence (a page or section that disappeared has nothing to quote).
 */
public record Change(
        String sourceName,
        SourceType sourceType,
        ChangeCategory category,
        String description,
        Confidence confidence,
        String evidenceSnippet) {
}
