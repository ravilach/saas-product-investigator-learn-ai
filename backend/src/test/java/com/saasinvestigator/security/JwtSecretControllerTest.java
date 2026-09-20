package com.saasinvestigator.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
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
import com.saasinvestigator.systemconfig.SystemConfigDocument;
import com.saasinvestigator.testsupport.MethodSecuritySliceConfig;
import com.saasinvestigator.testsupport.TestPrincipals;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tests the JWT signing-secret endpoints.
 *
 * <p>The thing being protected here is unusual: this is the one secret in the application that is never returned
 * in <em>any</em> form, not even as a masked tail, because nobody legitimate would recognise four characters of it
 * while an attacker would still have their search space narrowed. Several of these tests therefore assert on what
 * the response body does <b>not</b> contain.
 *
 * <p>The audit ordering test is the other one worth reading twice. The actor has to be recorded before the key
 * changes, because a moment afterwards their own security context is signed out along with everyone else's.
 */
@WebMvcTest(JwtSecretController.class)
@Import({MethodSecuritySliceConfig.class, GlobalExceptionHandler.class})
class JwtSecretControllerTest {

    private static final String SECRET = "a-32-character-minimum-signing-secret-value";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtSecretResolver secretResolver;

    @MockitoBean
    private AuditService auditService;

    @Test
    void aReadOnlyUserCannotSeeTheSecretsSource() throws Exception {
        mockMvc.perform(get("/api/admin/jwt-secret").with(TestPrincipals.readOnly()))
                .andExpect(status().isForbidden());

        verify(secretResolver, never()).activeSource();
    }

    @Test
    void aReadOnlyUserCannotSignEveryoneOut() throws Exception {
        // This endpoint is a one-click global logout. It being admin-only is the whole reason it can exist.
        mockMvc.perform(put("/api/admin/jwt-secret")
                        .with(TestPrincipals.readOnly())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"" + SECRET + "\"}"))
                .andExpect(status().isForbidden());

        verify(secretResolver, never()).applyOverride(anyString());
        verify(auditService, never()).log(any(), anyString(), anyString(), any());
    }

    @Test
    void aReadOnlyUserCannotClearTheOverride() throws Exception {
        mockMvc.perform(delete("/api/admin/jwt-secret").with(TestPrincipals.readOnly()))
                .andExpect(status().isForbidden());

        verify(secretResolver, never()).clearOverride();
    }

    @Test
    void theStatusReportsTheSourceAndNeverTheValue() throws Exception {
        when(secretResolver.activeSource()).thenReturn(JwtSecretSource.AUTO_GENERATED);

        mockMvc.perform(get("/api/admin/jwt-secret").with(TestPrincipals.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.source").value("AUTO_GENERATED"))
                // Unlike every API key status in this application, there is no last4 - see JwtSecretStatus.
                .andExpect(jsonPath("$.value").doesNotExist())
                .andExpect(jsonPath("$.last4").doesNotExist())
                .andExpect(jsonPath("$.secret").doesNotExist());
    }

    @Test
    void theStatusAlwaysReportsConfigured() throws Exception {
        // There is no state in which no signing secret exists: with no override and no JWT_SECRET, one is
        // generated on first boot and persisted. A false here would mean the app could not issue tokens.
        when(secretResolver.activeSource()).thenReturn(JwtSecretSource.ENV_VAR);

        mockMvc.perform(get("/api/admin/jwt-secret").with(TestPrincipals.admin()))
                .andExpect(jsonPath("$.configured").value(true));
    }

    @Test
    void settingTheOverrideSaysPlainlyThatSessionsWereInvalidated() throws Exception {
        mockMvc.perform(put("/api/admin/jwt-secret")
                        .with(TestPrincipals.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"" + SECRET + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("ADMIN_OVERRIDE"))
                // The frontend has to confirm this with the user rather than bury it, so the API states it
                // outright instead of leaving a client free to treat this as an ordinary settings write.
                .andExpect(jsonPath("$.sessionsInvalidated").value(true))
                .andExpect(content().string(Matchers.not(Matchers.containsString(SECRET))));

        verify(secretResolver).applyOverride(SECRET);
    }

    @Test
    void theActorIsRecordedBeforeTheKeyThatAuthenticatesThemChanges() throws Exception {
        mockMvc.perform(put("/api/admin/jwt-secret")
                        .with(TestPrincipals.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"" + SECRET + "\"}"))
                .andExpect(status().isOk());

        InOrder order = inOrder(auditService, secretResolver);
        order.verify(auditService).log(AuditAction.JWT_SECRET_OVERRIDE_SET, "SystemConfig",
                SystemConfigDocument.KEY_JWT_SIGNING_SECRET_OVERRIDE,
                Map.of("effect", "all sessions invalidated"));
        order.verify(secretResolver).applyOverride(SECRET);
    }

    @Test
    void theAuditEntryCarriesNothingAboutTheSecretNotEvenItsLength() throws Exception {
        mockMvc.perform(put("/api/admin/jwt-secret")
                        .with(TestPrincipals.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"" + SECRET + "\"}"))
                .andExpect(status().isOk());

        // An exact-match on details. An audit log is the last place a signing secret should be recoverable
        // from, and "length: 43" plus a known generator is a real reduction in work for an attacker.
        verify(auditService).log(AuditAction.JWT_SECRET_OVERRIDE_SET, "SystemConfig",
                SystemConfigDocument.KEY_JWT_SIGNING_SECRET_OVERRIDE,
                Map.of("effect", "all sessions invalidated"));
    }

    @Test
    void aTooShortSecretIsRejectedBeforeItCanWeakenAnything() throws Exception {
        // The resolver SHA-256s whatever it is given, so a four-character secret would be accepted by the
        // crypto and be trivially guessable. That is exactly the case worth rejecting at the edge.
        mockMvc.perform(put("/api/admin/jwt-secret")
                        .with(TestPrincipals.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"tooshort\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("32")));

        verify(secretResolver, never()).applyOverride(anyString());
    }

    @Test
    void anEmptySecretIsRejected() throws Exception {
        mockMvc.perform(put("/api/admin/jwt-secret")
                        .with(TestPrincipals.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"\"}"))
                .andExpect(status().isBadRequest());

        verify(secretResolver, never()).applyOverride(anyString());
    }

    @Test
    void clearingAnOverrideThatExistsIsAuditedBeforeItTakesEffect() throws Exception {
        when(secretResolver.hasOverride()).thenReturn(true);

        mockMvc.perform(delete("/api/admin/jwt-secret").with(TestPrincipals.admin()))
                .andExpect(status().isNoContent());

        InOrder order = inOrder(auditService, secretResolver);
        order.verify(auditService).log(AuditAction.JWT_SECRET_OVERRIDE_CLEARED, "SystemConfig",
                SystemConfigDocument.KEY_JWT_SIGNING_SECRET_OVERRIDE,
                Map.of("effect", "all sessions invalidated"));
        order.verify(secretResolver).clearOverride();
    }

    @Test
    void clearingAnOverrideThatDoesNotExistChangesNothingAndIsNotAudited() throws Exception {
        when(secretResolver.hasOverride()).thenReturn(false);

        // Still 204: the caller asked for "no override", and that is the state. But nobody was signed out, so
        // an audit entry claiming otherwise would be a false record.
        mockMvc.perform(delete("/api/admin/jwt-secret").with(TestPrincipals.admin()))
                .andExpect(status().isNoContent());

        verify(auditService, never()).log(any(), anyString(), anyString(), any());
    }
}
