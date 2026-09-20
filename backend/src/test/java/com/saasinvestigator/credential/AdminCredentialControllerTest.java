package com.saasinvestigator.credential;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saasinvestigator.audit.AuditAction;
import com.saasinvestigator.audit.AuditService;
import com.saasinvestigator.error.GlobalExceptionHandler;
import com.saasinvestigator.llm.LlmProviderType;
import com.saasinvestigator.testsupport.MethodSecuritySliceConfig;
import com.saasinvestigator.testsupport.TestPrincipals;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Role-enforcement and masking tests for the system-wide credential endpoints.
 *
 * <p>These are the mirror image of {@link MeCredentialControllerTest}: the personal endpoints must be open to
 * every authenticated user, and these must be closed to all but admins. A system-wide key is spent on everyone's
 * runs, so who can set it is a billing question as much as a security one.
 */
@WebMvcTest(AdminCredentialController.class)
@Import({MethodSecuritySliceConfig.class, GlobalExceptionHandler.class})
class AdminCredentialControllerTest {

    private static final String KEY = "sk-ant-api03-system-wide-key-wxyz";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SystemCredentialService systemCredentials;

    @MockitoBean
    private AuditService auditService;

    @Test
    void aReadOnlyUserCannotSeeWhichSystemKeysAreConfigured() throws Exception {
        // Even the statuses are admin-only. "Anthropic is configured, ending wxyz, from an env var" is
        // reconnaissance, not a user-facing fact.
        mockMvc.perform(get("/api/admin/system-credentials").with(TestPrincipals.readOnly()))
                .andExpect(status().isForbidden());

        verify(systemCredentials, never()).statuses();
    }

    @Test
    void aReadOnlyUserCannotSetASystemWideKey() throws Exception {
        mockMvc.perform(put("/api/admin/system-credentials")
                        .with(TestPrincipals.readOnly())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"ANTHROPIC\",\"apiKey\":\"" + KEY + "\"}"))
                .andExpect(status().isForbidden());

        verify(systemCredentials, never()).setOverride(any(), anyString(), anyString());
        verify(auditService, never()).log(any(), anyString(), anyString(), any());
    }

    @Test
    void aReadOnlyUserCannotClearASystemWideKey() throws Exception {
        mockMvc.perform(delete("/api/admin/system-credentials/ANTHROPIC").with(TestPrincipals.readOnly()))
                .andExpect(status().isForbidden());

        verify(systemCredentials, never()).clearOverride(any());
    }

    @Test
    void anAnonymousCallerIsRefused() throws Exception {
        mockMvc.perform(get("/api/admin/system-credentials")).andExpect(status().isForbidden());

        verify(systemCredentials, never()).statuses();
    }

    @Test
    void anAdminSeesEveryProvidersStatusWithItsSourceAndNeverAKey() throws Exception {
        when(systemCredentials.statuses()).thenReturn(List.of(
                new SystemCredentialStatus(LlmProviderType.ANTHROPIC, true, "wxyz", CredentialSource.ENV_VAR),
                SystemCredentialStatus.none(LlmProviderType.OPENAI)));

        mockMvc.perform(get("/api/admin/system-credentials").with(TestPrincipals.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].source").value("ENV_VAR"))
                .andExpect(jsonPath("$[0].last4").value("wxyz"))
                .andExpect(jsonPath("$[1].configured").value(false))
                .andExpect(jsonPath("$[1].source").value("NONE"))
                // There is no field on SystemCredentialStatus that could hold a key. This asserts that stays
                // true of the serialised form.
                .andExpect(content().string(Matchers.not(Matchers.containsString("sk-"))));
    }

    @Test
    void anAdminSettingAKeyGetsBackOnlyItsTailAndIsAudited() throws Exception {
        when(systemCredentials.setOverride(LlmProviderType.ANTHROPIC, KEY, "admin")).thenReturn(
                new SystemCredentialStatus(LlmProviderType.ANTHROPIC, true, "wxyz",
                        CredentialSource.OVERRIDE));

        mockMvc.perform(put("/api/admin/system-credentials")
                        .with(TestPrincipals.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"ANTHROPIC\",\"apiKey\":\"" + KEY + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("OVERRIDE"))
                .andExpect(jsonPath("$.last4").value("wxyz"))
                .andExpect(content().string(Matchers.not(Matchers.containsString(KEY))));

        verify(auditService).log(AuditAction.SYSTEM_CREDENTIAL_OVERRIDE_SET, "SystemLlmCredential",
                "ANTHROPIC", Map.of("provider", "ANTHROPIC"));
    }

    @Test
    void clearingAnOverrideIsAuditedOnlyWhenThereWasOne() throws Exception {
        when(systemCredentials.clearOverride(LlmProviderType.OPENAI)).thenReturn(false);

        mockMvc.perform(delete("/api/admin/system-credentials/OPENAI").with(TestPrincipals.admin()))
                .andExpect(status().isNoContent());

        verify(auditService, never()).log(any(), anyString(), anyString(), any());

        when(systemCredentials.clearOverride(LlmProviderType.ANTHROPIC)).thenReturn(true);

        mockMvc.perform(delete("/api/admin/system-credentials/ANTHROPIC").with(TestPrincipals.admin()))
                .andExpect(status().isNoContent());

        verify(auditService).log(AuditAction.SYSTEM_CREDENTIAL_OVERRIDE_CLEARED, "SystemLlmCredential",
                "ANTHROPIC", Map.of("provider", "ANTHROPIC"));
    }

    @Test
    void anUnknownProviderIsABadRequest() throws Exception {
        // Cursor is explicitly out of scope as a provider, and this is what asking for it looks like.
        mockMvc.perform(delete("/api/admin/system-credentials/CURSOR").with(TestPrincipals.admin()))
                .andExpect(status().isBadRequest());

        verify(systemCredentials, never()).clearOverride(any());
    }

    @Test
    void anEmptyKeyIsRejectedBeforeReachingTheService() throws Exception {
        // Clearing is what DELETE is for. A blank PUT would otherwise store an empty override that shadows a
        // perfectly good environment variable.
        mockMvc.perform(put("/api/admin/system-credentials")
                        .with(TestPrincipals.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"ANTHROPIC\",\"apiKey\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("apiKey")));

        verify(systemCredentials, never()).setOverride(any(), anyString(), anyString());
    }
}
