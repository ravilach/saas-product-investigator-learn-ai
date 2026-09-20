# Getting Started

This is the **incremental** guide: five checkpoints, each small enough to verify on its own, each with an explicit
"you'll know it worked when." [`SETUP.md`](SETUP.md) is the reference (every env var, every flag) — this page is its
companion, the one that tells you whether what you just built actually runs.

The point of checkpoints rather than one big definition of done: you find out that layer *n* works before you build
layer *n+1* on top of it. Re-running a checkpoint costs seconds. Discovering at the end that the container and the
dev loop disagree costs an afternoon.

---

## Checkpoint 1 — Tooling

Before writing or running anything, confirm the four tools exist:

```sh
java -version     # 25 or newer (the build targets 25; a newer JDK is fine)
mvn -version
node --version    # 20 or newer. Note the two dashes - `node -version` is a Node syntax error,
                  #                not a "not installed" answer
docker version    # both Client and Server sections must print
```

**You'll know it worked when:** all four commands print a version instead of `command not found`, and `docker
version` shows a **Server** section, not just a client (a client-only response means the daemon isn't running —
start Docker Desktop, or `colima start` if you use Colima).

> **On the JDK:** the build sets `maven.compiler.release=25`, so it compiles against the Java 25 API surface even on
> a newer JDK. That is what keeps the jar runnable on the `eclipse-temurin:25-jre` base image the container uses.
> Building on 26 is supported and expected; building on 24 or older is not.

---

## Checkpoint 2 — Backend boots on its own

Start a MongoDB, then the backend. Nothing else. No frontend, no LLM key, no Docker image.

```sh
# One-time: a config file holding the one secret that must stay stable across runs
cp deploy/.env.example deploy/.env
printf 'CREDENTIAL_ENCRYPTION_KEY=%s\n' "$(head -c 32 /dev/urandom | base64)" >> deploy/.env

# A database, in its own container
docker compose -f deploy/docker-compose.yml up -d mongo

# Then the app
set -a; . deploy/.env; set +a
cd backend && mvn spring-boot:run
```

`deploy/.env` is gitignored, and the file it's copied from documents every variable it can hold. The reason to put
`CREDENTIAL_ENCRYPTION_KEY` in a file rather than generating it inline each time: regenerate it and everything
already encrypted under the old one (LLM keys, MCP tokens, the persisted JWT secret) becomes unreadable — silently,
since undecryptable values are treated as unset. The same knowledge lives in the `local-dev-loop` skill for Claude's
benefit; if you change one, change both.

**You'll know it worked when:**

```sh
curl localhost:8080/actuator/health
# {"groups":["liveness","readiness"],"status":"UP"}
```

Nothing else matters yet. Don't move on until this is boring.

Six more things are worth confirming here, because they're the foundation everything later sits on:

```sh
# 1. Unauthenticated calls are rejected - and rejected in this app's JSON error shape,
#    not with an HTML error page or a stack trace.
curl -i localhost:8080/api/users
# HTTP/1.1 401 ... {"error":"UNAUTHORIZED","message":"Authentication required. Send a valid ...

# 2. The seeded admin can log in, and the response carries no password hash.
curl -s -X POST localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin"}'
# {"token":"eyJ...","tokenType":"Bearer","expiresAt":"...","user":{...}}

# 3. A wrong password is a clean 401, not a 500 and not a hint about which half was wrong.
curl -s -X POST localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"wrong"}'
# {"error":"INVALID_CREDENTIALS","message":"Invalid username or password.", ...}

# 4. Both of those logins were recorded. Grab a token from check 2 and read the trail back:
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
curl -s localhost:8080/api/audit-logs -H "Authorization: Bearer $TOKEN"
# {"content":[{"action":"AUTH_LOGIN_SUCCESS",...},{"action":"AUTH_LOGIN_FAILURE",...}],
#  "page":0,"size":50,"totalElements":3,"totalPages":1,"first":true,"last":true}

# 5. A stored API key comes back as a tail and never as a key.
curl -s -X POST localhost:8080/api/users/me/credentials -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"provider":"ANTHROPIC","apiKey":"sk-ant-not-a-real-key-wxyz"}'
# {"provider":"ANTHROPIC","configured":true,"last4":"wxyz"}
curl -s localhost:8080/api/users/me/credentials -H "Authorization: Bearer $TOKEN"
# [{"provider":"ANTHROPIC","configured":true,"last4":"wxyz"}]

# 6. The JWT signing secret reports where it came from, and never what it is.
curl -s localhost:8080/api/admin/jwt-secret -H "Authorization: Bearer $TOKEN"
# {"configured":true,"source":"AUTO_GENERATED"}   # or ENV_VAR if you set JWT_SECRET
```

An `AUTH_LOGIN_FAILURE` entry carries `details.reason` but never the password that was tried — that's the rule the
audit log is built on, not a detail of this one action. Check 5 leaves an `LLM_CREDENTIAL_ADDED` entry whose
`details` is `{"provider":"ANTHROPIC"}` and nothing more — not the key, not its tail, not its length.

Check 5 works when logged in as *any* user, including a READ_ONLY one: bringing your own key is not an
administrative act. Check 6 is admin-only, along with everything else under `/api/admin/`. There is deliberately no
`PUT` here — [setting the JWT override signs everyone out](SETUP.md#rotating-the-jwt-signing-secret), including the
session you are holding, which is a poor thing to discover in the middle of a setup guide.

The startup log should also contain `MongoDB indexes verified.` and a loud multi-line
`DEFAULT ADMIN CREDENTIAL CREATED` warning on the very first boot against an empty database. Both are intentional —
see [`SETUP.md`](SETUP.md#-the-default-admin-credential).

---

## Checkpoint 3 — Full stack talks to itself

```sh
cd frontend && npm install && npm run dev
```

Open the Vite dev server (`http://localhost:5173` by default) and log in as `admin` / `admin`. The backend from
checkpoint 2 must still be running; the dev server proxies API calls to it, and `CORS_ALLOWED_ORIGINS` already
defaults to the Vite origin.

**You'll know it worked when:** you land on an empty Dashboard. No products yet, and that's correct — an empty state,
not an error.

Two things worth checking while you're here, because they are easy to get wrong and annoying to discover later:
paste a URL like `http://localhost:5173/account` while signed out and confirm you are sent to the login page and
then returned to `/account` after signing in, and narrow the window below 1024px to confirm the sidebar becomes a
drawer behind a hamburger rather than a squeezed rail.

---

## Checkpoint 4 — The MVP loop: one product, one source, one run

1. Add an Anthropic key — Admin Console → Secrets, or `export ANTHROPIC_API_KEY=...` before starting the backend.
2. Create a SaaS Product with a **single Website source** pointing at a changelog or docs page.
3. Click **Run** and watch the live execution view.

**You'll know it worked when:** a change report with a real `overallSummary` appears in History.

This is the smallest version of the actual idea. Everything else in the app — Compare, the other four source types,
PDF/DOCX export, the Admin Console, the second LLM provider — builds on this exact loop. Make it rock solid before
adding anything on top of it.

The first run has nothing to compare against, so expect a report that describes the current state rather than a
list of changes. Run it a second time to get a real comparison.

---

## Checkpoint 5 — Tests, then the container matches the dev loop

```sh
cd backend && mvn test          # then open target/site/jacoco/index.html
cd ../frontend && npm test -- --run
```

Both green. Some backend tests start a MongoDB container, so Docker must be reachable — if you see
`Could not find a valid Docker environment`, see
[Testcontainers setup in SETUP.md](SETUP.md#if-testcontainers-cant-find-docker) (two env vars, once).

Then the fully bare quick-boot:

```sh
docker build -t saas-investigator .
docker run -p 8080:8080 saas-investigator
```

No env vars at all: the container starts its own MongoDB, generates its own encryption key, auto-generates a JWT
secret, and seeds `admin`/`admin`.

**You'll know it worked when:** the container gives you the same login and the same MVP loop as checkpoints 3–4,
just self-contained. If it diverges, that's a real bug worth chasing before building further — the container is
supposed to be the same app, not a different one.

---

## From here

Build the rest of the work incrementally, and re-run checkpoints 2–5 after each step. That's the actual point of
having checkpoints rather than one big definition of done: cheap and repeatable, rather than discovering something
broke only once everything else is already built on top of it.

## Current status of these checkpoints

The repo is being built incrementally, so not all five are reachable yet. A checkpoint doc that was only ever true
partway through the build isn't worth much, so this table says exactly what was run and when — not what ought to
work.

| Checkpoint | Status |
|---|---|
| 1 — Tooling | ✅ **re-verified 2026-09-20**, on JDK 26, Maven 3.9.16, Node 22, Docker 29 (client and server). The `node --version` fix above came out of that walk — the command previously printed here was a Node syntax error. |
| 2 — Backend boots on its own | ✅ **re-verified 2026-09-20 end to end**, against a fresh `docker compose up -d mongo` volume, so the empty-database path was exercised for real: `MongoDB indexes verified.`, the `DEFAULT ADMIN CREDENTIAL CREATED` banner, health matching the output above, and all six checks passing — including `totalElements: 3` in check 4 and an `LLM_CREDENTIAL_ADDED` entry whose `details` is exactly `{"provider":"ANTHROPIC"}` with the key tail appearing nowhere in it. |
| 3 — Full stack | ✅ verified earlier in the build — login, route guards, theme, and the responsive shell at desktop, tablet and phone widths. **Not re-walked since**; it needs a browser, and the screens it would exercise are still step 9. |
| 4 — MVP loop | ⏳ **not reachable yet.** The backend half is complete and tested end to end, but driving it from the browser needs the product and run screens (step 9). |
| 5 — Tests + container | 🟡 **test half re-verified 2026-09-20**: `mvn test` green at **758 tests**, 0 failures, 0 errors, 0 skipped, with a JaCoCo report over 170 classes; `npm test -- --run` green at **37 tests** in 6 files. **Container half not reachable** — the image is step 11. |

The first `mvn test` of that walk failed 7 Testcontainers-backed tests with `Could not find a valid Docker
environment` on a machine where Docker was plainly running — the two env vars in
[SETUP.md](SETUP.md#if-testcontainers-cant-find-docker) fixed it, and those instructions are now confirmed correct
for Rancher Desktop. That is the documented failure behaving as designed: those tests fail rather than skip, because
a silently skipped test reporting as a pass is worse than a red build.
