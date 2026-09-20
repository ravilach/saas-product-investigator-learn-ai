---
name: add-llm-provider
description: Add a third LLM provider (Gemini, Bedrock, a local model) alongside Anthropic and OpenAI, or change which model a provider uses. Use when asked to support a new model vendor.
---

# Add an LLM provider

Two exist: `AnthropicLlmProvider` and `OpenAiLlmProvider`, both in `backend/.../llm/`, both behind the
`LlmProvider` interface. `LlmProviderResolver` picks one per request. The interface is the contract — implement it
and the rest of the app doesn't change.

## Just changing a model name?

That's an env var, not code. `ANTHROPIC_MODEL` / `OPENAI_MODEL`, defaults in `application.properties`. Model names
move faster than this repo does, which is exactly why they're overridable — see
`docs/decisions/0007-llm-providers.md`. Stop here.

## What `LlmProvider` requires

Read `LlmProvider.java` first; the shape it asks for is the design. Four things are easy to get wrong:

- **Streaming is not optional.** The live view and streamed answers are SSE all the way down, via `TokenSink`. A
  provider that only returns a complete response makes runs look hung for minutes.
- **JSON output must be enforceable.** Reports are parsed into a schema (`ReportJsonSchema`,
  `ChangeReportJsonParser`). Use the provider's structured-output/JSON mode. Prose that happens to look like JSON
  will fail on the first model that adds a preamble.
- **Errors map to `ProviderUnavailableException`.** Rate limits, auth failures, and timeouts should surface as this
  app's error shape, not a leaked SDK exception in an HTTP 500.
- **Respect the output budget from `AnalysisDepth`.** It lives on the enum with the prompt instruction it belongs
  with, on purpose.

## Checklist

1. **`LlmProviderType`** enum constant. Compiler-driven from here — the exhaustive switches will find the sites.
2. **SDK dependency** in `backend/pom.xml`, pinned to an exact version. Note which version you verified model IDs
   against.
3. **`YourLlmProvider implements LlmProvider`**, a `@Component`, taking its key from the resolved credential rather
   than reading config itself.
4. **Config** — `LlmProperties` plus `app.llm.<provider>.api-key` / `.model` in `application.properties`, both
   env-var backed. `ApplicationPropertiesBindingTest` will fail on a name Boot doesn't bind.
5. **Credentials** — the new provider must work in all three tiers of the existing resolution order (per-user BYOK,
   system-wide, host file). Don't add a fourth path. See `docs/decisions/0006-credential-resolution.md`.
6. **`LlmProviderResolver`** — include it in selection and in the "no provider configured" error message.
7. **Frontend** — the provider dropdowns in Account Settings (BYOK) and Admin → Secrets, plus the `last4`/configured
   display. There should be no place a provider exists in the backend but not the UI.
8. **Tests** — a stubbed streaming response parses into a report; a provider error becomes
   `ProviderUnavailableException`; the resolver picks it when preferred. Don't call the real API in a test.
9. **Docs** — `docs/ARCHITECTURE.md`, `docs/SETUP.md` env-var table, and a new ADR in `docs/decisions/` if the choice
   involved a trade-off worth recording.

## Verify

```sh
cd backend && mvn test
```

Then a real run with the new provider selected, at `NUCLEAR` depth — that's the path that exercises the largest
prompt, the longest stream, and the output budget together. Watch for tokens arriving progressively in the live view
rather than in one burst at the end; a burst means streaming isn't actually wired through.
