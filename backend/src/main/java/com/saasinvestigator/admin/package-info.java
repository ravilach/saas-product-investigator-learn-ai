/**
 * The Admin Console's own backend: statistics, detailed health, non-secret settings, and the Data Explorer.
 *
 * <h2>What is here and what is not</h2>
 *
 * <p>The Admin Console in the UI is one area with several tabs, but only some of them are new endpoints. Users live in
 * {@code user}, Secrets in {@code credential} and {@code security}, the Audit Log in {@code audit} - each beside the
 * thing it administers, because a package that gathered every admin-only endpoint would separate them from the code
 * whose invariants they have to respect. This package holds the four that exist only for the console.
 *
 * <h2>Every endpoint here is {@code hasRole('ADMIN')}</h2>
 *
 * <p>Declared at class level on each controller rather than relying on the {@code /api/admin/**} path, so the
 * restriction travels with the code if a path is ever remapped.
 *
 * <h2>Two deliberate separations</h2>
 *
 * <ul>
 *   <li>{@link com.saasinvestigator.admin.AdminHealthController} is not {@code /actuator/health}. That one is
 *       unauthenticated for liveness probes and stays minimal so it leaks nothing; this one is behind the admin check
 *       and is where the real detail belongs.</li>
 *   <li>{@link com.saasinvestigator.admin.AdminStatsService} reads the database rather than scraping the metrics it
 *       also publishes. The meter registry resets on restart, so the same numbers taken from there would quietly mean
 *       "since the last deploy".</li>
 * </ul>
 *
 * <h2>The Data Explorer's guardrail is the point of it</h2>
 *
 * <p>{@link com.saasinvestigator.admin.SecretFieldMasker} masks server-side and rejects on write, so no ciphertext or
 * password hash reaches the browser and none can be overwritten through a generic editor. Read its class comment before
 * changing anything in that path.
 */
package com.saasinvestigator.admin;
