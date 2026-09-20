package com.saasinvestigator.security;

import com.saasinvestigator.audit.AuditAction;
import com.saasinvestigator.audit.AuditService;
import com.saasinvestigator.user.User;
import com.saasinvestigator.user.UserRepository;
import com.saasinvestigator.user.UserResponse;
import com.saasinvestigator.user.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Login and "who am I" endpoints.
 *
 * <p>{@code POST /api/auth/login} is the only endpoint in the application reachable without a token. There
 * is no logout endpoint: auth is stateless, so logging out is the client discarding its token. Invalidating
 * tokens server-side is a different operation with a much bigger blast radius - rotating the signing secret
 * from the Admin Console, which signs out everyone at once.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "Login and current-user lookup")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserRepository userRepository;
    private final UserService userService;
    private final JwtService jwtService;
    private final AuditService auditService;

    /**
     * @param userRepository used to look the username up without throwing on a miss
     * @param userService supplies the BCrypt comparison
     * @param jwtService issues the token
     * @param auditService records both outcomes
     */
    public AuthController(UserRepository userRepository, UserService userService, JwtService jwtService,
                          AuditService auditService) {
        this.userRepository = userRepository;
        this.userService = userService;
        this.jwtService = jwtService;
        this.auditService = auditService;
    }

    /**
     * Authenticates a username/password pair and issues a JWT.
     *
     * <p>An unknown username and a wrong password produce the identical 401 and the identical message. The
     * difference is visible in the audit log, where an admin can see it, but not in the response, where it
     * would let anyone enumerate valid usernames.
     *
     * @param request the credentials
     * @return the token plus the authenticated user
     * @throws BadCredentialsException if the username is unknown or the password does not match
     */
    @PostMapping("/login")
    @Operation(summary = "Log in", description = "Exchanges a username and password for a signed JWT.")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        Optional<User> found = userRepository.findByUsername(request.username());

        if (found.isEmpty() || !userService.passwordMatches(found.get(), request.password())) {
            // Only the attempted username is recorded - never the password that was tried.
            auditService.logAs(found.map(User::getId).orElse(null), request.username(),
                    AuditAction.AUTH_LOGIN_FAILURE, "User", found.map(User::getId).orElse(null),
                    Map.of("reason", found.isEmpty() ? "unknown username" : "incorrect password"));
            log.warn("Failed login attempt for username '{}'", request.username());
            throw new BadCredentialsException("Invalid username or password.");
        }

        User user = found.get();
        String token = jwtService.issue(user);
        auditService.logAs(user.getId(), user.getUsername(), AuditAction.AUTH_LOGIN_SUCCESS, "User",
                user.getId(), Map.of("role", user.getRole().name()));
        log.info("User '{}' logged in", user.getUsername());

        return ResponseEntity.ok(LoginResponse.of(token,
                Instant.now().plus(jwtService.expiration()), UserResponse.from(user)));
    }

    /**
     * Returns the caller's own user record, as reconstructed from their token.
     *
     * <p>Used by the frontend on a page reload, when it has a token in {@code localStorage} but no user
     * object in memory. Reading from the database rather than from the token's claims means a role change
     * or a rename shows up on the next refresh instead of persisting until the token expires.
     *
     * @return the caller's user record
     */
    @GetMapping("/me")
    @Operation(summary = "Current user", description = "Returns the authenticated caller's own user record.")
    public UserResponse me() {
        return UserResponse.from(userService.findByUsername(CurrentUser.require().username()));
    }
}
