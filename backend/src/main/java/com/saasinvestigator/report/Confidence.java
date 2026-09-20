package com.saasinvestigator.report;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * How sure the model is that a reported change is real and correctly described.
 *
 * <p>Three levels rather than a 0-1 number. The build prompt names a {@code confidence} field without fixing its
 * type, and this is the reasonable choice made in its place - see
 * {@code docs/decisions/0004-confidence-as-three-levels.md}. Briefly: a model asked for {@code 0.87} will supply
 * {@code 0.87}, and the third digit will be invented. Three levels are the resolution the model can actually
 * support and the resolution a reader can actually act on, and they render as a badge without a decision about how
 * many decimal places to show.
 *
 * <p>Lowercase on the wire and lenient on parse, for the same reasons as {@link ChangeCategory} - except that the
 * fallback here is {@link #MEDIUM} rather than a dedicated "unknown". An unparseable confidence means the field is
 * uninformative, and the honest representation of an uninformative confidence is the middle of the range, not a
 * fourth value that every consumer then has to special-case.
 */
public enum Confidence {

    /** Stated plainly in the source; the evidence snippet says so more or less verbatim. */
    HIGH("high"),

    /** Supported by the evidence but involving some inference, or the wording is ambiguous. */
    MEDIUM("medium"),

    /** A plausible reading of the evidence that could also be explained another way. */
    LOW("low");

    private final String wireName;

    Confidence(String wireName) {
        this.wireName = wireName;
    }

    /** @return the lowercase name used in JSON, in prompts, and in the stored document */
    @JsonValue
    public String wireName() {
        return wireName;
    }

    /**
     * Parses a confidence leniently, case-insensitively, defaulting to {@link #MEDIUM}.
     *
     * @param value the confidence as the model wrote it; may be {@code null}, oddly cased, or an unknown word
     * @return the matching constant, or {@link #MEDIUM}
     */
    @JsonCreator
    public static Confidence fromWire(String value) {
        if (value == null) {
            return MEDIUM;
        }
        String normalised = value.trim().toLowerCase(java.util.Locale.ROOT);
        for (Confidence candidate : values()) {
            if (candidate.wireName.equals(normalised)) {
                return candidate;
            }
        }
        return MEDIUM;
    }
}
