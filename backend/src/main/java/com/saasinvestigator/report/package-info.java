/**
 * Change reports - the application's actual output.
 *
 * <p>Everything else exists to produce one of these. A report pairs the model's findings with the metadata that
 * makes them checkable: who asked, when, over what window, at what depth, from which sources as of when, and
 * whether any part of it came with a caveat.
 *
 * <p><b>Nothing in this package decides what counts as a change.</b> {@code overallSummary} and {@code changes} come
 * from the model; this package's contribution is the surrounding provenance and the guarantee that it is accurate.
 * A report is written once and never edited - a report that can be revised cannot be cited - which is why there is
 * no update path for one, the same reasoning as for an audit entry.
 *
 * <p>Three small closed enums do more work here than their size suggests:
 * {@link com.saasinvestigator.report.ChangeCategory} and {@link com.saasinvestigator.report.Confidence} keep the
 * model from inventing its own vocabulary run to run (which would make filtering and grouping meaningless), and both
 * parse leniently so one unexpected word cannot cost an otherwise sound report.
 * {@link com.saasinvestigator.report.AnalysisDepth} carries the prompt instruction and output budget for all three
 * depths in one place, so no provider implementation gets to have its own opinion about what NUCLEAR means.
 */
package com.saasinvestigator.report;
