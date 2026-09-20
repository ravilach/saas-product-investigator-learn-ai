package com.saasinvestigator.report;

import com.saasinvestigator.product.SourceType;
import java.time.Instant;

/**
 * One entry in a report's {@code sourcesIncluded} list: which source contributed, and as of when.
 *
 * <p>This is the "what it included and when it last ran" metadata the date picker relies on. It is also the answer
 * to the question a report cannot otherwise answer - a report that lists no changes for a source is ambiguous
 * between "nothing changed there" and "that source failed and was left out", and those call for very different
 * reactions. A source absent from this list was not consulted.
 *
 * <p>A record, not a mutable class, because nothing should ever edit one after the run that produced it. The
 * mutable {@code @Document} classes in this codebase are mutable because Spring Data's mapping layer needs that of
 * root documents; an embedded value has no such constraint, and Mongo maps records fine (see the driver's
 * {@code Jep395RecordCodecProvider}).
 *
 * @param sourceName the source's label within its product
 * @param sourceType the source's type at the time it was consulted, copied so the entry stays interpretable after
 *     the source is edited or deleted
 * @param fetchedAt when this source's data was gathered. For a crawled source in a standard run, the moment it was
 *     crawled. For a crawled source in a custom-range compare, the {@code fetchedAt} of the historical snapshot
 *     used - which is why this is per-source rather than one timestamp on the report: different sources can be as
 *     of genuinely different moments. {@code null} for an MCP source, whose data the backend never held and whose
 *     freshness it therefore cannot claim.
 */
public record SourceInclusion(String sourceName, SourceType sourceType, Instant fetchedAt) {
}
