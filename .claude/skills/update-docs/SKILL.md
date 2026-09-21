---
name: update-docs
description: Work out which docs a change makes stale, and update them. Use after changing an endpoint, an env var, a source type, a metric, a deployment sample, or anything a doc asserts.
---

# Update docs

Each doc has one job. A change usually touches two or three, and the failure mode is updating the obvious one and
leaving a second doc quietly asserting the old behaviour.

| Doc | Owns | Update when |
|---|---|---|
| `README.md` | The 30-second pitch, quick start, docs index | A new doc exists; the quick start changes |
| `docs/GETTING_STARTED.md` | The 5 verifiable checkpoints | A checkpoint's commands or expected output change |
| `docs/ARCHITECTURE.md` | How it fits together and *why* | A new component, a changed data model, a metric, a security decision |
| `docs/SETUP.md` | Every env var, running locally, running tests | Any new or renamed env var or property |
| `docs/API.md` | Every endpoint, with its required role | Any controller change |
| `docs/DEPLOYMENT.md` | Build/run, k8s, ECS, metrics, Harness | A manifest, a metric, a probe, a pipeline |
| `docs/decisions/` | One ADR per non-obvious trade-off | You chose between real alternatives |

## The routine

1. **Grep for what you changed**, don't guess which docs mention it:

   ```sh
   rg -n 'OLD_ENV_VAR|oldEndpointName|old.metric.name' README.md docs/ .claude/skills/ deploy/ harness/
   ```

   `.claude/skills/`, `deploy/`, `harness/` and `tools/` count as documentation here — a skill that names a deleted
   file is worse than no skill, because it will be followed, and the `tools/verify-*.mjs` harnesses assert documented
   behaviour in executable form, so a doc change that contradicts one of them means one of the two is wrong.

2. **Fix the fact, not just the sentence.** Where a doc says "these are two places that must agree," check the other
   one. Known pairs: the `CREDENTIAL_ENCRYPTION_KEY` warning in `GETTING_STARTED.md` checkpoint 2 and the
   `local-dev-loop` skill; the metric table in `ARCHITECTURE.md` and the fuller one in `DEPLOYMENT.md`; the env-var
   list in `SETUP.md` and `deploy/.env.example`.

3. **Verify every command you leave in a doc by running it.** Expected output shown in this repo's docs is real
   output, not illustration. If you can't run it, say so in the text rather than showing invented output.

4. **Check the anchors.** Cross-doc links use heading anchors (`SETUP.md#rotating-the-jwt-signing-secret`); renaming a
   heading breaks every link to it silently:

   ```sh
   rg -o '\]\((\.\./)?[A-Za-z_/.]*#[a-z0-9-]+\)' README.md docs/ .claude/skills/
   ```

5. **Write an ADR** when a real alternative was rejected. Existing ones are numbered `0001`–`0009`; follow the next
   number and the existing shape — context, decision, consequences, and honestly what it costs. Add it to the list at
   the end of `ARCHITECTURE.md`, which is the only index. An ADR recording a choice that had no alternative is noise.

6. **Update `README.md`'s status note** if a whole area of the repo changed state, and remove any `_(pending)_`
   marker whose step has landed. It cites test counts and harness results — those are claims with numbers in them, so
   either re-measure or don't touch them.

## House style, observed

- Explain **why**, not what. A comment or paragraph restating the code earns nothing.
- Name the failure mode. "Set X" is weaker than "unset, X falls back to Y, which silently means Z."
- Be honest about what isn't done. `_(pending — step N)_` markers and status tables beat a doc that describes an app
  that doesn't exist yet. Never mark something verified that you didn't run.
- Tables for reference material, prose for reasoning, Mermaid for topology and flow.

## Verify

Re-read the diff as someone who hasn't seen the change. Every claim should be one you actually checked — and if the
change was to a checkpoint, re-walk it rather than assuming it still holds. Checkpoints 3 and 4 have harnesses
(`tools/verify-checkpoint3.mjs`, `tools/verify-checkpoint4.mjs`), so re-walking them costs a minute; checkpoint 5's
status table cites test counts and container behaviour, which means re-running rather than re-reading. The reason
that table says *when* each checkpoint was walked and *from which vantage point* is that a checkpoint doc which was
only ever true partway through the build isn't worth much.
