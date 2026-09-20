package com.saasinvestigator.run;

import com.saasinvestigator.report.AnalysisDepth;

/**
 * Body of {@code POST /api/saas-products/{id}/run}.
 *
 * <p>One optional field, and the endpoint accepts no body at all. Running a product needs no input beyond which
 * product - the sources, their limits, and their credentials are all configuration - so requiring a body would make
 * the most common action in the application need a payload to say nothing.
 *
 * @param analysisDepth how thorough to be, or {@code null} for {@link AnalysisDepth#DEFAULT}
 */
public record RunRequest(AnalysisDepth analysisDepth) {
}
