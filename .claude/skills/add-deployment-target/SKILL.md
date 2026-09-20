---
name: add-deployment-target
description: Add a deployment sample for a new platform (Azure Container Apps, Cloud Run, Nomad, a Helm chart). Use when asked to deploy this app somewhere the k8s and ECS samples don't cover.
---

# Add a deployment target

Existing samples: `deploy/k8s/` (4 manifests), `deploy/ecs/` (task definition + service), `deploy/docker-compose.yml`.
A new target is a sibling directory, not a rewrite of an existing one.

## The four constraints every target must satisfy

These come from the application, not from any platform. Get them wrong and the deployment looks healthy and behaves
strangely.

1. **One replica, or session affinity.** A run's live progress is SSE from an in-memory `RunSession` — only the
   instance that started the run can serve its event stream. Round-robin across two instances 404s roughly half of
   all live views. Either pin to one instance or configure affinity for `/api/saas-products/*/runs/**` at the layer
   that actually chooses the backend.
2. **`MONGODB_URI` and `CREDENTIAL_ENCRYPTION_KEY` must both be set explicitly.** Unset, the container starts a
   private embedded MongoDB and generates its own key — so each instance is a separate disconnected app, and on any
   platform with ephemeral storage the whole database vanishes on every deploy. Silently. The entrypoint's `WARNING`
   lines are the only signal.
3. **A 10-minute streaming response must survive.** No response buffering, and an idle/read timeout of at least 600s
   on every proxy in the path. A 60s default cuts NUCLEAR-depth runs off mid-narration with nothing logged.
4. **Two health endpoints, used differently.** `/actuator/health/readiness` includes MongoDB;
   `/actuator/health/liveness` is JVM-only. Point restart-triggering checks at **liveness** and traffic-gating checks
   at **readiness**. Using the Mongo-aware aggregate for restarts turns a database blip into a restart loop.

## Checklist

1. **`deploy/<target>/`** with the platform's manifests. Match the existing samples' conventions:
   - Every value that must change is a literal `YOUR_...` or `CHANGEME` placeholder — never a plausible-looking
     default someone might ship.
   - Comments explain *why*, not what. If the format has no comments (JSON), use `_comment*` keys as
     `deploy/ecs/task-definition.json` does, and say that the API ignores them.
   - Non-secret config and secrets are separate objects, even if the platform allows one blob.
2. **Reference the same image** the Dockerfile produces and the same registry repository the Harness pipelines push
   to. Don't introduce a second build.
3. **Decide about the database and say so.** The existing samples deliberately ship no MongoDB — running a database
   is a decision with an owner (backups, storage, upgrades), and a sample without those looks production-shaped and
   isn't. Either follow that, or justify the exception in a comment.
4. **Resource floor.** ~250m CPU / 768Mi memory is where the k8s sample starts; the JVM plus a crawl plus a streamed
   completion needs headroom. Don't copy a default 256Mi from a tutorial.
5. **Harness** — if this target should be deployed by pipeline, add `harness/build-and-deploy-<target>-pipeline.yaml`
   as a sibling rather than branching the k8s one. Reuse the build stage verbatim; only the deploy stage differs.
6. **`docs/DEPLOYMENT.md`** — a section matching the shape of the existing three: how to apply it, what it assumes
   already exists, a Mermaid topology diagram, and how Prometheus scrapes it on this platform.
7. **`README.md`** — the docs table mentions which targets are covered.

## Verify

Validate offline before claiming it works:

```sh
# Kubernetes-shaped manifests, no cluster needed
docker run --rm -v "$PWD":/work -w /work ghcr.io/yannh/kubeconform:latest -strict deploy/<target>/*.yaml

# YAML parses at all
ruby -ryaml -e 'ARGV.each { |f| YAML.load_stream(File.read(f)); puts "ok #{f}" }' deploy/<target>/*.yaml
```

Then a real deploy, and check all four constraints above rather than only that the pod came up:

- start a NUCLEAR run and watch the live view narrate for its full duration without the stream dropping;
- confirm no `WARNING: no MONGODB_URI` / `no CREDENTIAL_ENCRYPTION_KEY` line in the logs;
- confirm `/actuator/prometheus` is scrapeable but not reachable from outside the network boundary.
