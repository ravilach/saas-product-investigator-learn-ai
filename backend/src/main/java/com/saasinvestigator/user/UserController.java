package com.saasinvestigator.user;

import com.saasinvestigator.audit.AuditAction;
import com.saasinvestigator.audit.AuditService;
import com.saasinvestigator.credential.UserCredentialService;
import com.saasinvestigator.error.ApiErrorResponse;
import com.saasinvestigator.error.BadRequestException;
import com.saasinvestigator.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * User management, admin-only throughout.
 *
 * <p>Every response goes through {@link UserResponse}, which has no {@code passwordHash} field at all - so
 * "never return the hash" is enforced by the type rather than by remembering to strip it.
 *
 * <p>Self-service endpoints for the caller's own credentials and provider preference live on
 * {@code /api/users/me/...} and are added with the credential store; they are not admin-gated and are
 * intentionally kept separate from this controller's uniform {@code @PreAuthorize("hasRole('ADMIN')")}.
 */
@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Users", description = "Administrative user management")
public class UserController {

    private final UserService userService;
    private final AuditService auditService;
    private final UserCredentialService credentialService;

    /**
     * @param userService performs the lifecycle operations
     * @param auditService records each mutation
     * @param credentialService consulted only on delete, to discard the departing user's stored API keys
     */
    public UserController(UserService userService, AuditService auditService,
            UserCredentialService credentialService) {
        this.userService = userService;
        this.auditService = auditService;
        this.credentialService = credentialService;
    }

    /**
     * Lists every user.
     *
     * @return all users, oldest first, without password hashes
     */
    @GetMapping
    @Operation(summary = "List users", description = "Returns every user. Never includes password hashes.")
    public List<UserResponse> list() {
        return userService.findAll().stream().map(UserResponse::from).toList();
    }

    /**
     * Creates a user.
     *
     * @param request the new user's details and role
     * @return the created user
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create user", description = "Creates a user with an admin-assigned role.")
    @ApiResponse(responseCode = "409", description = "That username or email address is already registered.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class)))
    public UserResponse create(@Valid @RequestBody CreateUserRequest request) {
        User created = userService.create(request.firstName(), request.lastName(), request.username(),
                request.email(), request.password(), request.role());
        // Only non-secret context: who was created and with what role, never the password.
        auditService.log(AuditAction.USER_CREATED, "User", created.getId(),
                Map.of("username", created.getUsername(), "role", created.getRole().name()));
        return UserResponse.from(created);
    }

    /**
     * Deletes a user.
     *
     * <p>Refuses to delete the caller's own account. Deleting yourself leaves you holding a token whose
     * subject no longer exists, which produces confusing failures on every subsequent request rather than a
     * clean logout.
     *
     * <p>Their stored LLM API keys go with them. Leaving the rows behind would accumulate encrypted secrets
     * belonging to people who no longer have accounts, keyed by an id nothing resolves any more - and a
     * recreated user with a recycled id would silently inherit them.
     *
     * @param id the user to delete
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete user",
            description = "Deletes the user and discards any LLM API keys they had stored.")
    public void delete(@PathVariable String id) {
        if (id.equals(CurrentUser.require().userId())) {
            throw new BadRequestException("You cannot delete your own account.");
        }
        User deleted = userService.delete(id);
        long credentialsDiscarded = credentialService.forgetAll(id);
        auditService.log(AuditAction.USER_DELETED, "User", id,
                Map.of("username", deleted.getUsername(), "role", deleted.getRole().name(),
                        "credentialsDiscarded", credentialsDiscarded));
    }

    /**
     * Resets a user's password to a new value.
     *
     * <p>The current password is not required and cannot be shown: it is stored as a one-way BCrypt hash.
     * See USERS &amp; ROLES in the build prompt for why this is the reset-not-view equivalent.
     *
     * @param id the user whose password is being reset
     * @param request the new password
     * @return the updated user
     */
    @PutMapping("/{id}/password")
    @Operation(summary = "Reset a user's password",
            description = "Sets a new password. The existing one cannot be viewed - it is a one-way hash.")
    public UserResponse resetPassword(@PathVariable String id,
                                      @Valid @RequestBody ResetPasswordRequest request) {
        User updated = userService.resetPassword(id, request.newPassword());
        // details records that a reset happened and to whom - never the old or new value.
        auditService.log(AuditAction.USER_PASSWORD_RESET, "User", id,
                Map.of("username", updated.getUsername()));
        return UserResponse.from(updated);
    }
}
