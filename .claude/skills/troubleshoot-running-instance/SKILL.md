---
name: troubleshoot-running-instance
description: Diagnose a deployed or local instance that is up but misbehaving — vanished credentials, everyone logged out, runs failing, an empty database, a dead live view. Use when something is wrong at runtime rather than at build time.
---

# Troubleshoot a running instance

Most failures in this app are **silent by design of the underlying mechanism**: an unreadable secret is treated as
unset, a removed Spring property is ignored rather than rejected, a missing metrics config configures nothing. So
"it booted fine" is not evidence. Work through this in order.

## 1. Start here, always

```sh
curl localhost:8080/actuator/health            # {"groups":["liveness","readiness"],"status":"UP"}
curl localhost:8080/actuator/health/readiness  # includes MongoDB
curl localhost:8080/actuator/health/liveness   # JVM only
```

If readiness is DOWN and liveness is UP, it's the database. Then, as an admin, `GET /api/admin/health` for the
component-by-component view — the public endpoint deliberately shows no details.

## 2. Read the startup log for these three lines

| Line | Means |
|---|---|
| `WARNING: no MONGODB_URI set - started an embedded local MongoDB` | This instance is running a private throwaway database. **This is the cause of most "where did our data go" reports.** |
| `WARNING: no CREDENTIAL_ENCRYPTION_KEY set - generated one` | Everything encrypted since this boot dies with the container. |
| `DEFAULT ADMIN CREDENTIAL CREATED` (multi-line banner) | The `users` collection was empty. Expected exactly once on a new database; at any other time you are connected to the wrong database. |

## 3. Match the symptom

### "Our API keys disappeared" / a stored key reads as not configured

`CREDENTIAL_ENCRYPTION_KEY` changed or was never set. Undecryptable values are treated as unset, so there is no error
anywhere. Confirm the key in the environment matches what the data was encrypted under. If it's genuinely lost, the
credentials must be re-entered — there is no recovery. See `rotate-secrets`.

### "Everyone got logged out"

One of: `JWT_SECRET` was set or changed; `CREDENTIAL_ENCRYPTION_KEY` changed, making the *persisted* JWT secret
unreadable so a new one was generated; or the container was recreated without a `/data` volume in the
single-container mode. Check `GET /api/admin/jwt-secret` — it reports `source` (`AUTO_GENERATED` / `ENV_VAR`) and
never the value.

### The live run view shows nothing, or 404s

In order of likelihood:

1. **More than one replica.** The event stream is in-memory and only readable from the instance running the run. Check
   the replica/task count first — this is the single most common cause.
2. **A proxy timeout or buffering.** Progress arriving in one burst at the end means buffering; the stream dying at
   60 seconds means a read timeout. Needs ≥600s and buffering off at every hop. See `docs/DEPLOYMENT.md`.
3. **The session expired.** Finished runs stay replayable for 15 minutes, then the stream 404s with a message
   pointing at history. The report is still there — narration is not durable, the report is.

### Runs finish `partial`

Expected behaviour, not a bug: at least one source couldn't be read and the rest of the run completed. Find which:

```promql
sum by (sourceType) (rate(saas_source_fetch_errors_total[1h]))
```

`WEBSITE` failures point at the network, robots.txt, or the target site; `MCP_SERVER` failures point at a credential
or an unreachable server. The report's source list marks which were included.

### Runs rejected with 503

`app.run.queue-capacity` (default 12) is full. Deliberate: past the queue, a fast "try again" beats a runId for a run
that won't start for half an hour, because the resource being protected is someone else's server and a metered API.
Raise `RUN_CONCURRENCY` / `RUN_QUEUE_CAPACITY` only if the LLM spend and the crawl politeness budget can take it.

### Asks are slow while runs are in flight

Shouldn't happen — separate pools (`app.run.ask-concurrency`). If it does, something is running on the wrong
executor; check `RunExecutor` and `AskService`.

### Everything works but the data is in the wrong database

Check the property name is `spring.mongodb.uri`, **not** `spring.data.mongodb.uri`. Boot 4 removed the latter at
deprecation level `error`: it is not bound and not warned about, and the connection silently falls back to
`mongodb://localhost/test`. `ApplicationPropertiesBindingTest` guards this.

### No `saas_*` metrics on the scrape

Micrometer registers a meter on first use, so a freshly started instance publishes none of the four. Do a run, scrape
again. If `saas_run_duration_seconds_bucket` is specifically missing while `_count`/`_sum` are present, the histogram
config isn't applying — the distribution property must be keyed on the *meter* name (`saas.run.duration`), not the
published series name.

### CrashLoopBackOff / a task being replaced repeatedly

Check what the restart-triggering probe points at. If it's `/actuator/health` or `/actuator/health/readiness`, MongoDB
is included, so a database blip restarts the process — which fixes nothing and kills in-flight runs. Restart checks
belong on `/actuator/health/liveness`.

## 4. Useful commands

```sh
kubectl logs -f deploy/saas-investigator            # k8s
kubectl describe pod -l app=saas-investigator       # events: pull failures, probe failures, OOMKilled
aws logs tail /ecs/saas-investigator --follow       # ECS
docker compose -f deploy/docker-compose.yml logs -f app
```

An ECS task that stops with no application logs at all is an execution-role or log-group problem — it failed before
the container started. Read the stopped-task reason.

## 5. Escalate to the audit log

`GET /api/audit-logs` (admin) records who did what and when. It never contains a secret, a password, or a key tail —
by rule, not by omission — so it answers "who changed this" and never "what was it changed to."
