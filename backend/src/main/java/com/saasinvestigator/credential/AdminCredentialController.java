package com.saasinvestigator.credential;

import com.saasinvestigator.audit.AuditAction;
import com.saasinvestigator.audit.AuditService;
import com.saasinvestigator.config.OpenApiConfig;
import com.saasinvestigator.llm.LlmProviderType;
import com.saasinvestigator.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The system-wide LLM credential override - Admin Console → Secrets.
 *
 * <p>These keys are used by any run whose initiating user has no personal credential for their preferred
 * provider, which is every run until somebody brings their own key. They are the difference between a fresh
 * container being useful and being a login screen attached to nothing.
 *
 * <p>Only {@code GET} reveals anything, and what it reveals is a {@code last4} plus which channel is winning -
 * never a key, not even one the caller just set. Nothing here can read back a stored key in full, because
 * nothing needs to: the operations are "set", "clear", and "tell me what is in effect".
 */
@RestController
@RequestMapping("/api/admin/system-credentials")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = OpenApiConfig.ADMIN_SECRETS_TAG, description = OpenApiConfig.ADMIN_SECRETS_TAG_DESCRIPTION)
public class AdminCredentialController {

    private final SystemCredentialService systemCredentials;
    private final AuditService auditService;

    /**
     * @param systemCredentials owns the store and the resolution chain
     * @param auditService records overrides being set and cleared, by provider only
     */
    public AdminCredentialController(SystemCredentialService systemCredentials, AuditService auditService) {
        this.systemCredentials = systemCredentials;
        this.auditService = auditService;
    }

    /**
     * Reports what is configured for every provider and where it came from.
     *
     * @return one row per provider, including providers with nothing configured
     */
    @GetMapping
    @Operation(summary = "List system-wide credentials",
            description = "Per provider: whether a key is available, its last4, and which channel supplied "
                    + "it (OVERRIDE, ENV_VAR, HOST_MOUNT, or NONE). Never the key itself.")
    public List<SystemCredentialStatus> list() {
        return systemCredentials.statuses();
    }

    /**
     * Sets or replaces the system-wide key for a provider.
     *
     * <p>Takes effect on the next run, on every replica, with no restart: the chain is read fresh each time
     * rather than cached.
     *
     * @param request the provider and the key
     * @return the resulting status, reporting {@link CredentialSource#OVERRIDE}
     */
    @PutMapping
    @Operation(summary = "Set the system-wide key for a provider",
            description = "Stored encrypted. Takes precedence over the provider's environment variable and "
                    + "over the host-mounted credential file.")
    public SystemCredentialStatus setOverride(@Valid @RequestBody CredentialRequest request) {
        String actor = CurrentUser.require().username();
        SystemCredentialStatus status = systemCredentials.setOverride(
                request.provider(), request.apiKey(), actor);
        auditService.log(AuditAction.SYSTEM_CREDENTIAL_OVERRIDE_SET, "SystemLlmCredential",
                request.provider().name(), Map.of("provider", request.provider().name()));
        return status;
    }

    /**
     * Clears the system-wide override for a provider, reverting to env var then host-mounted file.
     *
     * <p>Responds {@code 204} whether or not an override existed, for the same reason as the personal
     * endpoint: clearing something already clear produced the state the caller asked for.
     *
     * @param provider which provider's override to remove
     */
    @DeleteMapping("/{provider}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Clear the system-wide override for a provider",
            description = "Reverts to the provider's environment variable, then the host-mounted file.")
    public void clearOverride(@PathVariable LlmProviderType provider) {
        if (systemCredentials.clearOverride(provider)) {
            auditService.log(AuditAction.SYSTEM_CREDENTIAL_OVERRIDE_CLEARED, "SystemLlmCredential",
                    provider.name(), Map.of("provider", provider.name()));
        }
    }
}
