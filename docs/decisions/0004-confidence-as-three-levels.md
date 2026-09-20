# 0004 — `confidence` is three levels, not a number

**Status:** accepted
**Date:** 2026-09-20

## Context

The build prompt specifies a `confidence` field on every reported change:

> `changes: [ { sourceName, sourceType, category, description, confidence, evidenceSnippet } ]`

It does not say what type that field is, and both readings are plausible: a float in `[0,1]`, or a small set of
labels. It also has to be rendered in the History timeline, in the PDF export, and in the DOCX export, so the choice
propagates.

Three candidates:

1. **A float 0–1.** Sortable, filterable by threshold, and the conventional shape.
2. **Three levels — high / medium / low.** A closed enum, like `category` already is.
3. **Free text.** Whatever the model wants to say.

## Decision

**Three levels: `high`, `medium`, `low`**, as the `Confidence` enum — lowercase on the wire, parsed leniently, with
anything unrecognised becoming `medium`.

## Why not a number

The number would be precise and not accurate. A model asked for `0.87` will return `0.87`, and nothing about the
third significant figure corresponds to anything — LLM-reported numeric confidence is not calibrated, so the gap
between `0.72` and `0.78` is noise presented in the visual language of measurement. Worse, the precision invites
downstream use it can't support: a "only show changes above 0.8" filter reads as principled and is arbitrary.

It also forces a rendering decision with no good answer. One decimal place throws away detail the number claims to
have; two implies the model distinguishes percentage points. A progress bar or a coloured gauge makes it look
computed.

Three levels are the resolution the model can actually support and the resolution a reader can actually act on:
*take this as read*, *check this*, *probably worth a look*. They also render the same way `category` already does —
as a pill badge — so the report has one visual grammar rather than two.

## Why not free text

For the same reason `category` is a closed set. A model left to phrase its own confidence produces "fairly
confident", "high", and "likely" for the same state across three runs, which makes filtering useless and grouping
worse. Closed sets are what make the field comparable between reports.

## Implementation notes

- `Confidence` mirrors `ChangeCategory`: `@JsonValue` for the lowercase wire name, a lenient `@JsonCreator`, and a
  fallback for unrecognised input.
- The fallback is **`MEDIUM`, not a fourth `UNKNOWN` value.** An unparseable confidence means the field is
  uninformative, and the middle of the range is the honest representation of that. A fourth value would have to be
  special-cased by the UI, both exporters, and any future sort — permanent complexity to represent a transient
  parsing miss.
- Parsing is lenient because a single odd confidence word is not worth discarding a report over. The change's
  description, evidence, source, and category are all still exactly right. Same reasoning as `ChangeCategory`, and
  the boundary is deliberate: the *structure* of model output is validated strictly and retried on failure, while
  these two *vocabulary* fields degrade instead.

## Consequences

- Sorting by confidence is enum-ordinal sorting, which is fine — `HIGH` is declared first for exactly that reason.
- A future threshold filter is not available. If one is ever genuinely wanted, that's the point to revisit this, and
  the migration is a mechanical three-value mapping rather than a reinterpretation of stored data.
- Reports written before any such change stay readable, because the stored value is a word and not a number whose
  scale might have shifted.
