---
name: local-dev-loop
description: Start MongoDB, the backend, and the Vite dev server for local development, and know which of the three to restart after a change. Use when beginning work on this repo or when something local has stopped working.
---

# Local dev loop

Three processes. Start them in this order.

```sh
# 1. One-time: the one secret that must stay stable across runs
cp deploy/.env.example deploy/.env
printf 'CREDENTIAL_ENCRYPTION_KEY=%s\n' "$(head -c 32 /dev/urandom | base64)" >> deploy/.env

# 2. MongoDB, in its own container
docker compose -f deploy/docker-compose.yml up -d mongo

# 3. Backend (new terminal)
set -a; . deploy/.env; set +a
cd backend && mvn spring-boot:run

# 4. Frontend (new terminal)
cd frontend && npm install && npm run dev
```

`http://localhost:5173`, log in `admin` / `admin`.

**Generate `CREDENTIAL_ENCRYPTION_KEY` once and keep it.** Regenerate it and everything already encrypted under the
old one — LLM keys, MCP tokens, the persisted JWT secret — becomes unreadable, *silently*, because undecryptable
values are treated as unset. The symptom is "my API key disappeared." Same knowledge is in
`docs/GETTING_STARTED.md` checkpoint 2; if you change one, change both.

`up -d mongo` starts only the database. It deliberately doesn't build the app image, so the loop works whether or not
the image exists.

## What to restart after a change

| Changed | Do |
|---|---|
| Frontend `.ts`/`.tsx`/CSS | Nothing — Vite hot-reloads |
| Backend Java | Restart `mvn spring-boot:run` |
| `application.properties` or anything in `deploy/.env` | Restart the backend; for `.env`, re-run `set -a; . deploy/.env; set +a` first — a stale exported value in the shell outlives the file edit |
| `deploy/docker-compose.yml` | `docker compose -f deploy/docker-compose.yml up -d` again |
| An admin setting in the UI | Nothing — those persist in `system_config` and take effect immediately |

## Health check before blaming your change

```sh
curl localhost:8080/actuator/health
# {"groups":["liveness","readiness"],"status":"UP"}
```

## Things that will bite you

- **Port 27017 already allocated** — another Mongo container from other work holds it. `docker ps`, then either
  reuse it (point `MONGODB_URI` at it) or `docker rm -f <name>`.
- **`Using generated security password` in the log** — should not appear; `UserDetailsServiceAutoConfiguration` is
  excluded. If it's back, that exclusion was dropped, and the password it prints is not a credential for anything.
- **Empty database on a boot you didn't expect** — the loud `DEFAULT ADMIN CREDENTIAL CREATED` banner means the
  `users` collection was empty. Expected once; at any other time you're pointed at the wrong database.
- **CORS errors in the browser console** — `CORS_ALLOWED_ORIGINS` defaults to the Vite origins. Serving the frontend
  from a different port means setting it.
- **Backend runs but every query returns nothing** — check `spring.mongodb.uri`, **not** `spring.data.mongodb.uri`.
  The old name is silently ignored in Boot 4 and the app quietly uses `mongodb://localhost/test`.

## Tests

```sh
cd backend && mvn test            # Testcontainers-backed tests need a reachable Docker daemon
cd frontend && npm test -- --run  # --run matters; bare `npm test` watches
```

`Could not find a valid Docker environment` on a machine where Docker plainly works means Testcontainers is looking
at `/var/run/docker.sock`, which only Docker Desktop provides. Two env vars fix it —
[SETUP.md](../../../docs/SETUP.md#if-testcontainers-cant-find-docker) has the values for Rancher and Colima.
