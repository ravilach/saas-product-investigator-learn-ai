---
name: rotate-secrets
description: Rotate an LLM API key, the JWT signing secret, the admin password, or CREDENTIAL_ENCRYPTION_KEY. Use when asked to change or revoke any credential this app holds.
---

# Rotate secrets

Four different things with four different blast radii. Identify which one before touching anything.

| Secret | Blast radius | Reversible? |
|---|---|---|
| LLM API key (Anthropic/OpenAI) | Next run uses the new key | Yes |
| Admin or user password | That user re-logs in | Yes |
| `JWT_SECRET` | **Every session, everywhere, immediately** | Yes — but everyone signs in again |
| `CREDENTIAL_ENCRYPTION_KEY` | **Every stored secret becomes unreadable** | **No**, unless you re-enter them all |

## LLM API key — routine

Admin Console → Secrets (system-wide), or Account Settings (your own BYOK key). Stored encrypted, displayed as a
`last4` tail and never as a key.

1. Add the new key in the UI. It replaces the old one for that provider and scope.
2. Run something — a real run or an Ask — and confirm success.
3. **Then** revoke the old key at the provider. In that order; revoking first turns a rotation into an outage.
4. Check the audit log for `LLM_CREDENTIAL_ADDED`. Its `details` carries the provider and nothing else — not the key,
   not the tail, not the length.

Note the resolution order: a per-user BYOK key wins over the system-wide one. Rotating the system key changes nothing
for a user who has their own — see `docs/decisions/0006-credential-resolution.md`.

## JWT signing secret

`GET /api/admin/jwt-secret` reports `source` (`AUTO_GENERATED` or `ENV_VAR`) and never the value. There is
deliberately **no** `PUT` — setting an override signs out every session including the one issuing the request, which
is a poor thing to discover mid-request.

To rotate: set `JWT_SECRET` in the environment and restart. Everyone re-authenticates; nothing stored is lost.
Leaving it unset is the supported default — the app generates one on first boot and persists it encrypted, so
restarts don't sign everyone out. See `docs/decisions/0001-jwt-signing-secret.md`.

## Admin / user password

Admin Console → Users → Reset password, or `POST /api/users/{id}/reset-password`. Do this before any instance is
reachable by anyone but you; the app logs a loud multi-line banner on the boot that seeds `admin`/`admin`.

## `CREDENTIAL_ENCRYPTION_KEY` — read this before changing it

This is the root of trust for everything else the app stores: LLM keys, MCP tokens, the persisted JWT secret. It
cannot itself be stored encrypted, which is why it comes from the environment.

**Changing it does not migrate anything.** Every previously encrypted value becomes undecryptable, and the app treats
undecryptable as unset — so there is no error, no warning, no failed boot. The symptom is "all our API keys
disappeared." A rolled-back deploy does not undo it either.

Rotation, if you genuinely need it:

1. Inventory what's stored: every user's BYOK keys, every system-wide provider key, every MCP source token. You are
   going to re-enter all of them by hand.
2. Announce it. Sessions break too, since the persisted JWT secret was encrypted under the old key.
3. Set the new key. Restart.
4. Re-enter every credential from step 1.
5. Verify a run end to end, and verify each MCP source individually — a source whose token silently became unset
   fails at run time as a `partial` outcome, not at boot.

Prefer rotating the individual credentials instead. That's almost always the actual requirement.

## Where each secret lives per environment

| | Local | docker-compose | Kubernetes | ECS |
|---|---|---|---|---|
| `CREDENTIAL_ENCRYPTION_KEY` | `deploy/.env` | `deploy/.env` | `secret.yaml` | Secrets Manager |
| `MONGODB_URI` | `deploy/.env` or default | compose `environment` | `secret.yaml` | Secrets Manager |
| LLM keys | Admin Console | Admin Console or env | Admin Console or Secret | Admin Console or Secrets Manager |
| `JWT_SECRET` | unset (generated) | unset | commented out | omitted |

Never in `application.properties`, never in a manifest committed with a real value, never in a log line. The k8s
`secret.yaml` and the ECS task definition ship `CHANGEME` and ARN placeholders precisely so a real value can't be
committed by accident — and the Harness deploy pipeline must not include `secret.yaml` in its manifest list, or a
deploy overwrites live credentials with `CHANGEME`. See `docs/DEPLOYMENT.md`.

## Verify

After any rotation: log in, then trigger a real run. Boot succeeding proves nothing here — every one of these
failures is silent at startup and only surfaces when something tries to *use* the secret.
