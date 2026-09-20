# 0007 — LLM providers: models, return types, callbacks, thinking, and depth mapping

**Status:** accepted
**Date:** 2026-09-20

## Context

The build prompt specifies a pluggable `LlmProvider` with two implementations, Anthropic and OpenAI, chosen
per-request from the caller's own credentials. It names the Anthropic model (`claude-sonnet-4-6`) and describes the
OpenAI one as "OpenAI's current flagship", asks for structured JSON output with a parse-retry, an `analysisDepth`
knob with three levels, progress events with real detail, and a streaming ad-hoc ask.

Everything below that line is unspecified, and several of the choices are not reversible without changing stored
data or the wire format. They are recorded here.

## Decision 1 — the default model IDs deviate from the prompt, by one generation

The defaults are `claude-sonnet-5` and `gpt-6-astra`.

The prompt named `claude-sonnet-4-6`. That model exists and works; `claude-sonnet-5` is the same tier one
generation later, is what `anthropic-java` 2.64.0 exposes as `Model.CLAUDE_SONNET_5`, and is what the provider's
current documentation lists. For OpenAI the prompt named no model at all, so "current flagship" had to be resolved
against something: `openai-java` 4.65.0 exposes `ChatModel.GPT_6_ASTRA`, and OpenAI's model documentation lists
`gpt-6-astra` as the flagship.

Both were verified against the pinned SDK enums rather than recalled, because a wrong model ID is a 404 at the
first real run and nowhere earlier.

The deviation is safe to make unilaterally for one reason: neither ID is compiled in. Both are
`app.llm.<provider>.model` properties with env-var overrides (`ANTHROPIC_MODEL`, `OPENAI_MODEL`), so moving to
`claude-sonnet-4-6` — or to whatever is flagship six months from now — is a restart, not a build. Model names move
faster than this repository will, and the only defensible design is one where that does not matter.

## Decision 2 — `generateChangeReport` returns `GeneratedReport`, not `ChangeReport`

`ChangeReport` is a `@Document` with an id, a `saasProductId`, a `runBy`, a `runAt`, a `runType`, and a
`sourcesIncluded` list. A provider knows none of those things and has no business inventing them. If the interface
returned `ChangeReport`, every implementation would have to be handed the product, the actor, and the clock purely
so it could fill in fields it does not care about — and each implementation could fill them in slightly
differently.

`GeneratedReport` is therefore exactly what a model produces: an overall summary and a list of changes. The
orchestrator supplies everything else, in one place, once. The practical payoff is in the tests: a provider test
asserts on two fields, and none of its fixtures need a product or a user.

The cost is one extra type and one mapping step in `RunOrchestrator.analyse`. That mapping step turned out to be
where `asCustomRange(...)` belongs anyway, so the seam pays for itself.

## Decision 3 — progress reaches the caller by callback, not by return value

`generateChangeReport(RunContext, LlmActivityListener)` and `answerQuestion(AskContext, TokenSink)` both take a
callback.

The alternative is for a provider to return everything it saw and let the caller report it afterwards. That
produces correct logs and a useless live view: the point of the MCP progress events is that they appear *while*
the model is calling those servers, which is the part of a nuclear-depth run that takes the longest. A detail
string delivered after the call is a detail string delivered after the user stopped watching.

Two narrow single-method interfaces rather than one, because the two carry different things — `LlmActivityListener`
carries a human-readable description of an action, `TokenSink` carries raw generated text — and a single interface
would have made every implementation of it handle a case it never sees.

Both are invoked on the provider's own thread, and both can throw. That is used deliberately: `AskService` throws
out of its `TokenSink` when the client has disconnected, which aborts the provider call instead of paying for an
answer nobody will read.

## Decision 4 — on Anthropic, extended thinking is on for an ask and off for a report

`answerQuestion` requests adaptive thinking; `generateChangeReport` explicitly disables it.

This looks backwards, because the report is the more demanding task. The reason is the corrective retry, not the
difficulty of the work. A retry replays the model's own previous turn back to it as conversation history, and a
report turn contains two things that cannot be faithfully replayed as text: thinking blocks, and server-side MCP
tool-use blocks. Replaying such a turn unfaithfully is a request the API can reject — so the path that has a retry
is the path that keeps its turns replayable.

The ask path has no retry, no tools, and no JSON contract, so none of that applies, and it gets adaptive thinking.
It is also the path where thinking helps most in practice: an open-ended question may require combining four
sources and a previous report, while a report is shaped at every token by its schema.

The ask path also carries a fallback. Adaptive thinking is a 4.6-and-later feature and the model is an env var, so
a `BadRequestException` on the thinking request is retried once with thinking disabled rather than surfaced — an
operator who pins an older model gets a working ask, not a broken feature.

OpenAI's nearest knob is `reasoning.effort`. It is set to `MEDIUM` for an ask — there is no schema to shape the
answer, and no replay problem to avoid — and mapped from depth for a report; see Decision 6.

## Decision 5 — OpenAI requests set `store(false)` and `requireApproval(NEVER)`

`store(false)` is a security decision. The default is that OpenAI retains the request for the Responses API's
own history features. The request body here contains entire crawled pages and MCP tool output, which for a private
SaaS product under evaluation is exactly the material a user would not expect to be retained anywhere they did not
choose. Turning it off also disables `previous_response_id` continuation, which this application does not use: an
ask is a single turn by design.

`requireApproval(NEVER)` is a correctness decision, and a sharp one. The SDK's default for an MCP tool is
`ALWAYS`, which pauses the response and waits for a human to approve each tool call. In an unattended run on a
background thread there is nobody to approve anything, so the default would produce a run that hangs until its
timeout with no indication why. The MCP servers reached here are ones a user explicitly configured on their own
product, with their own token, so per-call approval would be asking permission for the thing that was just
requested.

## Decision 6 — depth maps to output budget on both providers, and additionally to reasoning effort on OpenAI

`AnalysisDepth` owns `maxOutputTokens()`, which both providers use, because the depth's prompt instruction and its
output budget have to move together — `NUCLEAR` asking for exhaustive detail under a `SHORT` budget is a truncated
report, and keeping them in one enum makes that impossible rather than merely unlikely.

OpenAI additionally maps depth to `ReasoningEffort` (`OpenAiLlmProvider.effortFor`): `SHORT` → `LOW`,
`REGULAR` → `MEDIUM`, `NUCLEAR` → `HIGH`. The available values run to `XHIGH` and `MAX`; `HIGH` is the ceiling used
because the depth control is a user-facing knob on a screen and `NUCLEAR` should be thorough, not open-ended in
cost.

Anthropic has no per-request effort equivalent to map onto, so on that provider depth acts through the prompt
instruction and the token budget alone. That is a real asymmetry between providers, and it is the reason
GETTING_STARTED's checkpoint asks for the three depths to be compared on *the same provider*: comparing SHORT on
one against NUCLEAR on the other measures two things at once.

## Decision 7 — a parse failure is retried once, with the failure quoted back

The model is asked for JSON matching a schema, and both providers enforce that schema server-side. It can still
come back unusable — wrapped in a markdown fence, truncated at the token limit, or with a category string that is
not in the enum.

`ChangeReportJsonParser` therefore salvages first (strip fences, find the outermost object, tolerate a missing
optional field) and, if the response is structurally unusable, `parseWithRetry` sends one correction containing the
specific parse error and takes the second response.

Two details of where that lives are deliberate:

- **The retry is in the parser, not in each provider.** The policy — how many attempts, what the correction says,
  what counts as worth retrying — is identical for both providers and should exist once. Each provider supplies only
  the mechanism, as a callback that replays its own conversation in its own SDK's shape.
- **The parse error is quoted back verbatim.** "Invalid JSON" produces another guess; "unexpected end of input at
  line 402" produces a shorter report that parses. This is the one place in the application where an internal
  exception message is deliberately forwarded — to the model, never to the client.

One retry, not three. A second failure after being told exactly what was wrong is not a transient fault, and three
attempts at nuclear depth is three times the cost of a run that is going to fail anyway.

Not every failure retries. A response that is valid JSON but has an unrecognised category or confidence value is
coerced to a default rather than retried, because the content is usable and a whole second nuclear-depth call to fix
one enum string is not a trade worth making. The same applies to attribution: a change credited to a source that is
not configured on this product is dropped with a warning rather than failing the run, because the summary still
describes the product correctly and a report missing one finding is better than no report.

That split — structural problems retry, field-level problems degrade — is the parser's organising idea. `parse`
throws only for the failures that make the response unusable as a whole: not JSON, no object in it, `changes` present
but not an array, and a missing or empty `overallSummary`. The last of those is in the strict list for a specific
reason: it is the only field with no sensible default, and an empty one renders as a blank report card that looks
like a bug in the UI rather than a bad response from a model.

## Consequences

- Upgrading a model is a config change. Upgrading an SDK may not be: both providers use beta surfaces (Anthropic's
  MCP client beta, structured output config) and those can change shape between versions. The pins in `pom.xml`
  are deliberate.
- `GeneratedReport` means a provider can never write to the database, which is asserted by the absence of any
  repository in either provider's constructor.
- Because progress is a callback, a provider implementation that forgets to call it is silent rather than broken:
  the run still succeeds and the live view simply shows less. Neither provider's streaming path can be unit-tested
  without a live API key, so this is the one part of the LLM layer whose correctness rests on the step-4 and step-10
  live checks in `/docs/GETTING_STARTED.md` rather than on a test. The pure parts around it —
  `ChangeReportJsonParser`, `PromptBuilder`, `ReportJsonSchema`, `LlmProviderResolver`,
  `OpenAiLlmProvider.effortFor` — are tested directly.
- `store(false)` means OpenAI's dashboard shows no request bodies for this application, which is the intent, and
  also means a support request about a bad response cannot be answered by pointing at a stored request.
