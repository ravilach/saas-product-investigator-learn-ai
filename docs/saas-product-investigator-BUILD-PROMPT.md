# SaaS Product Investigator — Build Prompt for Claude Code

Paste this whole file (or point Claude Code at it: "read and follow
saas-product-investigator-BUILD-PROMPT.md") as your first message in VS Code.

---

## ROLE

You are my coding partner in VS Code building my first full-stack app end to
end. Work efficiently: implement directly, don't narrate each step or
explain basics back to me in chat. The code itself should be thoroughly
documented instead — see CODE DOCUMENTATION — so "minimal chat narration"
does not mean minimal comments; it means don't explain in prose what the
Javadoc/comments already say in the code. Only stop and ask me if you hit a
genuine ambiguity that would change the architecture — otherwise make a
reasonable choice, note it in a one-line comment, and log it as a decision in
`/docs` (see DOCS section).

## GOAL

A multi-user tool for tracking multiple SaaS products. Each SaaS Product is a
self-contained unit: its own data sources (config) and its own history of
snapshots/reports (data). Running a product triggers an LLM (Anthropic or
OpenAI, per LLM PROVIDERS & CREDENTIALS) to pull fresh data from every
source, compare it against what was seen last time, and produce a
structured "what changed" report. There is NO hardcoded diffing logic — the
LLM decides what's meaningful based on current + prior content. The
backend's job is orchestration, access control, and observability — not
comparison logic.

## STACK

- Frontend: React + TypeScript + Vite + TanStack Query (data fetching/cache,
  see REACTIVITY & RESPONSIVE DESIGN)
- Backend: Spring Boot 4.0+ (Spring Framework 7), Java 25, Maven, Spring
  Security, Spring Boot Actuator, Micrometer + micrometer-registry-prometheus
- Auth: JWT (stateless), BCrypt password hashing
- DB: MongoDB (Spring Data MongoDB)
- LLM: pluggable multi-provider — Anthropic Messages API and OpenAI's
  Responses API, both server-side only, behind a common `LlmProvider`
  interface (see LLM PROVIDERS & CREDENTIALS). System-wide keys from env
  vars or the quick-boot host-credential mount, never exposed to the
  frontend.
- Report export: `openhtmltopdf` (PDF) + Apache POI `poi-ooxml` (DOCX) —
  see REPORT EXPORT
- Testing: JUnit 5 + Mockito + Testcontainers (backend), Vitest + React
  Testing Library (frontend), JaCoCo for coverage — see TESTING

Note on Java 25: this is the current LTS release (GA September 2025) —
a better long-term choice than Java 26 (which is non-LTS with a short
support window). Spring Boot 4.0+ officially supports it.

## CODE DOCUMENTATION

- Every public Java class and public method gets a Javadoc comment:
  purpose, `@param`, `@return`, `@throws` for checked/meaningful exceptions.
  A short `package-info.java` per package explaining what that package is
  responsible for.
- Add `springdoc-openapi-starter-webmvc-ui` so the REST API is documented
  live at `/swagger-ui.html` from annotations on the controllers
  (`@Operation`, `@ApiResponse`, etc.) — this is the standard Spring way to
  get interactive, always-current API docs, and it complements (doesn't
  replace) the hand-written `/docs/API.md` overview.
- Inline comments throughout wherever logic isn't self-evident: crawler
  traversal, provider resolution order, JSON parse-retry, credential
  encryption/decryption, audit redaction, host-credential precedence.
  Comments explain **why** a choice was made, not just restate what the
  next line does.
- Frontend (TypeScript/React): TSDoc comments on exported components,
  hooks, and utility functions (what props/args they take, what they
  return), plus inline comments for non-obvious UI logic (theme
  persistence, live-execution polling, etc.).

## TESTING

A real, wired-in-throughout suite, but **optional in the sense that it
never gates the quick-start experience** — see the callout at the end of
this section, which matters enough to build the Dockerfile around.

Tests are written alongside each BUILD ORDER step for the code that step
introduces, the same way Javadoc is (see CODE DOCUMENTATION) — not as a
separate pass at the end.

**Backend** — JUnit 5 (`spring-boot-starter-test` already pulls this in)
plus Mockito plus Testcontainers:
- **Unit tests** on the logic that's genuinely worth testing in isolation
  — the same list CODE DOCUMENTATION calls out for inline comments, not a
  coincidence: provider resolution order, the JSON parse-retry, credential
  encryption/decryption round-tripping, audit-log redaction (assert a
  password/API key/authToken never ends up in `details`), the crawler's
  same-origin/`robots.txt`/depth-limit logic, and the Compare endpoint's
  nearest-snapshot-before-a-date lookup.
- **Controller tests** via `@WebMvcTest`/MockMvc — role enforcement is
  the main thing worth asserting here: a READ_ONLY-authenticated request
  to an ADMIN-only endpoint actually gets `403`, not just "the happy path
  works."
- **Repository/integration tests** via Testcontainers' MongoDB module —
  a real ephemeral MongoDB per test run, not a mocked repository, for
  anything that exercises an actual query (the indexes from PERFORMANCE
  are only meaningfully tested against a real Mongo). This is the same
  philosophy as the embedded-Mongo quick-boot fallback in PACKAGING,
  applied to tests instead of local dev.
- **JaCoCo** (`jacoco-maven-plugin`) bound to the Maven `test` phase,
  producing an HTML report at `target/site/jacoco/index.html`. No hard
  coverage threshold that fails the build, at least to start — the point
  is visibility while the codebase is still being learned and extended,
  not a gate that fights the "quick start always works" goal below. A
  threshold can be added later once the suite has matured enough that
  failing it means something.

**Frontend** — Vitest (pairs naturally with the Vite setup already in
STACK) + React Testing Library for component tests: the Login form, the
add-source form's validation, the theme toggle, the depth selector,
the Data Explorer's masked-field rendering.

**The callout that matters:** `docker build` must never run this suite.
The Dockerfile's maven stage already uses `-DskipTests` (see PACKAGING) —
keep it that way. Tests are a real, separate gate you run on purpose
(`mvn test`, `npm test`), documented in `/docs/SETUP.md` alongside how to
open the JaCoCo report, but `docker build && docker run` has to produce a
working, bootable app regardless of the test suite's state — that's the
whole point of a teaching app where the tests themselves are still being
learned and written, possibly incompletely, possibly failing partway
through. A test failure should tell you something about your code; it
should never be the reason someone following the quick-start docs can't
get the app running at all.

## USERS & ROLES

Two roles: `ADMIN`, `READ_ONLY`.

- **ADMIN**: create/edit/delete SaaS Products and their sources;
  create/manage other users, including resetting any user's password (see
  note below); everything READ_ONLY can do.
- **READ_ONLY**: view SaaS Products and their sources (no edit); trigger a
  run; view report history; use the ad-hoc "ask" feature. Cannot touch
  source configuration or user management.

Each user has: `firstName`, `lastName`, `username`, `email`, `password`.
Login is by `username` + `password` (not email).

**Note on "admins can view the password":** with BCrypt (a one-way hash),
the original password cannot be recovered — that's the point of hashing it,
and storing it reversibly instead would be a real security liability even
for a learning project. The standard equivalent, implemented here, is that
an admin can **reset** any user's password to a new value; they cannot see
the current one. `GET /api/users` never returns `passwordHash`.

On first startup, if the `users` collection is empty, seed exactly one user:
`firstName` "Admin", `lastName` "User", `username` `admin`, `email`
`admin@localhost`, password `admin`, role `ADMIN`, `passwordHash` via
BCrypt. Log a clear WARNING on startup that this is a default credential and
should be rotated. Document this loudly in `/docs/SETUP.md` — do not bury
it.

## JWT SIGNING SECRET

**On "base one off the user":** literally deriving the signing secret from
the admin account's password would be a real problem, not just a style
choice — worth spelling out why, since it's not obvious: the default admin
password is `admin`, a value anyone reading this file now knows. If the
secret is derivable from it, anyone can compute the same secret and forge
a valid JWT for **any** user, not just admin — the entire auth system
becomes bypassable by a value that's already flagged elsewhere in this doc
as a known-weak default. It would also mean every admin password reset
invalidates every user's session, coupling two things that shouldn't be
coupled. So this isn't implemented as literally described.

What actually solves the underlying goal — not having to manually invent
and set a secret to boot — without that problem:

- `JWT_SECRET` becomes **optional**, not required.
- If it's not set, generate a cryptographically random 256-bit value with
  `SecureRandom` on first boot, encrypt it with `CREDENTIAL_ENCRYPTION_KEY`,
  and persist it in a new `system_config` collection (see DATA MODEL).
  Every subsequent boot — including additional replicas/pods reading the
  same MongoDB — reuses that persisted value, so restarts don't log
  everyone out and horizontal scaling still works.
- If `JWT_SECRET` **is** set — via `application.properties`, a plain env
  var, a Kubernetes `Secret`, or an ECS task-definition secret/hardcoded
  value, exactly the channels you described — that explicit value wins
  over the auto-generated one.
- An admin can also set it explicitly from the Admin Console's Secrets
  tab, which takes priority over both — see SECRETS RESOLUTION MODEL for
  the mechanics and an important side effect of using it.

This does mean `CREDENTIAL_ENCRYPTION_KEY` stays the one secret you must
actually supply. That's not an oversight: something has to be the root of
trust that encrypts everything else the app stores (the JWT secret now,
API keys already). If that were also auto-generated and self-encrypting,
you'd need another secret to encrypt *that* with, and so on forever — one
externally-supplied secret is the floor, not a limitation of this design.

## SECRETS RESOLUTION MODEL

You asked for the Admin Console to be the one place that can set up *all*
secrets and tokens, regardless of which of these channels they'd otherwise
come from: env vars/`application.properties`, a host-mounted file, a
Kubernetes `Secret`, or an ECS task-definition secret. That holds for most
of them — but two are structurally unable to work that way, for reasons
worth being precise about rather than glossing over, since they're not a
policy choice:

- **`CREDENTIAL_ENCRYPTION_KEY`** encrypts everything else this app
  stores, including whatever the Admin Console would store. A secret
  can't be "entered via the UI and stored encrypted in Mongo" using
  itself as the encryption key — there'd be nothing to decrypt it with
  afterward. That part never changes. What *has* changed, for the
  really-quick quick-boot in PACKAGING: if it's not set at all, the
  container generates one itself and keeps it in a local file
  (`/data/credential-key`), not in Mongo and not through the Admin
  Console — still outside both, just automated instead of asked-for. It's
  a genuinely weaker mode (see PACKAGING for exactly why) and is meant for
  a single throwaway local container, not anything with data worth
  keeping.
- **`MONGODB_URI`** can't be "read in and stored in MongoDB" either,
  because the Admin Console's ability to store *anything* already depends
  on that connection existing. It has to be resolved before the app can
  talk to Mongo at all. What *can* happen if it's simply not set: the
  container starts its own local MongoDB instead of failing outright —
  see PACKAGING for how and its own caveats. Either way it's resolved
  before the app touches Mongo, never through it.

Everything else genuinely does follow one consistent pattern —
**Admin Console override (stored encrypted in Mongo) > explicit external
config (env var/properties/K8s `Secret`/ECS secret) > an automatic
fallback where one exists** — across three places:

1. **LLM provider keys** (Anthropic/OpenAI) — already built this way; see
   LLM PROVIDERS & CREDENTIALS.
2. **JWT signing secret** — `PUT /api/admin/jwt-secret { value }` [ADMIN]
   sets an override in `system_config` (`key:
   "JWT_SIGNING_SECRET_OVERRIDE"`, encrypted); `DELETE
   /api/admin/jwt-secret` [ADMIN] clears it, reverting to
   `JWT_SECRET`/auto-generated; `GET /api/admin/jwt-secret` [ADMIN] →
   `{ configured, source: ADMIN_OVERRIDE|ENV_VAR|AUTO_GENERATED }` — never
   the value itself, there's no reason for even a masked hint of it the
   way `last4` helps for API keys. **Important side effect**: setting or
   clearing this immediately invalidates every currently-issued JWT,
   including the admin's own — every logged-in session gets signed out at
   once. The UI must say this plainly and require confirmation before the
   call, not bury it as fine print.
3. **Per-source MCP auth tokens** — `SourceConfig.authToken` (see SOURCE
   TYPES) is entered through the same add/edit-source UI as before, but
   now stored **encrypted** at rest with `CREDENTIAL_ENCRYPTION_KEY`, the
   same as the other two categories, and never returned in full on
   `GET /api/saas-products/{id}` once saved (masked, same `last4` pattern).

**Quick-boot, reframed now that this exists:** for a person clicking
through the UI, quick-boot is: boot the container (see PACKAGING for how
little that now requires), log in as `admin`/`admin`, paste an Anthropic
key into the Secrets tab. The env-var/host-mounted-file/K8s-`Secret`/ECS
channels documented elsewhere in this file remain there for **headless**
deployment, where a pipeline is provisioning the container and nobody's
present to click through a UI — that's the actual reason multiple channels
exist side by side, rather than one making the others redundant.

## DATA MODEL (MongoDB collections)

- `users`: `{ _id, firstName, lastName, username, email, passwordHash,
  role: ADMIN|READ_ONLY, preferredLlmProvider: ANTHROPIC|OPENAI|null,
  createdAt }` — `preferredLlmProvider` is null until the user picks one;
  see LLM PROVIDERS & CREDENTIALS for how it's used
- `saas_products`: `{ _id, name, description, sources: [SourceConfig],
  createdAt, createdBy }` — `sources` is an unbounded array; see SOURCE
  TYPES for `SourceConfig`'s shape, including its `authToken` now being
  stored encrypted (see SECRETS RESOLUTION MODEL). `GET
  /api/saas-products/{id}` adds a computed (not stored) `lastRun` summary
  — `{ runAt, sources: [{ sourceName, sourceType, fetchedAt }] } | null`
  — derived from the most recent `change_report`, so it can't go stale;
  this is the "meta-data of what it included and when it last ran" the
  date picker relies on
- `snapshots`: `{ _id, saasProductId, sourceName, sourceType, rawContent,
  pageUrls: [string], fetchedAt }` — one per source per run; `rawContent` is
  the (possibly multi-page, capped) crawled/fetched text, `pageUrls` records
  what was actually crawled for that run, for debugging/audit. Kept for
  history and to give Claude the "previous" version to compare against
- `change_reports`: `{ _id, saasProductId, runAt, runBy, runType:
  STANDARD|CUSTOM_RANGE, analysisDepth: SHORT|REGULAR|NUCLEAR, rangeFrom?,
  rangeTo?, mcpHistoryLimited: boolean, sourcesIncluded: [{ sourceName,
  sourceType, fetchedAt }], overallSummary, changes: [ { sourceName,
  sourceType, category, description, confidence, evidenceSnippet } ] }` —
  category is one of: feature, pricing, policy, bugfix, documentation,
  deprecation, other. `rangeFrom`/`rangeTo` are only set for
  `CUSTOM_RANGE`; `analysisDepth` is the verbosity level chosen when the
  run/compare was triggered (see ANALYSIS DEPTH); `sourcesIncluded` is the
  "what it included and when" metadata called for below; `mcpHistoryLimited`
  flags when an MCP source's portion of this report came from aggregated
  history rather than a live comparison (see CUSTOM DATE-RANGE COMPARE)
- `user_llm_credentials`: `{ _id, userId, provider: ANTHROPIC|OPENAI,
  apiKeyEncrypted, createdAt, updatedAt }` — one document per user per
  provider (unique on `userId`+`provider`); see LLM PROVIDERS & CREDENTIALS
  below
- `system_llm_credentials`: `{ _id, provider: ANTHROPIC|OPENAI,
  apiKeyEncrypted, updatedAt, updatedBy }` — admin-set system-wide override,
  one document per provider; see LLM PROVIDERS & CREDENTIALS below
- `system_config`: `{ _id, key, valueEncrypted?, value?, createdAt,
  updatedAt? }` — a general keyed config store, one doc per key: the
  auto-generated JWT secret (`key: "JWT_SIGNING_SECRET"`, encrypted), the
  admin-set JWT override (`key: "JWT_SIGNING_SECRET_OVERRIDE"`, encrypted
  — see SECRETS RESOLUTION MODEL), and the crawl defaults (`key:
  "CRAWL_DEFAULTS"`, plain `value: { maxDepth, maxPages }` — see ADMIN
  CONSOLE)
- `audit_logs`: `{ _id, actorUserId, actorUsername, action, targetType,
  targetId, details, timestamp }` — see AUDIT LOG below

## SOURCE TYPES

A SaaS Product has an **unlimited, arbitrary list of sources** — zero or
more of each type below, in any combination (e.g. three separate Docs MCP
servers for three different doc sets is fully supported; nothing in the
data model or UI caps count-per-type or total count).

1. **Docs MCP** — MCP server URL exposing documentation search/read tools
2. **Atlassian MCP** — MCP server URL for Atlassian's own MCP server, which
   exposes both Jira (issues, changelogs) and Confluence (pages) tools —
   broader than "Jira" alone, so treat whatever tools it exposes as fair
   game rather than assuming Jira-only
3. **Generic MCP** — any other MCP server URL not covered above. Since its
   tool shape is unknown ahead of time, don't give Claude domain-specific
   instructions for it (no "check for new Jira issues" style hints) — just
   include it in `mcp_servers` and instruct Claude to use its judgment about
   which of the server's tools are relevant to detecting recent changes
4. **Website** — a URL that gets **crawled**, not just fetched once: follow
   same-origin links breadth-first starting at `endpointUrl`, up to
   `maxDepth` (default 2) and `maxPages` (default 20), skip anything
   disallowed by `robots.txt`, extract visible text per page
5. **SaaS App URL** — e.g. a changelog/release-notes page; crawled the same
   way as Website (same defaults, same limits)

`SourceConfig = { type: DOCS_MCP|ATLASSIAN_MCP|GENERIC_MCP|WEBSITE|SAAS_URL,
name, endpointUrl, authToken?, maxDepth?, maxPages? }` — `maxDepth`/
`maxPages` only apply to Website/SaaS App URL types and are ignored
(validated away) for MCP types. `authToken` is stored encrypted with
`CREDENTIAL_ENCRYPTION_KEY` (see SECRETS RESOLUTION MODEL) and comes back
from `GET /api/saas-products/{id}` masked to its last 4 characters, same
as the LLM credential fields — never in full once saved.

MCP-type sources (Docs MCP, Atlassian MCP, Generic MCP) are declared as
remote MCP tools to whichever LLM provider is handling the run (Anthropic's
`mcp_servers` param or OpenAI's Responses API remote-MCP tool type — see
LLM PROVIDERS & CREDENTIALS), so the model calls their tools itself.
Crawled sources (Website, SaaS App URL) are fetched by the backend and
passed as plain text context, one block per crawled page, each headed by
its page URL, e.g. `--- PAGE: https://example.com/changelog ---`. Cap total
crawled text per source (e.g. ~200k characters) before it goes into the LLM
call — a full crawl can easily exceed what's useful in a single context
window, so truncate (keeping the earliest-crawled/most important pages
first) rather than sending everything unbounded.

## ANALYSIS DEPTH

Both `POST .../run` and `POST .../compare` (below) take an optional
`analysisDepth: SHORT|REGULAR|NUCLEAR` in the request body (default
`REGULAR`), shown in the UI as a 3-option control — **Short Summary /
Regular / Nuclear Analysis** — next to the Run button and next to the
Compare date pickers. It's a per-invocation choice, not a product setting,
and it's stored on the resulting `change_report` so History can show which
depth produced it.

This controls how thorough the LLM's analysis is, **not** what data goes
in — crawl `maxDepth`/`maxPages` (SOURCE TYPES) are a separate, already-
existing knob for that. Depth only changes the prompt instructions (and,
for NUCLEAR, the output token budget) given to whichever `LlmProvider` is
handling the call:

- **SHORT**: "Give a concise 2–4 sentence summary and list only the
  handful of most significant changes (roughly top 3–5), one sentence
  each. Omit minor or cosmetic changes entirely."
- **REGULAR**: the default behavior already described in ORCHESTRATION
  FLOW/CUSTOM DATE-RANGE COMPARE — comprehensive but not exhaustive.
- **NUCLEAR**: "Be exhaustive — identify every discernible change across
  every source, however minor, with a fuller explanation and more of the
  supporting evidence quoted per change. Don't omit anything for brevity."
  Also raise the model's max-output-tokens for this call specifically, so
  a genuinely exhaustive response doesn't get cut off mid-way.

Tag the OBSERVABILITY metrics with `analysisDepth` alongside `runType` —
worth watching, since NUCLEAR runs will cost more and take longer, and
that should show up in the numbers rather than be a surprise.

## ORCHESTRATION FLOW — run a product

`POST /api/saas-products/{id}/run` validates the product exists and kicks
off the run **asynchronously**, returning `202 Accepted` + `{ runId }`
immediately rather than blocking on the whole crawl+LLM pipeline. The
frontend's live execution view (see REACTIVITY & RESPONSIVE DESIGN)
subscribes to `GET /api/saas-products/{id}/runs/{runId}/events` (Server-Sent
Events) to watch it happen in real time — this is what makes that view
show genuine backend progress rather than a client-side animation timing
itself against nothing. Each event is
`{ type: step_started|step_progress|step_completed|step_failed|
run_completed|run_failed, step, detail, elapsedSeconds }`, where `detail`
is a real, specific string sourced from what's actually happening — not a
generic placeholder:
- crawling: `"Crawling https://example.com/changelog (page 3 of ~20)"`,
  one `step_progress` event per page fetched, not just one at the start
- consulting MCP tools: `"Calling Docs MCP tool: search_docs"`, emitted as
  the provider's tool-call events come back (both Anthropic's and
  OpenAI's streaming APIs surface tool-call names as they happen)
- comparing/summarizing: `"Analyzing 4 sources against prior snapshots..."`
  / `"Summarizing 7 detected changes..."` — these two phases are one
  non-streamed model call for the structured JSON, so `detail` here is a
  static-but-specific description plus the elapsed-time counter, rather
  than the running detail the fetch/MCP phases can give
The steps below are what happens inside that async task, mapped onto that
event stream, ending in a final `run_completed` carrying the persisted
`change_report`, or `run_failed`:

1. For each Website/SaaS App URL source, crawl starting at `endpointUrl`
   (same-origin links, respecting `robots.txt`, up to `maxDepth`/
   `maxPages`), extract visible text per page, concatenate with page-URL
   headers, cap total length.
2. For each source, load its most recent prior snapshot from MongoDB (if
   any).
3. Resolve which provider handles this run: the initiating user's
   `preferredLlmProvider` + their own stored key if both are set, else the
   server-wide default (Anthropic, using `ANTHROPIC_API_KEY`). Call that
   provider's `LlmProvider.generateChangeReport(...)` implementation
   (see LLM PROVIDERS & CREDENTIALS for what each implementation actually
   sends):
   - MCP-type sources (Docs MCP, Atlassian MCP, Generic MCP — however many
     there are) are declared to the model as remote MCP tools
   - message/input content = current + prior crawled text per Website/SaaS
     App URL source, plus instructions to check the Docs MCP/Atlassian MCP
     sources for anything new since `<lastRunAt>` and, for any Generic MCP
     source, to use judgment about which of its tools are relevant, plus
     the depth-specific instructions for this run's `analysisDepth` (see
     ANALYSIS DEPTH)
   - Instruct the model to return ONLY strict JSON matching the
     `change_reports` shape (no prose outside JSON) — use each provider's
     strongest available JSON-enforcement mechanism (e.g. OpenAI's
     structured outputs / `json_schema` response format; Anthropic via
     prompt instruction + the parse-retry in step 4)
4. Parse JSON; on parse failure, retry once with a "return valid JSON only"
   correction before failing that source.
5. Persist new snapshots for every source (even unchanged) and the new
   `change_report` (`runType: STANDARD`, `analysisDepth` as requested,
   `sourcesIncluded` listing each source with its `fetchedAt` from this
   run), tagged with `runBy` = current user.
6. If one source fails (MCP unreachable, crawl fails or times out), don't
   fail the whole run — mark that source "unavailable" in the report and
   continue.
7. Emit metrics for the whole run and per source (see OBSERVABILITY).

## CUSTOM DATE-RANGE COMPARE

The "what changed between two dates" ask, day-granularity, via a calendar
picker — the "since last run" preset is *not* this endpoint, it's just a
normal Run (above); this section covers picking two arbitrary dates.

`POST /api/saas-products/{id}/compare { fromDate, toDate, analysisDepth? }`
[ANY] — shares the same async-task + SSE + persistence infrastructure as
a standard run (`202` + `{ runId }`, same `GET .../runs/{runId}/events`
stream, result persisted as a `change_report` with `runType:
CUSTOM_RANGE`), but sources its "before"/"after" state differently, and
**never fetches or crawls anything new** — it only reasons over what's
already stored:

1. Validate `fromDate <= toDate`, neither in the future, and that at least
   one snapshot exists at or before `fromDate` for this product — if not,
   fail with a clear message naming the earliest date data actually exists
   for (see ERROR HANDLING).
2. For each **Website/SaaS App URL** source: find the most recent snapshot
   with `fetchedAt <= fromDate` (the "before" state) and the most recent
   snapshot with `fetchedAt <= toDate` (the "after" state). These feed the
   LLM call exactly like a standard run's current-vs-prior comparison,
   just anchored at two chosen historical points instead of now-vs-prior.
   Emit a `step_progress` event per source with a real detail string, e.g.
   `"Loading Website snapshot for example.com as of Jun 1"`.
3. For each **MCP-type** source (Docs MCP, Atlassian MCP, Generic MCP):
   there's no stored historical content to rewind to — live MCP tools only
   ever report their *current* state, so we can't ask a Jira/Confluence/
   Docs server what it looked like on a past date. Instead, aggregate the
   `changes` entries already recorded for that source across every
   `change_report` with `runAt` in `(fromDate, toDate]`. This is a real
   limitation, not a simplification for convenience — set
   `mcpHistoryLimited: true` on the result so the UI can show a caveat
   rather than implying it's as exact as the crawled-source half.
4. One LLM call, with the same depth-specific instructions from ANALYSIS
   DEPTH: crawled-source before/after text goes in for a fresh comparison;
   the MCP-aggregated changes go in already-summarized, with instructions
   to fold them into one coherent `overallSummary` rather than re-deriving
   them. Same strict-JSON contract as a standard run.
5. Persist as a `change_report` (`runType: CUSTOM_RANGE`, `analysisDepth`
   as requested, `rangeFrom`, `rangeTo`, `sourcesIncluded` noting which
   snapshot dates were actually used per source) — it shows up in the
   History timeline like any other report, just labeled with its date
   range instead of "Run".

## AD-HOC ASK (read-only "interact with the prompts" capability)

`POST /api/saas-products/{id}/ask { question }` — loads the product's latest
snapshots + most recent `change_report` as context, sends question +
context to whichever `LlmProvider` this user resolves to (same resolution
rule as a run — see LLM PROVIDERS & CREDENTIALS), and responds as
`text/event-stream`: token/chunk events as the model generates them, then a
`done` event with the complete answer. This is what backs the "answer
streams in below it, chat-style" behavior in REACTIVITY & RESPONSIVE
DESIGN — both Anthropic's and OpenAI's APIs support native streaming, so
this isn't simulated client-side. Available to both roles. Does not
persist anything.

## REPORT EXPORT

`GET /api/saas-products/{id}/reports/{reportId}/export?format=pdf|docx`
[ANY] — streams back a nicely formatted document for one `change_report`,
generated on demand (nothing pre-rendered or cached, so it's always
current with the underlying data).

- **PDF**: `openhtmltopdf` — render a styled HTML template to PDF rather
  than laying out primitives by hand, so the export can reuse the app's
  own look (Seafoam/Green, the same category-badge colors from DESIGN &
  UX) instead of looking like a generic report.
- **DOCX**: Apache POI (`poi-ooxml`) — build the document's paragraphs,
  headings, and a table for the changes list directly; no HTML
  intermediate step for this format.

Both formats contain the same content: product name, run metadata (date,
`runType`, date range if `CUSTOM_RANGE`, `analysisDepth`), the
`overallSummary`, then the `changes` grouped by category with source,
description, confidence, and evidence snippet per item, the
`mcpHistoryLimited` caveat if set, and a footer with export timestamp.

Add an "Export" action (with a PDF/DOCX choice) to each entry in the
History timeline (see DESIGN & UX) — it downloads directly, no extra page.

## LLM PROVIDERS & CREDENTIALS (multi-provider, bring-your-own-key)

Two providers are implemented — **Anthropic and OpenAI**. (Last round I'd
scoped OpenAI as future-only work; this round actually builds it, since
that's what "multiple AI providers" is asking for.) Cursor stays excluded,
same reasoning as before: there's no publicly documented API that lets a
third-party server make completions calls against a user's Cursor account
the way Anthropic's and OpenAI's API keys do. If you meant something else
by "Cursor credentials" — e.g. using this app from inside Cursor rather
than VS Code — that's unrelated to this section and worth telling me
separately.

**Abstraction:**
```
interface LlmProvider {
  ChangeReport generateChangeReport(RunContext context);
  String answerQuestion(AskContext context);
}
```
`RunContext`/`AskContext` carry the crawled text blocks and the list of
MCP-type sources (name, endpointUrl, authToken) — provider-agnostic inputs.
Two implementations:
- `AnthropicLlmProvider` — Messages API, `mcp_servers` param for MCP-type
  sources, model from `ANTHROPIC_MODEL` (default `claude-sonnet-4-6`)
- `OpenAiLlmProvider` — Responses API, remote MCP tools for MCP-type
  sources, model from `OPENAI_MODEL` (default: current flagship — check
  OpenAI's docs for the exact model name and the exact remote-MCP tool
  field names at build time, since both can move; the concept — declare an
  MCP server URL as a tool, let the model call it — is stable even if field
  names aren't)

**Provider resolution** for a given run/ask, in order:
1. If the initiating user has `preferredLlmProvider` set AND has a stored
   personal credential for that provider → use it, with their own key.
2. Else → the system-wide default, which is always Anthropic. Its key is
   resolved in this order (see the two subsections below for what each
   means): an admin-set override → the `ANTHROPIC_API_KEY` env var → the
   quick-boot host-mounted credential file → if none of those resolve, the
   run/ask fails gracefully with a clear message telling that user to
   configure their own credential (see ERROR HANDLING).

**Quick-boot host credential (convenience for local/dev use):**
On container startup, if a file exists at the fixed path
`/run/host-credentials/anthropic-api-key` (plain text, containing just the
key), and `IGNORE_HOST_CREDENTIALS` is not `true`, use its contents as the
system-wide Anthropic key. You get this by bind-mounting a file from the
host at `docker run` time, e.g.:
```
docker run -p 8080:8080 \
  -v ~/.anthropic/api-key.txt:/run/host-credentials/anthropic-api-key:ro \
  ... saas-investigator
```
This is a "put a file with your key where the container can see it"
mechanism, not literally reading Claude Code's own internal session/login
storage — that format is internal to the Claude Code product, isn't
documented for third-party reuse, and can change between versions, so
piggybacking on it directly would be fragile. If what you actually want is
for this app to reuse an existing Claude Code login rather than a fresh
Anthropic Console API key, say so and we can revisit — a plain API key in
a mounted file is the reliable version of this feature.

Set `IGNORE_HOST_CREDENTIALS=true` to skip reading this file entirely even
if it's mounted (the "explicitly ignored" case). If the mounted file exists
but is empty/unreadable, log a warning and continue startup — never crash
on this being malformed, since it's an optional convenience path.

**System-wide override, set inside the app (Admin only):** an admin can set
a system-wide Anthropic (or OpenAI) key from the Admin Console's Secrets
tab, stored encrypted in a new `system_llm_credentials` collection. This
takes priority over both the env var and the host-mounted file — it's one
instance of the general SECRETS RESOLUTION MODEL pattern, meant for when
you don't want to touch container config after the fact. Endpoints:
- `PUT /api/admin/system-credentials { provider, apiKey }` [ADMIN]
- `DELETE /api/admin/system-credentials/{provider}` [ADMIN] — clears the
  override, reverting to env var / host-mounted file resolution
- `GET /api/admin/system-credentials` [ADMIN] — `[{ provider, configured,
  last4, source: OVERRIDE|ENV_VAR|HOST_MOUNT|NONE }]`

**Storage and endpoints** (personal BYOK, works identically for either
provider):
- Encrypt the API key at rest (AES via a server-side symmetric key from env
  var `CREDENTIAL_ENCRYPTION_KEY` — never store it in plaintext)
- `POST /api/users/me/credentials { provider, apiKey }` [self] —
  create/update own credential for that provider (a user may have one
  stored per provider)
- `DELETE /api/users/me/credentials/{provider}` [self]
- `GET /api/users/me/credentials` [self] — returns `[{ provider, configured:
  true, last4 }]` per provider they've configured; never the decrypted key
- `PUT /api/users/me/preferred-provider { provider }` [self] — sets which
  configured provider actually gets used (see resolution rule above); a
  user with no credentials configured can still set this, it just won't
  take effect until they add the matching key

## AUDIT LOG

Every mutating action and every login attempt gets one `audit_logs` entry:
`AUTH_LOGIN_SUCCESS`, `AUTH_LOGIN_FAILURE`, `USER_CREATED`, `USER_DELETED`,
`USER_PASSWORD_RESET`, `PRODUCT_CREATED`, `PRODUCT_UPDATED`,
`PRODUCT_DELETED`, `PRODUCT_RUN_TRIGGERED`, `PRODUCT_COMPARE_TRIGGERED`,
`PRODUCT_ASK_SUBMITTED`, `LLM_CREDENTIAL_ADDED`, `LLM_CREDENTIAL_REMOVED`,
`SYSTEM_CREDENTIAL_OVERRIDE_SET`, `SYSTEM_CREDENTIAL_OVERRIDE_CLEARED`,
`SYSTEM_SETTINGS_UPDATED`, `JWT_SECRET_OVERRIDE_SET`,
`JWT_SECRET_OVERRIDE_CLEARED`, `DATA_EXPLORER_DOCUMENT_UPDATED`.

Implement as a single `AuditService.log(action, targetType, targetId,
details)` called explicitly at the end of each relevant service method
(simpler to follow than an AOP aspect while you're still learning the
codebase; can be refactored later). `details` must never contain a
password, an LLM API key, an MCP `authToken`, or the JWT signing secret,
encrypted or not — pass only non-secret context (e.g. which fields
changed, not their values, for any of these).

`GET /api/audit-logs` [ADMIN] — paginated, filterable by `actorUsername`,
`action`, and date range.

## ADMIN CONSOLE

Everything admin-only — Users, Secrets, Audit Log, plus the two genuinely
new pieces below — lives under one **Admin Console** area in the nav (see
DESIGN & UX), rather than scattered as separate top-level sidebar items.
READ_ONLY users never see this entry at all.

**Secrets** — the Anthropic/OpenAI system-wide overrides from LLM
PROVIDERS & CREDENTIALS and the JWT signing secret override from SECRETS
RESOLUTION MODEL, together in one tab, each row showing its current
source (admin override set here / env var / auto-generated or host-mount
where applicable) so it's obvious at a glance where a given secret is
actually coming from before you change it.

**Stats** — `GET /api/admin/stats` [ADMIN]: computed by Mongo aggregation,
not scraped back out of Prometheus (that would be a roundabout way to ask
your own database a question it can answer directly):
```
{
  totalUsers, totalProducts, totalSourcesConfigured,
  runsLast24h, runsLast7d, runSuccessRate7d, avgRunDurationSeconds7d,
  recentRuns: [{ productName, runType, status, runAt }]  // last ~10
}
```

**Health** — `GET /api/admin/health` [ADMIN]: intentionally **separate**
from the public, unauthenticated `/actuator/health` used for k8s/ECS
liveness probes (see REST API / ERROR HANDLING) — that one stays minimal
on purpose so it doesn't leak internals to an unauthenticated caller.
This endpoint is where the real detail belongs instead: reuse Spring
Boot's existing `HealthIndicator`/`HealthEndpoint` beans programmatically
(don't reimplement Mongo/disk checks from scratch) for infra-level status,
then layer app-specific checks on top — is the system-wide Anthropic
credential actually resolvable right now (and from which source: override/
env var/host mount), same for OpenAI, when did the last successful run
happen. Link out to `/swagger-ui.html` and (for whoever can reach it)
`/actuator/prometheus` from this page for convenience.

**Settings** — `GET`/`PUT /api/admin/settings` [ADMIN]: `{ defaultMaxDepth,
defaultMaxPages }`, the crawl defaults used when a Website/SaaS App URL
source doesn't override them (see SOURCE TYPES). Stored as another keyed
document in `system_config` (the same collection already holding the
auto-generated JWT secret — it's a general keyed config store, not
JWT-only). A `PUT` logs `SYSTEM_SETTINGS_UPDATED`. This tab is for
non-secret config; anything secret lives in Secrets above instead.

**Data Explorer** — since this is an example/learning app, a generic
Mongo browser is genuinely useful for seeing what's actually being
written, not just trusting the code. One guardrail, applied consistently
with everything else this file already protects: any field already
established elsewhere as encrypted/secret (`passwordHash`,
`apiKeyEncrypted`, `SourceConfig.authToken`, the `system_config`
`valueEncrypted` values) renders **read-only and masked** here — not
because the feature is being watered down, but because a generic "edit
any field" tool is exactly the kind of thing that would otherwise quietly
undo every protection built up in USERS & ROLES, LLM PROVIDERS &
CREDENTIALS, and SECRETS RESOLUTION MODEL. Scoped to browse + edit
non-secret fields, not arbitrary delete/insert — deleting a
`saas_products` doc without cascading its `snapshots`/`change_reports`,
for instance, would leave orphaned data, which is a bigger feature than
"see what's being written" asked for.

- `GET /api/admin/data-explorer/collections` [ADMIN] → collection names
- `GET /api/admin/data-explorer/collections/{name}/documents?page=&pageSize=`
  [ADMIN] → paginated documents, secret fields replaced server-side with
  `"[encrypted]"` — the real ciphertext never even reaches the browser
- `GET /api/admin/data-explorer/collections/{name}/documents/{id}` [ADMIN]
  → single document, same masking
- `PUT /api/admin/data-explorer/collections/{name}/documents/{id}` [ADMIN]
  → update non-secret fields; a request that touches a masked field is
  rejected with `400` and a message pointing to the actual right place
  (e.g. "passwordHash can't be edited directly — use Users → Reset
  password"), rather than silently ignored or silently corrupting it.
  Logs `DATA_EXPLORER_DOCUMENT_UPDATED` with the collection, document id,
  and which field names changed (not their values).

UI: a collection picker, a paginated table per collection, click a row to
open a detail panel — editable fields as real form inputs, masked fields
shown as a disabled `[encrypted]` chip — Save persists via the `PUT` above.

## REST API

| Method | Path | Role | Notes |
|---|---|---|---|
| POST | `/api/auth/login` | public | `{ username, password }` → JWT |
| GET | `/api/users` | ADMIN | list users |
| POST | `/api/users` | ADMIN | create user (role assigned by admin) |
| DELETE | `/api/users/{id}` | ADMIN | |
| PUT | `/api/users/{id}/password` | ADMIN | reset a user's password (cannot view the current one — see USERS & ROLES) |
| POST | `/api/users/me/credentials` | self | store/update own API key for a provider |
| DELETE | `/api/users/me/credentials/{provider}` | self | remove own credential for that provider |
| GET | `/api/users/me/credentials` | self | list of `{ provider, configured, last4 }`, never the raw key |
| PUT | `/api/users/me/preferred-provider` | self | `{ provider }` — which configured provider to use |
| PUT | `/api/admin/system-credentials` | ADMIN | set the system-wide override for a provider |
| DELETE | `/api/admin/system-credentials/{provider}` | ADMIN | clear the override |
| GET | `/api/admin/system-credentials` | ADMIN | current source per provider: override/env var/host mount/none |
| PUT | `/api/admin/jwt-secret` | ADMIN | set a JWT signing secret override — invalidates all sessions, see SECRETS RESOLUTION MODEL |
| DELETE | `/api/admin/jwt-secret` | ADMIN | clear the override — also invalidates all sessions |
| GET | `/api/admin/jwt-secret` | ADMIN | `{ configured, source: ADMIN_OVERRIDE\|ENV_VAR\|AUTO_GENERATED }`, never the value |
| GET | `/api/admin/stats` | ADMIN | usage/health stats for the Admin Console (see ADMIN CONSOLE) |
| GET | `/api/admin/health` | ADMIN | detailed health check, distinct from the public `/actuator/health` |
| GET | `/api/admin/settings` | ADMIN | current crawl defaults |
| PUT | `/api/admin/settings` | ADMIN | update crawl defaults |
| GET | `/api/admin/data-explorer/collections` | ADMIN | list collection names |
| GET | `/api/admin/data-explorer/collections/{name}/documents` | ADMIN | paginated, secret fields masked |
| GET | `/api/admin/data-explorer/collections/{name}/documents/{id}` | ADMIN | single document, secret fields masked |
| PUT | `/api/admin/data-explorer/collections/{name}/documents/{id}` | ADMIN | update non-secret fields; rejects edits to masked fields |
| GET | `/api/audit-logs` | ADMIN | paginated, filterable audit trail |
| POST | `/api/saas-products` | ADMIN | create |
| GET | `/api/saas-products` | ANY | list |
| GET | `/api/saas-products/{id}` | ANY | detail incl. sources |
| PUT | `/api/saas-products/{id}` | ADMIN | update sources |
| DELETE | `/api/saas-products/{id}` | ADMIN | |
| POST | `/api/saas-products/{id}/run` | ANY | `{ analysisDepth? }` — starts a run async, returns `202` + `{ runId }` |
| POST | `/api/saas-products/{id}/compare` | ANY | `{ fromDate, toDate, analysisDepth? }` — starts a historical compare async, same `202` + `{ runId }` shape (see CUSTOM DATE-RANGE COMPARE) |
| GET | `/api/saas-products/{id}/runs/{runId}/events` | ANY | SSE stream of run/compare progress (see ORCHESTRATION FLOW) |
| GET | `/api/saas-products/{id}/reports` | ANY | history, newest first |
| GET | `/api/saas-products/{id}/reports/{reportId}/export` | ANY | `?format=pdf\|docx` — download a formatted export (see REPORT EXPORT) |
| POST | `/api/saas-products/{id}/ask` | ANY | ad-hoc question, responds as SSE token stream |
| GET | `/actuator/health` | public | liveness/readiness |
| GET | `/actuator/prometheus` | public | Prometheus scrape endpoint |

Enforce roles with `@PreAuthorize`; return 403 with a clear message on
violation. Explicitly permit `/actuator/**` without JWT in the security
config (scrapers don't authenticate) — restrict actual access at the
network/ingress level instead, and say so in a comment + in
`/docs/DEPLOYMENT.md`.

## DESIGN & UX

**Inspiration source:** structural/UX patterns from harness.io's product
dashboards — specifically:
- A **live execution view** for long-running actions: a vertical list of
  steps, each with a status pill (`Running` / `Success` / `Failed`) and a
  "Thinking..." trace line with elapsed time, rather than a plain spinner.
  Use this for the Run flow: steps are *Fetching sources → Consulting MCP
  tools → Comparing → Summarizing*. Compare reuses the same component with
  a shorter step set — *Loading historical snapshots → Aggregating MCP
  history → Comparing → Summarizing* — since it never fetches or crawls
  anything new.
- A **natural-language query bar** where the question sits in a single input
  and the answer streams in below it, chat-style. Use this for the Ask flow.
- **Colored pill badges** for categorical/severity-like data in tables. Use
  this for change categories (feature/pricing/policy/bugfix/documentation/
  deprecation) in the report timeline.

Do not copy Harness's literal visual skin (colors/logo/typography) — only
these interaction/layout patterns. Build the actual look from the palette
below.

**Color tokens** (define as CSS custom properties, switched via a
`data-theme` attribute on `<html>`; default to `prefers-color-scheme`, with a
manual toggle in the top bar, persisted to `localStorage`):

Light — White is the dominant base, Seafoam and Green are the only accents
(no other hues introduced):
```
--bg: #FFFFFF;         /* White — dominant base */
--surface: #F4FBFA;    /* faint seafoam tint, lifts cards off the white bg */
--primary: #2F9E8F;    /* Seafoam */
--primary-hover: #1F7A6D; /* Deep Teal */
--accent: #4CAF7D;     /* Green — positive change / success */
--text: #12241F;       /* Ink */
--text-muted: #7C948F; /* Ash */
--warning: #C98A2F;
--error: #C6524A;
```

Dark (navy-based, NOT pure black):
```
--bg: #0A1622;         /* Navy Deep */
--surface: #102232;    /* Navy Surface */
--primary: #4FD1C5;    /* Seafoam Bright */
--primary-hover: #7BE0D6;
--accent: #6EE7A8;     /* Fresh Green Bright */
--text: #E7F3F1;       /* Fog — never pure white */
--text-muted: #4C6672; /* Steel */
--warning: #E0A94D;
--error: #E0645A;
```

**Typography:** two families, clearly distinct roles — a highly legible UI
sans (e.g. Inter, with tabular figures enabled for metrics/tables) for all
body/UI text, and a slightly more geometric display face (e.g. Space
Grotesk) used sparingly, only for the product name lockup and page titles.
One consistent type scale (e.g. 12/14/16/20/24/32px), 1.4–1.6 line-height for
body text.

**Layout:**
- Collapsible left sidebar: SaaS Products list, and — for ADMIN only — a
  single **Admin Console** entry (see ADMIN CONSOLE) rather than separate
  top-level items for Users/Audit Log/etc.
- Top bar: current context, dark-mode toggle, account menu (Account
  Settings, Logout)
- Dashboard: card grid of SaaS Products, each showing a last-run status pill
  and time-since-last-run
- Product detail: source config on one side (chips, read-only for
  READ_ONLY; editable list for ADMIN — add-source form shows a type
  dropdown with all 5 types, no limit on how many of a given type; Website/
  SaaS App URL types reveal optional "max pages"/"max depth" fields for the
  crawler, defaulted so most users never need to touch them), tabs on the
  other for Run (a Short/Regular/Nuclear segmented control next to the Run
  button — see ANALYSIS DEPTH — then the live execution view), **Compare**
  (two calendar date inputs — "from"/"to", day granularity — plus a
  one-click "Since last run" preset that just triggers a normal Run
  instead; shows the product's `lastRun` summary above the picker so the
  person knows what "since last run" actually resolves to before clicking
  it; the same depth control as Run; the resulting comparison renders in
  the same live-execution-view + category-badge style as a Run, with a
  small caveat note on any source whose result came back
  `mcpHistoryLimited`), History (expandable report timeline with category
  badges, each entry labeled either "Run" or with its date range plus a
  small depth badge, and an "Export" action per entry offering PDF or
  DOCX — see REPORT EXPORT), and Ask (query bar + streaming answer)
- Account Settings page [any user]: own name/email (read-only or editable —
  your call), an "AI Provider" section listing Anthropic and OpenAI, each
  with an add/remove API key field (masked once saved, showing only last 4
  characters) and a way to pick which one is "active" if both are
  configured
- Admin Console [ADMIN only], own sub-nav:
  - **Overview**: stat cards from `/api/admin/stats` (totals, run success
    rate, avg run duration) and a status list from `/api/admin/health`
    (Mongo, each configured LLM provider, disk); links out to
    `/swagger-ui.html`
  - **Users**: table of users with a "Reset password" action per row
    (opens a dialog to set a new password — never displays the current one)
  - **Secrets**: the admin-set provider overrides from LLM PROVIDERS &
    CREDENTIALS plus the JWT signing secret override, listed together
    with a "source" indicator per row (see ADMIN CONSOLE); changing or
    clearing the JWT row requires an explicit confirmation dialog stating
    that every active session — including the admin's own — is about to
    be signed out
  - **Audit Log**: filterable/paginated table of actions, actor, target,
    and timestamp
  - **Settings**: the crawl defaults from `/api/admin/settings`
  - **Data Explorer**: collection picker + paginated document table +
    detail/edit panel (see ADMIN CONSOLE) — masked fields shown as a
    disabled `[encrypted]` chip rather than an editable input
- Don't apply identical rounded-corner+shadow treatment to every block —
  differentiate primary content cards (subtle border, minimal shadow) from
  status pills (solid fill, higher contrast) so hierarchy reads clearly

## REACTIVITY & RESPONSIVE DESIGN

**Reactive** — the UI reflects real state changes as they happen, not on a
fixed timer or only after a manual refresh:
- The Run live-execution view and the Ask answer are both driven by real
  SSE streams from the backend (see ORCHESTRATION FLOW / AD-HOC ASK) — the
  step pills and the streamed answer text are rendering actual server
  progress, not a client-side animation that happens to look similar.
- Use a data-fetching/cache library on the frontend (e.g. TanStack Query)
  so that after any mutation (source added/removed, password reset,
  credential saved, provider switched) the relevant list/detail view
  refetches and re-renders automatically, instead of requiring a manual
  page reload to see the change.
- Simple mutations (adding a source, resetting a password) update
  optimistically where it's safe to do so — show the new state
  immediately, roll back and show an error if the request actually fails —
  rather than freezing the UI until the round-trip completes.
- **Show what's actually happening, not a generic "please wait":** the
  live execution view's trace line renders the real `detail` string each
  SSE event carries (see ORCHESTRATION FLOW / CUSTOM DATE-RANGE COMPARE for
  the event schema) — "Crawling https://.../changelog (page 3 of ~20)",
  "Calling Docs MCP tool: search_docs", "Analyzing 4 sources..." — not a
  static "Thinking..." with nothing behind it. Anywhere else that waits on
  a slower request (Users/Audit Log/Admin Console tables loading), use a
  skeleton placeholder shaped like the real content rather than a bare
  spinner, so the person sees what's coming rather than just that
  something, unspecified, is loading.

**Responsive** — the layout works from phone width up to desktop, not just
at whatever size it was designed at:
- Breakpoints: mobile (<640px), tablet (640–1024px), desktop (>1024px).
- The collapsible left sidebar becomes a slide-over drawer behind a
  hamburger control below the tablet breakpoint, rather than just shrinking.
- The Dashboard's card grid reflows to a single column on mobile; the
  Audit Log and Users tables scroll horizontally within their own
  container rather than forcing the page to scroll sideways (consistent
  with the wide-content handling already expected of any data table here).
- Touch targets (buttons, row actions, the theme toggle) stay at least
  44px, and forms (New/Edit SaaS Product, Login) go full-width on mobile
  rather than keeping a fixed desktop width that gets clipped.
- Verify at least one full flow (login → dashboard → product detail → run)
  at a phone-width viewport, not just desktop, before calling a screen done.

## PERFORMANCE

Concrete, learnable performance work rather than a vague goal — each item
below is something with a specific, checkable cause:

- **Mongo indexes** — without these, the exact query patterns this app
  already relies on become full collection scans as data grows:
  - `snapshots`: compound index on `{ saasProductId: 1, sourceName: 1,
    fetchedAt: -1 }` — this is what makes both "most recent prior
    snapshot" (every run) and "nearest snapshot at-or-before a date"
    (every Compare) fast lookups instead of scans
  - `change_reports`: `{ saasProductId: 1, runAt: -1 }`
  - `audit_logs`: `{ actorUsername: 1, action: 1, timestamp: -1 }` —
    matches the actual filter fields `GET /api/audit-logs` exposes
  - `users`: unique indexes on `username` and `email`
  - `user_llm_credentials`: unique compound index on `{ userId: 1,
    provider: 1 }`; `system_llm_credentials`: unique index on `provider`
- **Bounded crawl concurrency** — fetch a source's pages with a capped
  thread/coroutine pool (e.g. 4–5 concurrent requests) instead of strictly
  one page at a time, while still respecting `robots.txt` and not hammering
  the target server — concurrency and politeness aren't in tension here,
  both are about not doing something naive (unbounded sequential is slow;
  unbounded parallel is rude).
- **Pagination everywhere a list can grow** — `saas_products` list and a
  product's `reports` history get the same cursor/offset pagination
  `/api/audit-logs` already has, rather than assuming the list stays small
  forever.
- **Frontend** — route-level code-splitting (Vite/React lazy `import()`
  per page) so the initial bundle isn't the whole app; lean on the
  TanStack Query cache from REACTIVITY & RESPONSIVE DESIGN to avoid
  re-fetching data a view already has rather than re-requesting on every
  mount.
- **Measure it, don't just assert it** — the RED metrics from OBSERVABILITY
  (`http.server.requests` timings, `saas_run_duration_seconds`) and the
  Admin Console's `avgRunDurationSeconds7d` stat are how you'd actually
  notice a regression here; no invented SLA numbers to hit, just don't fly
  blind.

## OBSERVABILITY (RED metrics via Micrometer → Prometheus)

- Add `spring-boot-starter-actuator` + `micrometer-registry-prometheus`.
  Expose only `health` + `prometheus` under
  `management.endpoints.web.exposure`.
- **Generic layer (free):** configure `management.metrics.distribution` to
  enable histogram buckets on `http.server.requests`, tagged by
  uri/method/status — gives Rate, Errors, Duration for every REST endpoint
  automatically.
- **Business layer (custom)** — the interesting failures happen inside the
  run, not at the HTTP boundary:
  - Counter `saas_run_total` tagged `{product, status=success|partial|failure, runType=STANDARD|CUSTOM_RANGE, analysisDepth=SHORT|REGULAR|NUCLEAR}`
  - Timer `saas_run_duration_seconds` tagged `{product, runType, analysisDepth}`
  - Counter `saas_source_fetch_errors_total` tagged `{product, sourceType}`
  - Counter `saas_ask_total` tagged `{product, status}`

  Wrap the orchestration and ask service methods with these; increment/time
  them regardless of which branch (success/partial/failure) is hit.
- Document exact metric names, tags, and meaning in `/docs/DEPLOYMENT.md` so
  a Grafana dashboard can be built against them later without re-reading
  code.

## ERROR HANDLING

Every error path should degrade gracefully — surface a clear, actionable
message and keep the rest of the app usable, rather than propagating a raw
exception or crashing.

- **Backend:** a global `@RestControllerAdvice` maps exceptions to a
  consistent JSON shape (`{ error, message, timestamp, path }`) with the
  right HTTP status (400/401/403/404/409/500...). Log full exception
  detail server-side; never return a raw stack trace or internal exception
  message to the client.
- Every external call — MCP tool calls, Website/SaaS App URL crawling,
  Anthropic/OpenAI API calls, Mongo operations — is wrapped so a single
  failure there doesn't take down a whole request. This generalizes the
  "one source fails → mark unavailable, keep going" rule from
  ORCHESTRATION FLOW into a house rule for all I/O, not just that one flow.
- A malformed or unreadable quick-boot host-credential file logs a warning
  and the app continues starting — never crash on this, since it's an
  optional convenience path (see LLM PROVIDERS & CREDENTIALS).
- If no provider can be resolved for a run/ask (no personal credential, no
  system default configured at all), fail that one request with a clear
  message telling the user to configure a credential — don't let it 500
  with a NullPointerException.
- Compare requests with `fromDate > toDate`, a future date, or a `fromDate`
  before any data exists for the product all fail with a 400 naming
  exactly what's wrong (and, for the last case, the earliest date data
  actually exists) rather than a generic error.
- **Frontend:** a top-level error boundary so a rendering error in one view
  doesn't blank the whole app; failed API calls show an inline error or
  toast with a human-readable message, not a raw error object; every async
  view (Dashboard, Product detail tabs, Users, Audit Log, Account Settings)
  has distinct loading/empty/error states rather than assuming the happy
  path.

## CONFIG / ENV VARS

| Var | Required | Default |
|---|---|---|
| `ANTHROPIC_API_KEY` | no | none — configure via the Admin Console's Secrets tab after boot instead, or a run/ask fails gracefully until one exists (see ERROR HANDLING) |
| `ANTHROPIC_MODEL` | no | `claude-sonnet-4-6` |
| `OPENAI_API_KEY` | no | none — same as Anthropic above |
| `OPENAI_MODEL` | no | current OpenAI flagship — confirm exact name in OpenAI's docs at build time |
| `MONGODB_URI` | no | if unset, the container starts its own local MongoDB — see PACKAGING |
| `JWT_SECRET` | no | auto-generated + persisted on first boot if unset — see JWT SIGNING SECRET |
| `CREDENTIAL_ENCRYPTION_KEY` | no | if unset, the container generates one and keeps it in a local file — see PACKAGING for why that's a demo-only fallback, not a recommendation |
| `IGNORE_HOST_CREDENTIALS` | no | `false` — set `true` to skip the quick-boot host-mounted credential file even if present |
| `SERVER_PORT` | no | `8080` |

Every one of these is now optional — see PACKAGING for exactly what
`docker run -p 8080:8080 <image>` alone gets you, and where the line is
between "quick local demo" and "set these explicitly."

Quick-boot host credential: mount a file to the fixed container path
`/run/host-credentials/anthropic-api-key` (see LLM PROVIDERS & CREDENTIALS)
instead of setting `ANTHROPIC_API_KEY` directly, if you'd rather keep the
key on the host filesystem than in the container's env.

Document the full table in `/docs/SETUP.md`.

## PACKAGING — single image, single run command

Multi-stage Dockerfile at repo root:

1. **node** stage: `npm ci && npm run build` in the frontend dir → `dist/`
2. **maven** stage: `eclipse-temurin:25-jdk` base, copy stage 1's `dist/`
   into `backend/src/main/resources/static/`, then `mvn -q package
   -DskipTests`
3. **eclipse-temurin:25-jre** stage — **the Debian-based tag, not
   `-jre-alpine`**: MongoDB's official server binaries need glibc and
   aren't supported on Alpine's musl libc, and this stage now needs a real
   `mongod` (see below). Install MongoDB Server from its official apt
   repo (`mongodb-org-server` package only — no need for the shell/tools
   packages, though `mongosh` is a nice-to-have if you want to poke at
   the embedded database directly). Copy the fat jar from stage 2 and an
   `entrypoint.sh` (below); expose `SERVER_PORT`;
   `ENTRYPOINT ["/entrypoint.sh"]`.

`entrypoint.sh` is what makes "just `docker run` and a port" actually
work — it resolves the two things that can't be Java config defaults
because the JVM needs them (or a working Mongo) before it even starts:

```sh
#!/bin/sh
set -e

# MongoDB: use MONGODB_URI if given, else run one locally in this container
if [ -z "$MONGODB_URI" ]; then
  mkdir -p /data/db
  mongod --dbpath /data/db --bind_ip 127.0.0.1 --fork --logpath /data/mongod.log
  export MONGODB_URI="mongodb://127.0.0.1:27017/saas-investigator"
  echo "WARNING: no MONGODB_URI set - started an embedded local MongoDB. Data lives in /data/db in this container and is LOST if the container is removed, unless you mount a volume at /data."
fi

# Credential encryption key: use the env var if given, else generate one once and keep it in /data
if [ -z "$CREDENTIAL_ENCRYPTION_KEY" ]; then
  KEY_FILE=/data/credential-key
  [ -f "$KEY_FILE" ] || head -c 32 /dev/urandom | base64 > "$KEY_FILE"
  export CREDENTIAL_ENCRYPTION_KEY="$(cat "$KEY_FILE")"
  echo "WARNING: no CREDENTIAL_ENCRYPTION_KEY set - generated one and stored it at $KEY_FILE. Fine for a local demo; if you later set MONGODB_URI to a real external database, set this explicitly too from that point on - otherwise anything encrypted now becomes unrecoverable the moment this container is recreated."
fi

exec java -jar app.jar
```

Both fallbacks live under the same `/data` directory on purpose: mount
one volume there and the embedded Mongo's data *and* the generated
encryption key persist together across restarts; mount nothing and both
are ephemeral together — never a mismatch where one outlives the other.
`JWT_SECRET` and `ANTHROPIC_API_KEY`/`OPENAI_API_KEY` don't need this
shell-level treatment — they're already handled inside the Spring app
itself once it's running (JWT SIGNING SECRET; LLM PROVIDERS &
CREDENTIALS), since by that point Mongo and the encryption key both
already exist.

Put together, **every** env var in CONFIG / ENV VARS is now optional.

Verify, from the most self-contained to the most explicit:

```sh
# As minimal as this gets: embedded Mongo, a generated encryption key,
# an auto-generated JWT secret, admin/admin seeded. Add your Anthropic
# key from the Secrets tab after logging in.
docker build -t saas-investigator .
docker run -p 8080:8080 saas-investigator
```
```sh
# Same, but durable across container restarts/recreation
docker run -p 8080:8080 -v saas-data:/data saas-investigator
```
```sh
# Explicit everywhere - what you'd actually want beyond a local demo
docker run -p 8080:8080 \
  -e ANTHROPIC_API_KEY=... -e MONGODB_URI=... \
  -e CREDENTIAL_ENCRYPTION_KEY=... \
  saas-investigator
```
```sh
# Quick-boot via a host-mounted key file instead of the UI or an env var
docker run -p 8080:8080 \
  -v ~/.anthropic/api-key.txt:/run/host-credentials/anthropic-api-key:ro \
  -e MONGODB_URI=... -e CREDENTIAL_ENCRYPTION_KEY=... \
  saas-investigator
```

`-p 8080:8080` is about the only flag that's genuinely irreducible here —
Docker has to be told to publish the port for the host to reach it at
all, that's not something the app can default its way around.

**Where the line actually is:** the fully self-contained mode (no
`MONGODB_URI`, no `CREDENTIAL_ENCRYPTION_KEY`) is a real, working
instance, not a toy — but it's a *single-container, single-instance*
mode. It can't be scaled to more than one replica (each would boot its
own disconnected embedded Mongo with its own generated key) and isn't
meant to be the way you'd run this anywhere data needs to outlive "I
deleted the container." That's exactly what MONGODB_URI/
CREDENTIAL_ENCRYPTION_KEY being explicit, and the docker-compose/k8s/ECS
samples below (which always set them), are for.

## DEPLOYMENT SAMPLES (`/deploy`)

- `/deploy/docker-compose.yml` — app image + a local `mongo` service with a
  named volume, wired via `MONGODB_URI`. This is the "MongoDB local to
  start" path — a real separate Mongo container, distinct from the
  single-container embedded fallback in PACKAGING; use this once you want
  the app and its database to scale or restart independently.
- `/deploy/k8s/deployment.yaml`, `service.yaml`, `configmap.yaml`,
  `secret.yaml` — Deployment referencing the built image, non-secret env via
  ConfigMap, `ANTHROPIC_API_KEY`/`CREDENTIAL_ENCRYPTION_KEY`/`MONGODB_URI`
  via Secret (`JWT_SECRET` included but commented-out/optional — omit it to
  let the app auto-generate and persist one instead), Prometheus scrape
  annotations on the pod template
  (`prometheus.io/scrape`, `/path`, `/port`). Add a commented-out
  ServiceMonitor example for Prometheus Operator users.
- `/deploy/ecs/task-definition.json` — Fargate task def: container pointing
  at the image, env vars sourced from Secrets Manager ARNs (placeholders),
  `awslogs` log driver to CloudWatch, health check hitting
  `/actuator/health`. Include a short `service.json` sample assuming an
  existing cluster/VPC.
- All samples use placeholder values (image registry, ARNs, cluster name)
  clearly marked as such — not meant to apply as-is.

## CI/CD: HARNESS PIPELINES (`/harness`)

Two sample pipeline YAML files, consuming exactly what's already been
built — the Dockerfile (PACKAGING) and the k8s manifests (DEPLOYMENT
SAMPLES) — rather than inventing a separate build/deploy story. Like the
k8s/ECS samples, these use clearly-marked placeholders (`YOUR_...`) for
account-specific identifiers and connectors; they're a starting point to
paste into Harness and adjust, not something that runs unmodified.

`/harness/ci-build-pipeline.yaml` — tests, then build-and-push, as two
genuinely separate concerns: the pipeline is a real gate on code quality
that the Dockerfile itself deliberately isn't (see TESTING).

```yaml
pipeline:
  name: SaaS Product Investigator - CI Build
  identifier: saas_investigator_ci_build
  projectIdentifier: YOUR_PROJECT_ID
  orgIdentifier: YOUR_ORG_ID
  stages:
    - stage:
        name: Build and Test
        identifier: build_and_test
        type: CI
        spec:
          cloneCodebase: true
          infrastructure:
            type: KubernetesDirect
            spec:
              connectorRef: YOUR_K8S_BUILD_INFRA_CONNECTOR
              namespace: harness-builds
          execution:
            steps:
              - step:
                  type: Run
                  name: Backend tests + JaCoCo
                  identifier: backend_tests
                  spec:
                    shell: Sh
                    command: |
                      cd backend
                      mvn -B test jacoco:report
                    reports:
                      type: JUnit
                      spec:
                        paths:
                          - backend/target/surefire-reports/*.xml
              - step:
                  type: Run
                  name: Frontend tests
                  identifier: frontend_tests
                  spec:
                    shell: Sh
                    command: |
                      cd frontend
                      npm ci
                      npm test -- --run
              - step:
                  type: BuildAndPushDockerRegistry
                  name: Build and push image
                  identifier: build_and_push
                  spec:
                    connectorRef: YOUR_DOCKER_REGISTRY_CONNECTOR
                    repo: YOUR_REGISTRY/saas-investigator
                    tags:
                      - <+pipeline.sequenceId>
                    dockerfile: Dockerfile
```

`/harness/build-and-deploy-k8s-pipeline.yaml` — the same build stage,
plus a Deployment stage that rolls the just-built image out using the
manifests from `/deploy/k8s`. This assumes a Harness Service pointing at
those manifests and an Environment/Infrastructure Definition pointing at
your cluster (set up once in Harness itself, referenced here rather than
redefined) — the pipeline is the sequencing, not where the cluster
connection details live.

```yaml
pipeline:
  name: SaaS Product Investigator - Build and Deploy (K8s)
  identifier: saas_investigator_build_and_deploy_k8s
  projectIdentifier: YOUR_PROJECT_ID
  orgIdentifier: YOUR_ORG_ID
  stages:
    - stage:
        name: Build and Test
        identifier: build_and_test
        type: CI
        spec:
          cloneCodebase: true
          infrastructure:
            type: KubernetesDirect
            spec:
              connectorRef: YOUR_K8S_BUILD_INFRA_CONNECTOR
              namespace: harness-builds
          execution:
            steps:
              - step:
                  type: Run
                  name: Backend tests + JaCoCo
                  identifier: backend_tests
                  spec:
                    shell: Sh
                    command: |
                      cd backend
                      mvn -B test jacoco:report
                    reports:
                      type: JUnit
                      spec:
                        paths:
                          - backend/target/surefire-reports/*.xml
              - step:
                  type: BuildAndPushDockerRegistry
                  name: Build and push image
                  identifier: build_and_push
                  spec:
                    connectorRef: YOUR_DOCKER_REGISTRY_CONNECTOR
                    repo: YOUR_REGISTRY/saas-investigator
                    tags:
                      - <+pipeline.sequenceId>
                    dockerfile: Dockerfile
    - stage:
        name: Deploy to Kubernetes
        identifier: deploy_to_kubernetes
        type: Deployment
        spec:
          deploymentType: Kubernetes
          service:
            serviceRef: YOUR_HARNESS_SERVICE
          environment:
            environmentRef: YOUR_HARNESS_ENVIRONMENT
            infrastructureDefinitions:
              - identifier: YOUR_INFRA_DEFINITION
          execution:
            steps:
              - step:
                  type: K8sDryRun
                  name: Dry Run
                  identifier: dry_run
              - step:
                  type: K8sRollingDeploy
                  name: Rolling Deployment
                  identifier: rolling_deployment
                  spec:
                    skipDryRun: false
            rollbackSteps:
              - step:
                  type: K8sRollingRollback
                  name: Rollback
                  identifier: rollback
        tags: {}
```

Both pipelines are starting points, not turnkey — the connector refs,
project/org identifiers, and (for the deploy pipeline) the Service/
Environment/Infrastructure Definition all need to exist in your Harness
account first and get swapped in for the placeholders. `/docs/DEPLOYMENT.md`
should say as much and link back to `/deploy/k8s` for what's actually
being deployed.

## DOCS FOLDER — treat as a living record, not a final writeup

Create and update `/docs` incrementally as each BUILD ORDER step completes —
add the relevant doc update to the definition of done for that step, don't
defer it to the end.

- `/README.md` (repo root) — branded, not a bare file list: a badge row,
  a one-paragraph overview, the true MVP quick-start inlined (not just
  linked), a documentation table, and a dedicated section on using Claude
  against this repo. Create this in step 1 alongside the first doc
  content and keep it current as pages are added; use this as the actual
  draft rather than reinventing the structure:

  ```markdown
  # 🔍 SaaS Product Investigator

  ![Java](https://img.shields.io/badge/Java-25-2F9E8F?style=flat-square)
  ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0-2F9E8F?style=flat-square)
  ![React](https://img.shields.io/badge/React-TypeScript-4CAF7D?style=flat-square)
  ![MongoDB](https://img.shields.io/badge/MongoDB-4CAF7D?style=flat-square)

  Point it at a SaaS product's docs, changelog, Jira/Atlassian instance,
  or any MCP server, and ask what changed — an LLM does the comparing, on
  whatever schedule and at whatever depth you ask for. Multi-user, multi-
  provider (Anthropic or OpenAI), and self-contained enough to boot with
  one command.

  ## Quick start (MVP)

  ```sh
  docker build -t saas-investigator .
  docker run -p 8080:8080 saas-investigator
  ```

  Open `http://localhost:8080`, log in as `admin` / `admin`, and add an
  Anthropic key from the Secrets tab in the Admin Console. That's a
  complete, working instance — embedded MongoDB and a generated
  encryption key included, no other setup required. Rotate the default
  admin password before this is anything but a local demo.

  Building or modifying this locally instead? Start with
  [`docs/GETTING_STARTED.md`](docs/GETTING_STARTED.md) — the same idea,
  broken into small, verifiable steps.

  ## Documentation

  | Doc | What it's for |
  |---|---|
  | [`docs/GETTING_STARTED.md`](docs/GETTING_STARTED.md) | Incremental local build/test/run checkpoints — start here if you're developing, not just running it |
  | [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | How the pieces fit together, with diagrams, and *why* the non-obvious choices were made |
  | [`docs/SETUP.md`](docs/SETUP.md) | Full environment variable reference, running locally with and without Docker, running the tests |
  | [`docs/API.md`](docs/API.md) | REST endpoint reference (also live at `/swagger-ui.html`) |
  | [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md) | Docker, docker-compose, Kubernetes, ECS, and the Harness CI/CD pipeline samples |
  | [`docs/decisions/`](docs/decisions/) | Short ADRs for architecture decisions made along the way |

  ## Using Claude against this repo

  This repo ships `.claude/skills/` — checklists Claude Code picks up
  automatically in this project, no special syntax needed. Just describe
  what you're doing in plain language and the right one applies:

  | You'd say to Claude... | It reaches for... |
  |---|---|
  | "Add a source type that pulls from a Notion page" | `add-source-connector` |
  | "Add an endpoint for admins to bulk-delete old audit logs" | `add-a-new-endpoint` |
  | "I need to deploy this to Azure Container Apps too" | `add-deployment-target` |
  | "Write tests for the crawler" | `write-tests-for-new-code` |
  | "It's time to turn on a real coverage gate" | `enable-coverage-gate` |
  | "We should rotate our Anthropic key" | `rotate-secrets` |
  | "Everyone got logged out and I don't know why" | `troubleshoot-running-instance` |

  Full list and categories (Architecture / Build / Deployment / Testing /
  Troubleshooting) in `.claude/skills/`. If Claude's about to do something
  that isn't covered by an existing skill but probably should be, that's
  usually a sign to add one — see the `update-docs` skill.

  ## Tech stack

  React + TypeScript + Vite · Spring Boot 4 + Java 25 · MongoDB · JWT
  auth · Anthropic + OpenAI (pluggable) · Prometheus/Micrometer · JUnit +
  Vitest + JaCoCo
  ```

- `/docs/GETTING_STARTED.md` — **incremental, checkpoint-based, not a
  reference** (SETUP.md is the reference; this is the "confirm it
  actually works, one small piece at a time" companion to it). The point
  is a person can run each checkpoint, see it pass, and trust the next
  layer they add sits on solid ground — rather than building the whole
  spec and only finding out at the end whether any of it works. Five
  checkpoints, each with an explicit "you'll know it worked when":
  1. **Tooling** — Java 25 JDK, Maven, Node 20+, Docker installed;
     `java -version`/`mvn -version`/`node -version`/`docker version` all
     resolve before doing anything else.
  2. **Backend boots on its own** — start a local Mongo (`docker compose
     up mongo` from `/deploy/docker-compose.yml`, or your own instance),
     set `MONGODB_URI`/`CREDENTIAL_ENCRYPTION_KEY` as real env vars (see
     the `local-dev-loop` skill — this doc and that skill should stay in
     sync, they're the same knowledge for two audiences), `cd backend &&
     mvn spring-boot:run`. **You'll know it worked when:**
     `curl localhost:8080/actuator/health` returns `{"status":"UP"}`.
     Nothing else matters yet — don't move on until this is boring.
  3. **Full stack talks to itself** — `cd frontend && npm install && npm
     run dev`, open the dev server, log in as `admin`/`admin`. **You'll
     know it worked when:** you land on an empty Dashboard. No products
     yet, and that's correct.
  4. **The MVP loop — one product, one source, one run** — create a
     SaaS Product with a single Website source, add an Anthropic key
     (Secrets tab or env var), click Run. **You'll know it worked when:**
     a change report with a real `overallSummary` appears in History.
     This is the smallest version of the actual core idea; everything
     else in the app — Compare, the other 4 source types, exports, the
     Admin Console, multi-provider — builds on this exact loop, so it's
     worth making this one rock solid before adding anything on top.
  5. **Tests, then the container matches the dev loop** — `mvn test`
     (open the JaCoCo report), `npm test -- --run`, both green; then
     `docker build -t saas-investigator .` and `docker run -p 8080:8080
     saas-investigator` (the fully bare quick-boot from PACKAGING).
     **You'll know it worked when:** the container gives you the same
     login and the same MVP loop as checkpoints 3–4, just self-contained.
     If it diverges, that's a real bug worth chasing before building
     further — the container is supposed to be the same app, not a
     different one.

  Closing note in the doc itself: build the rest of BUILD ORDER
  incrementally from here, re-running checkpoints 2–5 after each step —
  that's the actual point of having them as checkpoints rather than one
  big definition of done, cheap and repeatable rather than discovering
  something broke only once everything else is already built on top of
  it.

- `/docs/ARCHITECTURE.md` — system overview, component responsibilities,
  data model, and WHY behind non-obvious choices (e.g. LLM-driven comparison
  instead of hardcoded diffing, JWT over sessions, business metrics beyond
  the generic HTTP layer). Include three diagrams as Mermaid fenced code
  blocks (` ```mermaid `), since GitHub and most doc viewers render these
  natively without an image file to keep in sync:
  - a component/flow diagram: Frontend → Backend → {MongoDB, MCP servers,
    Anthropic/OpenAI}
  - an `erDiagram` for the data model — how `users`, `saas_products`,
    `snapshots`, `change_reports`, the credential collections, and
    `audit_logs` relate
  - a `sequenceDiagram` for a run: Frontend → `POST /run` → async task →
    SSE events back to Frontend → final `change_report`
- `/docs/SETUP.md` — prerequisites, full env var table, how to run locally
  (with and without docker), how the default admin/admin seed works and a
  clear callout that it must be rotated before any non-local use, how to
  run the test suite (`mvn test`, `npm test`) and open the JaCoCo report,
  and an explicit note that `docker build`/`docker run` never run tests
  by design (see TESTING) — the quick-start path always works regardless
  of test state
- `/docs/API.md` — endpoint reference: method, path, required role,
  request/response shape
- `/docs/DEPLOYMENT.md` — single-image build/run instructions,
  docker-compose usage, what each k8s manifest does and how to apply it,
  what the ECS task def assumes, exact metric names/tags/meaning and how
  Prometheus should scrape them, and the `/actuator` unauthenticated-by-
  design note. Include a Mermaid flow diagram of deployment topology
  (container/pod, MongoDB, Prometheus scrape target) for each of the
  docker-compose/k8s/ECS paths. Also cover the two `/harness` pipeline
  samples: what each connector placeholder needs to become, and that the
  deploy pipeline additionally needs a Service/Environment/Infrastructure
  Definition set up in Harness first (see CI/CD: HARNESS PIPELINES).
- `/docs/decisions/000N-<slug>.md` — one short ADR file per meaningful
  architecture decision made during the build (context, decision,
  consequences), created as decisions happen

## PROJECT SKILLS (Claude Code skills, auto-discovered on future sessions)

Create these under `.claude/skills/<name>/SKILL.md` in this repo, grouped
here by what they're for — the folders themselves are flat, this grouping
is just for finding the right one:

**Architecture** — extending how the app is put together:
1. **add-source-connector** — checklist for adding a new source type:
   backend connector interface + registration, `SourceConfig` type union
   update, frontend form field addition, `/docs/ARCHITECTURE.md` update.
2. **manage-roles-and-permissions** — pattern for adding a role or changing
   endpoint permissions: Spring Security config location, frontend route
   guard location, what to update in both places together.
3. **add-llm-provider** — checklist for adding a new `LlmProvider`
   implementation: interface methods to implement, how MCP-type sources
   map onto that provider's tool-calling API, where credentials/env vars
   get added, what changes in the Account Settings UI and provider
   resolution logic.
4. **update-docs** — trigger: any structural or architectural change.
   Reminds to update `/docs/ARCHITECTURE.md` and add an ADR entry in
   `/docs/decisions/`.

**Build** — the day-to-day loop of adding and running code:
5. **local-dev-loop** — how to actually iterate on this in VS Code without
   rebuilding a container each time: start a standalone MongoDB (e.g.
   `docker compose up mongo` from `/deploy/docker-compose.yml`, or your
   own local instance), set `MONGODB_URI`/`CREDENTIAL_ENCRYPTION_KEY`
   directly as run-config env vars — the entrypoint.sh fallbacks in
   PACKAGING are Docker-only, local dev needs these set for real —
   `mvn spring-boot:run` for the backend with hot reload, `npm run dev`
   for the frontend against it, then log in as admin/admin.
6. **add-a-new-endpoint** — checklist for a new REST endpoint: controller
   method + `@PreAuthorize` role, service method + an `AuditService.log`
   call (AUDIT LOG), `@Operation`/`@ApiResponse` annotations so it shows
   up in `/swagger-ui.html`, a row added to the REST API table +
   `/docs/API.md`, a MockMvc test asserting role enforcement (TESTING),
   Javadoc (CODE DOCUMENTATION) — one checklist covering everything a new
   endpoint actually touches, so nothing gets half-added.

**Deployment** — getting it running somewhere real:
7. **add-deployment-target** — pattern for adding a new deploy target
   (beyond k8s/ECS): what config is environment-specific vs shared, where
   samples live in `/deploy`, what `/docs/DEPLOYMENT.md` needs updated,
   and whether `/harness/build-and-deploy-k8s-pipeline.yaml` needs a
   parallel pipeline for the new target.
8. **rotate-secrets** — three different secrets, three different
   consequences, don't treat them the same: rotating `JWT_SECRET` (env
   var or the Admin Console override) immediately signs out every active
   session, no way around that (JWT SIGNING SECRET). Rotating
   `CREDENTIAL_ENCRYPTION_KEY` is **not** a simple swap — every value
   already encrypted with the old key (LLM API keys, MCP `authToken`s,
   the persisted JWT secret) must be decrypted with the old key and
   re-encrypted with the new one *first*, or it's permanently
   unrecoverable the moment the old key is gone (SECRETS RESOLUTION
   MODEL). Rotating a provider API key (Anthropic/OpenAI) is the genuinely
   low-stakes one — just update it via Secrets or personal BYOK; nothing
   else depends on the old value.

**Testing:**
9. **write-tests-for-new-code** — checklist for what a new piece of code
   needs (see TESTING): a unit test if it's service-layer logic worth
   testing in isolation, a MockMvc test if it's a new endpoint (role
   enforcement especially), a Testcontainers test if it's a new Mongo
   query, a Vitest/RTL test if it's a new component — and a reminder that
   none of this changes the Dockerfile's `-DskipTests`.
10. **enable-coverage-gate** — for the day the suite has matured enough
    to act on (TESTING already flags this as deliberately deferred, not
    forgotten): check the current JaCoCo numbers, pick a sensible
    threshold per module rather than one blanket number, wire
    `jacoco-maven-plugin`'s `check` goal into the Maven `verify` phase,
    and update `/harness/ci-build-pipeline.yaml`'s test step to run `mvn
    verify` instead of `mvn test` so the threshold actually gates CI —
    the Dockerfile still never touches any of this.

**Troubleshooting** — for a running instance that's misbehaving, not for
writing new code:
11. **troubleshoot-running-instance** — a diagnostic order, not a
    grab-bag: (1) `/actuator/health` (public, basic — is it up at all)
    and, if you can log in, `/api/admin/health` (detailed — Mongo, each
    provider's resolvability, disk); (2) the Admin Console Overview's run
    success rate and recent runs — a sudden dip usually points at a
    provider or crawl target, not the app itself; (3) the Audit Log for
    anything that would explain a sudden change — a JWT override/clear
    explains "everyone got logged out," a system-credential change
    explains "runs stopped working," a settings change explains "sources
    started crawling differently"; (4) container logs for the
    entrypoint.sh `WARNING` lines — is this instance running on an
    embedded Mongo or a generated encryption key it shouldn't be, a sign
    `MONGODB_URI`/`CREDENTIAL_ENCRYPTION_KEY` didn't actually reach the
    container; (5) `/actuator/prometheus`'s business metrics
    (`saas_run_total`, `saas_source_fetch_errors_total`) tagged by
    `sourceType`/`runType` to narrow a problem to one feature rather than
    the whole app. A few symptom-to-cause pairs worth knowing without
    looking them up: "run fails immediately, 'no provider configured'" →
    check Secrets/env vars/host-mount; "a Website source contributes
    nothing" → check `robots.txt`/`maxDepth`/`maxPages` and that run's
    SSE detail log; "an MCP source is silently skipped" → check its
    URL/`authToken` and whether it's actually reachable from wherever
    this container runs.

Keep each SKILL.md short and concrete — a checklist, not prose.

## NON-FUNCTIONAL

- `CREDENTIAL_ENCRYPTION_KEY` and `MONGODB_URI` are never stored in Mongo
  or set via the Admin Console — see SECRETS RESOLUTION MODEL for why
  that's structural. They're either supplied explicitly (env
  var/properties/K8s `Secret`/ECS secret) or, if neither is, generated/
  started locally by the container itself (see PACKAGING) — that local
  fallback is a single-container demo convenience, not a substitute for
  setting them explicitly anywhere data needs to survive the container
  being recreated. Everything else covered in SECRETS RESOLUTION MODEL
  (LLM provider keys, the JWT signing secret, per-source MCP
  `authToken`s) can be set either explicitly or through the Admin Console.
- The embedded-MongoDB/generated-encryption-key fallback in PACKAGING is
  single-instance only — it can't be scaled to more than one replica,
  since each would start its own disconnected local Mongo with its own
  generated key. Anything beyond one local container needs an explicit
  `MONGODB_URI` (and, for consistency, `CREDENTIAL_ENCRYPTION_KEY`).
- CORS enabled for local Vite dev server
- Basic URL/required-field validation on source config
- Website/SaaS App URL crawling stays same-origin, respects `robots.txt`,
  enforces `maxDepth`/`maxPages` (with sane defaults) and a per-page fetch
  timeout, and de-dupes visited URLs — this is a safety boundary, not just
  a performance knob, so don't let a misconfigured source crawl unbounded
- Passwords never stored or logged in plaintext; admins can reset a
  password but never view the current one (see USERS & ROLES)
- LLM API keys, MCP `authToken`s, and the JWT signing secret are all
  encrypted at rest with `CREDENTIAL_ENCRYPTION_KEY` and only ever
  returned to the frontend as a masked `last4` (or, for the JWT secret,
  not returned at all — just its configured/source status) — never in
  full, decrypted or not
- Audit log `details` never contains a password, an API key, an
  `authToken`, or the JWT signing secret, encrypted or not
- `/actuator/prometheus` is intentionally unauthenticated; access must be
  restricted at the network/ingress layer, not the app layer

## BUILD ORDER

Javadoc/TSDoc and inline comments (see CODE DOCUMENTATION) are written as
part of each step below, not as a separate cleanup pass at the end. Tests
(see TESTING) follow the same rule — write them for the code each step
introduces, not in one pass after everything else is "done."

1. Backend: User model (firstName/lastName/username/email/passwordHash) +
   Mongo repo + admin seed + BCrypt + JWT auth (login endpoint, security
   filter, role-based `@PreAuthorize`, `/actuator` permitted without JWT);
   JWT secret resolution (env var if set, else generate + persist to
   `system_config` on first boot — see JWT SIGNING SECRET); global
   `@RestControllerAdvice` exception handling (see ERROR HANDLING) set up
   now so every later step can rely on it; JUnit/Mockito/Testcontainers +
   JaCoCo set up in the Maven build (`test` phase, not the `package`
   Docker uses) alongside the auth tests for this step; `/README.md` +
   `/docs/ARCHITECTURE.md` (with its first diagram)/`/docs/SETUP.md`
   started; `/docs/GETTING_STARTED.md` started too — checkpoints 1
   (tooling) and 2 (backend boots, `/actuator/health` is `UP`) are
   verifiable right now, so verify them for real rather than just writing
   them down
2. Backend: AuditService + `audit_logs` model/repo + `/api/audit-logs`
   endpoint; wire `AUTH_LOGIN_SUCCESS`/`AUTH_LOGIN_FAILURE` logging into
   step 1's auth flow now, since every later step adds more audit calls
3. Backend: SaasProduct/Snapshot/ChangeReport models + Mongo repos +
   indexes (see PERFORMANCE)
4. Backend: crawler for Website/SaaS App URL sources — same-origin link
   following, `robots.txt` check, `maxDepth`/`maxPages` limits, bounded
   concurrent page fetches (see PERFORMANCE), per-page text extraction,
   concatenation with page-URL headers, total-length cap
5. Backend: user password reset endpoint + personal BYOK credential store
   (`user_llm_credentials`, AES encryption, the `/api/users/me/credentials`
   endpoints + preferred-provider endpoint) + the quick-boot host-credential
   loader (reads `/run/host-credentials/anthropic-api-key` at startup,
   respects `IGNORE_HOST_CREDENTIALS`) + admin system-credential override
   (`system_llm_credentials`, `/api/admin/system-credentials` endpoints) +
   the JWT secret admin override (`/api/admin/jwt-secret` endpoints,
   `system_config` key `JWT_SIGNING_SECRET_OVERRIDE`, wired into the JWT
   filter's key resolution ahead of the env var/auto-generated value) +
   encrypting `SourceConfig.authToken` at rest wherever sources are
   saved — all wired to audit logging
6. Backend: `LlmProvider` interface + `AnthropicLlmProvider` +
   `OpenAiLlmProvider` implementations, full provider resolution logic
   (personal → admin override → env var → host mount → graceful failure),
   the `analysisDepth`-specific prompt instructions from ANALYSIS DEPTH,
   orchestration as an async task emitting SSE progress events (the
   `runs/{runId}/events` endpoint) rather than a blocking call, structured
   JSON parsing, persistence + the `/ask` endpoint streaming tokens over
   SSE, instrumented with the custom metrics from OBSERVABILITY (including
   the `analysisDepth` tag) and audit logging; the `/compare` endpoint
   sharing that same async/SSE/persistence plumbing but sourcing
   before/after state per CUSTOM DATE-RANGE COMPARE
7. Backend: REST controllers above with role enforcement and pagination on
   `saas_products`/`reports` list endpoints (see PERFORMANCE); wire up
   Actuator + Micrometer Prometheus registry + springdoc-openapi for
   `/swagger-ui.html`; Admin Console backend — `/api/admin/stats` (Mongo
   aggregation), `/api/admin/health` (wrapping Spring's own
   `HealthIndicator` beans plus the provider-resolvable checks),
   `/api/admin/settings` (crawl defaults in `system_config`), the Data
   Explorer's generic collection/document endpoints with server-side
   secret-field masking (see ADMIN CONSOLE); the report export endpoint
   (`openhtmltopdf` for PDF, Apache POI for DOCX — see REPORT EXPORT)
8. Frontend: design tokens (light/dark CSS variables) + theme toggle;
   TanStack Query setup; route-level code-splitting (see PERFORMANCE);
   responsive breakpoints/sidebar-to-drawer behavior (see REACTIVITY &
   RESPONSIVE DESIGN); Login page + auth context/JWT storage + route
   guards; a top-level error boundary and shared toast/error-display
   component used everywhere else
9. Frontend: Dashboard + New/Edit SaaS Product + Product detail (source
   config, Short/Regular/Nuclear depth control, Run live-execution view
   wired to the SSE events endpoint and rendering real `detail` strings,
   Compare tab with the calendar date-range picker + "Since last run"
   preset, History timeline w/ category badges + depth badge + PDF/DOCX
   export action, Ask query bar wired to the SSE token stream) + Account
   Settings page (personal BYOK keys + preferred provider) + the
   consolidated Admin Console (Overview stats/health, Users with password
   reset, Secrets with the JWT-change confirmation dialog, Audit Log,
   Settings, Data Explorer with masked-field chips) — all responsive down
   to phone width, with skeleton loading states on the list-heavy pages.
   `/docs/GETTING_STARTED.md` checkpoint 3 (login → empty Dashboard) is
   verifiable now — confirm it for real.
10. Seed one example SaaS Product (mock/placeholder MCP URLs are fine) and
    verify a full run + ask flow end to end, as both roles, watching the
    live execution view show real per-page/per-tool detail strings (not a
    generic "Thinking...") from real SSE events — this is also
    `/docs/GETTING_STARTED.md` checkpoint 4, the MVP loop, so confirm it
    with a single fresh product/source/run too, not only the seeded one;
    run the same source at
    Short, Regular, and Nuclear depth and confirm the resulting reports
    are actually different in length/detail, not just labeled differently;
    export a report as both PDF and DOCX and open both to confirm they're
    legible and correctly formatted, not just non-empty files; run it at
    least twice so a custom-range Compare between those two dates has
    real data to work with, and confirm the `mcpHistoryLimited` caveat
    shows up correctly for MCP-type sources; confirm the Admin Console
    Overview shows correct stats and health status, and that updating
    crawl defaults in Settings actually changes what a new source without
    overrides uses; in Data Explorer, browse a few collections, edit a
    non-secret field on a document and confirm it saves, then confirm
    attempting to edit a masked field (e.g. `passwordHash`) is rejected
    rather than silently accepted; set a JWT secret override in the
    Secrets tab and confirm it immediately signs everyone out (including
    the admin who set it), then clear it and confirm the same; add a
    source with an MCP `authToken` and confirm `GET
    /api/saas-products/{id}` comes back with it masked, never in full;
    confirm `/actuator/prometheus` shows the expected metric names;
    confirm dark mode; confirm audit log entries appear for logins, a run,
    a compare, a password reset, a settings change, the JWT override, and
    a Data Explorer edit; confirm graceful error messages on a few forced
    failures (bad MCP URL, unreachable crawl target, no provider
    configured, an invalid date range); confirm the app boots with no
    `JWT_SECRET` set, and that restarting reuses the same persisted secret
    rather than invalidating sessions
11. Run the full test suite (`mvn test`, `npm test`) and confirm it
    passes, open the generated JaCoCo report; then write the multi-stage
    Dockerfile (Debian-based final stage with `mongodb-org-server`
    installed) and `entrypoint.sh` (embedded-Mongo start + local
    encryption-key generation when those env vars are unset — see
    PACKAGING); verify `docker build` and all four `docker run` variants
    from PACKAGING actually work: fully bare, bare with a `/data` volume,
    fully explicit, and the host-mounted-key quick-boot — confirming the
    build succeeds regardless of test results, since `-DskipTests` means
    it never even looks. This is `/docs/GETTING_STARTED.md` checkpoint 5
    — fill in that checkpoint's real commands/output now that it's
    actually been run, rather than writing it from what you'd expect to
    happen.
12. Write `/deploy/k8s` and `/deploy/ecs` samples, then `/harness/ci-
    build-pipeline.yaml` and `/harness/build-and-deploy-k8s-pipeline.yaml`
    (see CI/CD: HARNESS PIPELINES), which reference them
13. Finalize `/docs` (including `/docs/GETTING_STARTED.md` — re-walk all
    5 checkpoints once more against the finished app before calling it
    done; a checkpoint doc that was only ever true partway through the
    build isn't worth much), `/README.md` (should already be mostly
    written from steps 1–10), and `.claude/skills/`

## DEFINITION OF DONE

I can log in as admin, create a SaaS Product with multiple sources across
all 5 types — including two of the same type (e.g. two separate Docs MCP
servers) — click Run and watch a live step-by-step execution view driven
by real SSE events showing specific detail (which page is crawling, which
MCP tool is being called) rather than a generic "please wait", confirm a
Website/SaaS App URL source actually crawled more than one page, see a
structured LLM-generated change report persisted in Mongo and rendered in
the UI with category badges, ask an ad-hoc question about it and watch the
answer stream in token by token, toggle dark mode and see a navy-based
theme (not pure black), create a read-only user, reset that user's
password as admin (and confirm there is no way to view their current
password), add my own Anthropic key in Account Settings and confirm a run
uses it, then add an OpenAI key too, switch my preferred provider to
OpenAI, and confirm a run/ask actually uses OpenAI instead (e.g. via the
audit log or a log line showing which provider handled it), confirm that
user can run/view/ask but not edit config, view the audit log and see
entries for logins/runs/compares/password resets, and re-running later
correctly reflects only what's new since the prior snapshot. I can open the Compare tab, see the
product's last-run metadata (when it ran and which sources it included)
before I pick anything, pick two arbitrary calendar dates spanning two
past runs, and get a fresh LLM-generated comparison anchored at exactly
those two points for crawled sources, with a visible caveat on any
MCP-type source whose portion came from aggregated history rather than a
live diff; picking an invalid range (from after to, or a date before any
data exists) fails with a clear message instead of a generic error. I can
start the container with **no**
`JWT_SECRET` and **no** `ANTHROPIC_API_KEY` set (just a key file mounted at
`/run/host-credentials/anthropic-api-key`) and have a run succeed; a
restart reuses the same auto-generated JWT secret rather than logging
everyone out; setting `IGNORE_HOST_CREDENTIALS=true` makes that same run
fail gracefully with a clear message instead; setting an admin
system-credential override in the UI takes priority over both. I can run
`docker run -p 8080:8080 saas-investigator` — nothing else, no flags, no
env vars, no volumes — and get a fully working instance: an embedded
MongoDB started inside the container, a generated encryption key, an
auto-generated JWT secret, admin/admin seeded, ready to take an Anthropic
key through the Secrets tab. Re-running that exact command again starts a
fresh, empty instance (expected, since nothing was mounted); adding
`-v saas-data:/data` makes the same data persist across restarts instead.
The whole app — sidebar, dashboard, product detail, forms — is usable at
a phone width, not just desktop. Forcing a few failures (bad
MCP URL, unreachable crawl target, malformed host-credential file)
produces clear error messages rather than crashes or raw stack traces,
both in the API responses and in the UI.
`/actuator/prometheus` exposes RED metrics for both the generic HTTP layer
and the run/ask pipeline. `/swagger-ui.html` shows the live API docs.
`/deploy/k8s` and `/deploy/ecs` contain valid, documented sample manifests.
`/harness/ci-build-pipeline.yaml` and
`/harness/build-and-deploy-k8s-pipeline.yaml` exist, reference the same
Dockerfile and k8s manifests already built, and their placeholders are
documented in `/docs/DEPLOYMENT.md`. `mvn test` and `npm test` both pass
and generate a JaCoCo report I can open, covering the logic called out in
TESTING; deleting/breaking a test on purpose still lets `docker build &&
docker run` boot a working app, because the Dockerfile never runs the
suite in the first place. The Admin Console's Overview shows accurate
usage stats and a health status distinct from the public
`/actuator/health`, its Secrets tab can set and clear both the LLM
provider overrides and the JWT secret override (the latter visibly
signing everyone out when changed), changing the crawl defaults in its
Settings tab actually takes effect on the next run of a source that
doesn't override them, and its Data Explorer can browse every collection
and edit non-secret fields while refusing edits to anything masked. Any
report in the History timeline can be run at Short, Regular, or Nuclear
depth with genuinely different output, and exported as a formatted PDF or
DOCX on demand. A brand new person (or me, months from now) can follow
`/docs/GETTING_STARTED.md` checkpoint by checkpoint from a clean clone and
land on a working MVP loop before ever reading the rest of the spec.
`/README.md`, `/docs`, and `.claude/skills` exist, the README links to
every docs page, `/docs/ARCHITECTURE.md` and `/docs/DEPLOYMENT.md` each
render their Mermaid diagrams correctly, and public classes/methods across
the codebase have Javadoc/TSDoc — all current with what was actually
built.
current with what was actually built.
