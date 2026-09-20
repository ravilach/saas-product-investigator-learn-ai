package com.saasinvestigator.crawl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parsed {@code robots.txt}, reduced to the two questions the crawler actually asks it: may I fetch this
 * path, and how long should I wait between requests.
 *
 * <p>Implemented to RFC 9309 rather than to the folklore version of the format, because the differences are
 * the cases that matter:
 *
 * <ul>
 *   <li><b>Longest match wins, and {@code Allow} beats {@code Disallow} on a tie.</b> A file with
 *       {@code Disallow: /docs} and {@code Allow: /docs/changelog} means "not the docs, except the changelog".
 *       A naive first-match-wins parser reads that as "not the docs" and silently loses the one page the
 *       source was configured for.
 *   <li><b>{@code *} and {@code $} are significant in paths.</b> {@code Disallow: /*.pdf$} is common and means
 *       nothing to a prefix-only matcher.
 *   <li><b>An empty {@code Disallow:} is permission, not prohibition.</b> It is the conventional way to say
 *       "everything is allowed" and treating it as a prefix match on the empty string forbids the entire site.
 *   <li><b>The group naming our product token wins outright over the {@code *} group</b> - including when it is
 *       more permissive. A site that blocks everyone and then unblocks us by name has said something specific,
 *       and merging the two groups would ignore it.
 * </ul>
 *
 * <p>{@code Crawl-delay} is not in RFC 9309 at all, but it is widely published and widely honoured, and
 * ignoring an explicit request to slow down while claiming to respect {@code robots.txt} would be a bit rich.
 * It is honoured up to a configured ceiling - see {@link #crawlDelay()}.
 *
 * <p>Instances are immutable and safe to share across the threads of one crawl.
 */
public final class RobotsTxt {

    /**
     * One {@code Allow} or {@code Disallow} line, pre-compiled.
     *
     * @param pattern the path pattern matcher, with {@code *} and {@code $} already translated
     * @param specificity the pattern's character length, which is how RFC 9309 defines "most specific"
     * @param allow whether a match permits ({@code true}) or forbids ({@code false}) the path
     */
    private record Rule(Pattern pattern, int specificity, boolean allow) {
    }

    private final List<Rule> rules;
    private final Duration crawlDelay;

    private RobotsTxt(List<Rule> rules, Duration crawlDelay) {
        // Sorted once, at construction: most specific first, and among equally specific rules the allowing one
        // first. isAllowed can then return on the first match instead of scanning and scoring every time.
        this.rules = rules.stream()
                .sorted(Comparator.comparingInt(Rule::specificity).reversed()
                        .thenComparing(Comparator.comparing(Rule::allow).reversed()))
                .toList();
        this.crawlDelay = crawlDelay;
    }

    /**
     * The permissive instance, used when a site publishes no {@code robots.txt} at all.
     *
     * <p>This is the RFC 9309 behaviour for a {@code 404}: no file means no restrictions. It is deliberately
     * <em>not</em> what happens when the file exists but cannot be fetched - see
     * {@link WebCrawler} for why a {@code 5xx} is treated as a refusal instead.
     *
     * @return a {@code robots.txt} that allows every path with no delay
     */
    public static RobotsTxt allowAll() {
        return new RobotsTxt(List.of(), Duration.ZERO);
    }

    /**
     * Parses a {@code robots.txt} body.
     *
     * <p>Unparseable lines, unknown directives, and syntax from other crawlers' extensions are skipped rather
     * than treated as errors. A crawler that refuses to read a file because of one malformed {@code Sitemap:}
     * line has turned a cosmetic problem into a blocked source.
     *
     * @param body the file contents; may be empty
     * @param userAgentToken our product token, matched case-insensitively against {@code User-agent} lines
     * @param maxCrawlDelay ceiling on the honoured {@code Crawl-delay}; a larger published value is clamped to
     *     this, because a 300-second delay times a 20-page budget is a run that appears to have hung
     * @return the parsed rules
     */
    public static RobotsTxt parse(String body, String userAgentToken, Duration maxCrawlDelay) {
        List<Rule> forUs = new ArrayList<>();
        List<Rule> forEveryone = new ArrayList<>();
        Duration delayForUs = null;
        Duration delayForEveryone = null;
        // "Did a group name us at all?" is tracked separately from "did that group contain any rules?", because
        // a group naming us with an empty Disallow is a deliberate, meaningful grant of full access.
        boolean namedUs = false;

        // Group state: consecutive User-agent lines accumulate into one group's agent list, and the first
        // rule line closes that list. A User-agent line after a rule line therefore starts a new group.
        boolean inAgentList = false;
        boolean groupMatchesUs = false;
        boolean groupMatchesEveryone = false;

        for (String rawLine : body.split("\\R")) {
            String line = stripComment(rawLine).trim();
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String field = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();

            if ("user-agent".equals(field)) {
                if (!inAgentList) {
                    // A new group begins - forget which agents the previous one was about.
                    groupMatchesUs = false;
                    groupMatchesEveryone = false;
                    inAgentList = true;
                }
                if ("*".equals(value)) {
                    groupMatchesEveryone = true;
                } else if (value.equalsIgnoreCase(userAgentToken)) {
                    groupMatchesUs = true;
                    namedUs = true;
                }
                continue;
            }

            inAgentList = false;
            if (!groupMatchesUs && !groupMatchesEveryone) {
                continue; // A group addressed to some other crawler.
            }
            List<Rule> target = groupMatchesUs ? forUs : forEveryone;

            switch (field) {
                case "disallow" -> {
                    // Empty value means "nothing is disallowed" - a grant, so no rule is recorded.
                    if (!value.isEmpty()) {
                        target.add(rule(value, false));
                    }
                }
                case "allow" -> {
                    if (!value.isEmpty()) {
                        target.add(rule(value, true));
                    }
                }
                case "crawl-delay" -> {
                    Duration parsed = parseCrawlDelay(value, maxCrawlDelay);
                    if (parsed != null) {
                        if (groupMatchesUs) {
                            delayForUs = parsed;
                        } else {
                            delayForEveryone = parsed;
                        }
                    }
                }
                default -> {
                    // Sitemap, Host, and anything else a site chooses to publish: not our business.
                }
            }
        }

        List<Rule> effective = namedUs ? forUs : forEveryone;
        Duration effectiveDelay = namedUs
                ? (delayForUs == null ? Duration.ZERO : delayForUs)
                : (delayForEveryone == null ? Duration.ZERO : delayForEveryone);
        return new RobotsTxt(effective, effectiveDelay);
    }

    /**
     * Whether a path may be fetched.
     *
     * @param pathAndQuery the request target, e.g. {@code /docs/changelog?page=2}. A {@code robots.txt} pattern
     *     can match against the query string, so it is included rather than stripped.
     * @return {@code true} if no rule forbids it, or if the most specific matching rule allows it
     */
    public boolean isAllowed(String pathAndQuery) {
        String target = (pathAndQuery == null || pathAndQuery.isEmpty()) ? "/" : pathAndQuery;
        for (Rule rule : rules) {
            if (rule.pattern().matcher(target).find()) {
                return rule.allow();
            }
        }
        return true;
    }

    /**
     * How long to wait between requests to this origin.
     *
     * <p>A non-zero delay also forces the crawl to serialise its fetches: waiting a second between each of four
     * parallel request streams is not what a site asking for a one-second delay meant.
     *
     * @return the published delay, clamped to the configured ceiling, or {@link Duration#ZERO} if none was set
     */
    public Duration crawlDelay() {
        return crawlDelay;
    }

    /**
     * @return how many {@code Allow}/{@code Disallow} rules apply to this crawler; {@code 0} means unrestricted
     */
    public int ruleCount() {
        return rules.size();
    }

    private static Rule rule(String pathPattern, boolean allow) {
        return new Rule(toPattern(pathPattern), pathPattern.length(), allow);
    }

    /**
     * Translates a {@code robots.txt} path pattern into a regex anchored at the start of the path.
     *
     * <p>{@code *} becomes {@code .*}; a trailing {@code $} anchors the end; everything else is quoted, so a
     * literal {@code .} or {@code ?} in a URL cannot accidentally act as a metacharacter.
     */
    private static Pattern toPattern(String pathPattern) {
        String working = pathPattern;
        boolean anchorEnd = working.endsWith("$");
        if (anchorEnd) {
            working = working.substring(0, working.length() - 1);
        }
        StringBuilder regex = new StringBuilder("^");
        for (String literal : working.split("\\*", -1)) {
            if (regex.length() > 1) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(literal));
        }
        if (anchorEnd) {
            regex.append('$');
        }
        return Pattern.compile(regex.toString());
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);
    }

    private static final Pattern DECIMAL = Pattern.compile("^\\d+(\\.\\d+)?$");

    private static Duration parseCrawlDelay(String value, Duration ceiling) {
        Matcher matcher = DECIMAL.matcher(value);
        if (!matcher.matches()) {
            return null;
        }
        long millis = Math.round(Double.parseDouble(value) * 1_000);
        return millis > ceiling.toMillis() ? ceiling : Duration.ofMillis(millis);
    }
}
