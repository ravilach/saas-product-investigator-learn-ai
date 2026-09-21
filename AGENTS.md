# AGENTS.md

Baseline context for coding agents working in this repo. Deep, task-specific procedures live in
[`.claude/skills/`](.claude/skills/) — this file is only the part that applies no matter what you were asked to do.

## What this is

A SaaS change-investigator: point it at a product's docs, changelog, Jira/Atlassian instance or an MCP server, and an
LLM reports what changed. Spring Boot backend, React frontend, MongoDB, per-user BYOK LLM keys, role-based access.

The spec of record is [`docs/saas-product-investigator-BUILD-PROMPT.md`](docs/saas-product-investigator-BUILD-PROMPT.md)
— a 13-step BUILD ORDER plus a Definition of Done. When behaviour is ambiguous, that file decides, not this one.

## Layout

| Path | What's in it |
|---|---|
| [`backend/`](backend/) | Spring Boot 4 / Java 25. One package per feature under `src/main/java/com/saasinvestigator/` (`crawl`, `llm`, `run`, `credential`, `security`, …) |
| [`frontend/`](frontend/) | React 19 + TypeScript + Vite. Feature folders under `src/` (`auth`, `pages`, `components`, `api`, `theme`) |
| [`tools/`](tools/) | Executable verification harnesses — `verify-step10.mjs` (40 checks over the API), `verify-checkpoint3.mjs` and `verify-checkpoint4.mjs` (11 each, in a real browser via Playwright; `npm install` in `tools/` first) — plus [`tools/mock-llm/`](tools/mock-llm/), an Anthropic-shaped stub and a fake product site, so the pipeline can be driven end to end without a real model or network |
| [`deploy/`](deploy/) | docker-compose, k8s and ECS Fargate samples |
| [`docs/`](docs/) | Reference docs and [`docs/decisions/`](docs/decisions/) ADRs |
| [`evals/`](evals/) | One log file per work session — see below |

## Commands

```sh
# Frontend — from frontend/
npm run test:run     # tests, one shot
npm test             # DON'T: bare vitest, watch mode, never exits
npm run lint         # tsc -b --noEmit
npm run build        # tsc -b && vite build

# Backend — from backend/
mvn test             # JUnit + JaCoCo
mvn spring-boot:run  # needs MongoDB up and deploy/.env sourced
```

Two things that bite:

- **`npm test` is watch mode.** It will hang until killed. Always `npm run test:run` in a non-interactive context.
- **There is no Maven wrapper.** `mvn` comes from the system, and the pom targets Java 25. A newer default JDK
  compiles fine via `--release`, but a JDK *older* than 25 fails outright.

To get a local instance running, use the `local-dev-loop` skill rather than assembling the steps yourself — the
`CREDENTIAL_ENCRYPTION_KEY` handling in it is easy to get silently wrong.

## Conventions

- **Tests are not optional.** Backend tests mirror the main package tree under `src/test/java/`; frontend tests sit
  beside the file they cover (`Badges.tsx` → `Badges.test.tsx`). The `write-tests-for-new-code` skill has the real
  rules about what to mock and what runs against a live MongoDB.
- **Docs ship with the change.** An endpoint, env var, source type, role or deployment sample that changes makes some
  doc stale. The `update-docs` skill works out which.
- **Non-obvious decisions get an ADR** in [`docs/decisions/`](docs/decisions/), numbered, short.
- **Every controller is mapped under `/api/**`, and this is a security boundary, not a style rule.** The jar serves
  the built frontend from the same port, so any `GET` outside `api/`, `actuator/`, `v3/api-docs` and `swagger-ui` is
  treated as a client-side route and **permitted without authentication** — `@PreAuthorize` won't save you, because
  the request never reaches the method. See `add-a-new-endpoint` and
  [ADR 0009](docs/decisions/0009-spa-fallback-and-one-frontend-predicate.md).
- **Verify against the packaged image, not just the dev server**, for anything touching security config, static
  resources, the filter chain or streaming. Vite serves the SPA fallback and terminates proxied streams itself, and
  each of those conveniences hid a real bug here until a container was walked.
- **Don't put test counts or "all green" claims in docs** unless you just ran the suite and are willing to keep the
  number current. Several already drifted.

## Session logging

Per [`docs/evals-tracking-PROMPT.md`](docs/evals-tracking-PROMPT.md), each continuous stretch of work gets its own
`evals/session-<YYYY-MM-DD-HHmm>.md` — starting commit, a timestamped log written *in the moment*, and a close-out with
the ending commit and what carried over. Human corrections go in the human's own words, not paraphrased into sounding
more resolved than they were. A new invocation is a new file, not an append. This is the repo's record of where agents
needed steering, so it's only worth anything if it's honest.

## Skills

Describe the task in plain language and the matching skill applies automatically. Check for one before improvising:

`add-a-new-endpoint` · `add-source-connector` · `add-llm-provider` · `add-deployment-target` ·
`write-tests-for-new-code` · `enable-coverage-gate` · `manage-roles-and-permissions` · `rotate-secrets` ·
`local-dev-loop` · `troubleshoot-running-instance` · `update-docs`

If you're about to do something repeatable that no skill covers, that's a sign to add one.
