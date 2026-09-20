package com.saasinvestigator.error;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Maps every exception escaping a controller to the single {@link ApiErrorResponse} JSON shape with
 * an appropriate HTTP status.
 *
 * <p>Set up in build step 1 specifically so every later step can rely on it: a service method can
 * throw {@link NotFoundException} and trust it becomes a clean 404, rather than each controller
 * re-implementing try/catch.
 *
 * <p>The division of labour on logging is deliberate. Client-caused failures (400/403/404/409) are
 * logged at WARN with just their message - they are routine and a stack trace per bad request is
 * noise. Anything unexpected is logged at ERROR <em>with</em> the stack trace, while the response
 * body gets a generic message: an internal exception message can name classes, queries, or hosts,
 * and none of that belongs in a client response.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handles a missing entity.
     *
     * @param ex the thrown exception
     * @param request the failing request, used for the {@code path} field
     * @return 404 with a message naming what was not found
     */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(
            NotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), request, ex, false);
    }

    /**
     * Handles a semantically invalid request.
     *
     * @param ex the thrown exception
     * @param request the failing request
     * @return 400 with the precise reason
     */
    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiErrorResponse> handleBadRequest(
            BadRequestException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage(), request, ex, false);
    }

    /**
     * Handles a collision with existing state.
     *
     * @param ex the thrown exception
     * @param request the failing request
     * @return 409 with the reason
     */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleConflict(
            ConflictException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "CONFLICT", ex.getMessage(), request, ex, false);
    }

    /**
     * Handles "no LLM provider available".
     *
     * @param ex the thrown exception
     * @param request the failing request
     * @return 503 with a message pointing at where to configure a credential
     */
    @ExceptionHandler(ProviderUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleProviderUnavailable(
            ProviderUnavailableException ex, HttpServletRequest request) {
        // Logged with its cause: a provider failure is usually worth investigating even though the
        // client-facing message is intentionally brief.
        log.warn("LLM provider unavailable for {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiErrorResponse.of(
                        "PROVIDER_UNAVAILABLE", ex.getMessage(), request.getRequestURI()));
    }

    /**
     * Handles bean-validation failures on {@code @Valid} request bodies.
     *
     * @param ex the validation failure, carrying per-field messages
     * @param request the failing request
     * @return 400 listing every rejected field so the form can highlight all of them at once
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        if (details.isBlank()) {
            details = "Request validation failed.";
        }
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", details, request, ex, false);
    }

    /**
     * Handles validation failures on method parameters (e.g. {@code @RequestParam} constraints).
     *
     * @param ex the validation failure
     * @param request the failing request
     * @return 400
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleHandlerValidation(
            HandlerMethodValidationException ex, HttpServletRequest request) {
        return build(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "One or more request parameters were invalid.",
                request,
                ex,
                false);
    }

    /**
     * Handles a missing required query parameter.
     *
     * @param ex the failure, carrying the parameter name
     * @param request the failing request
     * @return 400 naming the missing parameter
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingParam(
            MissingServletRequestParameterException ex, HttpServletRequest request) {
        return build(
                HttpStatus.BAD_REQUEST,
                "MISSING_PARAMETER",
                "Required parameter '" + ex.getParameterName() + "' is missing.",
                request,
                ex,
                false);
    }

    /**
     * Handles a query parameter or path variable that could not be converted to its declared type.
     *
     * <p>Worth a handler of its own because the most common case - a mistyped enum value such as
     * {@code ?action=LOGIN_SUCESS} or a date that is not ISO-8601 - would otherwise fall through to the
     * catch-all and become a 500, telling the caller their typo is a server fault. Naming the offending
     * parameter and, for enums, the values that would have worked is the difference between a fixable
     * error and a support ticket.
     *
     * @param ex the conversion failure
     * @param request the failing request
     * @return 400 naming the parameter, and its permitted values when the target type is an enum
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        StringBuilder message = new StringBuilder("Parameter '")
                .append(ex.getName())
                .append("' has an invalid value.");
        Class<?> target = ex.getRequiredType();
        if (target != null && target.isEnum()) {
            message.append(" Expected one of: ")
                    .append(Arrays.stream(target.getEnumConstants())
                            .map(String::valueOf)
                            .collect(Collectors.joining(", ")))
                    .append('.');
        }
        return build(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", message.toString(), request, ex, false);
    }

    /**
     * Handles an unparseable request body.
     *
     * @param ex the parse failure
     * @param request the failing request
     * @return 400 with a generic message - the parser's own message can echo raw payload content
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadable(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        return build(
                HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST",
                "Request body could not be parsed as JSON.",
                request,
                ex,
                false);
    }

    /**
     * Handles an illegal argument that reached the controller boundary (e.g. a bad enum value).
     *
     * @param ex the failure
     * @param request the failing request
     * @return 400
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(
            IllegalArgumentException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage(), request, ex, false);
    }

    /**
     * Handles a role violation. Spring Security's {@code @PreAuthorize} throws this.
     *
     * @param ex the denial
     * @param request the failing request
     * @return 403 with a clear message rather than an empty body
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest request) {
        return build(
                HttpStatus.FORBIDDEN,
                "FORBIDDEN",
                "Your role does not permit this action.",
                request,
                ex,
                false);
    }

    /**
     * Handles a rejected login.
     *
     * <p>Handled separately from {@link AuthenticationException} below so the login form can say "Invalid
     * username or password" instead of the generic session-expired wording, which is confusing on the very
     * screen you use to start a session. The message deliberately does not distinguish an unknown username
     * from a wrong password - that distinction is recorded in the audit log, where an admin can see it,
     * rather than handed to anyone who wants to enumerate valid usernames.
     *
     * @param ex the credentials failure
     * @param request the failing request
     * @return 401 with the login-specific wording
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiErrorResponse> handleBadCredentials(
            BadCredentialsException ex, HttpServletRequest request) {
        return build(
                HttpStatus.UNAUTHORIZED,
                "INVALID_CREDENTIALS",
                "Invalid username or password.",
                request,
                ex,
                false);
    }

    /**
     * Handles a failed or missing authentication.
     *
     * @param ex the authentication failure
     * @param request the failing request
     * @return 401
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthentication(
            AuthenticationException ex, HttpServletRequest request) {
        return build(
                HttpStatus.UNAUTHORIZED,
                "UNAUTHORIZED",
                "Authentication is required or your session has expired.",
                request,
                ex,
                false);
    }

    /**
     * Catch-all for anything unanticipated.
     *
     * @param ex the unexpected exception
     * @param request the failing request
     * @return 500 with a deliberately generic message; the real detail goes to the server log
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(
            Exception ex, HttpServletRequest request) {
        return build(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "Something went wrong handling this request. Check the server logs for detail.",
                request,
                ex,
                true);
    }

    /**
     * Assembles the response and logs at the severity appropriate to who caused the failure.
     *
     * @param status the HTTP status to return
     * @param code the stable machine-readable error code
     * @param message the client-facing message
     * @param request the failing request
     * @param ex the exception being mapped
     * @param unexpected {@code true} to log at ERROR with a stack trace, {@code false} for WARN
     * @return the response entity to return from the handler
     */
    private ResponseEntity<ApiErrorResponse> build(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request,
            Exception ex,
            boolean unexpected) {
        String path = request.getRequestURI();
        if (unexpected) {
            log.error("Unhandled exception on {} {}", request.getMethod(), path, ex);
        } else {
            log.warn("{} on {} {}: {}", code, request.getMethod(), path, ex.getMessage());
        }
        String safeMessage = (message == null || message.isBlank())
                ? status.getReasonPhrase()
                : message;
        return ResponseEntity.status(status).body(ApiErrorResponse.of(code, safeMessage, path));
    }
}
