/**
 * Stored point-in-time captures of crawled sources - the application's memory.
 *
 * <p>Without this package the application could only report what a source currently says. Snapshots are what make
 * "what changed" answerable: each run stores one per crawled source and reads the previous one back to hand the
 * model a before and an after.
 *
 * <p>Nothing here compares anything. There is no diff function in this package and there should not be one - the
 * comparison is the model's job, deliberately (see {@code docs/ARCHITECTURE.md}). This package's entire
 * responsibility is to store the text faithfully and to find the right prior version quickly.
 *
 * <p>Only crawled sources are represented. MCP sources are reached by the model directly, so the backend never holds
 * their content and cannot snapshot it. That gap is real and is declared rather than hidden - see
 * {@code mcpHistoryLimited} on a change report.
 *
 * <p>This is the largest collection in the database by a wide margin: one document per source per run, each holding
 * the full extracted text of a crawl. Every finder in {@link com.saasinvestigator.snapshot.SnapshotRepository} is
 * shaped to the compound index for that reason.
 */
package com.saasinvestigator.snapshot;
