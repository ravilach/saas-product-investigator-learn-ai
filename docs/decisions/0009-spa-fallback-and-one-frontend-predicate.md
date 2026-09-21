# 0009 — Client routes fall back to the HTML shell, and one predicate decides which paths those are

**Status:** accepted · **Date:** 2026-09-21

## Context

The built frontend ships inside the jar as static resources, so one port serves both the UI and the API — see
[ARCHITECTURE.md](../ARCHITECTURE.md) for the layout and
[SETUP.md](../SETUP.md#ports-and-why-there-is-no-reverse-proxy) for why there is no reverse proxy. The frontend is a
single-page app: `/login`, `/products/{id}`, `/admin/users` and the rest exist only in the client router. The server
has no mapping for any of them.

That is fine as long as the browser only ever arrives at `/` and navigates client-side. It is not fine the moment
someone bookmarks a page, shares a link, or presses reload — the browser sends the server an ordinary `GET /products/abc`,
and the server has to answer with the HTML shell so the client router can take it from there.

**This was discovered by walking the packaged image, not by reasoning about it.** Before this decision there was no
fallback at all, and `SecurityConfig` permitted a hand-written list of five paths (`/`, `/index.html`, `/favicon.ico`,
`/assets/**`, `/fonts/**`). Every client route except `/` answered **401 with a JSON body**: reload the app and it
was replaced by an error payload, and the app's own "not found" page was unreachable. The dev loop hid it completely,
because Vite serves this fallback itself.

## Decision

Two rules, and the second matters more than the first.

1. **A resource resolver maps genuinely unmapped, non-file paths to `/static/index.html`** — `SpaForwardingConfig`.
   Spring MVC's resource handling runs at the lowest precedence, so a `/**` handler shadows no `@RequestMapping`;
   only paths nothing else claimed reach it.
2. **`SecurityConfig` and `SpaForwardingConfig` share a single predicate** — `SpaForwardingConfig.isFrontendPath` —
   rather than each describing the frontend's routes in its own words. The original bug was not the missing handler;
   it was two places holding separate, drifting opinions about which paths belong to the frontend.

Paths are classified by prefix, not by enumeration: `api/`, `actuator/`, `v3/api-docs`, `swagger-ui` are the server's,
and everything else is the frontend's. Three outcomes:

| Request | Answer | Why |
|---|---|---|
| A file that exists (`/assets/index-abc123.js`) | the file | ordinary static serving |
| A path with a dot in its last segment that doesn't exist (`/assets/deleted.js`, `/robots.txt`) | **404** | a missing asset is a missing asset; returning HTML for it produces a confusing MIME error in the console instead of a clear 404 |
| Anything else unmapped (`/login`, `/products/abc`, `/no-such-page`) | the HTML shell, `200` | the client router owns it — including rendering the app's own not-found page |

## Consequences

- **Any `GET` outside those four prefixes is public.** That is the price of matching by prefix, and it is why the
  prefix list carries a maintenance rule in its own Javadoc: an endpoint mapped outside them would be served without
  authentication. In practice every controller in this application is under `/api/**`, which was verified before the
  matcher was written and is the convention to keep. Adding a top-level server path means adding its prefix here.
  The matcher is `GET`-only, so a stray `POST` is never silently public.
- **Only `GET` falls back.** A `POST` to an unmapped path gets a 404, not an HTML page.
- `/api/**` is unaffected: unknown API paths return this app's JSON 404 (see `GlobalExceptionHandler`), never the
  shell. An API client that mistypes a path gets a machine-readable error, not a page of HTML.
- The rules are unit-testable without a servlet container, because the decision is a `static Resolution
  resolutionFor(String, boolean)` and the resolver is a thin adapter over it — 23 cases in `SpaForwardingConfigTest`.
- The dev server and the packaged jar now agree about what a client route does. Anything that relies on this
  behaviour will be exercised by both, which was the actual failure: the two halves disagreed, and only the half
  nobody walked was wrong.

## Alternatives considered

- **Enumerate the client routes server-side.** Rejected: it makes every new frontend route a backend change, and a
  forgotten one fails as a 401 on reload — the exact failure this replaces, just less often.
- **`ErrorPageRegistrar` mapping 404 to `/index.html`.** Works, but routes normal navigation through the error
  pipeline, which muddies real 404s and the error-handling tests.
- **Let the 404 stand and make the frontend hash-routed** (`/#/products/abc`). Avoids the problem, at the cost of
  uglier URLs and a caveat on every link anyone shares.
