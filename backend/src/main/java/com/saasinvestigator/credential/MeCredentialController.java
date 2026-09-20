package com.saasinvestigator.credential;

import com.saasinvestigator.audit.AuditAction;
import com.saasinvestigator.audit.AuditService;
import com.saasinvestigator.llm.LlmProviderType;
import com.saasinvestigator.security.AuthenticatedUser;
import com.saasinvestigator.security.CurrentUser;
import com.saasinvestigator.user.UserResponse;
import com.saasinvestigator.user.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own LLM credentials and provider preference - the Account Settings screen's backend.
 *
 * <p>Separate from {@code UserController} because that controller is uniformly
 * {@code @PreAuthorize("hasRole('ADMIN')")} and these endpoints are deliberately available to every
 * authenticated user, READ_ONLY included: bringing your own key is how a READ_ONLY user runs a report
 * without an admin having configured a system-wide one.
 *
 * <p><b>There is no user id in any path here.</b> Every operation acts on {@link CurrentUser}, taken from the
 * verified JWT. That is the access control: there is no id to tamper with, so no possibility of a
 * {@code /api/users/{someoneElse}/credentials} request at all. An {@code @PreAuthorize} expression comparing
 * a path variable to the principal would be the alternative, and it is one typo away from being wrong.
 */
@RestController
@RequestMapping("/api/users/me")
@Tag(name = "My account", description = "The caller's own LLM credentials and provider preference")
public class MeCredentialController {

    private final UserCredentialService credentialService;
    private final UserService userService;
    private final AuditService auditService;

    /**
     * @param credentialService stores and masks the caller's keys
     * @param userService persists the provider preference on the user document
     * @param auditService records credential changes, by provider only - never the key
     */
    public MeCredentialController(UserCredentialService credentialService, UserService userService,
                                 AuditService auditService) {
        this.credentialService = credentialService;
        this.userService = userService;
        this.auditService = auditService;
    }

    /**
     * Lists which providers the caller has a key stored for.
     *
     * @return one entry per stored credential, with a masked {@code last4} and never the key
     */
    @GetMapping("/credentials")
    @Operation(summary = "List my stored LLM credentials",
            description = "Returns { provider, configured, last4 } per configured provider. Never the key.")
    public List<CredentialStatus> listCredentials() {
        return credentialService.list(CurrentUser.require().userId());
    }

    /**
     * Stores or replaces the caller's key for a provider.
     *
     * @param request the provider and the key
     * @return the masked view of what is now stored
     */
    @PostMapping("/credentials")
    @Operation(summary = "Store or replace my API key for a provider",
            description = "Encrypted at rest with CREDENTIAL_ENCRYPTION_KEY. Replaces any existing key "
                    + "for the same provider.")
    public CredentialStatus storeCredential(@Valid @RequestBody CredentialRequest request) {
        AuthenticatedUser caller = CurrentUser.require();
        CredentialStatus stored = credentialService.store(
                caller.userId(), request.provider(), request.apiKey());
        // Only the provider. Not the key, not its last4, not its length - an audit log is read by admins,
        // and none of those facts about somebody else's personal key are any of their business.
        auditService.log(AuditAction.LLM_CREDENTIAL_ADDED, "UserLlmCredential", caller.userId(),
                Map.of("provider", request.provider().name()));
        return stored;
    }

    /**
     * Removes the caller's key for a provider.
     *
     * <p>Responds {@code 204} whether or not anything was stored. A {@code DELETE} is idempotent by
     * contract, and a second click on Remove reporting {@code 404} would present the state the user asked
     * for as an error. The audit entry is written only when something was actually removed, so the trail
     * records changes rather than clicks.
     *
     * @param provider which provider to forget
     */
    @DeleteMapping("/credentials/{provider}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove my API key for a provider")
    public void removeCredential(@PathVariable LlmProviderType provider) {
        AuthenticatedUser caller = CurrentUser.require();
        if (credentialService.remove(caller.userId(), provider)) {
            auditService.log(AuditAction.LLM_CREDENTIAL_REMOVED, "UserLlmCredential", caller.userId(),
                    Map.of("provider", provider.name()));
        }
    }

    /**
     * Sets which provider the caller would rather have handle their runs.
     *
     * <p>Accepts {@code null} to clear the preference. Setting a provider the caller has no key for is
     * allowed and simply has no effect yet - see {@link PreferredProviderRequest}.
     *
     * @param request the preferred provider, or {@code null}
     * @return the caller's updated user record, so the frontend can reflect the change without a refetch
     */
    @PutMapping("/preferred-provider")
    @Operation(summary = "Set my preferred LLM provider",
            description = "Takes effect only for a provider the caller also has a stored key for. "
                    + "Send null to clear and fall back to the system-wide default.")
    public UserResponse setPreferredProvider(@RequestBody PreferredProviderRequest request) {
        AuthenticatedUser caller = CurrentUser.require();
        return UserResponse.from(userService.updatePreferredProvider(caller.username(), request.provider()));
    }
}
