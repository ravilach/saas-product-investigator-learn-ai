# EVAL_LOG

One entry per build run, rolled up from the per-session files in [`evals/`](evals/). Those files are
the raw log this was built from — they stay as they are, and where this summary and a session file
disagree, the session file is the record.

The convention this file follows was derived from [`docs/evals-tracking-PROMPT.md`](docs/evals-tracking-PROMPT.md),
which asks for a rollup per run: wall-clock time, interventions with the interesting ones named, the
final diff against the first commit for scale, and the outcome against the spec's Definition of Done.
That prompt refers to "that file's existing template" — there was none, so this is it.

---

## Run 1 — 2026-09-20 → 2026-09-21 · **interim, the build is not closed**

Built from [`docs/saas-product-investigator-BUILD-PROMPT.md`](docs/saas-product-investigator-BUILD-PROMPT.md)
in Claude Code, model Opus 5. Spec commit `a912316`, content hash
`80416c78de8e251f77cc9353727138fd21676427` — **provably unedited across the whole build**, so every
step ran against the same text.

> **Why this entry is interim.** The prompt asks for this rollup at the end of a full build. **12 of
> 13 BUILD ORDER steps are done**; step 13 (finalise the docs, re-walk all five `GETTING_STARTED.md`
> checkpoints against the finished app) and the Definition of Done sweep are not. Written now because
> three consecutive session files flagged this file's absence as a gap, and an honest interim entry is
> more useful than a missing final one. It needs one more pass when step 13 closes.

### Scale

| | |
|---|---|
| **Wall-clock** | **≈10h 36m** across 3 session files / 13 Claude Code sessions: `10:46`–`19:28` (8h 42m, 8 sessions), `19:39`–`20:38` (59m, 4 sessions), `10:30`–`11:25` next day (55m, 1 session) |
| **Diff vs. first commit** (`0fcae72`, an effectively empty repo) | `372 files changed, 55260 insertions(+), 8 deletions(-)`, plus 1,094 uncommitted lines in `tools/` and one new test class |
| **Composition** | 373 tracked files: 166 main Java classes, 68 backend test classes (67 tracked + 1 new), 64 frontend `.ts`/`.tsx`, 8 ADRs, 6 docs pages, 11 Claude skills, 2 Harness pipelines, k8s + ECS + compose manifests |
| **Tests** | backend **769** (0 failures, 0 errors, 0 skipped), JaCoCo over 170 classes; frontend **80** in 9 files. Both measured 2026-09-21 `11:11`–`11:12`. |
| **Context compactions** | **25** (23 + 0 + 2). The dominant operational cost of day 1, and near-zero once work was scoped to one artifact at a time. |

### Interventions — 19 substantive human messages, 7 interrupts, 1 permission denial

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

Not on the timeline but real: `.claude/settings.local.json` accumulated ~126 allow-rules, each of
which required a human "yes, don't ask again" at some point. No individual timestamps exist for them.

### What the build produced, and how each part is known to work

| Verified by | Covers |
|---|---|
| **Automated suites** — 769 backend / 80 frontend | Unit and integration logic, including Testcontainers-backed Mongo paths |
| **`tools/verify-step10.mjs`, 40/40 green against the container image** | The whole pipeline over the API: crawl, `robots.txt` obedience, prompt construction, the provider call, structured-output parsing, persistence, SSE narration, PDF and DOCX rendering (content read back out, not byte counts), Compare, RBAC, secret masking, auditing, JWT-secret persistence across restart, graceful failure with no provider configured |
| **Five container runs, day 1 `19:39`–`20:38`** | Embedded Mongo, external Mongo, a named volume, a read-only root filesystem, a host-mounted credential file |
| **A browser, earlier in the build** | Login, route guards, theme toggle, the responsive shell at desktop/tablet/phone widths |

### Outcome against the Definition of Done

**Most of it is met. Five clauses are not, and they are all of one kind: they need a human looking at
the running app.** Listing them rather than rounding up, since a Definition of Done that has been
rounded up is not one.

- **Anything about rendered appearance.** Dark mode being "navy-based, not pure black"; category
  badges rendering in the History timeline; the live execution view rendering detail strings *as they
  arrive*. What *is* verified: all 23 colour tokens have dark counterparts and no token exists only
  in dark; the toggle has 5 tests; the harness asserts the per-page and per-tool strings really are in
  the SSE stream (`Crawling http://…/changelog (page 2 of ~10)`, `Calling Acme Jira MCP tool: …`).
  Structure, not appearance.
- **The OpenAI provider path.** The DoD requires adding an OpenAI key, switching
  `preferredLlmProvider`, and confirming a run actually used OpenAI. The mock is Anthropic-shaped and
  the harness does not touch OpenAI at all. The class has unit tests; the switch has never run end to
  end. Cheapest outstanding gap — the `OPENAI_BASE_URL` seam added on day 2 is the other half of the
  same knob.
- **Analysis quality, and "re-running later correctly reflects only what's new".** Structurally
  exercised — the fixture site advances between runs and the second run has something real to find —
  but with a stub provider there is no analysis to judge. This needs a real model and a human reading
  the output, and no harness can substitute for it.
- **Phone-width usability of the finished screens.** Verified for the shell, before the product and
  admin screens existed. Not re-walked since.
- **`GETTING_STARTED.md` from a clean clone.** Checkpoints 1, 2 and 5 have been re-walked and their
  recorded output is first-hand. Nobody has followed the document from a fresh `git clone` on a
  machine that never built this, which is the actual claim it makes.

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

Three things, each supported by a timestamp rather than an impression:

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
