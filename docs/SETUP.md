# Setup

The reference page: every prerequisite, every environment variable, every way to run the app and its tests. If you
want the guided, verify-as-you-go path instead, use [`GETTING_STARTED.md`](GETTING_STARTED.md).

## Prerequisites

| Tool | Version | Why |
|---|---|---|
| JDK | 25 or newer | The build sets `maven.compiler.release=25`, so it compiles against the Java 25 API on any newer JDK and the jar still runs on the `eclipse-temurin:25-jre` base image. |
| Maven | 3.9+ | Wrapper not committed; use your own. |
| Node | 20 or newer | Frontend build and Vitest. |
| Docker | any recent | For MongoDB locally, and for the packaged image. A running **daemon**, not just a client. |
| MongoDB | 7 or 8 | Only if you'd rather run one outside Docker. |

---

## ⚠️ The default admin credential

**On first startup against an empty `users` collection, the app seeds exactly one user:**

| Field | Value |
|---|---|
| username | `admin` |
| password | `admin` |
| email | `admin@localhost` |
| role | `ADMIN` |

This is a **publicly known credential printed in this repo's documentation.** It exists so that a bare
`docker run -p 8080:8080 saas-investigator` is a complete working instance with nothing to configure first — that is
the only problem it solves.

**Rotate it before this instance is reachable by anyone but you.** Log in, go to Admin Console → Users → Reset
password. The app logs a loud multi-line warning on the boot that creates it; that warning is not decoration.

Two related things worth knowing:

- The seeder only runs when the `users` collection is **completely empty**. It will never recreate `admin` after you
  delete it, and it will never reset a password you've changed.
- The JWT signing secret is **not** derived from the admin password — deliberately. If it were, this
  publicly-known default password would let anyone forge a token for any user, and every password reset would
  invalidate every session. See [`decisions/0001-jwt-signing-secret.md`](decisions/0001-jwt-signing-secret.md).

---

## Environment variables

Every one of these is optional. That is a design goal, not an accident: `docker run -p 8080:8080 <image>` with no
variables at all must produce a fully working instance.

| Var | Required | Default | Notes |
|---|---|---|---|
| `ANTHROPIC_API_KEY` | no | none | Configure via Admin Console → Secrets after boot instead. Until a key exists from some source, a run or ask fails with an actionable message rather than a stack trace. |
| `ANTHROPIC_MODEL` | no | `claude-sonnet-5` | |
| `OPENAI_API_KEY` | no | none | Same story as the Anthropic key. |
| `OPENAI_MODEL` | no | `gpt-6-astra` | |
| `LLM_MAX_PROMPT_CHARS` | no | `600000` | Total source-text budget for one prompt, across every block in it. See [prompt and concurrency limits](#prompt-and-concurrency-limits). |
| `RUN_CONCURRENCY` | no | `3` | Runs and comparisons executing at once. |
| `RUN_QUEUE_CAPACITY` | no | `12` | Runs allowed to wait. Full means `503`, not a longer queue. |
| `ASK_CONCURRENCY` | no | `6` | Questions answered at once, on a pool of their own. |
| `MONGODB_URI` | no | `mongodb://localhost:27017/saas-investigator` locally; **unset inside the container means the container starts its own MongoDB** | See [container fallbacks](#container-fallbacks-and-where-the-line-is). Bound to `spring.mongodb.uri`, not `spring.data.mongodb.uri` — see the note below. |
| `JWT_SECRET` | no | auto-generated on first boot and persisted | Left unset, a 256-bit secret is generated, encrypted, and stored in `system_config`, so restarts do **not** sign everyone out. |
| `JWT_EXPIRATION_HOURS` | no | `12` | |
| `CREDENTIAL_ENCRYPTION_KEY` | no | none locally (the app refuses to start without it); generated into `/data/credential-key` inside the container | The master key for everything encrypted at rest. See the warning below. |
| `IGNORE_HOST_CREDENTIALS` | no | `false` | `true` skips the host-mounted credential file even when present. |
| `HOST_CREDENTIAL_ANTHROPIC_PATH` | no | `/run/host-credentials/anthropic-api-key` | Mount a key file here instead of putting it in the container's env. |
| `SERVER_PORT` | no | `8080` | |
| `CORS_ALLOWED_ORIGINS` | no | `http://localhost:5173,http://127.0.0.1:5173` | Only matters in local development; in the packaged image the frontend and API share an origin. |

> **`CREDENTIAL_ENCRYPTION_KEY` is the one variable worth being careful with.** LLM API keys, MCP `authToken`s, and
> the auto-generated JWT secret are all AES-256-GCM encrypted under it. Change it and all of those become
> permanently unreadable — the app treats undecryptable values as unset and logs an error rather than refusing to
> boot, so the symptom is "my saved key vanished," not a crash. Generate one with
> `head -c 32 /dev/urandom | base64` and keep it wherever you keep secrets.
>
> Neither `CREDENTIAL_ENCRYPTION_KEY` nor `MONGODB_URI` can be set from the Admin Console. That's structural: they
> are needed to read the database that the Admin Console's settings live in.

> **If you are editing the Mongo connection, the property is `spring.mongodb.uri`.** Spring Boot 4.0 split
> connection configuration out of the Spring Data namespace and removed `spring.data.mongodb.uri` at deprecation
> level `error`, which means it is **not bound and not warned about** — it is silently ignored and the connection
> falls back to Boot's own default of `mongodb://localhost/test`. The app boots, every query works, and the data
> lands in a database nobody configured. This repo hit exactly that, which is why
> `ApplicationPropertiesBindingTest` now checks every Spring-owned property in `application.properties` against the
> configuration metadata Boot ships, and fails the build on any that a future upgrade removes. Most search results
> still show the old name; `spring.data.mongodb.auto-index-creation` is a genuine Spring Data property and keeps
> its prefix, which is why the two sit side by side looking inconsistent.

### Secrets resolution order

For LLM provider keys, the JWT signing secret, and per-source MCP auth tokens, the same precedence applies:

1. **Admin Console override** (encrypted in Mongo) — wins over everything, so an operator can fix a bad value
   without a redeploy.
2. **Explicit external config** — env var, `application.properties`, Kubernetes Secret, ECS secret.
3. **Automatic fallback** — the host-mounted credential file for the Anthropic key; generate-and-persist for the JWT
   secret; nothing for the rest.

See [ADR 0006](decisions/0006-credential-resolution.md) for why the override outranks the env var, and what that
costs.

### Where an LLM API key can live

Four places, and they are not alternatives to each other — they answer different questions.

| Where | Who sets it | Applies to | Use when |
|---|---|---|---|
| Admin Console → Secrets (`PUT /api/admin/system-credentials`) | admin | everyone's runs | The deployed key was rotated, or you never set one and want to get going without a redeploy. |
| `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` | whoever deploys | everyone's runs | Headless provisioning — K8s Secret, ECS secret, CI. |
| Host-mounted file | whoever runs the container | everyone's runs (Anthropic only) | A throwaway local container that should use the key already on your laptop. |
| Account Settings (`POST /api/users/me/credentials`) | any user, including READ_ONLY | that user's own runs | Bring your own key, so your usage is on your own quota. |

Personal keys win for the user who set one; otherwise the system-wide chain applies. A user also picks
`preferredLlmProvider` (`PUT /api/users/me/preferred-provider`, `null` to clear) — with no preference, the
system-wide default is Anthropic.

The host mount is the shortest path to a working local instance:

```bash
# The file must contain the key and nothing else. A trailing newline is fine — it's trimmed.
printf '%s' "$ANTHROPIC_API_KEY" > ~/.anthropic-key
docker run -p 8080:8080 -v ~/.anthropic-key:/run/host-credentials/anthropic-api-key:ro saas-product-investigator
```

Read once at startup, Anthropic only. Every way it can be wrong — missing, a directory, the wrong file, unreadable,
blank — logs a line and continues; nothing about a credential file can stop the app from booting. Set
`IGNORE_HOST_CREDENTIALS=true` to skip it deliberately, which is how you check the app's behaviour with no key
configured at all.

Nothing ever returns a stored key. The API answers with `{ provider, configured, last4 }` and, for system keys, the
`source` that won — `OVERRIDE`, `ENV_VAR`, `HOST_MOUNT`, or `NONE`.

### Rotating the JWT signing secret

`PUT /api/admin/jwt-secret` with `{ "value": "…" }` (32 characters minimum) sets an override, and
`DELETE /api/admin/jwt-secret` clears it.

> **Both sign every user out, including you.** Tokens are stateless and signed, so changing the key that verifies
> them *is* what invalidation means — there is no selective version and no way to exempt the admin who pressed the
> button. Your next request gets a `401` and you log in again. That is the intended behaviour: this endpoint is how
> "sign everyone out now" is implemented.

`GET /api/admin/jwt-secret` returns `{ configured, source }` and never the value, not even a masked tail — see
[ADR 0006](decisions/0006-credential-resolution.md), decision 2.

### Crawler settings

These are `application.properties` values rather than environment variables, because unlike the table above they are
operator tuning rather than per-deployment wiring. Override any of them the usual Spring way — a property, a
`SPRING_APPLICATION_JSON` entry, or the relaxed-binding env var (`APP_CRAWLER_CONCURRENCY=6`).

| Property | Default | What it does |
|---|---|---|
| `app.crawler.default-max-depth` | `2` | Used when a source sets no `maxDepth`. Counts **hops from the starting URL**: `0` fetches only that page. Also settable at runtime from Admin Console → Settings. |
| `app.crawler.default-max-pages` | `20` | Used when a source sets no `maxPages`. Also settable from Admin Console → Settings. |
| `app.crawler.max-allowed-depth` | `5` | **Ceiling.** No source and no Admin Console setting can exceed it. |
| `app.crawler.max-allowed-pages` | `200` | **Ceiling**, same. |
| `app.crawler.concurrency` | `4` | Simultaneous fetches per crawl. Clamped to 1–16 in code: this is a politeness budget aimed at one origin, and 500 would be a denial-of-service tool rather than a performance setting. |
| `app.crawler.per-page-timeout-ms` | `10000` | Per-page request timeout. Floored at 1000 — a sub-second timeout abandons pages that were merely slow. |
| `app.crawler.connect-timeout-ms` | `5000` | TCP/TLS connect timeout, shared across the crawl's HTTP client. |
| `app.crawler.max-bytes-per-page` | `5242880` | Response body read limit, applied with a bounded read so a chunked response with no `Content-Length` can't stream forever. |
| `app.crawler.max-chars-per-source` | `200000` | Total extracted text per source. On hitting it the crawl keeps the earliest pages and marks the snapshot truncated. |
| `app.crawler.max-crawl-delay-ms` | `2000` | Ceiling on an honoured `robots.txt` `Crawl-delay`. The delay is respected; a site publishing 300 seconds is clamped and the clamp logged. |
| `app.crawler.user-agent` | `SaaSProductInvestigator/0.1 (+<repo URL>)` | Sent on every request. The product token before the `/` is what's matched against `robots.txt` `User-agent` lines, so change both halves together or change neither. |

The first two resolve in three layers — the source's own value, then the `CRAWL_DEFAULTS` document in
`system_config`, then these defaults — read fresh on every crawl, so an Admin Console change takes effect without a
restart. The ceilings are deliberately not editable from the Admin Console; see
[ADR 0005](decisions/0005-crawl-bounds.md) for why, along with the `robots.txt` and truncation behaviour.

> Output token budgets per analysis depth are **not** properties. They live on the `AnalysisDepth` enum, because the
> budget and the depth's prompt instruction have to change together — NUCLEAR asking for exhaustive detail with a
> SHORT budget is just a truncated report.

### Prompt and concurrency limits

| Property (env var) | Default | What it does |
|---|---|---|
| `app.llm.max-prompt-chars` (`LLM_MAX_PROMPT_CHARS`) | `600000` | Total source text in one prompt, across every block. Floored at 20 000 in code. |
| `app.run.concurrency` (`RUN_CONCURRENCY`) | `3` | Runs and comparisons executing simultaneously. |
| `app.run.queue-capacity` (`RUN_QUEUE_CAPACITY`) | `12` | How many runs may wait for a thread. |
| `app.run.ask-concurrency` (`ASK_CONCURRENCY`) | `6` | Questions answered simultaneously, on a separate pool. Its queue is four times this. |

The prompt cap is not a model context limit — it is a cost and latency limit. One nuclear run over several large
documentation sites can assemble far more text than any question needs answered, and the budget is spent from the
top, so the freshest and most-changed sources survive truncation. Raising it raises the price of every run at that
depth.

The two pools exist separately on purpose. A run crawls somebody else's website and makes a metered model call, so an
unbounded queue would turn twenty impatient clicks into twenty simultaneous crawls; past the queue the answer is a
`503` saying to try again in a few minutes, which is a correct answer rather than a degraded one. Questions get their
own pool because they are short, cheap, and typed into a query bar — behind a shared queue, three concurrent nuclear
runs would make the query bar stop responding with no error at all.

---

## Running locally

### With Docker for MongoDB only (the usual dev loop)

Do the one-time setup first, so the encryption key is the same on every subsequent run:

```sh
cp deploy/.env.example deploy/.env
printf 'CREDENTIAL_ENCRYPTION_KEY=%s\n' "$(head -c 32 /dev/urandom | base64)" >> deploy/.env
```

`deploy/.env` is gitignored. `deploy/.env.example` is committed and documents every variable it can hold. Then, on
each run:

```sh
docker compose -f deploy/docker-compose.yml up -d mongo

set -a; . deploy/.env; set +a      # -a exports every assignment, so `mvn` inherits them
cd backend && mvn spring-boot:run
```

`docker compose` reads `deploy/.env` by itself, so the fully containerised commands below need no `set -a`.

Frontend, in a second terminal:

```sh
cd frontend && npm install && npm run dev
```

### Without Docker at all

Install and start MongoDB yourself, point `MONGODB_URI` at it, and the commands above are otherwise identical.
Nothing in the backend requires Docker — it's only ever the easiest way to get a database.

### Fully containerised

```sh
docker build -t saas-investigator .
docker run -p 8080:8080 saas-investigator
```

Or app-and-database as two containers, which is what you want as soon as they should restart independently:

```sh
docker compose -f deploy/docker-compose.yml up --build      # reads deploy/.env
```

### Container fallbacks, and where the line is

With no `MONGODB_URI`, the container's entrypoint starts a local `mongod` with its data in `/data/db`. With no
`CREDENTIAL_ENCRYPTION_KEY`, it generates one into `/data/credential-key`. Both fallbacks live under `/data` on
purpose: mount one volume there (`-v saas-data:/data`) and the database *and* the key persist together; mount
nothing and both are ephemeral together. There's never a mismatch where one outlives the other.

That fully self-contained mode is a real working instance, not a toy — but it is **single-container,
single-instance**. It cannot be scaled past one replica (each would boot its own disconnected database with its own
generated key). Set `MONGODB_URI` and `CREDENTIAL_ENCRYPTION_KEY` explicitly for anything else; the
docker-compose/Kubernetes/ECS samples under [`/deploy`](../deploy/) always do.

---

## Running the tests

```sh
cd backend && mvn test
open target/site/jacoco/index.html    # coverage report, written by the `test` phase
```

```sh
cd frontend && npm test -- --run
```

JaCoCo is bound to the `test` phase and produces `target/site/jacoco/index.html`. There is deliberately **no
coverage threshold** yet — see the `enable-coverage-gate` skill for turning one on when the code has settled.

### If Testcontainers can't find Docker

Some tests start a real MongoDB in a container, because the queries they cover (dynamic filters, count-before-paginate)
prove nothing against a mock. They fail rather than skip when Docker is missing — a silently skipped test that reports
as a pass is worse than a red build.

Testcontainers looks for the daemon at `/var/run/docker.sock`, which only Docker Desktop provides. Anything else
needs two variables. Find your endpoint with `docker context ls`, then:

```sh
# Rancher Desktop
export DOCKER_HOST="unix://$HOME/.rd/docker.sock"
# Colima
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"

# Both: tells Testcontainers where Ryuk (its cleanup sidecar) should find the socket *inside* the container,
# which is the default path regardless of where it lives on the host.
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

`Could not find a valid Docker environment` is the error this fixes. Set these once in your shell profile rather than
per-run; they're machine configuration, so they intentionally aren't committed to the repo.

### `docker build` and `docker run` never run tests — by design

The image is built with `mvn package -DskipTests`. This is intentional and worth being explicit about: the
quick-start path must work regardless of test state, so a red or half-written test suite can never be the reason
`docker run` fails to boot the app. The CI pipeline in [`/harness`](../harness/) is where tests gate anything;
`docker build` is not a quality gate and isn't pretending to be one.

---

## Troubleshooting

| Symptom | Cause |
|---|---|
| `CREDENTIAL_ENCRYPTION_KEY is not set` at startup | Exactly what it says — the app fails fast rather than silently storing secrets it can't protect. Export one. |
| Saved LLM key shows as absent after a restart | `CREDENTIAL_ENCRYPTION_KEY` changed. Undecryptable values are treated as unset; re-enter the key. |
| Everyone was signed out unexpectedly | The JWT signing secret changed — an admin override was set or cleared, or `JWT_SECRET` was changed/removed. See the `troubleshoot-running-instance` skill. |
| `docker version` shows only a Client section | The daemon isn't running. Start Docker Desktop, or `colima start`. |
| Connection refused on port 27017 | Mongo isn't up: `docker compose -f deploy/docker-compose.yml up -d mongo`. |
| App works, but the data is in a database called `test` | A Mongo connection property was written with the pre-Boot-4 `spring.data.mongodb.` prefix, so it was ignored and the default `mongodb://localhost/test` applied. Use `spring.mongodb.uri`; `mvn test` catches this. |
