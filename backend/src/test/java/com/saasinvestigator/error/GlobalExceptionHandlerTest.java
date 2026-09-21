package com.saasinvestigator.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Tests the handlers that are hard to reach through a controller slice, because nothing throws them on purpose.
 *
 * <p>{@link NoResourceFoundException} is raised by Spring itself when no mapping and no static resource match a
 * request. Until it was handled here it fell through to the catch-all and every mistyped URL answered
 * <strong>500 INTERNAL_ERROR</strong>, logged at ERROR with a full stack trace. Two things were wrong with
 * that: it told the client the server had broken when the client had simply asked for something that was never
 * there, and it meant any bot probing for {@code /wp-login.php} wrote error-level noise that reads like an
 * outage in progress.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /**
     * The exception Spring raises for an unmatched request. Its own message is irrelevant here - the handler
     * builds the client-facing one from the request, so that it names the path the client actually sent.
     */
    private static NoResourceFoundException unmatched(HttpMethod method, String path) {
        return new NoResourceFoundException(method, path, "No static resource");
    }

    @Test
    void anUnknownPathIsA404NamingWhatWasAskedFor() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/does-not-exist");

        ResponseEntity<ApiErrorResponse> response = handler.handleNoResource(
                unmatched(HttpMethod.GET, "/api/does-not-exist"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("NOT_FOUND");
        // The method and path are echoed because the usual cause is a typo or a stale client, and naming the
        // request back is what makes that obvious without going to the server log.
        assertThat(response.getBody().message()).isEqualTo("No endpoint matches GET /api/does-not-exist.");
        assertThat(response.getBody().path()).isEqualTo("/api/does-not-exist");
    }

    @Test
    void theSameShapeIsUsedForAnyMethod() {
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/saas-products/nope/x");

        ResponseEntity<ApiErrorResponse> response = handler.handleNoResource(
                unmatched(HttpMethod.DELETE, "/api/saas-products/nope/x"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).contains("DELETE");
    }

    @Test
    void anythingGenuinelyUnexpectedIsStillA500WithNothingInternalInIt() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/saas-products");

        ResponseEntity<ApiErrorResponse> response = handler.handleUnexpected(
                new IllegalStateException("connection to mongodb://user:pw@db-7.internal refused"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        // The exception's own message names a host and a credential. It belongs in the log, not in a response.
        assertThat(response.getBody().message())
                .isEqualTo("Something went wrong handling this request. Check the server logs for detail.")
                .doesNotContain("mongodb", "db-7.internal");
    }
}
