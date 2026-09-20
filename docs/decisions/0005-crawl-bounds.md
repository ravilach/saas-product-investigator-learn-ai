# 0005 — Crawl bounds: depth semantics, hard ceilings, and an unreachable `robots.txt`

**Status:** accepted
**Date:** 2026-09-20

## Context

The build prompt is explicit that crawling is a safety boundary:

> Crawling is a safety boundary, not just a performance knob: stay same-origin, respect `robots.txt`, enforce
> `maxDepth`/`maxPages` with sane defaults and a per-page fetch timeout, de-dupe visited URLs. Don't let a
> misconfigured source crawl unbounded.

It gives the defaults (`maxDepth: 2`, `maxPages: 20`) and the concurrency budget (4–5 at a time), and leaves four
questions that each have more than one defensible answer. All four are decided here because all four are
observable from outside — they change what a snapshot contains, which changes what the next run reports as a
change.

## Decision 1 — `maxDepth` counts hops, and `0` means the start page alone

`0` fetches only `endpointUrl`. `1` adds the pages it links to. `2` — the default — reaches three levels of pages.

The alternative reading is "number of levels", where `1` means the start page and `0` means nothing. That reading
makes `0` a configuration that crawls nothing, produces an empty snapshot, and gets compared against on the next
run as though the entire site had been deleted. Hops make `0` mean something a user would actually want: *this one
changelog page, nothing else*.

## Decision 2 — ceilings that no source and no admin can raise

`app.crawler.max-allowed-depth=5` and `max-allowed-pages=200` bound every other layer.

Without them, "crawling is a safety boundary" is a claim about the default rather than about the code. A source
form field accepting `maxPages: 100000`, or an Admin Console default of depth 20, is a request to walk somebody
else's site until they notice. The ceilings live in `application.properties` rather than in the Admin Console
precisely because the person the boundary protects is not the person using the UI.

**Clamping and rejecting are deliberately asymmetric:**

| Value arriving from | Behaviour | Why |
|---|---|---|
| A `SourceConfig`, or the `CRAWL_DEFAULTS` document | Clamped | It may predate the ceiling, or have been written straight into Mongo via the Data Explorer. There is nobody present to tell. |
| `CrawlSettings.updateDefaults` (the Admin Console form) | `400 BadRequestException` naming the field and the ceiling | Storing 200 while echoing back the 500 they typed makes the form lie. |

## Decision 3 — an unreachable `robots.txt` refuses the crawl; a 404 permits it

- **2xx** → parse and obey.
- **404/410, or any 4xx** → `RobotsTxt.allowAll()`. Per RFC 9309, "no file" means no restrictions.
- **5xx, a timeout, a connection failure** → `CrawlFailedException`, and this source is unavailable for the run.

The third case is the one worth writing down, because the convenient behaviour is to crawl anyway. RFC 9309 says a
crawler "MAY" treat unreachable as disallowed, so both are permissible; the question is which failure we prefer. A
5xx on `/robots.txt` frequently means the site is already unhealthy — exactly the moment when 20 more requests are
least welcome — and the rules we would be ignoring are rules we have never read. One unavailable source in one
report is a recoverable, visible, self-explaining outcome. Crawling a site that was trying to tell us not to is
not.

An honoured `Crawl-delay` is clamped to `app.crawler.max-crawl-delay-ms` (2s) and the clamp is logged. A site
publishing `Crawl-delay: 300` against a 20-page budget is an hour-long run that is indistinguishable from a hang.

## Decision 4 — the character cap truncates, including mid-page

At `max-chars-per-source` (200k) the crawl keeps the **earliest** pages — the start page and its nearest links,
which is where a changelog or pricing page lives — and includes a **partial** final page when at least 2,000
characters remain for it, followed by an explicit `[truncated at the per-source character cap]` marker.

Dropping the straddling page entirely is simpler and has one fatal case: a single page longer than the cap yields
an empty snapshot. That is the empty-snapshot failure again, and 200k characters of a very long page is far more
useful to a diff than nothing at all. The marker is there so the model is told the text ends arbitrarily rather
than inferring that the page does.

`CrawlResult.pageUrls()` therefore lists pages actually *included*, not pages fetched. A URL listed in a snapshot
is a claim that this snapshot covers it.

## Consequences

- The ceilings are a second enforcement point, so `CrawlSettingsTest` asserts them for values from all three
  layers — including a stored document that never went through `updateDefaults`.
- Raising a ceiling is a deploy, not a setting. That is the intent; it should require someone who can read this
  file.
- A site with a flapping `robots.txt` will show intermittently unavailable sources rather than intermittently
  complete reports. The failure is in the right place: visible in the run, attributable to one source, and not a
  silently thinner comparison.
- Because `maxDepth` is hops, changing the default from `2` to `3` is a meaningful widening (a fourth level of
  pages), not an off-by-one. Anyone tuning it should watch `maxPages`, which will usually bind first.
