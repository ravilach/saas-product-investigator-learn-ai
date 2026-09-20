/**
 * SaaS Products and the sources they are watched through.
 *
 * <p>A product is the unit of self-containment in this application: it owns its own source list, and through its id
 * it owns its own snapshots ({@code com.saasinvestigator.snapshot}) and reports
 * ({@code com.saasinvestigator.report}). Nothing is shared between products, which is what makes "run this product"
 * a complete operation and keeps one product's broken source out of another's history.
 *
 * <p>The distinction that drives most of the code here is <b>MCP versus crawled</b>, not the five source types -
 * see {@link com.saasinvestigator.product.SourceType}. MCP sources are handed to the model as remote tools and the
 * backend never sees their content; crawled sources are fetched here, snapshotted, and passed in as text. Almost
 * every difference downstream - whether a snapshot exists, whether {@code maxDepth} means anything, whether a
 * custom-range compare can answer honestly - follows from which family a source is in.
 *
 * <p><b>Secret handling:</b> a {@code SourceConfig}'s {@code authToken} is encrypted at rest and never leaves the
 * backend in readable form. It is masked to its last 4 characters on the way out and cannot be read back in full
 * once saved, which also means an edit that does not supply a new token must leave the stored one alone rather than
 * overwrite it with the mask it was shown.
 */
package com.saasinvestigator.product;
