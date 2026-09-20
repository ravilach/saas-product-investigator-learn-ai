package com.saasinvestigator.security;

import com.saasinvestigator.error.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Renders 401s and 403s raised inside the filter chain in the same JSON shape as every other error.
 *
 * <p>{@code GlobalExceptionHandler} cannot help here: a request rejected by the filter chain never reaches
 * a controller, so without this the client would get Spring's default HTML error page for an unauthenticated
 * call and this project's {@link ApiErrorResponse} for everything else. The frontend would then need two
 * parsers for the same failure.
 *
 * <p>Implements both Spring Security callbacks in one class because the two bodies differ only in status
 * and wording.
 *
 * <p>The two {@code error} codes below deliberately match the ones {@code GlobalExceptionHandler} uses for the
 * same conditions. A 403 can be raised in either place - by the filter chain before a controller is reached, or
 * by {@code @PreAuthorize} once it has been - and a client switching on {@code error} must not have to care
 * which. Keep them in sync if either side changes.
 */
@Component
public class SecurityErrorWriter implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    /**
     * @param objectMapper the application's Jackson mapper, so these bodies serialise exactly like
     *     controller responses (Jackson 3, which is what Spring Boot 4 uses for HTTP)
     */
    public SecurityErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Called when an unauthenticated request hits a protected endpoint. */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         org.springframework.security.core.AuthenticationException authException)
            throws IOException {
        write(request, response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                "Authentication required. Send a valid 'Authorization: Bearer <token>' header - "
                        + "log in again if your session has expired.");
    }

    /** Called when an authenticated request lacks the role an endpoint requires. */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       org.springframework.security.access.AccessDeniedException accessDeniedException)
            throws IOException {
        write(request, response, HttpStatus.FORBIDDEN, "FORBIDDEN",
                "Your role does not permit this action.");
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
                       String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                ApiErrorResponse.of(code, message, request.getRequestURI()));
    }
}
