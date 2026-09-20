# Prompt: Set up live eval tracking in /evals

Paste this whole file (or point Claude Code at it: "read and follow
evals-tracking-PROMPT.md") as your second message in VS Code.

## What to create

An `/evals` folder at the repo root, one file per work session:
`/evals/session-<YYYY-MM-DD-HHmm>.md`, timestamped from when that session
starts. "Session" means one continuous stretch of work — if you stop and
get re-invoked later, that's a new session file, not an appended one.

Each session file:

```markdown
# Session: <YYYY-MM-DD HH:mm> (start)

- **Starting commit**: <git rev-parse HEAD, before any changes this session>
- **Spec version**: <commit hash of saas-product-investigator-BUILD-PROMPT.md>

## Log

- `<timestamp>` — <what you're starting: which BUILD ORDER step(s) or task>
- `<timestamp>` — <intervention: what a human corrected, redirected, or
  had to approve, in their words if possible, not paraphrased into
  something that sounds more resolved than it was>
- `<timestamp>` — <milestone: step completed, tests passing, etc.>

## End of session — <YYYY-MM-DD HH:mm>

- **Ending commit**: <git rev-parse HEAD>
- **Files changed**: `git diff --stat <starting-commit> <ending-commit>` (paste the summary line)
- **Tests**: passing / failing / not run this session
- **Steps completed this session**: <list from BUILD ORDER>
- **Carried over to next session**: <anything left mid-way>
```

## Rules for keeping this honest and lightweight

1. **Log in the moment, not reconstructed at the end.** A timestamp you
   add when something actually happens is real data; a timestamp you
   guess at afterward isn't. If you forget to log something as it
   happens, note that it's a reconstructed entry rather than presenting
   it as precise.
2. **Every human correction gets logged as it happens**, no matter how
   small. Don't editorialize it into sounding smoother than it was — "I
   had the wrong Mongo index field, was corrected" is more useful than
   "adjusted indexing approach."
3. **This never blocks or slows down the actual build.** A log entry is
   one line, not a report. If tracking it is taking real effort, you're
   doing too much — same philosophy as tests never blocking
   `docker build`.
4. **Timestamps come from the actual system clock** (`date`), not
   estimated or invented.
5. **Since a build is already underway**: create one retroactive session
   file now for everything done so far, reconstructed from `git log`
   (commit timestamps and messages are real data even after the fact —
   just label the file's header as reconstructed rather than live-logged,
   so it's not confused with sessions logged in real time going forward).

## At the end of a full build

Don't duplicate the session files into `/EVAL_LOG.md` — summarize them.
Roll the session files up into one `EVAL_LOG.md` "Run" entry at the repo
root (see that file's existing template): total wall-clock time across
all sessions, total interventions (with the interesting ones called out
by name, not just counted), final `git diff --stat` against the very
first commit for scale, and overall outcome against the spec's Definition
of Done. The session files in `/evals` stay as-is afterward — they're the
raw log this summary was built from, not something to clean up or delete.
