/**
 * Cross-cutting error handling.
 *
 * <p>Holds the small set of application exceptions the service layer throws to signal an HTTP
 * outcome ({@link com.saasinvestigator.error.NotFoundException},
 * {@link com.saasinvestigator.error.BadRequestException},
 * {@link com.saasinvestigator.error.ConflictException},
 * {@link com.saasinvestigator.error.ProviderUnavailableException}) plus the single
 * {@code @RestControllerAdvice} that maps every exception to one consistent JSON shape.
 *
 * <p>The house rule this package exists to enforce: clients get a clear, actionable message and a
 * correct status code; full exception detail including stack traces is logged server-side and never
 * serialised into a response.
 */
package com.saasinvestigator.error;
