package com.saasinvestigator.user;

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
import com.saasinvestigator.credential.UserCredentialService;
import com.saasinvestigator.error.GlobalExceptionHandler;
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
 * Role-enforcement tests for {@link UserController}.
 *
 * <p>The point of these is not that the happy path works but that the <em>unhappy</em> one does: a READ_ONLY caller
 * hitting an ADMIN-only endpoint must get a 403 <em>and</em> the underlying service must never be touched. Asserting
 * only the status code would still pass if the handler had run, mutated state, and then been denied on the way out.
 */
@WebMvcTest(UserController.class)
@Import({MethodSecuritySliceConfig.class, GlobalExceptionHandler.class})
class UserControllerRoleTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private AuditService auditService;

    @MockitoBean
    private UserCredentialService credentialService;

    @Test
    void readOnlyUserCannotListUsers() throws Exception {
        mockMvc.perform(get("/api/users").with(TestPrincipals.readOnly()))
                .andExpect(status().isForbidden());

        verify(userService, never()).findAll();
    }

    @Test
    void readOnlyUserCannotCreateAUser() throws Exception {
        mockMvc.perform(post("/api/users")
                        .with(TestPrincipals.readOnly())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"New","lastName":"User","username":"newbie",
                                 "email":"new@example.com","password":"password","role":"ADMIN"}"""))
                .andExpect(status().isForbidden());

        verify(userService, never())
                .create(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        verify(auditService, never()).log(any(), anyString(), anyString(), any());
    }

    @Test
    void readOnlyUserCannotResetAPassword() throws Exception {
        mockMvc.perform(put("/api/users/someone/password")
                        .with(TestPrincipals.readOnly())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"brand-new-password\"}"))
                .andExpect(status().isForbidden());

        verify(userService, never()).resetPassword(anyString(), anyString());
    }

    @Test
    void readOnlyUserCannotDeleteAUser() throws Exception {
        mockMvc.perform(delete("/api/users/someone").with(TestPrincipals.readOnly()))
                .andExpect(status().isForbidden());

        verify(userService, never()).delete(anyString());
    }

    @Test
    void anonymousCallerCannotListUsers() throws Exception {
        mockMvc.perform(get("/api/users")).andExpect(status().isForbidden());

        verify(userService, never()).findAll();
    }

    @Test
    void adminCanListUsersAndTheResponseNeverCarriesAPasswordHash() throws Exception {
        User user = new User("Admin", "User", "admin", "admin@localhost",
                "$2a$10$aVeryRealLookingBcryptHashThatMustNotBeReturned", Role.ADMIN);
        user.setId("user-1");
        when(userService.findAll()).thenReturn(List.of(user));

        mockMvc.perform(get("/api/users").with(TestPrincipals.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("admin"))
                // UserResponse has no such field, so this cannot fail today. It is here to fail loudly if
                // someone later "simplifies" the controller into returning the entity directly.
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist())
                .andExpect(content().string(Matchers.not(Matchers.containsString("$2a$10$"))));
    }

    @Test
    void adminResettingAPasswordIsAuditedWithoutTheNewValue() throws Exception {
        User user = new User("Dana", "Scully", "dana", "dana@example.com", "hash", Role.READ_ONLY);
        user.setId("user-2");
        when(userService.resetPassword("user-2", "brand-new-password")).thenReturn(user);

        mockMvc.perform(put("/api/users/user-2/password")
                        .with(TestPrincipals.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"brand-new-password\"}"))
                .andExpect(status().isOk());

        // The exact-match on details is the assertion that matters: nothing resembling the password is in it.
        verify(auditService).log(AuditAction.USER_PASSWORD_RESET, "User", "user-2",
                Map.of("username", "dana"));
    }

    @Test
    void deletingAUserAlsoDiscardsTheirStoredApiKeys() throws Exception {
        User user = new User("Dana", "Scully", "dana", "dana@example.com", "hash", Role.READ_ONLY);
        user.setId("user-2");
        when(userService.delete("user-2")).thenReturn(user);
        when(credentialService.forgetAll("user-2")).thenReturn(1L);

        mockMvc.perform(delete("/api/users/user-2").with(TestPrincipals.admin()))
                .andExpect(status().isNoContent());

        // Leaving the rows behind would accumulate encrypted secrets belonging to people who no longer have
        // accounts, keyed by an id nothing resolves any more.
        verify(credentialService).forgetAll("user-2");
        verify(auditService).log(AuditAction.USER_DELETED, "User", "user-2",
                Map.of("username", "dana", "role", "READ_ONLY", "credentialsDiscarded", 1L));
    }

    @Test
    void adminCannotDeleteTheirOwnAccount() throws Exception {
        mockMvc.perform(delete("/api/users/self-id")
                        .with(TestPrincipals.principal("admin", Role.ADMIN, "self-id")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You cannot delete your own account."));

        verify(userService, never()).delete(anyString());
    }

    @Test
    void createRejectsAnInvalidEmailBeforeReachingTheService() throws Exception {
        mockMvc.perform(post("/api/users")
                        .with(TestPrincipals.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"New","lastName":"User","username":"newbie",
                                 "email":"not-an-email","password":"password","role":"READ_ONLY"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("email")));

        verify(userService, never())
                .create(anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }
}
