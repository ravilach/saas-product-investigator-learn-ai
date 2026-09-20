package com.saasinvestigator.export;

import com.saasinvestigator.report.ChangeCategory;
import java.util.EnumMap;
import java.util.Map;

/**
 * The badge colour for each change category, in one place.
 *
 * <h2>Why the backend owns these hex values</h2>
 *
 * <p>The category badges appear in three renderings of the same report: the History timeline on screen, the PDF, and
 * (as plain labels) the DOCX. A PDF whose "pricing" badge is a different colour from the screen's makes the two look
 * like different reports. Since the PDF is generated server-side from HTML, some colour has to be chosen here, so the
 * choice is made once and named - and the frontend's design tokens reference these same values rather than picking
 * their own. See {@code docs/API.md} on report export.
 *
 * <p>Every colour is derived from the app's light-theme palette: seafoam, green, the two status hues, and muted ink.
 * No new hue is introduced, which is a constraint from the design spec rather than a shortage of imagination - seven
 * arbitrary colours would read as a legend to memorise instead of a hierarchy to scan. The pairs are chosen so that
 * <em>something happened that costs money or breaks things</em> (pricing, deprecation, policy) reads warm, and
 * <em>something was added or written down</em> (feature, bugfix, documentation) reads in the brand hues.
 */
final class CategoryPalette {

    private static final Map<ChangeCategory, Badge> BADGES = badges();

    private CategoryPalette() {
    }

    /**
     * @param category the category to style
     * @return its badge colours; never {@code null}, falling back to the muted pair
     */
    static Badge of(ChangeCategory category) {
        return BADGES.getOrDefault(category, new Badge("#EEF2F1", "#4A5C58"));
    }

    private static Map<ChangeCategory, Badge> badges() {
        Map<ChangeCategory, Badge> map = new EnumMap<>(ChangeCategory.class);
        // Seafoam: the product changed in a way a user would notice.
        map.put(ChangeCategory.FEATURE, new Badge("#E2F4F1", "#1F7A6D"));
        // Warning: it changed in a way that touches somebody's bill or contract.
        map.put(ChangeCategory.PRICING, new Badge("#FAF0DE", "#8A5C16"));
        map.put(ChangeCategory.POLICY, new Badge("#FAF0DE", "#8A5C16"));
        // Green: something was fixed.
        map.put(ChangeCategory.BUGFIX, new Badge("#E6F5EC", "#2F7A52"));
        // Muted: the documentation moved, the product did not.
        map.put(ChangeCategory.DOCUMENTATION, new Badge("#EEF2F1", "#4A5C58"));
        // Error: something is going away, which is the one category with a deadline attached.
        map.put(ChangeCategory.DEPRECATION, new Badge("#FBEBE9", "#9B3A33"));
        map.put(ChangeCategory.OTHER, new Badge("#EEF2F1", "#4A5C58"));
        return map;
    }

    /**
     * One badge's two colours.
     *
     * @param background the pill fill
     * @param foreground the label colour, chosen for contrast against {@code background} rather than for variety
     */
    record Badge(String background, String foreground) {
    }
}
