package com.saasinvestigator.security;

import com.saasinvestigator.audit.AuditAction;
import com.saasinvestigator.audit.AuditService;
import com.saasinvestigator.systemconfig.SystemConfigDocument;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The JWT signing secret override - Admin Console → Secrets.
 *
 * <p><b>Both mutating endpoints sign every user out, including the caller.</b> Tokens are stateless and
 * signed; changing the key that verifies them is what invalidation means, so there is no selective version of
 * this and no way to exempt the admin who pressed the button. The next request from any session - including
 * the one that made this call - gets a {@code 401}. That is correct behaviour, and the audit entry written
 * before it happens is the record of who did it, since the actor's own session will be gone a moment later.
 *
 * <p>The build prompt requires the UI to state this plainly and confirm, rather than bury it as fine print.
 * The API contributes what it can: an explicit {@code sessionsInvalidated} field in the response, so a client
 * cannot treat these as ordinary settings writes without noticing.
 *
 * <p>Nothing here returns the secret, in any form, ever - not even a masked tail. See {@link JwtSecretStatus}
 * for why that differs from how API keys are reported.
 */
@RestController
@RequestMapping("/api/admin/jwt-secret")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin: secrets", description = "The JWT signing secret override")
public class JwtSecretController {

    private final JwtSecretResolver secretResolver;
    private final AuditService auditService;

    /**
     * @param secretResolver owns the resolution chain, the override, and the cache that has to be dropped
     *     when it changes
     * @param auditService records the change before the actor's own session becomes invalid
     */
    public JwtSecretController(JwtSecretResolver secretResolver, AuditService auditService) {
        this.secretResolver = secretResolver;
        this.auditService = auditService;
    }

    /**
     * Reports whether a secret is in effect and which channel supplied it.
     *
     * @return the status; never the value
     */
    @GetMapping
    @Operation(summary = "Report the JWT signing secret's source",
            description = "Returns { configured, source: ADMIN_OVERRIDE | ENV_VAR | AUTO_GENERATED }. "
                    + "Never returns the secret, not even masked.")
    public JwtSecretStatus status() {
        return new JwtSecretStatus(true, secretResolver.activeSource());
    }

    /**
     * Sets an explicit signing secret, overriding {@code JWT_SECRET} and the auto-generated value.
     *
     * <p>Audit-logged <em>before</em> the change takes effect for a practical reason as well as a tidy one:
     * once the key changes, this request's own security context is the last one that will ever be valid under
     * the old key, so recording the actor afterwards would risk recording nothing.
     *
     * @param request the new secret
     * @return confirmation including the resulting source and an explicit note that sessions were invalidated
     */
    @PutMapping
    @Operation(summary = "Set the JWT signing secret override",
            description = "INVALIDATES EVERY ISSUED TOKEN, including the caller's own. Requires the client "
                    + "to have confirmed with the user first.")
    public Map<String, Object> setOverride(@Valid @RequestBody JwtSecretRequest request) {
        // details records that the secret changed and nothing about its value - not its length, not a
        // prefix, not a hash. An audit log is the last place a signing secret should be recoverable from.
        auditService.log(AuditAction.JWT_SECRET_OVERRIDE_SET, "SystemConfig",
                SystemConfigDocument.KEY_JWT_SIGNING_SECRET_OVERRIDE,
                Map.of("effect", "all sessions invalidated"));
        secretResolver.applyOverride(request.value());
        return Map.of(
                "configured", true,
                "source", JwtSecretSource.ADMIN_OVERRIDE,
                "sessionsInvalidated", true);
    }

    /**
     * Clears the override, reverting to {@code JWT_SECRET} or the auto-generated secret.
     *
     * <p>Responds {@code 204}. Note that this invalidates every session <em>even when there was no override
     * to clear</em> in the sense that the resolver's cache is dropped - but the key itself only changes if an
     * override actually existed, so an admin clearing an already-clear override does not sign anyone out. The
     * audit entry is written only in the case that really changed something.
     */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Clear the JWT signing secret override",
            description = "Reverts to JWT_SECRET, or to the auto-generated persisted secret. Invalidates "
                    + "every issued token if an override was in effect.")
    public void clearOverride() {
        if (secretResolver.hasOverride()) {
            auditService.log(AuditAction.JWT_SECRET_OVERRIDE_CLEARED, "SystemConfig",
                    SystemConfigDocument.KEY_JWT_SIGNING_SECRET_OVERRIDE,
                    Map.of("effect", "all sessions invalidated"));
        }
        secretResolver.clearOverride();
    }
}
