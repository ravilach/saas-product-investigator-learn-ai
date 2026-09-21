# EVAL_LOG

One entry per build run, rolled up from the per-session files in [`evals/`](evals/). Those files are
the raw log this was built from — they stay as they are, and where this summary and a session file
disagree, the session file is the record.

The convention this file follows was derived from [`docs/evals-tracking-PROMPT.md`](docs/evals-tracking-PROMPT.md),
which asks for a rollup per run: wall-clock time, interventions with the interesting ones named, the
final diff against the first commit for scale, and the outcome against the spec's Definition of Done.
That prompt refers to "that file's existing template" — there was none, so this is it.

---

## Run 1 — 2026-09-20 → 2026-09-21 · **closed, all 13 steps**

Built from [`docs/saas-product-investigator-BUILD-PROMPT.md`](docs/saas-product-investigator-BUILD-PROMPT.md)
in Claude Code, model Opus 5. Spec commit `a912316`, content hash
`80416c78de8e251f77cc9353727138fd21676427` — **provably unedited across the whole build**, so every
step ran against the same text.

> **This entry was written as an interim one and closed on 2026-09-21 at step 13.** What changed in
> the closing pass is worth naming rather than folding in silently: step 13's requirement to re-walk
> all five `GETTING_STARTED.md` checkpoints *against the finished app* was the first time anything in
> this build was driven through a real browser, and it **found three backend defects that 769 tests
> and a 40/40 API acceptance pass had all missed** (see the last row of the interventions table and
> [`evals/session-2026-09-21-1131.md`](evals/session-2026-09-21-1131.md)). Two of the five open
> Definition-of-Done clauses closed in the same pass. The earlier interim text recorded those clauses
> as "needs a human looking at the running app" — which was true, and was also the reason they stayed
> open for the entire build.

### Scale

| | |
|---|---|
| **Wall-clock** | **≈12h 27m** across 4 session files / 14 Claude Code sessions: `10:46`–`19:28` (8h 42m, 8 sessions), `19:39`–`20:38` (59m, 4 sessions), then day 2 `10:30`–`11:25` (55m, 1 session) and `11:31`–`13:22` (1h 51m, 1 session — step 13) |
| **Diff vs. first commit** (`0fcae72`, an effectively empty repo) | `379 files changed, 57008 insertions(+), 8 deletions(-)` tracked, plus 12 untracked files (1,571 lines) and 15 modified files from step 13 still in the working tree |
| **Composition** | 392 files: 167 main Java classes, 71 backend test classes, 70 frontend `.ts`/`.tsx`, 8 ADRs, 7 docs pages, 11 Claude skills, 3 verification harnesses, 2 Harness pipelines, k8s + ECS + compose manifests |
| **Tests** | backend **801** (0 failures, 0 errors, 0 skipped), JaCoCo over 173 classes; frontend **97** in 11 files. Measured 2026-09-21 `12:50`–`12:57`, after the step 13 fixes. At the interim entry they were 769 over 170 classes, and 80 in 9 files. |
| **Context compactions** | **26** (23 + 0 + 2 + 1). The dominant operational cost of day 1, and near-zero once work was scoped to one artifact at a time. |

### Interventions — 20 substantive human messages, 7 interrupts, 1 permission denial

The count is the least interesting part. Five changed the outcome:

1. **`17:53` day 1 — _"Other agent was taking too long"_.** The human abandoned a 5h29m unattended
   session sitting at 7 of 13 steps and restarted the work in a fresh one. Steps 8 and 9 then took
   ~90 minutes. That contrast is the single most useful measurement in this log: **the long
   unsupervised run was not productive in proportion to its length**, and its zero interventions were
   evidence of absent supervision, not of a clean run.
2. **`17:57` day 1 — _"We never built the Swagger UI HTML"_.** A human premise that was factually
   wrong. Claude disagreed with evidence instead of building a duplicate. Worth naming because the
   failure mode it avoids — agreeing and building the redundant thing — leaves no trace when it
   happens.
3. **`20:03`–`20:27` day 1 — the container defects.** Both found by the human, neither by Claude's
   verification: a `docker build` that only passed behind a `--secret` CA mount the documented
   command never uses, and a vendored signing key that was `.gitignore`'d — which would have broken
   the build for everyone who cloned the repo. **Local verification is structurally incapable of
   catching the second one**, and the first was a cached exit-0 being reported as a pass.
4. **`10:37` day 2 — a denied permission prompt.** Claude asked to inspect `deploy/.env` and grep the
   environment for provider keys. Denied. The refusal is what forced the mock-provider approach
   below; recorded because it changed the work rather than just costing a turn.
5. **`10:39` day 2 — _"Try to mock it "_.** Four words, asked in answer to "how should I get a
   provider key for step 10". It made the acceptance pass answerable on a day with no key, and it
   permanently bounds what that pass can mean. Best return on any instruction in the build.

**The single highest-leverage instruction in this build came from neither of those five, and from no
human turn at all.** It is one clause of prompt 1's BUILD ORDER step 13: *"re-walk all 5 checkpoints
once more against the finished app before calling it done; a checkpoint doc that was only ever true
partway through the build isn't worth much."* Honouring it literally, on day 2 at `12:10`, is what
found three backend defects that 769 tests and a 40/40 API acceptance pass had all passed over —
including every SSE stream in the application being truncated rather than terminated. The human's
contribution that turn was four words of scheduling (_"Let's execute step 13 now, repo is clean."_);
the leverage was in the spec, written before any code existed. That is an argument for putting
verification requirements in the prompt rather than hoping they emerge.

Not on the timeline but real: `.claude/settings.local.json` accumulated ~126 allow-rules, each of
which required a human "yes, don't ask again" at some point. No individual timestamps exist for them.

### What the build produced, and how each part is known to work

| Verified by | Covers |
|---|---|
| **Automated suites** — 801 backend / 97 frontend | Unit and integration logic, including Testcontainers-backed Mongo paths |
| **`tools/verify-step10.mjs`, 40/40 green against the container image** | The whole pipeline over the API: crawl, `robots.txt` obedience, prompt construction, the provider call, structured-output parsing, persistence, SSE narration, PDF and DOCX rendering (content read back out, not byte counts), Compare, RBAC, secret masking, auditing, JWT-secret persistence across restart, graceful failure with no provider configured |
| **`tools/verify-checkpoint3.mjs`, 11/11 in a real browser** | What only exists once rendered: route guards and the bounce-and-return through login, the theme toggle's effect on the *computed* background, the dark palette being navy rather than black, a **measured** 16.07:1 contrast ratio, and the shell at 390px with an overflow check |
| **`tools/verify-checkpoint4.mjs`, 11/11 in a real browser** | The MVP loop through the UI: the creation form, the live execution view rendering the backend's own detail strings *as they arrive* (via a `MutationObserver`, because a 100ms poll misses a crawl step that lives for tens of milliseconds), seven distinct category badges, History expansion, and a streamed answer growing across 6 distinct lengths |
| **Five container runs, day 1 `19:39`–`20:38`** | Embedded Mongo, external Mongo, a named volume, a read-only root filesystem, a host-mounted credential file |
| **Nothing** | Analysis quality, and the OpenAI provider end to end. Named here rather than omitted — see below |

The browser rows are the last two added, on the final day, and they are the ones that earned their
keep: between them they closed two Definition-of-Done clauses that had been open for the whole build
and **found four defects** — three in the backend, plus a frontend enum-case mismatch that had every
category badge rendering in one colour where the spec asks for seven. Everything above them had been
green while all four were live.

### Outcome against the Definition of Done

**Most of it is met. Three clauses are not, and one is half-met.** Listing them rather than rounding
up, since a Definition of Done that has been rounded up is not one. The interim version of this entry
listed five; two closed on the final day, and it is worth being precise about *how* they closed,
because "we looked at it" and "we measured it" are different claims.

- ~~**Anything about rendered appearance.**~~ **Closed 2026-09-21**, by measurement and then by eye.
  Dark mode is navy rather than black (`rgb(10,22,34)`, blue channel dominant) with body text at
  **16.07:1**; the category badges render as **seven distinct pills** in both the report and the
  History timeline; the live execution view renders the backend's own strings as they arrive
  (`Crawling http://…/changelog (page 1 of ~20)`, `Analyzing 1 source as a first baseline…`), captured
  by a `MutationObserver` rather than a poll. The interim entry recorded this as "structure, not
  appearance" — which was accurate, and understated the gap: when it was finally looked at, **every
  badge was one colour**, because the frontend keyed on `FEATURE` while the wire format is `feature`.
  Structural verification had been green throughout.
- 🟡 **Phone-width usability of the finished screens — half.** The shell and Dashboard are now verified
  at 390px: the sidebar parks off-canvas at `x=-248`, the hamburger slides it back, and
  `scrollWidth == clientWidth` so nothing has to be panned. The **product-detail and admin screens
  have not been walked at phone width**, and those are the dense ones — a six-tab console and a page
  with a run view, a history timeline and a comparison on it.
- **The OpenAI provider path.** The DoD requires adding an OpenAI key, switching
  `preferredLlmProvider`, and confirming a run actually used OpenAI. The mock is Anthropic-shaped and
  the harness does not touch OpenAI at all. The class has unit tests; the switch has never run end to
  end. Cheapest outstanding gap — the `OPENAI_BASE_URL` seam added on day 2 is the other half of the
  same knob.
- **Analysis quality, and "re-running later correctly reflects only what's new".** Structurally
  exercised — the fixture site advances between runs and the second run has something real to find —
  but with a stub provider there is no analysis to judge. This needs a real model and a human reading
  the output, and no harness can substitute for it. The one clause no amount of further work *here*
  can close.
- **`GETTING_STARTED.md` from a clean clone.** All five checkpoints have now been walked against one
  finished build and every number in that document's status table is first-hand. Nobody has followed
  the document from a fresh `git clone` on a machine that never built this, which is the actual claim
  it makes — and day 1 produced direct evidence that this gap is not theoretical: the vendored signing
  key that was `.gitignore`'d would have broken the build for every clone while working perfectly
  here.

Everything else in the DoD is met and has first-hand evidence behind it: the bare
`docker run -p 8080:8080` instance with no flags, volume persistence with `-v saas-data:/data`, the
no-`JWT_SECRET`/no-`ANTHROPIC_API_KEY`/host-mounted-key boot, `IGNORE_HOST_CREDENTIALS` failing
gracefully, admin override precedence, forced-failure messages, `/actuator/prometheus` RED metrics,
`/swagger-ui.html`, the k8s/ECS samples and both Harness pipelines, JaCoCo, the Admin Console's
stats/health/Secrets/Settings/Data Explorer behaviour including refused edits to masked fields, the
three depths producing genuinely different output, and PDF/DOCX export.

One clause is met by construction rather than by experiment, and the distinction is worth keeping:
"deleting or breaking a test on purpose still lets `docker build && docker run` boot a working app".
The image is built with `mvn package -DskipTests`, so the suite is never invoked and a broken test
cannot affect it. Nobody has actually broken a test and rebuilt to watch it work.

### What this build's own record says about building this way

Four things, each supported by a timestamp rather than an impression:

- **Unsupervised length is not throughput.** 5h29m and one human message produced 7 of 13 steps;
  ~90 supervised minutes produced 2 more. The eval habit is what makes that comparable at all.
- **Claude's verification missed what a human caught, twice, in the same hour** — and both misses
  were of the same shape: a command that passed for a reason the documented command would not
  reproduce. Neither was a coding error.
- **Writing the acceptance pass as a script rather than a conversation paid for itself twice.** It
  found a real user-facing bug no unit test could reach (a Compare ending "today" rejected as being in
  the future, because a calendar's "to today" resolves to the end of today), and it survived two
  context compactions that landed mid-task — a file on disk is readable afterwards in a way a
  conversation is not. It also produced four of its *own* false failures, each of which first looked
  like an application bug; the script made them cheap to disprove.
- **The gap was never capability; it was never having tried.** For the whole build, "a human needs to
  look at this in a browser" was recorded as a known limitation and carried forward — three sessions
  running. On day 2 at `11:31` it took one `npx playwright install chromium` to remove, and the next
  90 minutes closed two Definition-of-Done clauses and found four defects: every category badge
  rendering in one colour instead of seven, every client route in the packaged image answering 401
  JSON, every unknown path answering 500, and every SSE stream truncated rather than terminated. Three
  of the four were **invisible from the dev server on purpose** — Vite serves the SPA fallback and
  terminates proxied streams itself — so they were not reachable by testing harder at the layer already
  being tested. The lesson is narrower and more useful than "test more": **an honestly-recorded gap is
  still a gap, and a limitation carried forward three times deserves one attempt at removing it before
  the fourth.**
