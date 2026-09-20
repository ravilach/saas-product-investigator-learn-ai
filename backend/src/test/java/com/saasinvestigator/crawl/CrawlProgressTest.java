package com.saasinvestigator.crawl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests the exact {@code detail} string that reaches the live execution view.
 *
 * <p>A test on a display string looks like over-testing until you remember what this one is for. The build
 * prompt specifies this format literally, and specifies it because the live view's entire justification is that
 * it reports genuine backend progress rather than animating a spinner. Once the string drifts to something
 * generic, the view is decorative - and a decorative progress view is indistinguishable from a hung run, which
 * is the one thing it exists to rule out.
 */
class CrawlProgressTest {

    @Test
    void aFetchedPageReadsExactlyAsTheBuildPromptSpecifies() {
        CrawlProgress progress = CrawlProgress.fetched("https://example.com/changelog", 3, 20);

        assertThat(progress.detail()).isEqualTo("Crawling https://example.com/changelog (page 3 of ~20)");
        assertThat(progress.succeeded()).isTrue();
    }

    @Test
    void theTildeIsThereBecauseThePageCountIsACeilingNotATarget() {
        // A site with six pages finishes at six. "of 20" without the tilde would leave the counter looking
        // stuck at 30% on a run that completed normally.
        assertThat(CrawlProgress.fetched("https://example.com/", 1, 20).detail()).contains("of ~20");
    }

    @Test
    void aFailedPageSaysWhatWentWrongRatherThanBeingHidden() {
        // A run that skipped half its pages on 403s produced a thin report for a reason, and that reason belongs
        // in front of whoever is watching it happen - not only in a server log they cannot read.
        CrawlProgress progress = CrawlProgress.failed("https://example.com/private", 4, 20, "HTTP 403");

        assertThat(progress.detail()).isEqualTo("Skipped https://example.com/private (page 4 of ~20): HTTP 403");
        assertThat(progress.succeeded()).isFalse();
        assertThat(progress.failureReason()).isEqualTo("HTTP 403");
    }
}
