# 0002 — SSE is consumed with `fetch`, not `EventSource`

**Status:** accepted · **Date:** 2026-09-20

## Context

Runs, custom-range compares, and ad-hoc asks all stream server-sent events to the browser. Every one of those
endpoints is behind JWT authentication, and the JWT arrives in an `Authorization: Bearer` header.

The browser's native `EventSource` cannot set request headers. The usual workaround is to accept the token as a
query parameter (`?access_token=...`) and have the auth filter read it from there as well as from the header.

## Decision

The backend's `JwtAuthenticationFilter` reads the token **only** from the `Authorization` header. The frontend
consumes SSE with `fetch` and reads the streamed response body, parsing the event framing itself.

Reasons, in order of weight:

1. **A token in a URL is a token in the logs.** Query strings land in web server access logs, reverse-proxy logs,
   CDN logs, `Referer` headers, and browser history. A credential valid for hours ends up in half a dozen places
   nobody is treating as a secret store.
2. **`fetch` is required anyway.** `/ask` is a `POST` — it carries a question body — and `EventSource` only ever
   issues `GET`. Supporting `EventSource` for runs while using `fetch` for asks would mean two streaming code paths
   and two auth paths for one feature.
3. **Better control of the failure modes.** `EventSource` auto-reconnects on its own schedule and gives no access to
   the response status, so a 401 looks like a transient network blip and retries forever. With `fetch` the status is
   visible and a 401 can route the user to the login screen.

## Consequences

- The frontend owns a small SSE parser (split on `\n\n`, strip `data:` prefixes) instead of getting one free. That's
  perhaps thirty lines, tested once.
- No automatic reconnection. For this app that's acceptable: a dropped stream doesn't lose the run — the run is
  server-side and its result is persisted as a `change_report` — so the UI falls back to polling the report rather
  than resurrecting the stream.
- `spring.mvc.async.request-timeout` is raised and `server.compression.enabled=false` is set, because a buffered or
  gzipped stream defeats the point. That's a global compression setting; if compression is wanted later it must be
  enabled per-path, excluding the SSE endpoints.
- Any future non-browser consumer gets the same header-based auth as every other endpoint, with no special case.
- **`JwtAuthenticationFilter` must run on the ASYNC dispatch, and does — `shouldNotFilterAsyncDispatch()` returns
  `false` there.** This is the one non-obvious cost of being stateless, and it cost a real bug before it was
  understood. When an `SseEmitter` completes, the container dispatches the request back through the filter chain to
  finish the response. `OncePerRequestFilter` skips that dispatch **by default**; Spring Security's
  `AuthorizationFilter` does not skip it (Boot's default `spring.security.filter.dispatcher-types` includes ASYNC).
  A session-based app would be fine, because the context would be restored from the session — there is no session
  here, so the context is empty and **the run's own stream is denied as anonymous at the moment it finishes**. The
  symptom is nasty precisely because it is not a failure: every event arrives, the UI looks correct, and only the
  chunked terminator is missing, so `curl` exits 18 and browsers log `ERR_INCOMPLETE_CHUNKED_ENCODING` on runs that
  succeeded. Nothing in development shows it — Vite's proxy terminates the stream to the browser itself. Re-reading
  the header on that dispatch is cheap and safe: same request object, same header, same verification.
