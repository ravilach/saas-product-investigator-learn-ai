package com.saasinvestigator.audit;

import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Spring Data repository over {@code audit_logs}.
 *
 * <p>Deliberately minimal: the filterable {@code GET /api/audit-logs} query combines optional
 * {@code actorUsername}, {@code action}, and date-range predicates, which as derived query methods would
 * need one method per combination. {@code AuditService} builds a single dynamic {@code Query} instead.
 */
public interface AuditLogRepository extends MongoRepository<AuditLog, String> {
}
