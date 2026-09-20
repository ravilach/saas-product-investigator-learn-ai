package com.saasinvestigator.crawl;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A real HTTP server on a loopback port, for testing the crawler against actual HTTP.
 *
 * <p>Uses the JDK's own {@code com.sun.net.httpserver} rather than a mock {@code HttpClient} or a WireMock
 * dependency, for a reason specific to what is being tested. Most of the crawler's interesting behaviour is not
 * in its own logic - it is in the interaction: redirects being followed and then rejected for leaving the origin,
 * a {@code Content-Type} deciding whether a body is parsed or discarded, a socket read timing out, and above all
 * <b>concurrency actually being bounded</b>. A mocked client returns canned responses and would assert that the
 * code calls itself the way it was written to, which is not the question. A real server that counts its
 * simultaneous connections answers the question.
 *
 * <p>It also keeps the test suite dependency-free: no extra jar, nothing to keep up to date, and no container
 * to start - unlike the repository tests, these run without Docker.
 *
 * <p>Every instance binds port 0 (an ephemeral port), so tests can run in parallel and never collide. Close it
 * in a try-with-resources.
 */
final class TestWebSite implements AutoCloseable {

    /**
     * One canned response.
     *
     * @param status the HTTP status to return
     * @param contentType the {@code Content-Type} header, or {@code null} to send none - which is itself a case
     *     worth testing, since a real server occasionally does it
     * @param body the response body
     * @param delayMs how long to stall before responding, for timeout and concurrency tests
     * @param location a {@code Location} header, for redirects
     */
    record Stub(int status, String contentType, String body, long delayMs, String location) {
    }

    private static final String HTML = "text/html; charset=utf-8";

    private final HttpServer server;
    private final ExecutorService executor;
    private final Map<String, Stub> stubs = new ConcurrentHashMap<>();
    private final List<String> requestedPaths = new CopyOnWriteArrayList<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger peakInFlight = new AtomicInteger();

    TestWebSite() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        // Generously sized on purpose: the point of the concurrency test is to observe how many requests the
        // *crawler* makes at once, so the server must never be the thing doing the limiting.
        executor = Executors.newFixedThreadPool(32);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    /**
     * @param path a path on this site, e.g. {@code /docs}
     * @return the absolute URL for it
     */
    String url(String path) {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + path;
    }

    /** Serves {@code html} at {@code path} as {@code text/html}. */
    TestWebSite html(String path, String html) {
        stubs.put(path, new Stub(200, HTML, html, 0, null));
        return this;
    }

    /** Serves a minimal HTML page whose body links to each of {@code hrefs}. */
    TestWebSite page(String path, String title, String... hrefs) {
        StringBuilder body = new StringBuilder("<html><head><title>").append(title)
                .append("</title></head><body><p>Content of ").append(path).append("</p>");
        for (String href : hrefs) {
            body.append("<a href=\"").append(href).append("\">").append(href).append("</a>");
        }
        return html(path, body.append("</body></html>").toString());
    }

    /** Serves {@code body} at {@code path} as {@code text/plain}, for the robots.txt stubs. */
    TestWebSite text(String path, String body) {
        stubs.put(path, new Stub(200, "text/plain; charset=utf-8", body, 0, null));
        return this;
    }

    /** Returns a bare status with no body, for error cases. */
    TestWebSite status(String path, int status) {
        stubs.put(path, new Stub(status, null, "", 0, null));
        return this;
    }

    /** Returns {@code 301} to {@code location}. */
    TestWebSite redirect(String path, String location) {
        stubs.put(path, new Stub(301, null, "", 0, location));
        return this;
    }

    /** Serves a page after stalling for {@code delayMs}. */
    TestWebSite slowPage(String path, long delayMs) {
        stubs.put(path, new Stub(200, HTML, "<html><head><title>Slow</title></head><body>slow</body></html>",
                delayMs, null));
        return this;
    }

    /** Serves a body with an arbitrary content type, for the "this is not a web page" cases. */
    TestWebSite typed(String path, String contentType, String body) {
        stubs.put(path, new Stub(200, contentType, body, 0, null));
        return this;
    }

    /** Serves {@code body} with no {@code Content-Type} header at all. */
    TestWebSite untyped(String path, String body) {
        stubs.put(path, new Stub(200, null, body, 0, null));
        return this;
    }

    /**
     * @return every path requested, in order, including {@code /robots.txt} and paths that 404'd
     */
    List<String> requestedPaths() {
        return List.copyOf(requestedPaths);
    }

    /**
     * @return how many requests were being served simultaneously at the busiest moment - the number that tells
     *     you whether concurrency was bounded, unbounded, or absent
     */
    int peakConcurrency() {
        return peakInFlight.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requestedPaths.add(path);
        peakInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
        try {
            Stub stub = stubs.get(path);
            if (stub == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            if (stub.delayMs() > 0) {
                Thread.sleep(stub.delayMs());
            }
            if (stub.location() != null) {
                exchange.getResponseHeaders().set("Location", stub.location());
            }
            if (stub.contentType() != null) {
                exchange.getResponseHeaders().set("Content-Type", stub.contentType());
            }
            byte[] body = stub.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(stub.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            inFlight.decrementAndGet();
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
