package com.saasinvestigator.audit;

import com.saasinvestigator.security.AuthenticatedUser;
import com.saasinvestigator.security.CurrentUser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Writes and queries the audit trail.
 *
 * <p>Two entry points for writing: {@link #log} takes the acting user from the security context, which
 * covers every request-scoped call; {@link #logAs} takes the actor explicitly, for the login flow (no
 * security context exists yet) and for background work.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository repository;
    private final MongoTemplate mongoTemplate;

    /**
     * @param repository the {@code audit_logs} repository
     * @param mongoTemplate used for the dynamic filter query, which has too many optional predicates to
     *     express as derived query methods
     */
    public AuditService(AuditLogRepository repository, MongoTemplate mongoTemplate) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Records an action by the currently authenticated user.
     *
     * @param action what happened
     * @param targetType the kind of thing acted on, or {@code null}
     * @param targetId the id of the thing acted on, or {@code null}
     * @param details non-secret context, or {@code null}. Must never contain a password, API key,
     *     {@code authToken}, or signing secret - see this package's documentation.
     */
    public void log(AuditAction action, String targetType, String targetId, Map<String, Object> details) {
        AuthenticatedUser actor = CurrentUser.find().orElse(null);
        logAs(actor == null ? null : actor.userId(),
                actor == null ? "system" : actor.username(),
                action, targetType, targetId, details);
    }

    /**
     * Records an action by an explicitly named actor.
     *
     * <p>Failures are swallowed and logged rather than propagated. An audit write that fails should not
     * turn a successful login or a successful product update into a 500 the user has to retry - the action
     * already happened, and surfacing the audit failure as the action's failure would misreport what
     * actually occurred. The ERROR line is the signal that the trail has a gap.
     *
     * @param actorUserId the acting user's id, or {@code null} if unknown
     * @param actorUsername the acting or attempted username
     * @param action what happened
     * @param targetType the kind of thing acted on, or {@code null}
     * @param targetId the id of the thing acted on, or {@code null}
     * @param details non-secret context, or {@code null}
     */
    public void logAs(String actorUserId, String actorUsername, AuditAction action, String targetType,
                      String targetId, Map<String, Object> details) {
        try {
            repository.save(new AuditLog(actorUserId, actorUsername, action, targetType, targetId, details));
        } catch (RuntimeException e) {
            log.error("Failed to write audit entry {} for actor {} (target {}/{}). The action itself "
                    + "succeeded; the audit trail now has a gap.",
                    action, actorUsername, targetType, targetId, e);
        }
    }

    /**
     * Returns a page of audit entries, newest first, narrowed by any filters supplied.
     *
     * @param actorUsername exact username to filter by, or {@code null} for all actors
     * @param action action to filter by, or {@code null} for all actions
     * @param from inclusive lower bound on {@code timestamp}, or {@code null}
     * @param to inclusive upper bound on {@code timestamp}, or {@code null}
     * @param pageable the requested page; sorting is forced to newest-first regardless of what is asked
     *     for, because an audit trail read in any other order is not useful
     * @return the matching page
     */
    public Page<AuditLog> search(String actorUsername, AuditAction action, Instant from, Instant to,
                                 Pageable pageable) {
        List<Criteria> criteria = new ArrayList<>();
        if (actorUsername != null && !actorUsername.isBlank()) {
            criteria.add(Criteria.where("actorUsername").is(actorUsername.trim()));
        }
        if (action != null) {
            criteria.add(Criteria.where("action").is(action.name()));
        }
        if (from != null && to != null) {
            criteria.add(Criteria.where("timestamp").gte(from).lte(to));
        } else if (from != null) {
            criteria.add(Criteria.where("timestamp").gte(from));
        } else if (to != null) {
            criteria.add(Criteria.where("timestamp").lte(to));
        }

        Query query = new Query();
        if (!criteria.isEmpty()) {
            query.addCriteria(new Criteria().andOperator(criteria.toArray(new Criteria[0])));
        }

        // Count before paginating: Query is mutable and with(pageable) applies skip/limit in place, which
        // would otherwise make the total match the page size.
        long total = mongoTemplate.count(query, AuditLog.class);
        List<AuditLog> entries = mongoTemplate.find(
                query.with(pageable).with(Sort.by(Sort.Direction.DESC, "timestamp")), AuditLog.class);
        return new PageImpl<>(entries, pageable, total);
    }

    /**
     * @return the total number of audit entries, for the Admin Console stats panel
     */
    public long count() {
        return repository.count();
    }
}
