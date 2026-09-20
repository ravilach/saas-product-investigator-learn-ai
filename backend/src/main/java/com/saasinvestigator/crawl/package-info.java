/**
 * Fetching the content of Website and SaaS App URL sources.
 *
 * <p><b>The one idea:</b> this package turns a URL into a block of text, and knows nothing about what that text
 * is for. It does not compare anything, does not decide what is interesting, and does not know that an LLM exists
 * downstream. Everything it produces is text plus a record of how it got there.
 *
 * <p>Only two of the five {@code SourceType}s come through here. MCP-type sources are never fetched by the
 * backend at all - they are declared to the provider as remote MCP tools and the model calls them itself - which
 * is also why they have no {@code Snapshot} history and why a custom-range compare has to caveat their half of
 * the answer.
 *
 * <p><b>The constraint to preserve:</b> every bound in this package is a safety bound, not a tuning knob. The
 * crawler makes requests to somebody else's server on the strength of a URL typed into a form, so it stays
 * same-origin (re-checked after redirects), honours {@code robots.txt} (including treating an unreachable one as
 * a refusal), enforces depth and page limits clamped to ceilings no admin setting can raise, bounds how much of
 * one response it reads, and keeps a fixed number of requests in flight. A change here that loosens one of those
 * is a change to how much damage a typo in a URL field can do.
 *
 * <p><b>Failure handling:</b> a page that fails is recorded in {@link com.saasinvestigator.crawl.CrawlResult}
 * and the crawl continues. Only a crawl with nothing at all to show throws
 * {@link com.saasinvestigator.crawl.CrawlFailedException}, which the orchestrator turns into one unavailable
 * source rather than a failed run.
 *
 * <p>Nothing in this package reads or writes secrets. A crawled source's {@code authToken} is not used - if
 * authenticated crawling is ever wanted, that is a deliberate addition, and the token would have to be decrypted
 * here, which is exactly the kind of change that should be hard to make by accident.
 */
package com.saasinvestigator.crawl;
