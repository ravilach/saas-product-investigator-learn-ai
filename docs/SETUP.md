# Setup

The reference page: every prerequisite, every environment variable, every way to run the app and its tests. If you
want the guided, verify-as-you-go path instead, use [`GETTING_STARTED.md`](GETTING_STARTED.md). For running this
somewhere other than a laptop — Kubernetes, ECS, what to scrape, what to alert on — see
[`DEPLOYMENT.md`](DEPLOYMENT.md).

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
| `ANTHROPIC_BASE_URL` | no | none (the SDK's own endpoint) | Points the Anthropic client somewhere else — a gateway, a proxy, or a stub. See [pointing a provider somewhere else](#pointing-a-provider-somewhere-else). |
| `OPENAI_API_KEY` | no | none | Same story as the Anthropic key. |
| `OPENAI_MODEL` | no | `gpt-6-astra` | |
| `OPENAI_BASE_URL` | no | none (the SDK's own endpoint) | Same as `ANTHROPIC_BASE_URL`, for the OpenAI client. |
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
| `SERVER_PORT` | no | `8080` | The one port that serves both the UI and the API. To reach the app on port 80, prefer publishing it — `-p 80:8080` — rather than setting this. See [ports](#ports-and-why-there-is-no-reverse-proxy). |
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

### Pointing a provider somewhere else

`ANTHROPIC_BASE_URL` and `OPENAI_BASE_URL` (`app.llm.anthropic.base-url`, `app.llm.openai.base-url`) replace the
endpoint the provider's SDK would use by default. Unset — the normal case — the SDK's own default applies; nothing
about the request path changes. Three reasons to set one:

- **A gateway or proxy** in front of the provider, for egress control, logging or spend caps.
- **An API-compatible service** you host yourself.
- **A stub, so the pipeline can be tested without a model.** This is what the repo uses — see below.

Both are validated at startup: a value without a scheme and host (`gateway:8080`) fails the boot with a message
naming the property, rather than failing inside an SDK call minutes into someone's first run. When one is set, the
startup log says so explicitly — an instance quietly talking to something other than the provider's API is
something an operator should be able to confirm from the logs rather than by reading env vars.

The key still has to be present. A base URL is not a way to run without credentials; the stub below accepts any key,
and the containers that use it are given the literal string `stub-key-not-a-real-credential`.

### Testing the pipeline without a model

[`tools/mock-llm/`](../tools/mock-llm/) holds two fixtures, and
[`tools/verify-step10.mjs`](../tools/verify-step10.mjs) drives the whole application against them. No dependencies
beyond Node, no provider account, no spend.

| Fixture | What it is |
|---|---|
| `anthropic-stub.mjs` | An Anthropic-shaped endpoint. Streams invented but well-formed reports, honours the structured-output schema, and emits MCP tool-use blocks when the request carries `mcp_servers`. It reads the requested depth out of the prompt's own instruction text and returns a correspondingly larger report, which is what makes "do the three depths actually differ" a real check. Logs every request it receives to `MOCK_LLM_LOG`. |
| `fake-product-site.mjs` | A crawl target with two revisions of `/changelog` and `/pricing`. `POST /_advance` switches revisions, so a second run has something genuine to find. Its `robots.txt` disallows `/internal/`, and the disallowed page is marked, so "did the crawler obey robots" is answerable rather than assumed. |

`verify-step10.mjs` turns every clause of BUILD ORDER step 10 into one numbered assertion and exits non-zero on any
failure — its header comment has the exact commands, including why the fixtures run as containers on a shared network
rather than on the host. Currently **40 of 40 checks pass**.

What a green run means: crawl, robots, prompt construction, the provider call, the structured-output parse,
persistence, SSE narration, PDF and DOCX rendering, Compare, RBAC, secret masking and auditing all work end to end.
What it does not mean: anything about analysis quality. With the stub there is no analysis. Judging that needs a real
model and a human reading the output.

### The browser harnesses

`verify-step10.mjs` speaks HTTP, which leaves out everything that only exists once a browser has rendered it — that a
colour token produces a readable contrast ratio, that a sidebar is off-canvas rather than merely narrow, that
streamed text arrives in pieces instead of in one lump. Two more harnesses cover that half:

| Harness | What it walks |
|---|---|
| [`verify-checkpoint3.mjs`](../tools/verify-checkpoint3.mjs) | Checkpoint 3 of [GETTING_STARTED.md](GETTING_STARTED.md): route guards and the bounce-and-return through login, the empty-state Dashboard, the theme toggle's effect on the *computed* background, the dark palette being navy rather than black, a measured WCAG contrast ratio, and the responsive shell at 390px including an overflow check. 11 checks. |
| [`verify-checkpoint4.mjs`](../tools/verify-checkpoint4.mjs) | Checkpoint 4: the whole MVP loop through the UI — create, run, watch the live view render the backend's own detail strings, read the report, advance the fixture site, run again, read the comparison's category badges, expand a History entry, and stream an ad-hoc answer. 11 checks. |

These need Playwright, which is why [`tools/`](../tools/) has a `package.json` and the mock-LLM fixtures do not:

```sh
cd tools && npm install && npx playwright install chromium
node verify-checkpoint3.mjs                                  # against the Vite dev server
CP3_BASE=http://127.0.0.1:8080 node verify-checkpoint3.mjs   # against a container
```

Each takes its target as an environment variable and defaults to `http://localhost:5173`, the dev server — because
that is where checkpoints 3 and 4 are written to be walked. Point `CP3_BASE`/`CP4_BASE` at a published container port
to walk the packaged artifact instead; nothing else changes. `verify-checkpoint4.mjs` takes the fixture site's address
**twice** — `CP4_SITE` is what gets typed into the source URL field, `CP4_SITE_LOCAL` is how this script reaches the
same site to advance its content between runs. Those differ exactly when the app is in a container and the harness is
not; its header comment has the container-mode invocation.

Both write a screenshot per step to `CP3_SHOTS`/`CP4_SHOTS` (`/tmp/cp3-shots` and `/tmp/cp4-shots` by default). That
is deliberate: a passing assertion about a contrast
ratio still benefits from an image a human can glance at, and a failing one is much easier to diagnose with the page
in front of you.

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

The dev server proxies `/api`, `/actuator`, `/swagger-ui` and `/v3/api-docs` to `localhost:8080`, so development
runs same-origin exactly like the packaged image does — no CORS preflights in the dev loop that would not exist in
production, and no second base URL to keep in sync. `CORS_ALLOWED_ORIGINS` still defaults to the Vite origin so
that pointing a browser straight at port 8080 also works.

If you do need the frontend to talk to a backend somewhere else, set `VITE_API_BASE_URL` (e.g.
`VITE_API_BASE_URL=https://investigator.internal npm run dev`); leaving it unset means "same origin", which is what
the container relies on. Note that `VITE_`-prefixed variables are compiled into the bundle and readable by anyone
who loads the page, so only ever put a URL there — never a key.

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

### If `docker build` fails fetching the MongoDB signing key

On a corporate network that intercepts TLS (Zscaler, Netskope, and most others), the build stops in the final stage
with a message like:

```
ERROR: could not fetch the MongoDB signing key from https://pgp.mongodb.com/server-8.0.asc
```

The build needs MongoDB's public signing key to install `mongodb-org-server` — the embedded database used only by the
single-container fallback — and it fetches that key rather than keeping a copy in this repository. An intercepting
proxy terminates the TLS connection and presents a certificate signed by a private root that the base image has no
reason to trust, so `curl` refuses it (`curl: (60) SSL certificate problem: unable to get local issuer certificate`).

Rebuild with the TLS check on that one download turned off:

```sh
docker build --build-arg MONGODB_GPG_INSECURE=1 -t saas-investigator .
```

**This is not the security hole it reads as.** The `Dockerfile` pins the key's fingerprint
(`4B0752C1BCA238C0B4EE14DC41DE058A4E7DCA05`) and compares what actually arrived against it, so a proxy that tampers
with the key fails the build rather than getting its key trusted — and `apt` then verifies every downloaded package
against that key. What the flag gives up is confidentiality of the request, not the integrity of what comes back.
The flag is deliberately not the default, so turning it off stays a decision someone makes rather than one the build
makes quietly.

Two related knobs, both rarely needed: `--build-arg MONGODB_GPG_URL=…` points the fetch at an internal mirror of the
key, and `--build-arg MONGODB_GPG_FINGERPRINT=…` is what you bump if MongoDB rotates the key or this image moves off
MongoDB 8.0.

### Ports, and why there is no reverse proxy

**The container listens on exactly one port, and it serves both the UI and the API.** The `Dockerfile` copies the
built frontend into `src/main/resources/static/`, which Spring Boot serves straight out of the jar, so
`http://host:8080/` is the UI and `http://host:8080/api/...` is the API on the same origin.

That is why there is **no NGINX, no Caddy, and no reverse proxy inside this image**, and why adding one would be a
step backwards rather than forwards:

- There is nothing to route. A proxy in front of a single upstream that already serves both halves is a second
  process to supervise and a second config to keep in sync for no routing decision.
- It would break run progress unless configured carefully. Run progress is Server-Sent Events, which is why
  `server.compression.enabled=false` and `spring.mvc.async.request-timeout=600000` are set in
  `application.properties`. NGINX buffers proxied responses by default, so without an explicit `proxy_buffering
  off;` and a raised `proxy_read_timeout`, progress events arrive in one burst at the end — or the stream is cut
  mid-run.
- Single-origin is load-bearing elsewhere. `CORS_ALLOWED_ORIGINS` only exists for the Vite dev server, and
  [`decisions/0008-jwt-in-localstorage.md`](decisions/0008-jwt-in-localstorage.md) assumes the API is same-origin.

TLS, a hostname, and who is allowed to reach the app *are* jobs for something in front — but for an Ingress, an ALB,
or a Gateway, outside the image, where it can be terminated once for every replica. See
[`DEPLOYMENT.md`](DEPLOYMENT.md).

#### Reaching it on port 80

Publish it there. The container port stays 8080; only the host side changes:

```sh
docker run -p 80:8080 saas-investigator      # then open http://localhost/
```

Both halves of `-p` matter and they are not interchangeable: the left number is the host port your browser connects
to, the right one is the port the app inside the container is actually listening on. `-p 80:80` alone publishes host
80 to container 80, where nothing is listening, and the browser gets a connection reset that looks like the app
failed to boot.

If you want the app itself to listen on 80 — so that `-p 80:80` is the correct command — set `SERVER_PORT` too, and
then publish that:

```sh
docker run -p 80:80 -e SERVER_PORT=80 saas-investigator
```

Two caveats before you reach for that, both of which are why `-p 80:8080` is the recommended form:

- **It is Docker-only.** The image runs as uid 1000 (`runAsNonRoot` in
  [`deploy/k8s/deployment.yaml`](../deploy/k8s/deployment.yaml) requires it). Docker sets
  `net.ipv4.ip_unprivileged_port_start=0` inside containers so a non-root process can bind 80 there; containerd
  under Kubernetes does not. `SERVER_PORT=80` therefore works on a laptop and crash-loops in a cluster with a
  permission error on bind.
- **`EXPOSE` and `HEALTHCHECK` do not follow it automatically.** `EXPOSE 8080` is only metadata and cannot read an
  env var set at `docker run` time. The `HEALTHCHECK` does read `${SERVER_PORT:-8080}`, so it keeps working — but
  anything else that hardcodes 8080 (the ECS task definition's `containerPort`, the k8s Service's `targetPort`,
  the Prometheus scrape config) has to be changed in the same breath.

### Container fallbacks, and where the line is

With no `MONGODB_URI`, the container's entrypoint starts a local `mongod` with its data in `/data/db`. With no
`CREDENTIAL_ENCRYPTION_KEY`, it generates one into `/data/credential-key`. Both fallbacks live under `/data` on
purpose: mount one volume there (`-v saas-data:/data`) and the database *and* the key persist together; mount
nothing and both are ephemeral together. There's never a mismatch where one outlives the other.

That fully self-contained mode is a real working instance, not a toy — but it is **single-container,
single-instance**. It cannot be scaled past one replica (each would boot its own disconnected database with its own
generated key). Set `MONGODB_URI` and `CREDENTIAL_ENCRYPTION_KEY` explicitly for anything else; the
docker-compose/Kubernetes/ECS samples under [`/deploy`](../deploy/) always do, and
[`DEPLOYMENT.md`](DEPLOYMENT.md) walks through each of them.

---

## Running the tests

```sh
cd backend && mvn test
open target/site/jacoco/index.html    # coverage report, written by the `test` phase
```

```sh
cd frontend && npm test -- --run      # or `npm run test:run`; bare `npm test` stays in watch mode
```

The frontend tests run in jsdom against the real `fetch` client — they stub `globalThis.fetch` rather than mocking
the API module, so a mistake in request shape, headers, or error translation fails a test instead of being mocked
away.

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
