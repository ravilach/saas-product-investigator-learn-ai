package com.saasinvestigator.audit;

import java.time.Instant;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One audit trail entry, stored in the {@code audit_logs} collection.
 *
 * <p>{@code actorUsername} is stored alongside {@code actorUserId} on purpose. Denormalising it means the
 * trail still reads correctly after the acting user is deleted, which is the exact moment an audit log
 * matters most; joining back to {@code users} would leave those entries showing a dangling id.
 */
@Document(collection = "audit_logs")
public class AuditLog {

    @Id
    private String id;

    /** The acting user's id, or {@code null} for a failed login where no user was identified. */
    private String actorUserId;

    /** The acting user's username at the time, or the attempted username for a failed login. */
    private String actorUsername;

    private AuditAction action;

    /** What kind of thing was acted on, e.g. {@code User}, {@code SaasProduct}, {@code SystemConfig}. */
    private String targetType;

    /** The id or name of the thing acted on, or {@code null} where the action has no single target. */
    private String targetId;

    /**
     * Non-secret context about what happened - e.g. {@code {"fieldsChanged": ["sources", "description"]}}.
     * Never a password, API key, {@code authToken}, or signing secret, encrypted or otherwise.
     */
    private Map<String, Object> details;

    private Instant timestamp;

    /** Required by Spring Data's mapping layer. */
    public AuditLog() {
    }

    /**
     * @param actorUserId the acting user's id, or {@code null}
     * @param actorUsername the acting or attempted username
     * @param action what happened
     * @param targetType the kind of thing acted on, or {@code null}
     * @param targetId the id of the thing acted on, or {@code null}
     * @param details non-secret context, or {@code null}
     */
    public AuditLog(String actorUserId, String actorUsername, AuditAction action, String targetType,
                    String targetId, Map<String, Object> details) {
        this.actorUserId = actorUserId;
        this.actorUsername = actorUsername;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.details = details;
        this.timestamp = Instant.now();
    }

    /** @return the Mongo document id */
    public String getId() {
        return id;
    }

    /** @param id the Mongo document id */
    public void setId(String id) {
        this.id = id;
    }

    /** @return the acting user's id, or {@code null} */
    public String getActorUserId() {
        return actorUserId;
    }

    /** @param actorUserId the acting user's id */
    public void setActorUserId(String actorUserId) {
        this.actorUserId = actorUserId;
    }

    /** @return the acting or attempted username */
    public String getActorUsername() {
        return actorUsername;
    }

    /** @param actorUsername the acting or attempted username */
    public void setActorUsername(String actorUsername) {
        this.actorUsername = actorUsername;
    }

    /** @return what happened */
    public AuditAction getAction() {
        return action;
    }

    /** @param action what happened */
    public void setAction(AuditAction action) {
        this.action = action;
    }

    /** @return the kind of thing acted on */
    public String getTargetType() {
        return targetType;
    }

    /** @param targetType the kind of thing acted on */
    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    /** @return the id of the thing acted on */
    public String getTargetId() {
        return targetId;
    }

    /** @param targetId the id of the thing acted on */
    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    /** @return non-secret context about the action */
    public Map<String, Object> getDetails() {
        return details;
    }

    /** @param details non-secret context about the action */
    public void setDetails(Map<String, Object> details) {
        this.details = details;
    }

    /** @return when the action happened */
    public Instant getTimestamp() {
        return timestamp;
    }

    /** @param timestamp when the action happened */
    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }
}
