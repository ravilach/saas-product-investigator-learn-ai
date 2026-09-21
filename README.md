# 🔍 SaaS Product Investigator

![Java](https://img.shields.io/badge/Java-25-2F9E8F?style=flat-square)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-2F9E8F?style=flat-square)
![React](https://img.shields.io/badge/React-TypeScript-4CAF7D?style=flat-square)
![MongoDB](https://img.shields.io/badge/MongoDB-4CAF7D?style=flat-square)

Point it at a SaaS product's docs, changelog, Jira/Atlassian instance, or any MCP server, and ask what changed — an
LLM does the comparing, on whatever schedule and at whatever depth you ask for. Multi-user, multi-provider
(Anthropic or OpenAI), and self-contained enough to boot with one command.

> **Build status — almost done.** This repo is being built incrementally against
> [`docs/saas-product-investigator-BUILD-PROMPT.md`](docs/saas-product-investigator-BUILD-PROMPT.md); 10 of its 13
> BUILD ORDER steps are fully complete and every spec artifact but one is written. In place and tested: the whole
> backend — auth/user/audit foundation, web crawler,
> secrets layer (per-user BYOK LLM keys, system-wide keys with a documented resolution order, JWT signing-secret
> rotation, encryption at rest for all of them), LLM orchestration over SSE, and the REST API with role enforcement
> — plus the whole frontend, shell and screens: design tokens and the light/dark toggle, TanStack Query,
> route-level code-splitting, the sidebar-to-drawer responsive layout, login with route guards, the shared
> error-boundary/toast layer, and the Dashboard, product form, product detail (run/compare/history/ask), Account
> Settings and six-tab Admin Console. Also in place: the deployment samples for docker-compose, Kubernetes and ECS
> Fargate, the two Harness pipelines that build and roll them out, the docs below, and the eleven Claude skills.
> Both suites are green as of 2026-09-20 — **758 backend tests** (`mvn test`, JaCoCo over 170 classes) and
> **80 frontend tests** in 9 files (`npm test`), 0 failures on either.
>
> **What's left is two things, both verification-and-packaging rather than features:** the full end-to-end
> acceptance pass over the finished UI (step 10 — a real run at all three depths, PDF/DOCX export, custom-range
> compare, the JWT-override sign-out, Data Explorer masked-field rejection), and the container image itself
> (step 11 — the multi-stage `Dockerfile` and `entrypoint.sh` are the only spec artifacts not yet written). That
> second one is why the quick start below doesn't work yet, and why checkpoints 4 and 5 in
> [`docs/GETTING_STARTED.md`](docs/GETTING_STARTED.md) aren't marked verified. Until the image exists, use the
> local dev loop in [`docs/GETTING_STARTED.md`](docs/GETTING_STARTED.md).

## Quick start (MVP)

```sh
docker build -t saas-investigator .
docker run -p 80:8080 saas-investigator
```

Open `http://localhost/`, log in as `admin` / `admin`, and add an Anthropic key from the Secrets tab in the
Admin Console. That's a complete, working instance — embedded MongoDB and a generated encryption key included, no
other setup required. Rotate the default admin password before this is anything but a local demo.

One port serves both the UI and the API: the built frontend ships inside the jar as static resources, so there is no
reverse proxy in this image and nothing to configure to make the two halves talk. The container always listens on
**8080**; `-p 80:8080` just publishes it where a browser will find it without a port in the URL. Prefer
`-p 8080:8080` if 80 is already taken on your machine, or if you're following the `curl` examples in
[`docs/API.md`](docs/API.md) and [`docs/GETTING_STARTED.md`](docs/GETTING_STARTED.md), which all use 8080.

_(The image is produced in BUILD ORDER step 11; until then use the local dev loop below.)_

Building or modifying this locally instead? Start with [`docs/GETTING_STARTED.md`](docs/GETTING_STARTED.md) — the
same idea, broken into small, verifiable steps.

## Starting with Claude

This whole codebase was built in Claude Code from **two prompts**, both kept in the repo so the inputs are auditable
next to the output:

| # | Prompt | What it asked for |
|---|---|---|
| 1 | [`docs/saas-product-investigator-BUILD-PROMPT.md`](docs/saas-product-investigator-BUILD-PROMPT.md) | The build spec — goal, stack, roles, LLM provider resolution, observability, packaging, a 13-step BUILD ORDER, and a Definition of Done to check the result against |
| 2 | [`docs/evals-tracking-PROMPT.md`](docs/evals-tracking-PROMPT.md) | A tracking habit layered on top: log each work session to [`evals/`](evals/) — real timestamps, human interventions in the human's own words, what was carried over |

The intended workflow is: paste prompt 1 as the first message, prompt 2 as the second, then `Go and build it!`. What
actually happened is in [`evals/`](evals/) — prompt 1 and `Go and build it!` went in as planned, but prompt 2 wasn't
pasted until five hours into the build, which is why the first session had to be reconstructed after the fact instead
of logged live. Paste them both up front.

Two things worth knowing if you want to reproduce this:

- **Prompt 1 does the heavy lifting, and its length is the point.** The BUILD ORDER matters more than any other part
  of it — it forces the error handler, audit service and test setup to exist in step 1 or 2, so every later step
  builds on them instead of retrofitting them. The Definition of Done matters second: it's what makes "is it
  finished?" a question with an answer.
- **Prompt 2 is what keeps the first one honest.** Without it there's no record of what a human actually had to step
  in and correct — and that's the interesting data, not the line count. [`evals/`](evals/) has the log for this
  build, including where Claude pushed back on a premise and where it was the one that was wrong.

Already have the repo and want Claude to *change* it rather than rebuild it? That's the next section.

## Documentation

| Doc | What it's for |
|---|---|
| [`docs/GETTING_STARTED.md`](docs/GETTING_STARTED.md) | Incremental local build/test/run checkpoints — start here if you're developing, not just running it |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | How the pieces fit together, with diagrams, and *why* the non-obvious choices were made |
| [`docs/SETUP.md`](docs/SETUP.md) | Full environment variable reference, running locally with and without Docker, running the tests |
| [`docs/API.md`](docs/API.md) | REST endpoint reference (also live at `/swagger-ui.html`) |
| [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md) | Docker, docker-compose, Kubernetes, ECS, metrics and scraping, and the Harness CI/CD pipeline samples |
| [`docs/decisions/`](docs/decisions/) | Short ADRs for architecture decisions made along the way |

## Using Claude against this repo

This repo ships `.claude/skills/` — checklists Claude Code picks up automatically in this project, no special
syntax needed. Just describe what you're doing in plain language and the right one applies:

| You'd say to Claude... | It reaches for... |
|---|---|
| "Add a source type that pulls from a Notion page" | `add-source-connector` |
| "Add an endpoint for admins to bulk-delete old audit logs" | `add-a-new-endpoint` |
| "I need to deploy this to Azure Container Apps too" | `add-deployment-target` |
| "Write tests for the crawler" | `write-tests-for-new-code` |
| "It's time to turn on a real coverage gate" | `enable-coverage-gate` |
| "We should rotate our Anthropic key" | `rotate-secrets` |
| "Everyone got logged out and I don't know why" | `troubleshoot-running-instance` |

Eleven of them, in [`.claude/skills/`](.claude/skills/) — one flat folder each, all four others being
`manage-roles-and-permissions`, `add-llm-provider`, `local-dev-loop` and `update-docs`. If Claude's about to do
something that isn't covered by an existing skill but probably should be, that's usually a sign to add one — see the
`update-docs` skill.

## Tech stack

React + TypeScript + Vite · Spring Boot 4 + Java 25 · MongoDB · JWT auth · Anthropic + OpenAI (pluggable) ·
Prometheus/Micrometer · JUnit + Vitest + JaCoCo
