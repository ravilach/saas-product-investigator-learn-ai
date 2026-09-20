package com.saasinvestigator.crawl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests {@link RobotsTxt} against the cases that separate a correct parser from a plausible one.
 *
 * <p>Worth testing thoroughly for a reason that is not obvious: <b>every bug in this class is silent.</b> A
 * too-permissive parser crawls pages a site asked us not to, and the only symptom is on somebody else's access
 * log. A too-restrictive one produces thin change reports that look like "nothing changed". Neither shows up as
 * a stack trace, and neither is discoverable from the report that results.
 *
 * <p>The cases below are drawn from RFC 9309's rules rather than invented: longest-match precedence,
 * allow-wins-on-tie, wildcards, end anchors, empty {@code Disallow} as permission, and a named group taking
 * precedence over the wildcard group even when it is more permissive.
 */
class RobotsTxtTest {

    private static final String US = "SaaSProductInvestigator";
    private static final Duration CEILING = Duration.ofSeconds(2);

    private static RobotsTxt parse(String body) {
        return RobotsTxt.parse(body, US, CEILING);
    }

    @Test
    void noFileMeansNoRestrictions() {
        RobotsTxt robots = RobotsTxt.allowAll();

        assertThat(robots.isAllowed("/")).isTrue();
        assertThat(robots.isAllowed("/anything/at/all")).isTrue();
        assertThat(robots.ruleCount()).isZero();
        assertThat(robots.crawlDelay()).isZero();
    }

    @Test
    void anEmptyFileMeansNoRestrictions() {
        assertThat(parse("").isAllowed("/docs")).isTrue();
    }

    @Test
    void aWildcardGroupAppliesToUsWhenNoGroupNamesUs() {
        RobotsTxt robots = parse("""
                User-agent: *
                Disallow: /internal
                """);

        assertThat(robots.isAllowed("/internal")).isFalse();
        assertThat(robots.isAllowed("/internal/notes")).isFalse();
        assertThat(robots.isAllowed("/public")).isTrue();
    }

    @Test
    void aGroupNamingUsWinsOverTheWildcardGroupEvenWhenMorePermissive() {
        // A site that blocks everyone and then unblocks us by name has said something specific. Merging the two
        // groups - or preferring the stricter one because it is stricter - throws that away.
        RobotsTxt robots = parse("""
                User-agent: *
                Disallow: /

                User-agent: SaaSProductInvestigator
                Disallow: /admin
                """);

        assertThat(robots.isAllowed("/changelog")).isTrue();
        assertThat(robots.isAllowed("/admin")).isFalse();
    }

    @Test
    void aGroupNamingUsWithAnEmptyDisallowGrantsFullAccess() {
        // "Disallow:" with no value is the conventional way to say "everything is permitted". Treating it as a
        // prefix match on the empty string would forbid the entire site - the exact inversion of what it means.
        RobotsTxt robots = parse("""
                User-agent: *
                Disallow: /

                User-agent: SaaSProductInvestigator
                Disallow:
                """);

        assertThat(robots.isAllowed("/")).isTrue();
        assertThat(robots.isAllowed("/anything")).isTrue();
        assertThat(robots.ruleCount()).isZero();
    }

    @Test
    void groupsAddressedToOtherCrawlersAreIgnored() {
        RobotsTxt robots = parse("""
                User-agent: BadBot
                Disallow: /

                User-agent: *
                Disallow: /internal
                """);

        assertThat(robots.isAllowed("/docs")).isTrue();
        assertThat(robots.isAllowed("/internal")).isFalse();
    }

    @Test
    void consecutiveUserAgentLinesFormOneGroup() {
        RobotsTxt robots = parse("""
                User-agent: SomeBot
                User-agent: SaaSProductInvestigator
                Disallow: /private
                """);

        assertThat(robots.isAllowed("/private")).isFalse();
        assertThat(robots.isAllowed("/public")).isTrue();
    }

    @Test
    void theLongestMatchingRuleWinsRegardlessOfOrder() {
        // The case a first-match-wins parser gets wrong, and the one that matters most in practice: a site
        // excluding its docs tree but explicitly permitting the changelog inside it. Read as "not the docs",
        // the one page the source was configured for is the page that gets skipped.
        RobotsTxt robots = parse("""
                User-agent: *
                Disallow: /docs
                Allow: /docs/changelog
                """);

        assertThat(robots.isAllowed("/docs")).isFalse();
        assertThat(robots.isAllowed("/docs/internal")).isFalse();
        assertThat(robots.isAllowed("/docs/changelog")).isTrue();
        assertThat(robots.isAllowed("/docs/changelog/2026")).isTrue();
    }

    @Test
    void theLongestMatchStillWinsWhenTheAllowIsWrittenFirst() {
        RobotsTxt robots = parse("""
                User-agent: *
                Allow: /docs/changelog
                Disallow: /docs
                """);

        assertThat(robots.isAllowed("/docs/changelog")).isTrue();
        assertThat(robots.isAllowed("/docs/internal")).isFalse();
    }

    @Test
    void allowWinsWhenAnAllowAndADisallowAreEquallySpecific() {
        RobotsTxt robots = parse("""
                User-agent: *
                Disallow: /docs
                Allow: /docs
                """);

        assertThat(robots.isAllowed("/docs")).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "/private/notes, false",
            "/private, false",
            "/privateer, false",
            "/public, true",
    })
    void aDisallowIsAPrefixMatchNotAnExactOne(String path, boolean allowed) {
        RobotsTxt robots = parse("""
                User-agent: *
                Disallow: /private
                """);

        assertThat(robots.isAllowed(path)).isEqualTo(allowed);
    }

    @Test
    void anAsteriskInAPathMatchesAnySpan() {
        RobotsTxt robots = parse("""
                User-agent: *
                Disallow: /*/draft
                """);

        assertThat(robots.isAllowed("/docs/draft")).isFalse();
        assertThat(robots.isAllowed("/blog/2026/draft")).isFalse();
        assertThat(robots.isAllowed("/docs/published")).isTrue();
    }

    @Test
    void aTrailingDollarAnchorsTheEndOfThePath() {
        RobotsTxt robots = parse("""
                User-agent: *
                Disallow: /*.pdf$
                """);

        assertThat(robots.isAllowed("/reports/2026.pdf")).isFalse();
        assertThat(robots.isAllowed("/reports/2026.pdf.html")).isTrue();
        assertThat(robots.isAllowed("/reports/2026.html")).isTrue();
    }

    @Test
    void metacharactersInAPathAreLiteralNotRegex() {
        // Without quoting, the "." here would match any character and the "?" would make the preceding
        // character optional - turning one excluded path into a broad and arbitrary exclusion.
        RobotsTxt robots = parse("""
                User-agent: *
                Disallow: /search?q=
                """);

        assertThat(robots.isAllowed("/search?q=pricing")).isFalse();
        assertThat(robots.isAllowed("/searchXq=pricing")).isTrue();
        assertThat(robots.isAllowed("/search")).isTrue();
    }

    @Test
    void rulesMatchAgainstTheQueryStringAsWellAsThePath() {
        RobotsTxt robots = parse("""
                User-agent: *
                Disallow: /*?print=1
                """);

        assertThat(robots.isAllowed("/docs?print=1")).isFalse();
        assertThat(robots.isAllowed("/docs?page=2")).isTrue();
    }

    @Test
    void theUserAgentTokenIsMatchedCaseInsensitively() {
        RobotsTxt robots = parse("""
                user-AGENT: saasproductinvestigator
                Disallow: /nope
                """);

        assertThat(robots.isAllowed("/nope")).isFalse();
    }

    @Test
    void commentsAndUnknownDirectivesAreSkippedRatherThanFailing() {
        // A crawler that gives up on a file because of one line it does not recognise has turned a cosmetic
        // problem into a blocked source.
        RobotsTxt robots = parse("""
                # Our robots file
                Sitemap: https://example.com/sitemap.xml
                Host: example.com
                this line has no colon
                User-agent: *   # everyone
                Disallow: /internal   # keep out
                """);

        assertThat(robots.isAllowed("/internal")).isFalse();
        assertThat(robots.isAllowed("/docs")).isTrue();
    }

    @Test
    void rulesBeforeAnyUserAgentLineAreIgnored() {
        // A Disallow with no group is addressed to nobody. Applying it to us would be inventing a rule.
        RobotsTxt robots = parse("""
                Disallow: /orphaned
                User-agent: *
                Disallow: /internal
                """);

        assertThat(robots.isAllowed("/orphaned")).isTrue();
        assertThat(robots.isAllowed("/internal")).isFalse();
    }

    @Test
    void aUserAgentLineAfterARuleStartsANewGroup() {
        RobotsTxt robots = parse("""
                User-agent: SaaSProductInvestigator
                Disallow: /ours
                User-agent: OtherBot
                Disallow: /theirs
                """);

        assertThat(robots.isAllowed("/ours")).isFalse();
        assertThat(robots.isAllowed("/theirs")).isTrue();
    }

    @Test
    void anEmptyOrNullPathIsTreatedAsTheRoot() {
        RobotsTxt robots = parse("""
                User-agent: *
                Disallow: /
                """);

        assertThat(robots.isAllowed("")).isFalse();
        assertThat(robots.isAllowed(null)).isFalse();
    }

    @Test
    void aCrawlDelayIsHonouredUpToTheCeiling() {
        assertThat(parse("User-agent: *\nCrawl-delay: 1").crawlDelay()).isEqualTo(Duration.ofSeconds(1));
        assertThat(parse("User-agent: *\nCrawl-delay: 0.5").crawlDelay()).isEqualTo(Duration.ofMillis(500));
        // Clamped rather than obeyed: 300s times a 20-page budget is a run that appears to have hung.
        assertThat(parse("User-agent: *\nCrawl-delay: 300").crawlDelay()).isEqualTo(CEILING);
    }

    @ParameterizedTest
    @ValueSource(strings = {"soon", "-1", "1s", ""})
    void anUnparseableCrawlDelayIsTreatedAsNoneRatherThanAsAnError(String value) {
        assertThat(parse("User-agent: *\nCrawl-delay: " + value).crawlDelay()).isZero();
    }

    @Test
    void ourOwnGroupsCrawlDelayWinsOverTheWildcardGroups() {
        RobotsTxt robots = parse("""
                User-agent: *
                Crawl-delay: 2

                User-agent: SaaSProductInvestigator
                Crawl-delay: 0.25
                """);

        assertThat(robots.crawlDelay()).isEqualTo(Duration.ofMillis(250));
    }
}
