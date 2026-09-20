---
name: add-source-connector
description: Add a new source type (Notion, GitHub Releases, RSS, a new MCP server) that a SaaS product can be investigated from. Use when asked to pull content from somewhere the five existing SourceTypes don't cover.
---

# Add a source connector

A source type is a way to get text about a product. `SourceFetcher` already handles *when* to fetch, how to report
progress, and what to do when a fetch fails — a new connector supplies the fetching and nothing else.

## Decide first: is this actually a new type?

- **An MCP server this app hasn't seen before is not a new source type.** `MCP_SERVER` already covers arbitrary MCP
  endpoints; adding one is configuration in the UI, not code. Stop here.
- **A website with an unusual layout is not a new source type either.** `WEBSITE` crawls anything HTML.
- It *is* a new type when the transport or auth differs — a REST API with pagination, a feed format, a protocol.

## Checklist

1. **`SourceType` enum** — `backend/.../product/SourceType.java`. Add the constant. Every `switch` over it should be
   exhaustive without a `default`, so the compiler will now point you at every site that needs updating. Follow the
   compiler; don't grep.
2. **`SourceConfig`** — add only the fields the new type needs (`backend/.../product/SourceConfig.java`). Reuse
   `url`/`credentialRef` if they fit rather than adding a parallel field with a different name.
3. **Validation** — `SourceConfigRequest` / `SourceConfigMapper`. A source that can't work should be rejected at
   creation with a 400 naming the missing field, not at run time with a failed source three minutes in.
4. **The fetch** — `backend/.../run/SourceFetcher.java`. Contract to honour:
   - Emit progress through the existing `RunEventSink` so the live view narrates this type like the others.
   - Respect `app.crawler.max-chars-per-source`. An unbounded source blows the prompt budget for every *other*
     source in the run.
   - Throw so the run records a **`partial`** outcome. A failing source must not fail the whole run — that is the
     difference between "one source was unreachable" and "your investigation didn't happen."
   - Increment `saas.source.fetch.errors` with your `sourceType` tag on failure (`RunMetrics`).
5. **Secrets** — if it needs a token, it goes through `CryptoService` and the existing credential resolution order.
   Never a new plaintext field on `SourceConfig`, and never a new env var read directly.
6. **Prompt** — `backend/.../llm/PromptBuilder.java`. The model needs to know what kind of thing it's reading;
   "changelog entries" and "support tickets" warrant different framing.
7. **Frontend** — the source form's type dropdown and its per-type fields, plus the source-type label/icon wherever
   sources are listed.
8. **Tests** — see `write-tests-for-new-code`. At minimum: the mapper rejects an invalid config, and the fetcher
   surfaces a failure as `partial` rather than propagating.
9. **Docs** — `docs/ARCHITECTURE.md` source-type list, `docs/API.md` request/response shape. See `update-docs`.

## Verify

- `cd backend && mvn test`
- Create a product with the new source and run it. Watch the live view: your source should narrate its own progress,
  and the report should cite it.
- Then break it on purpose — bad URL, revoked token — and confirm the run finishes `partial` with the other sources
  intact.
