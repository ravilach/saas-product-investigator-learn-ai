# 🔍 SaaS Product Investigator

![Java](https://img.shields.io/badge/Java-25-2F9E8F?style=flat-square)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-2F9E8F?style=flat-square)
![React](https://img.shields.io/badge/React-TypeScript-4CAF7D?style=flat-square)
![MongoDB](https://img.shields.io/badge/MongoDB-4CAF7D?style=flat-square)

Point it at a SaaS product's docs, changelog, Jira/Atlassian instance, or any MCP server, and ask what changed — an
LLM does the comparing, on whatever schedule and at whatever depth you ask for. Multi-user, multi-provider
(Anthropic or OpenAI), and self-contained enough to boot with one command.

> **Build status:** this repo is being built incrementally against
> [`docs/saas-product-investigator-BUILD-PROMPT.md`](docs/saas-product-investigator-BUILD-PROMPT.md). In place and
> tested: the backend auth/user/audit foundation, the web crawler, and the secrets layer — per-user BYOK LLM keys,
> system-wide keys with a documented resolution order, JWT signing-secret rotation, and encryption at rest for all
> of them. LLM orchestration, the frontend, and the container image are still landing. Docs marked _(pending)_
> below arrive with the step that produces them.

## Quick start (MVP)

```sh
docker build -t saas-investigator .
docker run -p 8080:8080 saas-investigator
```

Open `http://localhost:8080`, log in as `admin` / `admin`, and add an Anthropic key from the Secrets tab in the
Admin Console. That's a complete, working instance — embedded MongoDB and a generated encryption key included, no
other setup required. Rotate the default admin password before this is anything but a local demo.

_(The image is produced in BUILD ORDER step 11; until then use the local dev loop below.)_

Building or modifying this locally instead? Start with [`docs/GETTING_STARTED.md`](docs/GETTING_STARTED.md) — the
same idea, broken into small, verifiable steps.

## Documentation

| Doc | What it's for |
|---|---|
| [`docs/GETTING_STARTED.md`](docs/GETTING_STARTED.md) | Incremental local build/test/run checkpoints — start here if you're developing, not just running it |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | How the pieces fit together, with diagrams, and *why* the non-obvious choices were made |
| [`docs/SETUP.md`](docs/SETUP.md) | Full environment variable reference, running locally with and without Docker, running the tests |
| `docs/API.md` _(pending — step 7)_ | REST endpoint reference (also live at `/swagger-ui.html`) |
| `docs/DEPLOYMENT.md` _(pending — step 12)_ | Docker, docker-compose, Kubernetes, ECS, and the Harness CI/CD pipeline samples |
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

Full list and categories (Architecture / Build / Deployment / Testing / Troubleshooting) in `.claude/skills/`. If
Claude's about to do something that isn't covered by an existing skill but probably should be, that's usually a
sign to add one — see the `update-docs` skill. _(The skills land in step 13.)_

## Tech stack

React + TypeScript + Vite · Spring Boot 4 + Java 25 · MongoDB · JWT auth · Anthropic + OpenAI (pluggable) ·
Prometheus/Micrometer · JUnit + Vitest + JaCoCo
