package com.saasinvestigator.report;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * What kind of change was detected. The model picks one per reported change.
 *
 * <p>A closed set rather than free text, because these drive the coloured pill badges in History and the category
 * filter - and a model left to invent its own labels will produce "Pricing", "price change", and "billing" for the
 * same thing across three runs, which makes filtering useless and grouping worse.
 *
 * <p>On the wire these are lowercase ({@code "feature"}, not {@code "FEATURE"}) because that is what the prompt
 * asks the model to emit, and having one spelling end to end - prompt, stored document, API response, CSS class -
 * means no layer needs a translation table.
 *
 * <p><b>Unrecognised values become {@link #OTHER} rather than failing.</b> See {@link #fromWire}: this is the
 * deliberate asymmetry in the whole parsing path. Everything else about a malformed model response is worth
 * retrying, but one unexpected category word is not a reason to discard an otherwise good report - the change's
 * description, evidence, and source are all still exactly right, and only the badge colour is a guess.
 */
public enum ChangeCategory {

    /** New or changed functionality. */
    FEATURE("feature"),

    /** Anything about what the product costs: plans, tiers, limits that gate on payment. */
    PRICING("pricing"),

    /** Terms, licensing, privacy, support commitments, security posture. */
    POLICY("policy"),

    /** A fix to something that was broken. */
    BUGFIX("bugfix"),

    /** The docs changed without the product changing - clarifications, new guides, corrections. */
    DOCUMENTATION("documentation"),

    /** Something is going away or is no longer recommended. Usually the most time-sensitive category. */
    DEPRECATION("deprecation"),

    /** Genuinely none of the above, and the fallback for a category the model invented. */
    OTHER("other");

    private final String wireName;

    ChangeCategory(String wireName) {
        this.wireName = wireName;
    }

    /** @return the lowercase name used in JSON, in prompts, and in the stored document */
    @JsonValue
    public String wireName() {
        return wireName;
    }

    /**
     * Parses a category name leniently, case-insensitively, defaulting to {@link #OTHER}.
     *
     * @param value the category name as the model wrote it; may be {@code null}, oddly cased, padded, or a word
     *     that is not in this enum at all
     * @return the matching constant, or {@link #OTHER} if there is no match
     */
    @JsonCreator
    public static ChangeCategory fromWire(String value) {
        if (value == null) {
            return OTHER;
        }
        String normalised = value.trim().toLowerCase(java.util.Locale.ROOT);
        for (ChangeCategory candidate : values()) {
            if (candidate.wireName.equals(normalised)) {
                return candidate;
            }
        }
        return OTHER;
    }
}
