# 0008 — The JWT is stored in `localStorage`, not an HttpOnly cookie

**Status:** accepted · **Date:** 2026-09-20

## Context

The backend issues a stateless JWT from `POST /api/auth/login` and expects it back as
`Authorization: Bearer <token>` on every request (see [0001](0001-jwt-signing-secret.md) and
[0002](0002-sse-auth-via-fetch.md)). The browser has to keep that token somewhere across page loads.

The two realistic options are `localStorage` and an `HttpOnly; Secure; SameSite` cookie. The received wisdom is
"never put a token in `localStorage`, because XSS can read it," and that is true as far as it goes.

## Decision

Store the session — token, expiry, and the user record — as one JSON value in `localStorage` under `spi.session`.

The reasoning is that the XSS argument, for *this* app, does not distinguish the options:

1. **An HttpOnly cookie is unreadable, not unusable.** Injected script can't exfiltrate it, but it can issue
   authenticated requests from the victim's browser for as long as the page is open, and this app's dangerous
   operations (create a user, read the Data Explorer, rotate the JWT secret) are all things an attacker would rather
   *do* than replay later. The cookie narrows the window of abuse; it does not close it.
2. **A cookie moves the harder problem rather than removing it.** Cookies are sent automatically, which reintroduces
   CSRF and therefore a CSRF token, its own endpoint, and its own failure mode. A bearer header is not sent
   automatically and so is not forgeable cross-site by construction.
3. **`Secure` cookies need HTTPS, and the quick start is `http://localhost:8080`.** A cookie-based session that
   silently degrades to non-`Secure` in the documented first-run path is worse than a header the user can see.
4. **SSE already requires the header.** Per [0002](0002-sse-auth-via-fetch.md), the token must be readable by
   JavaScript to put it on a streaming `fetch`. An HttpOnly cookie is by definition not.

## Consequences

- XSS is the real defence, not storage choice: no `dangerouslySetInnerHTML` anywhere, no `eval`, and LLM-generated
  report text is rendered as text, never as markup. That last one is the live risk in this app — report content is
  derived from crawled third-party pages, which is untrusted input by definition.
- Stored sessions are validated before being trusted. An expired `expiresAt` is cleared on read rather than used
  optimistically, so a stale tab shows the login page instead of a shell that 401s on its first query.
- Sign-out is purely client-side, because there is no server-side session to end. Cross-tab consistency comes from
  a `storage` event listener: one tab signing out (or having its token rejected after a JWT-secret rotation) signs
  the others out too.
- If this app ever grows a refresh-token flow, that revisits this decision — a long-lived refresh token is exactly
  the credential where HttpOnly earns its keep, while the short-lived access token stays in memory.
