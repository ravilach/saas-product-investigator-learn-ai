package com.saasinvestigator.admin;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saasinvestigator.credential.CredentialSource;
import com.saasinvestigator.error.GlobalExceptionHandler;
import com.saasinvestigator.llm.LlmProviderType;
import com.saasinvestigator.testsupport.MethodSecuritySliceConfig;
import com.saasinvestigator.testsupport.TestPrincipals;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tests for {@link AdminHealthController} - the HTTP surface over the detailed health view.
 *
 * <h2>The behaviour that is easy to get wrong</h2>
 *
 * <p><b>A {@code DOWN} instance still answers {@code 200}.</b> This endpoint is a report <em>about</em> health, not a
 * health check: mapping {@code DOWN} onto a 503 would mean the console cannot render the page that explains why the
 * instance is unwell, at exactly the moment somebody opened it to find out. The instinct to make the status code match
 * the payload is strong and wrong here, so a test states it.
 *
 * <p><b>The response carries a {@code last4} and never a key.</b> The masking rule is enforced upstream, in
 * {@code SystemCredentialService}, but this is the response that would carry a leak to the browser if it were ever
 * broken - so the assertion is repeated at the boundary where it would be visible.
 *
 * <p>Whether the detail is correct is {@link AdminHealthServiceTest}'s subject, including two tests that run against a
 * real {@code HealthEndpoint}. What is left here is the status code, the role, and the shape of the JSON.
 */
@WebMvcTest(AdminHealthController.class)
@Import({MethodSecuritySliceConfig.class, GlobalExceptionHandler.class})
class AdminHealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminHealthService health;

    @Test
    void returnsTheComponentsProvidersLastRunAndLinksThePageRenders() throws Exception {
        when(health.health()).thenReturn(new AdminHealthResponse("UP",
                List.of(new AdminHealthResponse.ComponentHealth("mongo", "UP", Map.of("maxWireVersion", 25))),
                List.of(new AdminHealthResponse.ProviderHealth(LlmProviderType.ANTHROPIC, true,
                        CredentialSource.HOST_MOUNT, "wxyz")),
                new AdminHealthResponse.LastSuccessfulRun("Acme Analytics",
                        Instant.parse("2026-06-01T09:30:00Z"), "partial"),
                new AdminHealthResponse.Links("/swagger-ui.html", "/actuator/prometheus", "/actuator/health")));

        mockMvc.perform(get("/api/admin/health").with(TestPrincipals.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components[0].name").value("mongo"))
                // The detail an unauthenticated /actuator/health deliberately withholds. Its presence here is the point
                // of the endpoint existing at all.
                .andExpect(jsonPath("$.components[0].details.maxWireVersion").value(25))
                .andExpect(jsonPath("$.providers[0].configured").value(true))
                .andExpect(jsonPath("$.providers[0].source").value("HOST_MOUNT"))
                .andExpect(jsonPath("$.lastSuccessfulRun.productName").value("Acme Analytics"))
                .andExpect(jsonPath("$.links.prometheus").value("/actuator/prometheus"));
    }

    @Test
    void reportsAProvidersLastFourCharactersAndNoFieldThatCouldHoldTheKeyItself() throws Exception {
        when(health.health()).thenReturn(new AdminHealthResponse("UP", List.of(),
                List.of(new AdminHealthResponse.ProviderHealth(LlmProviderType.ANTHROPIC, true,
                        CredentialSource.PERSONAL, "wxyz")),
                null, new AdminHealthResponse.Links("/swagger-ui.html", "/actuator/prometheus", "/actuator/health")));

        mockMvc.perform(get("/api/admin/health").with(TestPrincipals.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers[0].last4").value("wxyz"))
                // Not "the key is absent from this fixture" but "the response record has nowhere to put one": a field
                // added later would fail here rather than quietly start serialising.
                .andExpect(jsonPath("$.providers[0].apiKey").doesNotExist())
                .andExpect(jsonPath("$.providers[0].apiKeyEncrypted").doesNotExist());
    }

    @Test
    void answers200WithADownStatusRatherThan503SoTheConsoleCanStillRenderThePage() throws Exception {
        when(health.health()).thenReturn(new AdminHealthResponse("DOWN",
                List.of(new AdminHealthResponse.ComponentHealth("mongo", "DOWN",
                        Map.of("error", "connection refused"))),
                List.of(), null,
                new AdminHealthResponse.Links("/swagger-ui.html", "/actuator/prometheus", "/actuator/health")));

        mockMvc.perform(get("/api/admin/health").with(TestPrincipals.admin()))
                // A 503 here would hide the explanation behind the failure it is explaining.
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components[0].details.error",
                        Matchers.containsString("connection refused")));
    }

    @Test
    void reportsANullLastSuccessfulRunRatherThanOmittingTheFieldOrInventingAPlaceholder() throws Exception {
        when(health.health()).thenReturn(new AdminHealthResponse("UP", List.of(), List.of(), null,
                new AdminHealthResponse.Links("/swagger-ui.html", "/actuator/prometheus", "/actuator/health")));

        mockMvc.perform(get("/api/admin/health").with(TestPrincipals.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastSuccessfulRun").isEmpty());
    }

    @Test
    void aReadOnlyUserCannotReadTheDetailedHealthView() throws Exception {
        // The whole design rests on this: /actuator/health is public and terse, and the detail is safe to show only
        // because it sits behind the role check. If READ_ONLY could read this, the terse public endpoint would be
        // pointless caution.
        mockMvc.perform(get("/api/admin/health").with(TestPrincipals.readOnly()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(health);
    }

    @Test
    void anAnonymousCallerCannotReadTheDetailedHealthView() throws Exception {
        mockMvc.perform(get("/api/admin/health")).andExpect(status().isForbidden());

        verifyNoInteractions(health);
    }
}
