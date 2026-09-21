# API

Every REST endpoint this application exposes: path, role, request shape, response shape. The same description is
generated from the code and served live at `/swagger-ui.html`, where you can log in and call any of it — this page
exists so you can read the whole surface at once, and see the conventions that the generated document can only show
you one operation at a time.

- **Interactive:** `http://localhost:8080/swagger-ui.html`
- **Raw OpenAPI 3 document:** `http://localhost:8080/v3/api-docs`

If the two ever disagree, the generated document is right and this page is stale — it is written by hand.

---

## Conventions

### Base path and content type

Every endpoint below is under `/api`. Requests and responses are `application/json` unless noted; the two exceptions
are the event streams (`text/event-stream`) and the report export (`application/pdf` or the DOCX media type).

### Authentication

One endpoint is public: `POST /api/auth/login`. Everything else requires a bearer token.

```sh
TOKEN=$(curl -s localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin"}' | jq -r .token)

curl -s localhost:8080/api/saas-products -H "Authorization: Bearer $TOKEN"
```

Tokens are signed JWTs and expire after `JWT_EXPIRATION_HOURS` (default 12). There is no refresh endpoint: log in
again. A token is invalidated early only by rotating the signing secret — see
[`decisions/0001-jwt-signing-secret.md`](decisions/0001-jwt-signing-secret.md).

Browsers cannot set an `Authorization` header on an `EventSource`, so the frontend reads the two SSE endpoints with
`fetch` instead. That is a real constraint, not a preference:
[`decisions/0002-sse-auth-via-fetch.md`](decisions/0002-sse-auth-via-fetch.md).

### Roles

| Role in the tables below | Means |
|---|---|
| **public** | No token needed |
| **any** | Any valid token — `ADMIN` or `READ_ONLY` |
| **self** | Any valid token, and it acts on the caller's own record only. There is no path that names another user |
| **ADMIN** | `ADMIN` role required; a `READ_ONLY` token gets a 403 |

`READ_ONLY` restricts **configuration**, not **use**. A read-only user can run analyses, read every report, export
them, and ask questions — but cannot change what is tracked, which URLs this server will fetch, or who has an account.

### Pagination

Paginated endpoints take `page` (zero-based) and `size`, and return a `PageResponse`:

```json
{
  "content": [],
  "page": 0,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0,
  "first": true,
  "last": true
}
```

`size` defaults to 20 (50 on the audit log) and is capped at **200**. `page` below 0 or `size` below 1 is a 400
rather than a silent correction.

### Timestamps

All timestamps are ISO-8601 instants in UTC (`2026-09-20T14:03:11.482Z`). The one exception is `POST .../compare`,
whose `fromDate`/`toDate` are plain `yyyy-MM-dd` calendar days, resolved to a UTC window server-side —
`fromDate` at the start of its day, `toDate` at the **end** of its day, so a comparison ending today includes the run
you just did.

### Errors

Every failure — from validation, from security, from an unhandled exception — has the same body. One shape means the
frontend has one error renderer.

```json
{
  "error": "VALIDATION_FAILED",
  "message": "name: must not be blank",
  "timestamp": "2026-09-20T14:03:11.482Z",
  "path": "/api/saas-products"
}
```

`error` is a stable machine-readable code; `message` is for a human and may change wording. Match on `error`.

| Status | `error` | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | A field failed a bean-validation constraint. `message` names the field |
| 400 | `MISSING_PARAMETER` | A required query parameter was absent |
| 400 | `INVALID_PARAMETER` | A parameter could not be parsed — including an unknown enum value, where `message` lists the valid ones |
| 400 | `MALFORMED_REQUEST` | The body was not readable JSON |
| 400 | `BAD_REQUEST` | A rule the types cannot express: an inverted date range, deleting your own account |
| 401 | `INVALID_CREDENTIALS` | Login failed. Identical for an unknown username and a wrong password, so the endpoint cannot be used to enumerate users |
| 401 | `UNAUTHORIZED` | No token, or it is expired, malformed, or signed with a retired secret |
| 403 | `FORBIDDEN` | Authenticated, but a `READ_ONLY` token was used on an `ADMIN` endpoint |
| 404 | `NOT_FOUND` | No such resource — or it exists but is not reachable under this path, which is deliberately indistinguishable |
| 409 | `CONFLICT` | A uniqueness rule: product name, username, email |
| 503 | `PROVIDER_UNAVAILABLE` | This server is already at its configured concurrency limit, or an export could not be rendered. Safe to retry |
| 500 | `INTERNAL_ERROR` | Anything unhandled. `message` is generic on purpose; the detail is in the server log against a correlation id |

Three things worth knowing about 403 and 404:

- An **unauthenticated** request to an `ADMIN` endpoint is a **401**, not a 403 — the entry point rejects it before
  authorization runs. 403 always means "we know who you are, and the answer is still no."
- A nested resource that exists but belongs to someone else's parent — a report id from a different product, a run id
  from a different product — is a **404**, not a 403. Distinguishing them would confirm that the id exists.
- A path with **no mapping at all** under `/api` is also this JSON 404 — `{"error":"NOT_FOUND","message":"No endpoint
  matches GET /api/does-not-exist."}` — and never an HTML page. A mistyped path names itself back at you, and clients
  can parse the answer. Note the prefix matters: the app serves its own frontend, so unmapped paths *outside* `/api`
  return the single-page app's HTML shell by design, and a client that drops the prefix gets HTML where it expected
  JSON. That is the most likely explanation for "the API returned a web page."

### Endpoints outside this description

`/actuator/health` and `/actuator/prometheus` are intentionally unauthenticated and are not part of the OpenAPI
document. Restrict them at the network layer; see [`SETUP.md`](SETUP.md).

---

## Endpoint index

36 operations across 12 groups. Grouping matches the Swagger UI tags.

| Method | Path | Role | What |
|---|---|---|---|
| POST | `/api/auth/login` | public | Exchange credentials for a JWT |
| GET | `/api/auth/me` | any | The caller's own user record |
| GET | `/api/saas-products` | any | List tracked products, newest first |
| POST | `/api/saas-products` | ADMIN | Create a tracked product |
| GET | `/api/saas-products/{id}` | any | One product with its sources |
| PUT | `/api/saas-products/{id}` | ADMIN | Replace name, description and sources |
| DELETE | `/api/saas-products/{id}` | ADMIN | Delete a product and everything derived from it |
| POST | `/api/saas-products/{id}/run` | any | Run now |
| POST | `/api/saas-products/{id}/compare` | any | Compare two past dates |
| GET | `/api/saas-products/{id}/runs/{runId}/events` | any | Watch a run (SSE) |
| POST | `/api/saas-products/{id}/ask` | any | Ask a question (SSE) |
| GET | `/api/saas-products/{productId}/reports` | any | A product's report history |
| GET | `/api/saas-products/{productId}/reports/{reportId}/export` | any | Download a report as PDF or DOCX |
| GET | `/api/users/me/credentials` | self | My stored LLM keys, masked |
| POST | `/api/users/me/credentials` | self | Store or replace my key for a provider |
| DELETE | `/api/users/me/credentials/{provider}` | self | Remove my key for a provider |
| PUT | `/api/users/me/preferred-provider` | self | Set my preferred provider |
| GET | `/api/users` | ADMIN | List users |
| POST | `/api/users` | ADMIN | Create a user |
| DELETE | `/api/users/{id}` | ADMIN | Delete a user |
| PUT | `/api/users/{id}/password` | ADMIN | Reset a user's password |
| GET | `/api/audit-logs` | ADMIN | Search the audit trail |
| GET | `/api/admin/system-credentials` | ADMIN | System-wide LLM keys and where each resolves from |
| PUT | `/api/admin/system-credentials` | ADMIN | Set the system-wide key for a provider |
| DELETE | `/api/admin/system-credentials/{provider}` | ADMIN | Clear the system-wide override |
| GET | `/api/admin/jwt-secret` | ADMIN | Where the JWT signing secret comes from |
| PUT | `/api/admin/jwt-secret` | ADMIN | Set a signing-secret override — **invalidates every session** |
| DELETE | `/api/admin/jwt-secret` | ADMIN | Clear the override — **also invalidates every session** |
| GET | `/api/admin/settings` | ADMIN | Crawl defaults and their ceilings |
| PUT | `/api/admin/settings` | ADMIN | Update the crawl defaults |
| GET | `/api/admin/stats` | ADMIN | Totals, run counts, 7-day success rate, recent activity |
| GET | `/api/admin/health` | ADMIN | Infrastructure, provider resolvability, last successful run |
| GET | `/api/admin/data-explorer/collections` | ADMIN | Browsable collections |
| GET | `/api/admin/data-explorer/collections/{name}/documents` | ADMIN | Page a collection, secrets masked |
| GET | `/api/admin/data-explorer/collections/{name}/documents/{id}` | ADMIN | One document, secrets masked |
| PUT | `/api/admin/data-explorer/collections/{name}/documents/{id}` | ADMIN | Edit a document's non-secret fields |

---

## Authentication

### `POST /api/auth/login` — public

```json
{ "username": "admin", "password": "admin" }
```

Both fields required. Responds 200:

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "expiresAt": "2026-09-21T02:03:11.482Z",
  "user": { "id": "...", "username": "admin", "role": "ADMIN", "...": "" }
}
```

401 `INVALID_CREDENTIALS` for both an unknown username and a wrong password. Every attempt, successful or not, is
recorded in the audit log — which *does* distinguish the two, because an admin investigating a break-in attempt needs
to know and an anonymous caller does not.

### `GET /api/auth/me` — any

Returns the caller's `UserResponse`, read from the database rather than from the token's claims, so a role change or a
rename shows up on the next page load instead of persisting until the token expires.

---

## SaaS Products

### `GET /api/saas-products` — any

Query: `page` (0), `size` (20, max 200). Returns a `PageResponse<SaasProductResponse>`, newest first, each entry
carrying the timestamp of its most recent run.

### `POST /api/saas-products` — ADMIN

```json
{
  "name": "Acme Cloud",
  "description": "Our vendor's docs and changelog",
  "sources": [
    {
      "type": "WEBSITE",
      "name": "Changelog",
      "endpointUrl": "https://acme.example.com/changelog",
      "maxDepth": 2,
      "maxPages": 50
    },
    {
      "type": "ATLASSIAN_MCP",
      "name": "Vendor Jira",
      "endpointUrl": "https://acme.atlassian.net/mcp",
      "authToken": "the-token"
    }
  ]
}
```

201 with the stored `SaasProductResponse`. `authToken` is encrypted at rest and never returned — the response carries
`authTokenConfigured` and `authTokenLast4` instead, which is why a product is safe for a `READ_ONLY` user to read in
full.

409 if the name is taken.

### `GET /api/saas-products/{id}` — any

One `SaasProductResponse` including all sources.

### `PUT /api/saas-products/{id}` — ADMIN

Same body as create. **A full replacement, not a patch:** sources omitted from the request are removed. Because the
stored `authToken` is never sent to the client, resubmitting a source *without* one keeps the token already on file —
that is how an edit-and-save round trip works without the client ever holding the secret.

409 if another product already uses the name.

### `DELETE /api/saas-products/{id}` — ADMIN

204. Cascades to the product's snapshots, change reports and run records. Not reversible.

---

## Runs

All four are available to **both roles**. See the two-shapes-of-asynchrony note below on why a run hands back an id
and an ask streams in place.

### `POST /api/saas-products/{id}/run` — any

Body optional:

```json
{ "analysisDepth": "REGULAR" }
```

202 with `{ "runId": "..." }`, immediately. The run continues in the background: fetch every source, compare against
the previous snapshots, store a report. Subscribe to the events endpoint to watch it.

503 if this server is already running as many analyses at once as it is configured to allow. A **provider** failure is
not a 503 — it arrives as a `run_failed` event on the stream, because by then the run exists.

### `POST /api/saas-products/{id}/compare` — any

```json
{ "fromDate": "2026-09-01", "toDate": "2026-09-20", "analysisDepth": "REGULAR" }
```

202 with a `runId`, narrated on the same event stream as a run. Fetches and crawls **nothing** — it reads only
snapshots already stored. MCP sources cannot be replayed historically, so what earlier runs recorded is summarised
instead and the resulting report sets `mcpHistoryLimited: true`.

400 if the range is inverted, in the future, or predates all stored data for this product; the message names the
earliest date data exists for.

### `GET /api/saas-products/{id}/runs/{runId}/events` — any

`text/event-stream`. Each event's SSE name is its type, and the JSON body repeats it as `type` so a client using
`onmessage` rather than `addEventListener` sees it too:

```
event: step_completed
data: {"runId":"...","type":"step_completed","step":"FETCHING_SOURCES","detail":"12 pages","elapsedSeconds":8,"report":null}
```

| `type` | Meaning |
|---|---|
| `step_started` | A step began |
| `step_progress` | Progress within a step; `detail` is human-readable |
| `step_completed` | A step finished |
| `step_failed` | A step failed but the run continues — one unreachable source does not abandon the others |
| `run_completed` | Terminal. `report` carries the finished `ChangeReportResponse` |
| `run_failed` | Terminal. `detail` says why |

`step` is one of `FETCHING_SOURCES`, `CONSULTING_MCP_TOOLS`, `LOADING_SNAPSHOTS`, `AGGREGATING_MCP_HISTORY`,
`COMPARING`, `SUMMARIZING`. `report` is `null` on every event except `run_completed`.

**Subscribing replays everything already emitted** before attaching for what comes next, so a client that opens the
stream a second after triggering the run still sees the first crawl, and a reloaded page catches up rather than
showing a run that appears to start halfway through. A finished run is therefore still readable for a short retention
window; after that it is a 404 — the report is permanent and on the history timeline, but its narration is not.

The stream carries an SSE comment keep-alive every 15 seconds, so proxies see bytes during the minutes a model call
takes, and closes on the terminal event. It times out after 30 minutes, which only happens to a run that has
genuinely hung.

### `POST /api/saas-products/{id}/ask` — any

```json
{ "question": "Did they change their SLA this month?" }
```

Responds `text/event-stream` on the POST itself. Answers from the latest stored snapshots and the most recent report;
fetches nothing, stores nothing, and cannot be resumed — which is why there is no id to fetch it with.

| `type` | Payload |
|---|---|
| `chunk` | `text` — the next fragment of the answer |
| `done` | `answer` — the complete answer, so a client need not have accumulated the chunks |
| `error` | `message` — the stream failed after opening |

503 (as a normal JSON error, before the stream opens) if the server is at its concurrency limit. Once the stream is
open, failures arrive as an `error` event instead.

---

## Reports

Both endpoints are readable by **both roles**, and there is no verb here but `GET`. A report is the record of what a
model saw at a moment in time; an endpoint that could edit one would make the history a claim rather than a record.
Reports are removed only by deleting their product.

### `GET /api/saas-products/{productId}/reports` — any

Query: `page`, `size`. Returns a `PageResponse<ChangeReportResponse>`, newest first. Each entry carries its **full**
list of changes rather than a summary, because the history timeline expands a row in place and fetching the detail
separately would be one request per row opened, for data that was already one document.

### `GET /api/saas-products/{productId}/reports/{reportId}/export` — any

Query: `format` — **required**, `pdf` or `docx`. Anything else is a 400 naming both.

Responds with the file, a `Content-Length`, and a `Content-Disposition` naming it after the product and the run date.
Generated on demand; nothing is pre-rendered or cached, so a failed render is a 503 with a JSON body rather than a
truncated file that opens to a blank page.

A `reportId` that exists but belongs to a different product is a 404.

---

## My account

Self-service, available to **both roles** — the path is `/me` and there is no `/api/users/{someoneElse}/credentials`
to guard, so the caller's identity comes from the token and cannot be pointed at anyone else.

### `GET /api/users/me/credentials` — self

```json
[{ "provider": "ANTHROPIC", "configured": true, "last4": "cdef" }]
```

### `POST /api/users/me/credentials` — self

```json
{ "provider": "ANTHROPIC", "apiKey": "sk-ant-..." }
```

`provider` is `ANTHROPIC` or `OPENAI`; `apiKey` needs at least 8 characters. Replaces any existing key for that
provider. Responds 200 with the masked `CredentialStatus` — the key itself is never readable again.

### `DELETE /api/users/me/credentials/{provider}` — self

204. 404 if no key was stored for that provider.

### `PUT /api/users/me/preferred-provider` — self

```json
{ "provider": "OPENAI" }
```

`null` clears the preference and falls back to the resolution order in
[`decisions/0006-credential-resolution.md`](decisions/0006-credential-resolution.md). Naming a provider you have no key
for is allowed and simply has no effect until you store one — the preference is a wish, not a routing rule.

Responds with the caller's updated `UserResponse`, so a client can reflect the change without a refetch.

---

## Users

ADMIN throughout. Every response goes through `UserResponse`, which has no `passwordHash` field at all — "never
return the hash" is enforced by the type rather than by remembering to strip it.

### `GET /api/users` — ADMIN

Every user, oldest first. Not paginated.

### `POST /api/users` — ADMIN

```json
{
  "firstName": "Ada",
  "lastName": "Lovelace",
  "username": "ada",
  "email": "ada@example.com",
  "password": "at-least-4-chars",
  "role": "READ_ONLY"
}
```

All fields required; `role` is `ADMIN` or `READ_ONLY`. 201 with the created user. 409 if the username or email is
already registered.

### `DELETE /api/users/{id}` — ADMIN

204. Their stored LLM API keys go with them — leaving the rows behind would accumulate encrypted secrets belonging to
people without accounts, and a recreated user with a recycled id would silently inherit them.

400 if `id` is the caller's own: deleting yourself leaves you holding a token whose subject no longer exists, which
produces confusing failures on every subsequent request rather than a clean logout.

### `PUT /api/users/{id}/password` — ADMIN

```json
{ "newPassword": "at-least-4-chars" }
```

The current password is not required and cannot be shown — it is a one-way BCrypt hash. Reset, not view.

---

## Admin — Audit Log

### `GET /api/audit-logs` — ADMIN

Read-only; nothing writes here through the API. Every filter is optional and they combine with AND.

| Param | Notes |
|---|---|
| `actorUsername` | **Exact** match, not a substring search — a prefix scan cannot use the `(actorUsername, action, timestamp)` index, and "show me what this user did" is the actual question |
| `action` | One `AuditAction`. An unrecognised value is a 400 listing the valid ones |
| `from`, `to` | Inclusive ISO-8601 bounds, e.g. `2026-09-01T00:00:00Z`. `from` after `to` is a 400, not an empty page |
| `page` | Default 0 |
| `size` | Default **50**, capped at 200 |

Ordering is fixed at newest first; an audit trail in any other order is not useful. Entries look like:

```json
{
  "id": "...",
  "actorUserId": "...",
  "actorUsername": "admin",
  "action": "PRODUCT_RUN_TRIGGERED",
  "targetType": "SaasProduct",
  "targetId": "...",
  "details": { "analysisDepth": "REGULAR" },
  "timestamp": "2026-09-20T14:03:11.482Z"
}
```

`details` is free-form context and never contains a secret — not a value, not a prefix, not a length, not a hash.

`action` is one of: `AUTH_LOGIN_SUCCESS`, `AUTH_LOGIN_FAILURE`, `USER_CREATED`, `USER_DELETED`,
`USER_PASSWORD_RESET`, `PRODUCT_CREATED`, `PRODUCT_UPDATED`, `PRODUCT_DELETED`, `PRODUCT_RUN_TRIGGERED`,
`PRODUCT_COMPARE_TRIGGERED`, `PRODUCT_ASK_SUBMITTED`, `LLM_CREDENTIAL_ADDED`, `LLM_CREDENTIAL_REMOVED`,
`SYSTEM_CREDENTIAL_OVERRIDE_SET`, `SYSTEM_CREDENTIAL_OVERRIDE_CLEARED`, `SYSTEM_SETTINGS_UPDATED`,
`JWT_SECRET_OVERRIDE_SET`, `JWT_SECRET_OVERRIDE_CLEARED`, `DATA_EXPLORER_DOCUMENT_UPDATED`.

---

## Admin — Secrets

### `GET /api/admin/system-credentials` — ADMIN

One entry per provider, with the `source` that would be used if a run started now:

```json
[{ "provider": "ANTHROPIC", "configured": true, "last4": "cdef", "source": "OVERRIDE" }]
```

`source` is `PERSONAL`, `OVERRIDE`, `ENV_VAR`, `HOST_MOUNT` or `NONE`, in that precedence order. The full rules are
in [`decisions/0006-credential-resolution.md`](decisions/0006-credential-resolution.md).

### `PUT /api/admin/system-credentials` — ADMIN

```json
{ "provider": "ANTHROPIC", "apiKey": "sk-ant-..." }
```

Sets the system-wide override — used by any user who has no personal key of their own. Responds with the masked
status.

### `DELETE /api/admin/system-credentials/{provider}` — ADMIN

204. Reverts to `ENV_VAR`/`HOST_MOUNT`, or `NONE`.

### `GET /api/admin/jwt-secret` — ADMIN

```json
{ "configured": true, "source": "ENV_VAR" }
```

`source` is `ADMIN_OVERRIDE`, `ENV_VAR` or `AUTO_GENERATED`. The secret itself is not readable here or anywhere.

### `PUT /api/admin/jwt-secret` — ADMIN

```json
{ "value": "at-least-32-characters-long-secret" }
```

> **This invalidates every issued token, including the caller's own.** Every signed-in user is logged out. A client
> must confirm with the user before calling it.

Responds `{ "configured": true, "source": "ADMIN_OVERRIDE", "sessionsInvalidated": true }`.

### `DELETE /api/admin/jwt-secret` — ADMIN

204, reverting to `JWT_SECRET` or the auto-generated secret. **Also logs everyone out** — the resolver's cache is
dropped either way, even if there was no override to clear.

---

## Admin — Settings

### `GET /api/admin/settings` — ADMIN

```json
{ "defaultMaxDepth": 2, "defaultMaxPages": 20, "maxAllowedDepth": 5, "maxAllowedPages": 200 }
```

The defaults apply to any website source that sets no override of its own. The `maxAllowed*` values are hard ceilings
from configuration (`app.crawler.max-allowed-depth` / `-pages`) and are read-only here. Why there are ceilings at all:
[`decisions/0005-crawl-bounds.md`](decisions/0005-crawl-bounds.md).

The two limits are enforced differently, on purpose: a **default** above a ceiling is rejected, because an admin
typing it has made a mistake worth telling them about, while a **per-source** `maxDepth`/`maxPages` above a ceiling is
silently clamped and logged, because refusing to crawl at all is a worse answer than crawling within bounds.

### `PUT /api/admin/settings` — ADMIN

```json
{ "defaultMaxDepth": 3, "defaultMaxPages": 100 }
```

Both required. `defaultMaxDepth` must be 0–`maxAllowedDepth`, `defaultMaxPages` 1–`maxAllowedPages`; outside that is a
400. Takes effect on the next run; nothing in flight is re-bounded.

---

## Admin — Stats

### `GET /api/admin/stats` — ADMIN

```json
{
  "totalUsers": 4,
  "totalProducts": 7,
  "totalSourcesConfigured": 19,
  "runsLast24h": 3,
  "runsLast7d": 22,
  "runSuccessRate7d": 0.95,
  "avgRunDurationSeconds7d": 41.6,
  "recentRuns": [
    { "productName": "Acme Cloud", "runType": "STANDARD", "status": "success", "runAt": "2026-09-20T13:00:00Z" }
  ]
}
```

`runSuccessRate7d` is a fraction between 0 and 1. `recentRuns` is the ten most recent runs, newest first; `status` is a
`RunOutcome` (`success`, `partial`, `failure`) and is `null` for a run still in flight.

---

## Admin — Health

### `GET /api/admin/health` — ADMIN

The detailed view behind the Admin Console's status panel. `/actuator/health` is the unauthenticated, machine-readable
one; this is the authenticated, explanatory one.

```json
{
  "status": "UP",
  "components": [{ "name": "mongo", "status": "UP", "details": { "maxWireVersion": 25 } }],
  "providers": [{ "provider": "ANTHROPIC", "configured": true, "source": "OVERRIDE", "last4": "cdef" }],
  "lastSuccessfulRun": { "productName": "Acme Cloud", "runAt": "2026-09-20T13:00:00Z", "outcome": "success" },
  "links": { "apiDocs": "/swagger-ui.html", "prometheus": "/actuator/prometheus", "actuatorHealth": "/actuator/health" }
}
```

`status` and each component's `status` are Boot's own codes (`UP`, `DOWN`, …), and `components` is whatever health
contributors are registered, sorted by name — only the top level, so a nested composite contributes its rolled-up
status and no details. `lastSuccessfulRun` is `null` if no run has ever succeeded.

Nothing here is cached: a cached health report is a report about the past, and the reason someone opened this page is
that they suspect the present is different. `providers` still reports whether a key *resolves*, not whether it works —
no request is made to the provider.

---

## Admin — Data Explorer

A read-mostly window onto this application's own MongoDB collections, so you can see what a run actually stored
without attaching a Mongo shell to a container. Every secret field is replaced with `[encrypted]` on the way out, and
a write that tries to set one is rejected — the masking is not a UI convenience that a crafted request can get past.

### `GET /api/admin/data-explorer/collections` — ADMIN

```json
[{ "name": "saas_products", "documentCount": 7, "secretFields": ["authTokenEncrypted"] }]
```

`secretFields` is named up front so a UI can render those inputs as disabled rather than discovering they are read-only
by being rejected. `documentCount` is `-1` when a collection could not be counted, rather than failing the whole
listing.

| Collection | Masked field |
|---|---|
| `users` | `passwordHash` |
| `user_llm_credentials` | `apiKeyEncrypted` |
| `system_llm_credentials` | `apiKeyEncrypted` |
| `system_config` | `valueEncrypted` |
| `saas_products` | `authTokenEncrypted` — nested inside the `sources` array, so masking is recursive |

### `GET /api/admin/data-explorer/collections/{name}/documents` — ADMIN

| Param | Notes |
|---|---|
| `page` | Default 0 |
| `pageSize` | Default 20, capped at 200 |
| `size` | Accepted as a synonym for `pageSize`. If both arrive, `pageSize` wins |

Returns a `PageResponse` of raw documents with secret fields masked. The synonym exists because every other paginated
endpoint here spells it `size`, and making callers remember which of two spellings this one endpoint wants costs more
than accepting both.

### `GET /api/admin/data-explorer/collections/{name}/documents/{id}` — ADMIN

One document, masked. 404 for an unknown collection or id.

### `PUT /api/admin/data-explorer/collections/{name}/documents/{id}` — ADMIN

Body is a partial document: only the fields you send are written.

```json
{ "description": "Corrected description" }
```

A request touching a masked field, `_id`, or `_class` is a 400 — checked recursively, so burying one inside a nested
object or an array does not get past it. The message names the offending field and where the change belongs instead
("use Admin Console > Secrets"), because the useful answer to "I can't edit this" is where you can.

Every accepted edit is audited as `DATA_EXPLORER_DOCUMENT_UPDATED`, recording the collection and which field names
changed — not their values.

---

## Schemas

Field types, bounds and enum values, as generated. `*` marks required.

### Requests

| Schema | Fields |
|---|---|
| `LoginRequest` | `username`\*, `password`\* |
| `SaasProductRequest` | `name`\* (≤200), `description` (≤2000), `sources[]` of `SourceConfigRequest` |
| `SourceConfigRequest` | `type`\*, `name`\* (≤120), `endpointUrl`\*, `authToken`, `maxDepth`, `maxPages` |
| `RunRequest` | `analysisDepth` |
| `CompareRequest` | `fromDate`\* (`yyyy-MM-dd`), `toDate`\*, `analysisDepth` |
| `AskRequest` | `question`\* (≤2000) |
| `CreateUserRequest` | `firstName`\*, `lastName`\*, `username`\*, `email`\*, `password`\* (≥4), `role`\* |
| `ResetPasswordRequest` | `newPassword`\* (≥4) |
| `CredentialRequest` | `provider`\*, `apiKey`\* (≥8) |
| `PreferredProviderRequest` | `provider` (nullable — `null` clears it) |
| `AdminSettingsRequest` | `defaultMaxDepth`\*, `defaultMaxPages`\* |
| `JwtSecretRequest` | `value`\* (≥32) |

### Responses

| Schema | Fields |
|---|---|
| `LoginResponse` | `token`, `tokenType`, `expiresAt`, `user` |
| `UserResponse` | `id`, `firstName`, `lastName`, `username`, `email`, `role`, `preferredLlmProvider`, `createdAt` |
| `SaasProductResponse` | `id`, `name`, `description`, `sources[]`, `sourceCount`, `createdAt`, `createdBy`, `lastRun` |
| `SourceConfigResponse` | `type`, `name`, `endpointUrl`, `authTokenConfigured`, `authTokenLast4`, `maxDepth`, `maxPages`, `effectiveMaxDepth`, `effectiveMaxPages` |
| `RunStartedResponse` | `runId` |
| `ChangeReportResponse` | `id`, `saasProductId`, `runAt`, `runBy`, `runType`, `analysisDepth`, `rangeFrom`, `rangeTo`, `mcpHistoryLimited`, `sourcesIncluded[]`, `overallSummary`, `changes[]`, `changeCount` |
| `SourceInclusionResponse` | `sourceName`, `sourceType`, `fetchedAt` |
| `ChangeResponse` | `sourceName`, `sourceType`, `category`, `description`, `confidence`, `evidenceSnippet` |
| `AuditLogResponse` | `id`, `actorUserId`, `actorUsername`, `action`, `targetType`, `targetId`, `details`, `timestamp` |
| `CredentialStatus` | `provider`, `configured`, `last4` |
| `SystemCredentialStatus` | `provider`, `configured`, `last4`, `source` |
| `JwtSecretStatus` | `configured`, `source` |
| `AdminSettingsResponse` | `defaultMaxDepth`, `defaultMaxPages`, `maxAllowedDepth`, `maxAllowedPages` |
| `AdminStatsResponse` | `totalUsers`, `totalProducts`, `totalSourcesConfigured`, `runsLast24h`, `runsLast7d`, `runSuccessRate7d`, `avgRunDurationSeconds7d`, `recentRuns[]` |
| `AdminHealthResponse` | `status`, `components[]`, `providers[]`, `lastSuccessfulRun`, `links` |
| `CollectionSummary` | `name`, `documentCount`, `secretFields[]` |
| `PageResponse<T>` | `content[]`, `page`, `size`, `totalElements`, `totalPages`, `first`, `last` |
| `ApiErrorResponse` | `error`, `message`, `timestamp`, `path` |

### Enums

| Enum | Values |
|---|---|
| `Role` | `ADMIN`, `READ_ONLY` |
| `SourceType` | `DOCS_MCP`, `ATLASSIAN_MCP`, `GENERIC_MCP`, `WEBSITE`, `SAAS_URL` |
| `AnalysisDepth` | `SHORT`, `REGULAR`, `NUCLEAR` (default `REGULAR`) |
| `RunType` | `STANDARD`, `CUSTOM_RANGE` |
| `RunOutcome` | `success`, `partial`, `failure` — `null` while a run is in flight |
| `LlmProviderType` | `ANTHROPIC`, `OPENAI` |
| `CredentialSource` | `PERSONAL`, `OVERRIDE`, `ENV_VAR`, `HOST_MOUNT`, `NONE` |
| `JwtSecretSource` | `ADMIN_OVERRIDE`, `ENV_VAR`, `AUTO_GENERATED` |
| `ChangeCategory` | `feature`, `pricing`, `policy`, `bugfix`, `documentation`, `deprecation`, `other` |
| `Confidence` | `high`, `medium`, `low` |
| `RunEventType` | `step_started`, `step_progress`, `step_completed`, `step_failed`, `run_completed`, `run_failed` |
| `RunStep` | `FETCHING_SOURCES`, `CONSULTING_MCP_TOOLS`, `LOADING_SNAPSHOTS`, `AGGREGATING_MCP_HISTORY`, `COMPARING`, `SUMMARIZING` |

`ChangeCategory` and `Confidence` are lowercase on the wire because they come back from the model, and three levels of
confidence rather than a percentage is a deliberate choice:
[`decisions/0004-confidence-as-three-levels.md`](decisions/0004-confidence-as-three-levels.md).

---

## Adding an endpoint

The `add-a-new-endpoint` skill in `.claude/skills/` is the checklist. Two things about this document specifically:

- Add a row to the endpoint index above and a section under the right group. The generated OpenAPI document updates
  itself; this page does not.
- `@Operation(summary = ...)` is not optional. Cross-cutting 400/401/403/404 responses are derived from the handler by
  `OpenApiConfig#commonErrorResponses`, so you do not annotate those — but an endpoint-specific code (409, 503) needs
  an explicit `@ApiResponse`, and that annotation has two non-obvious requirements documented on the customizer.

## See also

- [`ARCHITECTURE.md`](ARCHITECTURE.md) — why the system is shaped this way
- [`SETUP.md`](SETUP.md) — environment variables, running it, running the tests
- [`decisions/`](decisions/) — the ADRs referenced above
