package com.saasinvestigator.admin;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * A change to the crawl defaults.
 *
 * <p>Both fields are required. A partial update - send only {@code defaultMaxPages} - would be ambiguous in exactly the
 * wrong way: the two limits interact, since a depth of 4 over a large site is only bounded by the page budget, so
 * changing one without seeing the other is how someone accidentally authorises a much bigger crawl than they intended.
 * The form shows both, so the request carries both.
 *
 * <p>The upper bounds are not annotations here. They come from configuration and are enforced by
 * {@link com.saasinvestigator.crawl.CrawlSettings#updateDefaults(int, int)}, whose rejection message names the actual
 * ceiling in force; a hardcoded {@code @Max} would duplicate that number and could disagree with it.
 *
 * @param defaultMaxDepth link-hops from a source's starting URL; 0 means fetch only the given page
 * @param defaultMaxPages pages a source may fetch, at least one
 */
public record AdminSettingsRequest(
        @NotNull(message = "defaultMaxDepth is required")
        @Min(value = 0, message = "defaultMaxDepth must be 0 or greater")
        Integer defaultMaxDepth,

        @NotNull(message = "defaultMaxPages is required")
        @Min(value = 1, message = "defaultMaxPages must be at least 1")
        Integer defaultMaxPages) {
}
