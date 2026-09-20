package com.saasinvestigator.crawl;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Turns one fetched HTML page into the two things the crawl needs from it: its visible text, and the
 * same-origin links to follow next.
 *
 * <p><b>What this deliberately does not do is decide what is interesting.</b> Only elements the browser never
 * renders are stripped - {@code script}, {@code style}, and friends. Navigation menus, footers, cookie banners,
 * and sidebars all stay in. The temptation to strip them is real: a reordered nav menu is exactly the kind of
 * noise that makes a change report worse. But "this part of the page doesn't matter" is a judgement about what
 * counts as a change, and this codebase has one rule above the others - that judgement belongs to the LLM, not
 * to the backend (see {@code docs/ARCHITECTURE.md}). A heuristic that drops a {@code <footer>} is a heuristic
 * that drops a pricing footnote.
 *
 * <p>All methods are static and stateless; one instance of nothing is shared between crawls.
 */
final class PageTextExtractor {

    /**
     * Elements whose content is never visible to a reader. Removing them is not editorial: leaving them in means
     * a minified bundle's hash changing reads as a change to the page.
     */
    private static final String NON_RENDERED = "script, style, noscript, template, svg, iframe, link, meta";

    /**
     * File extensions that are certainly not pages worth extracting text from. Not an exhaustive list, and it
     * does not need to be - the content-type check at fetch time is the real filter. This just avoids spending
     * pages of the budget discovering that a PDF is a PDF.
     */
    private static final Set<String> NON_PAGE_EXTENSIONS = Set.of(
            "pdf", "zip", "tar", "gz", "tgz", "rar", "7z", "exe", "dmg", "pkg", "deb", "rpm",
            "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "bmp", "tiff", "avif",
            "mp3", "mp4", "avi", "mov", "wmv", "flv", "webm", "ogg", "wav", "m4a",
            "css", "js", "mjs", "map", "woff", "woff2", "ttf", "otf", "eot",
            "csv", "xls", "xlsx", "doc", "docx", "ppt", "pptx", "rtf");

    private PageTextExtractor() {
    }

    /**
     * Extracts the page's visible text, led by its {@code <title>}.
     *
     * <p>The title is included because it is frequently where a change announces itself - "Pricing" becoming
     * "Pricing &amp; Plans" - and because it gives the model a label for the block of text that follows,
     * beyond the URL in the block header.
     *
     * @param document the parsed page
     * @return the page's text with whitespace normalised, or an empty string if it has none
     */
    static String extractText(Document document) {
        Document working = document.clone();
        working.select(NON_RENDERED).remove();

        String title = working.title().trim();
        // jsoup's text() already collapses runs of whitespace and inserts separators at block boundaries, which
        // is what makes the output readable rather than one unbroken line.
        Element body = working.body();
        String text = body == null ? "" : body.text().trim();

        if (title.isEmpty()) {
            return text;
        }
        if (text.isEmpty()) {
            return title;
        }
        return title + "\n\n" + text;
    }

    /**
     * Finds the links worth following from this page.
     *
     * <p>Filtered to the same origin - scheme, host, <em>and</em> port must all match. Not host alone: a link
     * from {@code https://example.com} to {@code http://example.com} would be a downgrade, and one to
     * {@code https://example.com:8443} is a different service. "Same origin" is the standard notion here
     * precisely because it is the one that does not have interesting exceptions.
     *
     * <p>Fragments are stripped, because {@code /docs#install} and {@code /docs} are one page and fetching it
     * twice wastes a page of the budget. Query strings are kept, because {@code ?page=2} usually is not.
     *
     * @param document the parsed page, whose base URI jsoup uses to resolve relative hrefs
     * @param origin the crawl's starting URI, supplying the origin every link must match
     * @return absolute, de-duplicated, same-origin URLs in the order they appear on the page, which is the
     *     order a breadth-first crawl should visit them in
     */
    static List<String> sameOriginLinks(Document document, URI origin) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> links = new ArrayList<>();
        for (Element anchor : document.select("a[href]")) {
            String absolute = anchor.absUrl("href");
            if (absolute.isEmpty()) {
                continue; // A relative href jsoup could not resolve, or a javascript:/mailto: link.
            }
            String normalised = normalise(absolute, origin);
            if (normalised != null && seen.add(normalised)) {
                links.add(normalised);
            }
        }
        return links;
    }

    /**
     * Normalises a discovered URL, or rejects it.
     *
     * @return the URL with its fragment removed, or {@code null} if it is off-origin, not HTTP(S), malformed, or
     *     obviously not a page
     */
    static String normalise(String url, URI origin) {
        URI uri;
        try {
            uri = new URI(url).normalize();
        } catch (Exception e) {
            return null; // Not a URL we can reason about; cheaper to skip than to guess at.
        }
        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            return null;
        }
        if (!isSameOrigin(uri, origin)) {
            return null;
        }
        if (hasNonPageExtension(uri.getPath())) {
            return null;
        }
        try {
            URI withoutFragment = new URI(uri.getScheme(), uri.getAuthority(),
                    uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath(),
                    uri.getQuery(), null);
            return withoutFragment.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Whether two URIs share a scheme, host, and effective port.
     *
     * <p>Ports are compared after defaulting, so {@code https://example.com} and {@code https://example.com:443}
     * are correctly the same origin - a distinction a literal comparison of {@code getPort()} gets wrong, since
     * one of them returns {@code -1}.
     *
     * @param candidate the URL under consideration
     * @param origin the crawl's starting URI
     * @return {@code true} if the candidate may be fetched as part of this crawl
     */
    static boolean isSameOrigin(URI candidate, URI origin) {
        if (candidate.getHost() == null || origin.getHost() == null) {
            return false;
        }
        return candidate.getScheme().equalsIgnoreCase(origin.getScheme())
                && candidate.getHost().equalsIgnoreCase(origin.getHost())
                && effectivePort(candidate) == effectivePort(origin);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static boolean hasNonPageExtension(String path) {
        if (path == null) {
            return false;
        }
        int lastSlash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot <= lastSlash || dot == path.length() - 1) {
            return false;
        }
        return NON_PAGE_EXTENSIONS.contains(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
