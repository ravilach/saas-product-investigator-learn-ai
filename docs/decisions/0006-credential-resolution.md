# 0006 — Credential resolution: override before env var, no `last4` for the JWT secret, no caching

**Status:** accepted
**Date:** 2026-09-20

## Context

The build prompt fixes the resolution order for every secret in the application:

> **Admin Console override (encrypted in Mongo) > explicit external config (env var / properties / K8s Secret /
> ECS secret) > automatic fallback.**

That settles the order. It leaves four questions that are each visible from outside the code — they change what an
operator sees, what an admin can recover from, and what happens on the click after the one they just made. All four
are decided here.

## Decision 1 — a stored override outranks the environment variable

This is the order the prompt gives, and it is worth writing down *why*, because the instinct of most people reading
it for the first time is that explicit deployment configuration should win.

The scenario the order is built for: a container was deployed six weeks ago with `ANTHROPIC_API_KEY` baked into a
task definition. The key has since been rotated at the provider. Every run now fails on a 401. With the override
ranked first, an admin pastes the new key into the Secrets tab and the next run works. With the env var ranked
first, the Secrets tab is a form that appears to accept input and changes nothing, and the fix requires a redeploy
by whoever owns the task definition.

The cost is real and accepted: an operator who changes the env var and restarts may find the app still using an
override they have forgotten about. This is why `GET /api/admin/system-credentials` reports a `source` per provider,
and why `GET /api/admin/jwt-secret` reports `ADMIN_OVERRIDE | ENV_VAR | AUTO_GENERATED`. "Which channel won" is
always answerable from the UI, without shell access.

Two secrets sit outside this model entirely, structurally rather than by policy:

| Secret | Why it cannot be an override |
|---|---|
| `CREDENTIAL_ENCRYPTION_KEY` | It is what decrypts everything in Mongo, including itself if it were there. |
| `MONGODB_URI` | It is how Mongo is reached. Storing it in Mongo requires already having it. |

Neither is settable from the Admin Console, and no amount of UI work could change that.

## Decision 2 — the JWT signing secret has no `last4`

Every API key status in this application reports the last four characters, so an operator can tell *which* key is
configured without being shown it. `JwtSecretStatus` deliberately does not.

An API key's tail is recognisable: it appears in the provider's dashboard, so `…wxyz` answers "is this the
production key or the one from my laptop?" for someone who legitimately holds it. The JWT signing secret is held by
nobody — it is auto-generated on first boot in the common case, and nobody ever needs to recognise it. So four
characters of it would answer no question for anyone legitimate, while still removing four characters of search
space for anyone else. The status reports `configured` and `source`, and that is the complete set of facts anyone
needs.

`configured` is always `true`, which looks like a useless field until you notice what it rules out: there is no
state in which the app has no signing secret, because with no override and no `JWT_SECRET` one is generated and
persisted. A `false` would mean the app could not issue tokens at all.

## Decision 3 — nothing in `credential/` is cached

`JwtSecretResolver` caches its resolved key for 15 seconds. `SystemCredentialService` and `UserCredentialService`
cache nothing, which is an inconsistency worth a paragraph.

The JWT secret is consulted on **every authenticated request**, so a Mongo read per request would be a real cost for
a value that changes perhaps twice in an installation's life. A provider key is read **once per run or ask** — an
operation that then spends seconds inside an LLM API call. Caching it would save an amount of time that cannot be
measured, and would cost the following support conversation:

> "I pasted the new key and it works on the second attempt but not the first."

which is what a 15-second per-replica cache looks like from the outside when two replicas are behind a load
balancer. So the read happens every time, and pasting a key takes effect on the next run everywhere.

Where the JWT resolver *does* cache, the write and the cache-drop are deliberately a single method
(`applyOverride`, `clearOverride`) rather than two calls a controller has to remember to make in order. The failure
mode of separating them is invisible: the write succeeds, the cache is not dropped, and that instance keeps
accepting tokens it has just decided are invalid for up to 15 seconds.

## Decision 4 — an MCP `authToken` on update is absent / empty / value

`SourceConfigRequest.authToken` carries three meanings, because a source edit form submits every field including the
ones the user did not touch — and it can never submit the current token, because it was never shown one.

| Value | Meaning |
|---|---|
| `null` / absent | Leave the stored token exactly as it is |
| `""` | Remove the stored token — this source needs none |
| anything else | Replace the stored token with this value |

The alternative is a sentinel the client echoes back to mean "unchanged" — `"********"`, or the `last4` itself. It
was rejected for two reasons. The sentinel becomes a value a real token could theoretically equal, at which point a
user's genuine token silently fails to save. And it makes the client responsible for a protocol it can get wrong
invisibly: a form that forgets to echo the sentinel deletes a working credential, and the request looks entirely
well-formed. Absent-means-unchanged needs no agreement beyond ordinary JSON semantics, and the failure mode of
getting it wrong is a token that doesn't change rather than one that disappears.

Matching a submitted source against its stored counterpart is done **by name, case-insensitively**, which makes a
rename a replacement: the renamed source starts with no token. That follows from the name being the identity a
source's snapshots and report history are recorded against — carrying the token across would be a guess about which
old source was meant, and the wrong guess attaches a credential to something the user thinks is new.

## Consequences

- The Admin Console can always answer "which channel supplied the key in use", for every provider and for the JWT
  secret.
- An admin can recover from a rotated provider key, or a rotated `CREDENTIAL_ENCRYPTION_KEY`, without a redeploy —
  an override that cannot be decrypted is logged and skipped rather than failing resolution, so a working env var
  keeps the app running.
- A `DELETE` on either credential endpoint responds `204` whether or not anything was stored, and audit-logs only
  a real removal. The trail records changes, not clicks.
- `GET /api/saas-products/{id}` can never leak a token, in plaintext or ciphertext: `SourceConfigResponse` has no
  field that could hold one.
