/**
 * The append-only audit trail over every mutating action and every login attempt.
 *
 * <p>Written by explicit {@code AuditService.log(...)} calls at the end of each relevant service method
 * rather than by an AOP aspect. An aspect would be less code but it would also make "is this action
 * audited?" invisible at the call site, and a pointcut that silently stops matching after a refactor fails
 * quietly - the failure mode being a missing audit entry nobody notices.
 *
 * <p><b>Hard rule on {@code details}:</b> it must never contain a password, an LLM API key, an MCP
 * {@code authToken}, or the JWT signing secret - encrypted or not. Record which fields changed, never their
 * values. An audit log is read by more people, and exported more casually, than the collections it
 * describes; a secret copied into it has effectively been published.
 */
package com.saasinvestigator.audit;
