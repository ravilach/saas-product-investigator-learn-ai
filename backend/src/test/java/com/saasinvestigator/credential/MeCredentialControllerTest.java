package com.saasinvestigator.credential;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.saasinvestigator.user.Role;
import com.saasinvestigator.user.User;
import com.saasinvestigator.user.UserService;
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
 * Tests the personal credential endpoints.
 *
 * <p>Two properties matter here and neither is the happy path.
 *
 * <p>The first is that these endpoints are <b>available to READ_ONLY users</b>. Bringing your own key is not an
 * administrative act - it is how a read-only user pays for their own LLM usage - so a test that only checked
 * admin access would pass while the feature was broken for most of the people who need it.
 *
 * <p>The second is that <b>the caller's identity comes from the token, never from the request</b>. There is no
 * user id in any of these paths, so the tests assert that the id reaching the service is the authenticated one.
 */
@WebMvcTest(MeCredentialController.class)
@Import({MethodSecuritySliceConfig.class, GlobalExceptionHandler.class})
class MeCredentialControllerTest {

    private static final String KEY = "sk-ant-api03-a-real-looking-key-wxyz";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserCredentialService credentialService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private AuditService auditService;

    @Test
    void aReadOnlyUserCanListTheirOwnCredentials() throws Exception {
        when(credentialService.list("id-reader"))
                .thenReturn(List.of(new CredentialStatus(LlmProviderType.ANTHROPIC, true, "wxyz")));

        mockMvc.perform(get("/api/users/me/credentials").with(TestPrincipals.readOnly()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].provider").value("ANTHROPIC"))
                .andExpect(jsonPath("$[0].last4").value("wxyz"));
    }

    @Test
    void aReadOnlyUserCanStoreTheirOwnKey() throws Exception {
        when(credentialService.store("id-reader", LlmProviderType.ANTHROPIC, KEY))
                .thenReturn(new CredentialStatus(LlmProviderType.ANTHROPIC, true, "wxyz"));

        mockMvc.perform(post("/api/users/me/credentials")
                        .with(TestPrincipals.readOnly())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"ANTHROPIC\",\"apiKey\":\"" + KEY + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                // The response echoes a tail and nothing more. Returning the key would put it in every
                // browser cache and proxy log between here and the user.
                .andExpect(jsonPath("$.last4").value("wxyz"))
                .andExpect(content().string(Matchers.not(Matchers.containsString(KEY))));

        // The user id is the authenticated one, not anything the request could influence.
        verify(credentialService).store("id-reader", LlmProviderType.ANTHROPIC, KEY);
    }

    @Test
    void storingAKeyIsAuditedWithoutAnythingAboutTheKey() throws Exception {
        when(credentialService.store(anyString(), any(), anyString()))
                .thenReturn(new CredentialStatus(LlmProviderType.OPENAI, true, "wxyz"));

        mockMvc.perform(post("/api/users/me/credentials")
                        .with(TestPrincipals.readOnly())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"OPENAI\",\"apiKey\":\"" + KEY + "\"}"))
                .andExpect(status().isOk());

        // An exact match on details: not the key, not its tail, not its length. The audit log is read by
        // admins, and none of those facts about someone else's personal key are their business.
        verify(auditService).log(AuditAction.LLM_CREDENTIAL_ADDED, "UserLlmCredential", "id-reader",
                Map.of("provider", "OPENAI"));
    }

    @Test
    void aTooShortKeyIsRejectedBeforeReachingTheService() throws Exception {
        mockMvc.perform(post("/api/users/me/credentials")
                        .with(TestPrincipals.readOnly())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"ANTHROPIC\",\"apiKey\":\"short\"}"))
                .andExpect(status().isBadRequest());

        verify(credentialService, never()).store(anyString(), any(), anyString());
    }

    @Test
    void aMissingProviderIsRejected() throws Exception {
        mockMvc.perform(post("/api/users/me/credentials")
                        .with(TestPrincipals.readOnly())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"" + KEY + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("provider")));

        verify(credentialService, never()).store(anyString(), any(), anyString());
    }

    @Test
    void removingAKeyRespondsNoContentAndAuditsOnlyARealRemoval() throws Exception {
        when(credentialService.remove("id-reader", LlmProviderType.ANTHROPIC)).thenReturn(true);

        mockMvc.perform(delete("/api/users/me/credentials/ANTHROPIC").with(TestPrincipals.readOnly()))
                .andExpect(status().isNoContent());

        verify(auditService).log(AuditAction.LLM_CREDENTIAL_REMOVED, "UserLlmCredential", "id-reader",
                Map.of("provider", "ANTHROPIC"));
    }

    @Test
    void removingAKeyThatWasNotThereIsStillNoContentButIsNotAudited() throws Exception {
        when(credentialService.remove("id-reader", LlmProviderType.ANTHROPIC)).thenReturn(false);

        // A second click on Remove is not an error - it asked for a state that already holds.
        mockMvc.perform(delete("/api/users/me/credentials/ANTHROPIC").with(TestPrincipals.readOnly()))
                .andExpect(status().isNoContent());

        verify(auditService, never()).log(any(), anyString(), anyString(), any());
    }

    @Test
    void anUnknownProviderInThePathIsABadRequestRatherThanAServerError() throws Exception {
        mockMvc.perform(delete("/api/users/me/credentials/CURSOR").with(TestPrincipals.readOnly()))
                .andExpect(status().isBadRequest());

        verify(credentialService, never()).remove(anyString(), any());
    }

    @Test
    void aUserCanChooseTheirPreferredProvider() throws Exception {
        User user = new User("Dana", "Scully", "reader", "dana@example.com", "hash", Role.READ_ONLY);
        user.setId("id-reader");
        user.setPreferredLlmProvider(LlmProviderType.OPENAI);
        when(userService.updatePreferredProvider("reader", LlmProviderType.OPENAI)).thenReturn(user);

        mockMvc.perform(put("/api/users/me/preferred-provider")
                        .with(TestPrincipals.readOnly())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"OPENAI\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferredLlmProvider").value("OPENAI"));
    }

    @Test
    void aNullProviderClearsThePreferenceRatherThanBeingRejected() throws Exception {
        User user = new User("Dana", "Scully", "reader", "dana@example.com", "hash", Role.READ_ONLY);
        user.setId("id-reader");
        when(userService.updatePreferredProvider("reader", null)).thenReturn(user);

        // "No preference" is a legitimate state - it means "use whatever the system default is" - so the
        // request body deliberately has no @NotNull on provider.
        mockMvc.perform(put("/api/users/me/preferred-provider")
                        .with(TestPrincipals.readOnly())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":null}"))
                .andExpect(status().isOk());

        verify(userService).updatePreferredProvider("reader", null);
    }

    @Test
    void anAnonymousCallerGetsNothing() throws Exception {
        // In the running application SecurityConfig's anyRequest().authenticated() turns this into a 401
        // before the handler is reached. This slice deliberately permits every path at the URL level, so what
        // it pins down is the layer underneath: with no principal, CurrentUser.require() fails loudly rather
        // than the endpoint quietly resolving to some other user's credentials. The status is not the point -
        // the never() is.
        mockMvc.perform(get("/api/users/me/credentials"))
                .andExpect(status().is5xxServerError());

        verify(credentialService, never()).list(anyString());
    }
}
