package com.saasinvestigator.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.saasinvestigator.config.SpaForwardingConfig.Resolution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests the rule that decides whether a path is the frontend's or this server's.
 *
 * <p>These exist because of a bug that only appeared in the packaged image, and only in a browser. The
 * frontend's routes - {@code /login}, {@code /products/{id}}, {@code /admin/users} - live in the client router
 * and are unmapped on the server, so a bookmark, a shared link, or a plain page reload sent a {@code GET} for
 * a path nothing served. In development Vite answers those with {@code index.html} and nobody noticed; in the
 * container every one of them answered <strong>401 with a JSON error body</strong>. Pressing reload anywhere
 * in the application replaced it with raw JSON.
 *
 * <p>That is why the interesting assertions below are the negative ones. Forwarding everything to the shell
 * would "fix" reload and break two other things: a mistyped API path would return HTML for a client to choke
 * on while parsing it as JSON, and a missing script would return HTML with status 200, producing a
 * MIME-type console error that names the wrong problem.
 */
class SpaForwardingConfigTest {

    // ---------------------------------------------------------------------
    // Client-side routes resolve to the shell
    // ---------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
        "login",                    // the one that made the bug visible: reload on the login screen
        "account",
        "products/new",
        "products/65f1c2a3b4d5e6f7a8b9c0d1",
        "products/65f1c2a3b4d5e6f7a8b9c0d1/edit",
        "admin/users",
        "admin/audit-log",
        "no-such-page",             // the client renders its own NotFoundPage, which is a real screen
    })
    void everyRouteTheClientOwnsResolvesToTheShell(String path) {
        assertThat(SpaForwardingConfig.resolutionFor(path, false)).isEqualTo(Resolution.SHELL);
    }

    @Test
    void aFileThatExistsIsServedRatherThanReplacedByTheShell() {
        // The order matters: checking existence first is what keeps the catch-all from shadowing the bundle.
        assertThat(SpaForwardingConfig.resolutionFor("assets/index-abc123.js", true)).isEqualTo(Resolution.FILE);
        assertThat(SpaForwardingConfig.resolutionFor("index.html", true)).isEqualTo(Resolution.FILE);
    }

    // ---------------------------------------------------------------------
    // What must NOT be answered with HTML
    // ---------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
        "api/saas-products",
        "api/does-not-exist",
        "api/",
        "actuator/health",
        "v3/api-docs",
        "swagger-ui/index.html",
    })
    void pathsThisServerOwnsAreNeverAnsweredWithTheShell(String path) {
        // A client that asked for JSON and got an HTML document fails with a parse error that says nothing
        // about the actual mistake, which is that the endpoint does not exist.
        assertThat(SpaForwardingConfig.resolutionFor(path, false)).isEqualTo(Resolution.NOT_FOUND);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "assets/index-deleted.js",
        "assets/styles.css",
        "favicon.ico",
        "fonts/inter.woff2",
        "robots.txt",
    })
    void aMissingFileIsAMissingFileRatherThanTheShell(String path) {
        // Returning index.html for a missing .js yields "Expected a JavaScript module but the server
        // responded with a MIME type of text/html" - a 200 that reports the wrong failure.
        assertThat(SpaForwardingConfig.resolutionFor(path, false)).isEqualTo(Resolution.NOT_FOUND);
    }

    @Test
    void onlyTheLastSegmentIsInspectedForLookingLikeAFile() {
        // A dot earlier in the path does not make the request a file request, so a route that happens to
        // carry a version or a hostname in it still loads the application.
        assertThat(SpaForwardingConfig.resolutionFor("products/v1.2/overview", false))
                .isEqualTo(Resolution.SHELL);
    }

    // ---------------------------------------------------------------------
    // The predicate the security configuration shares
    // ---------------------------------------------------------------------

    @Test
    void theFrontendPredicateAgreesWithTheResolverAboutWhoOwnsAPath() {
        // SecurityConfig permits GET on exactly the paths this resolves to the shell. When the two disagree,
        // the result is the original bug: a route that is served but not permitted, or permitted but unserved.
        assertThat(SpaForwardingConfig.isFrontendPath("/login")).isTrue();
        assertThat(SpaForwardingConfig.isFrontendPath("/products/abc/edit")).isTrue();
        assertThat(SpaForwardingConfig.isFrontendPath("/api/saas-products")).isFalse();
        assertThat(SpaForwardingConfig.isFrontendPath("/actuator/health")).isFalse();
    }

    @Test
    void theFrontendPredicateAcceptsAPathWithOrWithoutItsLeadingSlash() {
        // It is fed HttpServletRequest.getRequestURI(), which always has the slash, while the resolver works
        // in paths relative to the static root, which never do.
        assertThat(SpaForwardingConfig.isFrontendPath("login"))
                .isEqualTo(SpaForwardingConfig.isFrontendPath("/login"));
        assertThat(SpaForwardingConfig.isFrontendPath("api/x"))
                .isEqualTo(SpaForwardingConfig.isFrontendPath("/api/x"));
    }
}
