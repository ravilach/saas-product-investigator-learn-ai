package com.saasinvestigator.audit;

import java.time.Instant;
import java.util.Map;

/**
 * One audit entry as the API returns it.
 *
 * <p>Currently a field-for-field copy of {@link AuditLog}, which is worth having anyway: it keeps the wire shape
 * from drifting whenever the document gains a field, and it means adding something internal to the document (a
 * retention marker, a schema version) does not silently publish it.
 *
 * @param id the entry id
 * @param actorUserId the acting user's id, or {@code null} for a failed login with no identified user
 * @param actorUsername the acting or attempted username
 * @param action what happened
 * @param targetType the kind of thing acted on, or {@code null}
 * @param targetId the id of the thing acted on, or {@code null}
 * @param details non-secret context; never a password, API key, {@code authToken}, or signing secret
 * @param timestamp when it happened
 */
public record AuditLogResponse(
        String id,
        String actorUserId,
        String actorUsername,
        AuditAction action,
        String targetType,
        String targetId,
        Map<String, Object> details,
        Instant timestamp) {

    /**
     * @param entry the stored entry
     * @return its API representation
     */
    public static AuditLogResponse from(AuditLog entry) {
        return new AuditLogResponse(
                entry.getId(),
                entry.getActorUserId(),
                entry.getActorUsername(),
                entry.getAction(),
                entry.getTargetType(),
                entry.getTargetId(),
                entry.getDetails(),
                entry.getTimestamp());
    }
}
