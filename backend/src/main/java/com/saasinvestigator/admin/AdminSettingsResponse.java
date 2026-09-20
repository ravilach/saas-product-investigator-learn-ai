package com.saasinvestigator.admin;

import com.saasinvestigator.crawl.CrawlSettings;

/**
 * The crawl defaults, plus the ceilings they may not exceed.
 *
 * <p>The ceilings are returned alongside the values so the Settings form can state the allowed range up front. Without
 * them the only way to learn that 500 pages is too many is to type it, save, and be rejected - and a limit a user can
 * only discover by hitting it reads as an arbitrary refusal.
 *
 * @param defaultMaxDepth link-hops from a source's starting URL when that source sets no override
 * @param defaultMaxPages pages a source may fetch when it sets no override
 * @param maxAllowedDepth the hard ceiling on {@code defaultMaxDepth}
 * @param maxAllowedPages the hard ceiling on {@code defaultMaxPages}
 */
public record AdminSettingsResponse(
        int defaultMaxDepth,
        int defaultMaxPages,
        int maxAllowedDepth,
        int maxAllowedPages) {

    /**
     * @param defaults the defaults currently in force
     * @param ceilings the hard limits from configuration
     * @return the combined view
     */
    static AdminSettingsResponse of(CrawlSettings.CrawlDefaults defaults, CrawlSettings.CrawlDefaults ceilings) {
        return new AdminSettingsResponse(
                defaults.defaultMaxDepth(), defaults.defaultMaxPages(),
                ceilings.defaultMaxDepth(), ceilings.defaultMaxPages());
    }
}
