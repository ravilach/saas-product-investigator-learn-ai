package com.saasinvestigator.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saasinvestigator.error.GlobalExceptionHandler;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tests for {@link AuditLogController}.
 *
 * <p>Two categories of assertion here, both about not being able to get at the trail incorrectly: a READ_ONLY user
 * cannot read it at all, and the query parameters an admin supplies are validated and capped before they reach the
 * database rather than after.
 */
@WebMvcTest(AuditLogController.class)
@Import({MethodSecuritySliceConfig.class, GlobalExceptionHandler.class})
class AuditLogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuditService auditService;

    @Test
    void readOnlyUserCannotReadTheAuditTrail() throws Exception {
        mockMvc.perform(get("/api/audit-logs").with(TestPrincipals.readOnly()))
                .andExpect(status().isForbidden());

        // The trail records who did what to whom; a non-admin must not even trigger the query.
        verifyNoInteractions(auditService);
    }

    @Test
    void anonymousCallerCannotReadTheAuditTrail() throws Exception {
        mockMvc.perform(get("/api/audit-logs")).andExpect(status().isForbidden());

        verifyNoInteractions(auditService);
    }

    @Test
    void adminGetsAPageOfEntriesInTheSharedEnvelope() throws Exception {
        when(auditService.search(isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(page(entry("admin", AuditAction.AUTH_LOGIN_SUCCESS)));

        mockMvc.perform(get("/api/audit-logs").with(TestPrincipals.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].actorUsername").value("admin"))
                .andExpect(jsonPath("$.content[0].action").value("AUTH_LOGIN_SUCCESS"))
                .andExpect(jsonPath("$.content[0].details.role").value("ADMIN"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.last").value(true))
                // Spring Data's own Page shape would put a "pageable" object here; the fixed envelope must not.
                .andExpect(jsonPath("$.pageable").doesNotExist());
    }

    @Test
    void filtersArePassedThroughExactlyAsGiven() throws Exception {
        Instant from = Instant.parse("2026-09-01T00:00:00Z");
        Instant to = Instant.parse("2026-09-20T00:00:00Z");
        when(auditService.search(eq("dana"), eq(AuditAction.USER_PASSWORD_RESET), eq(from), eq(to),
                any(Pageable.class)))
                .thenReturn(page());

        mockMvc.perform(get("/api/audit-logs")
                        .with(TestPrincipals.admin())
                        .param("actorUsername", "dana")
                        .param("action", "USER_PASSWORD_RESET")
                        .param("from", from.toString())
                        .param("to", to.toString()))
                .andExpect(status().isOk());

        verify(auditService).search(eq("dana"), eq(AuditAction.USER_PASSWORD_RESET), eq(from), eq(to),
                any(Pageable.class));
    }

    @Test
    void anOversizedPageRequestIsCappedRatherThanHonoured() throws Exception {
        when(auditService.search(isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(page());

        mockMvc.perform(get("/api/audit-logs").with(TestPrincipals.admin()).param("size", "1000000"))
                .andExpect(status().isOk());

        // Capping rather than rejecting: the caller gets data, just not the whole collection at once.
        var pageable = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(auditService).search(isNull(), isNull(), isNull(), isNull(), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(AuditLogController.MAX_PAGE_SIZE);
    }

    @Test
    void resultsAreRequestedNewestFirst() throws Exception {
        when(auditService.search(isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(page());

        mockMvc.perform(get("/api/audit-logs").with(TestPrincipals.admin())).andExpect(status().isOk());

        var pageable = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(auditService).search(isNull(), isNull(), isNull(), isNull(), pageable.capture());
        assertThat(pageable.getValue().getSort().getOrderFor("timestamp")).isNotNull();
        assertThat(pageable.getValue().getSort().getOrderFor("timestamp").isDescending()).isTrue();
    }

    @Test
    void anInvertedDateRangeIsRejectedRatherThanReturningNothing() throws Exception {
        mockMvc.perform(get("/api/audit-logs")
                        .with(TestPrincipals.admin())
                        .param("from", "2026-09-20T00:00:00Z")
                        .param("to", "2026-09-01T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("must not be after")));

        // An empty page here would be indistinguishable from "nothing happened in that window".
        verify(auditService, never()).search(any(), any(), any(), any(), any());
    }

    @Test
    void anUnknownActionIsA400ThatListsTheValidOnes() throws Exception {
        mockMvc.perform(get("/api/audit-logs").with(TestPrincipals.admin()).param("action", "LOGIN_SUCESS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("AUTH_LOGIN_SUCCESS")));

        verify(auditService, never()).search(any(), any(), any(), any(), any());
    }

    @Test
    void anUnparseableDateIsA400NotA500() throws Exception {
        mockMvc.perform(get("/api/audit-logs").with(TestPrincipals.admin()).param("from", "last Tuesday"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("from")));
    }

    @Test
    void aNegativePageNumberIsRejected() throws Exception {
        mockMvc.perform(get("/api/audit-logs").with(TestPrincipals.admin()).param("page", "-1"))
                .andExpect(status().isBadRequest());

        verify(auditService, never()).search(any(), any(), any(), any(), any());
    }

    private static Page<AuditLog> page(AuditLog... entries) {
        List<AuditLog> content = List.of(entries);
        return new PageImpl<>(content, PageRequest.of(0, AuditLogController.DEFAULT_PAGE_SIZE),
                content.size());
    }

    private static AuditLog entry(String actorUsername, AuditAction action) {
        AuditLog entry = new AuditLog("id-admin", actorUsername, action, "User", "id-admin",
                Map.of("role", "ADMIN"));
        entry.setId("audit-1");
        return entry;
    }
}
