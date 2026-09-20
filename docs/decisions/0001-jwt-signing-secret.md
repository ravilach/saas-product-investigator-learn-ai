# 0001 — JWT signing secret: resolved, not derived

**Status:** accepted · **Date:** 2026-09-20

## Context

Authentication is stateless: a signed JWT carries identity and role so any replica can serve any request. That
requires a signing secret, and the app has a hard constraint working against the obvious approaches — **every
environment variable must be optional**, because `docker run -p 8080:8080 <image>` with nothing else set has to
produce a working instance.

Three candidate approaches:

1. **Require `JWT_SECRET`.** Simple and explicit, but breaks the zero-config quick start.
2. **Derive the secret from the seeded admin's password hash.** Tempting: no new state, no new variable.
3. **Generate one on first boot and persist it.**

Option 2 is the one worth explaining, because it looks reasonable and isn't:

- The default admin password is `admin`, documented publicly in this repo. A secret derivable from it is a secret
  anyone can compute — and with it, forge a valid token for **any** user, including an admin they never created.
- Every password reset would silently invalidate every session, for every user, with no obvious connection between
  cause and effect.
- It couples the authentication layer to one specific row in one specific collection.

## Decision

The signing secret is **resolved** at use time, in this order:

1. **Admin Console override** — `JWT_SIGNING_SECRET_OVERRIDE` in `system_config`, encrypted at rest. Wins over
   everything, so an operator can rotate without a redeploy.
2. **`JWT_SECRET`** — env var or `application.properties`. Blank counts as unset; an empty-string Kubernetes Secret
   value is an easy mistake and must not become "signed with the empty secret."
3. **Auto-generated** — 256 bits from `SecureRandom` on first boot, encrypted under `CREDENTIAL_ENCRYPTION_KEY`, and
   persisted in `system_config` under `JWT_SIGNING_SECRET`.

Implementation details that follow from this:

- Whatever the source, the string is SHA-256'd into a 32-byte HMAC key, so a short hand-set passphrase still yields a
  valid HS256 key rather than a startup failure.
- Resolution is cached for 15 seconds. Without a cache every request hits Mongo; without a TTL an admin's rotation
  wouldn't take effect until restart. `invalidateCache()` makes a deliberate rotation immediate.
- First-boot generation goes through `putSecretIfAbsent`, which tolerates a `DuplicateKeyException` and adopts the
  winner's value. Two replicas starting simultaneously must converge on one secret, or each rejects the other's
  tokens. The unique index on `system_config.key` is load-bearing for this, not decorative.
- `GET /api/admin/jwt-secret` returns `{configured, source}` and never a value. Unlike an API key there is no useful
  `last4` for a signing secret — a masked hint of it helps nobody and leaks entropy.

## Consequences

- Zero-config boot works, and a restart does **not** sign everyone out, because the generated secret is persisted.
- Statelessness means individual tokens can't be revoked. The escape hatch is intentional and coarse: **changing the
  secret invalidates every issued token at once.** The Admin Console must state that plainly and require
  confirmation — it signs out the admin performing it too.
- The secret's availability now depends on `CREDENTIAL_ENCRYPTION_KEY`, since the persisted copy is encrypted. If
  that key changes, the persisted secret becomes unreadable, a fresh one is generated, and everyone is signed out
  once. That is the correct failure mode (fail closed), and it's documented in `SETUP.md`.
- Tests lock the ordering in `JwtSecretResolverTest`, including the replica race and the blank-env-var case. The
  ordering is a security property: if `JWT_SECRET` could beat an admin override, an admin who rotated to sign
  everyone out would believe they had, while every existing token stayed valid.
