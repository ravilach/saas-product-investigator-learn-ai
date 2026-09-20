/**
 * Types shared across features, with no feature-specific knowledge of their own.
 *
 * <p>Kept deliberately small. The packages in this application are organised by feature
 * ({@code user}, {@code audit}, {@code security}...), and a {@code common} package is the natural place for
 * unrelated things to accumulate until it means nothing. The bar for putting something here is that at least two
 * features already need it and neither owns it - {@link com.saasinvestigator.common.PageResponse} qualifies because
 * every paginated endpoint must return the same envelope.
 */
package com.saasinvestigator.common;
