package com.saasinvestigator.config;

import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the single-page frontend's own routes from the packaged jar.
 *
 * <p>The frontend owns paths like {@code /login}, {@code /products/{id}} and {@code /admin/users}, but only
 * the browser knows that: those paths exist in the React router, not on the server. A browser asking for one
 * of them - by following a bookmark, sharing a link, or simply pressing reload - sends a normal {@code GET}
 * for a path this server has no mapping for. The answer has to be {@code index.html}, so the bundle loads and
 * the client router resolves the path itself.
 *
 * <p>This was missing for the whole build and nothing noticed, because in development Vite serves the
 * frontend and Vite does this fallback for you. In the container - the only place the jar serves the frontend
 * - every client route except {@code /} answered <strong>401 with a JSON error body</strong>: unmapped, so
 * not in the security config's public list, so rejected before anything could serve it. Pressing reload
 * anywhere in the app replaced it with raw JSON. That is the divergence checkpoint 5 exists to catch, and it
 * is only visible in a browser against the container, which is why it survived a 40-of-40 API acceptance
 * pass.
 *
 * <p>Two exclusions keep the fallback from lying:
 *
 * <ul>
 *   <li><b>Server-owned prefixes</b> ({@code /api}, {@code /actuator}, and the API docs) resolve to nothing,
 *       so an unknown endpoint under them is a 404 as it should be. Returning the HTML shell for a mistyped
 *       API path would turn a clear error into a client parsing HTML as JSON.
 *   <li><b>Anything that looks like a file</b> - a last segment containing a dot - also resolves to nothing.
 *       A missing {@code .js} or {@code .css} must 404; answering it with HTML produces a MIME-type console
 *       error that names the wrong problem entirely.
 * </ul>
 */
@Configuration
public class SpaForwardingConfig implements WebMvcConfigurer {

    /** Where the built frontend lands - see the {@code COPY --from=frontend} step in the Dockerfile. */
    private static final String STATIC_ROOT = "classpath:/static/";

    /** The shell every client-side route resolves to. */
    private static final String INDEX = "/static/index.html";

    /**
     * Paths this server answers itself. A request under one of these is never the frontend's.
     *
     * <p>Without {@code actuator}, a probe for a disabled endpoint would come back as HTML with status 200,
     * and a health check reading it would call the app up.
     *
     * <p><strong>Maintenance rule.</strong> Every HTTP endpoint in this application is mapped under
     * {@code /api}, and {@link com.saasinvestigator.security.SecurityConfig} treats everything outside these
     * prefixes as the public frontend shell. An endpoint added outside them would therefore be public. Add
     * the prefix here when adding one - or, better, keep mapping endpoints under {@code /api}.
     */
    private static final String[] SERVER_PREFIXES = {"api/", "actuator/", "v3/api-docs", "swagger-ui"};

    /**
     * Whether a request path belongs to the frontend rather than to this server.
     *
     * <p>Shared with the security configuration on purpose: the set of paths that are publicly readable and
     * the set that resolve to the frontend shell are the same set, and defining it twice is how the two
     * drift into a route that is served but not permitted - which is the 401-on-reload bug this class fixes.
     *
     * @param path a request path, with or without a leading slash
     * @return true if the path should resolve to the single-page app
     */
    public static boolean isFrontendPath(String path) {
        String normalised = path.startsWith("/") ? path.substring(1) : path;
        return !isServerOwned(normalised);
    }

    /** What a request path should resolve to. */
    enum Resolution {
        /** A real file in the bundle: serve it. */
        FILE,
        /** Nothing serves this: let it 404. */
        NOT_FOUND,
        /** A client-side route: serve the HTML shell and let the browser's router take it. */
        SHELL
    }

    /**
     * Decides what a request path resolves to. Kept as a pure function of the path and whether a matching file
     * exists, so the rules can be tested without a servlet container - the rules are the whole substance here,
     * and the resolver below is only an adapter onto Spring's resource chain.
     *
     * @param resourcePath the request path relative to the static root, with no leading slash
     * @param fileExists whether a readable file matches that path in the bundle
     * @return which of the three answers applies
     */
    static Resolution resolutionFor(String resourcePath, boolean fileExists) {
        if (fileExists) {
            return Resolution.FILE;
        }
        if (isServerOwned(resourcePath) || looksLikeAFile(resourcePath)) {
            return Resolution.NOT_FOUND;
        }
        return Resolution.SHELL;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Registered against "/**", which is the broadest possible pattern - but resource handling runs at
        // the lowest precedence in Spring MVC, so every @RequestMapping is matched first and no controller
        // is shadowed by this. Only genuinely unmapped paths arrive here.
        registry.addResourceHandler("/**")
                .addResourceLocations(STATIC_ROOT)
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requested = location.createRelative(resourcePath);
                        boolean exists = requested.exists() && requested.isReadable();
                        return switch (resolutionFor(resourcePath, exists)) {
                            case FILE -> requested;
                            case NOT_FOUND -> null;
                            // In development the jar's static directory does not exist at all, because Vite
                            // is serving the frontend on its own port. Checked rather than assumed, so a
                            // dev-mode 404 stays a 404 instead of failing on a file that was never there.
                            case SHELL -> {
                                ClassPathResource index = new ClassPathResource(INDEX);
                                yield index.exists() ? index : null;
                            }
                        };
                    }
                });
    }

    /** Whether this path belongs to the API or the infrastructure endpoints rather than to the frontend. */
    private static boolean isServerOwned(String resourcePath) {
        for (String prefix : SERVER_PREFIXES) {
            if (resourcePath.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the last path segment names a file. {@code products/new} does not; {@code assets/main.js} does.
     * Only the last segment is examined, so a client route under a dotted parent is still served the shell.
     */
    private static boolean looksLikeAFile(String resourcePath) {
        int lastSlash = resourcePath.lastIndexOf('/');
        return resourcePath.indexOf('.', lastSlash + 1) >= 0;
    }
}
