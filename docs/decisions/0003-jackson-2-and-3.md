# 0003 — Jackson 3 for HTTP, Jackson 2 for LLM output parsing

**Status:** accepted · **Date:** 2026-09-20

## Context

Spring Boot 4.1 moved to **Jackson 3**, whose root package is `tools.jackson.*`. Jackson 2
(`com.fasterxml.jackson.*`) is nonetheless on the classpath, pulled in transitively by `jjwt-jackson` and by the
Anthropic SDK. Both are present, and both work — they are different artifacts with different root packages, so they
don't collide.

This is exactly the kind of thing that produces a confusing afternoon later: an `ObjectMapper` import that looks
correct, resolves, compiles, and is the wrong one.

## Decision

- **HTTP request/response serialisation uses Jackson 3.** It's what `WebMvcAutoConfiguration` configures, what
  `@RestController` return values go through, and what the auto-configured `ObjectMapper` bean is. Code that needs
  the app's mapper — e.g. `SecurityErrorWriter`, which writes an error body from inside a filter — injects
  `tools.jackson.databind.ObjectMapper`.
- **Parsing the LLM's structured JSON output uses Jackson 2**, constructed explicitly rather than injected, because
  it wants different settings from the HTTP mapper: lenient about unknown fields (a model may add one), strict about
  the fields we require, and never sharing configuration with the serialiser that talks to browsers. Using the SDK's
  own Jackson version also avoids a second copy of the same library doing the same job.
- **Annotations come from `com.fasterxml.jackson.annotation`** in both cases. Jackson 3 reads the Jackson 2
  annotation package, so `@JsonInclude`, `@JsonProperty`, and `@JsonIgnore` on a DTO work for both mappers. There is
  no need — and no benefit — to split annotations by mapper.

  This claim is load-bearing enough to be tested rather than trusted: `ReportJsonMappingTest` asserts that the
  `@JsonValue`/`@JsonCreator` pair on `ChangeCategory` and `Confidence` behaves identically under both mappers. If it
  didn't, the symptom would be categories arriving lowercase from the model and leaving uppercase over HTTP — which
  presents as a frontend bug about badge colours, a long way from the cause.

## Consequences

- Two Jackson major versions ship in the jar. Slightly larger image, no runtime conflict.
- Any new `ObjectMapper` usage has to make a conscious choice. The rule of thumb is short enough to remember:
  **injected mapper → Jackson 3 → HTTP; constructed mapper → Jackson 2 → LLM output.** A wrong import fails at
  compile time rather than silently, since the two types aren't interchangeable.
- If Jackson 2 later disappears from the dependency tree (an SDK upgrade dropping it), the LLM parsing code moves to
  Jackson 3 with a package rename and no behavioural change. Nothing in the design depends on version 2 specifically
  — only on *not* reusing the HTTP mapper's configuration.
