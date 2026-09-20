package com.saasinvestigator.admin;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saasinvestigator.error.GlobalExceptionHandler;
import com.saasinvestigator.report.RunType;
import com.saasinvestigator.testsupport.MethodSecuritySliceConfig;
import com.saasinvestigator.testsupport.TestPrincipals;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tests for {@link AdminStatsController} - the Admin Console's Overview figures.
 *
 * <h2>Why a pass-through controller is worth a test</h2>
 *
 * <p>Two reasons, neither of which is the method body. The first is the role check: the counts are individually
 * unremarkable, but together they describe how much of the instance is configured and how often it works, which is
 * reconnaissance rather than dashboard data for an account that should not have it. The annotation is on the class here,
 * so one careless refactor into a shared base class removes it from every endpoint at once.
 *
 * <p>The second is that the nullable rates survive serialisation as {@code null}. A fresh instance has not failed every
 * run, it has not run anything - and a dashboard showing "0% success" on an untouched install reads as a broken one. A
 * primitive {@code double} on the response record would turn that distinction into {@code 0.0} silently.
 */
@WebMvcTest(AdminStatsController.class)
@Import({MethodSecuritySliceConfig.class, GlobalExceptionHandler.class})
class AdminStatsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminStatsService stats;

    @Test
    void returnsEveryFigureTheOverviewTabRendersIncludingTheRecentActivityList() throws Exception {
        when(stats.stats()).thenReturn(new AdminStatsResponse(3, 2, 5, 1, 4, 0.75, 25.0,
                List.of(new AdminStatsResponse.RecentRun("Acme Analytics", RunType.STANDARD, "partial",
                        Instant.parse("2026-06-01T09:30:00Z")))));

        mockMvc.perform(get("/api/admin/stats").with(TestPrincipals.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalUsers").value(3))
                .andExpect(jsonPath("$.totalProducts").value(2))
                .andExpect(jsonPath("$.totalSourcesConfigured").value(5))
                .andExpect(jsonPath("$.runsLast24h").value(1))
                .andExpect(jsonPath("$.runsLast7d").value(4))
                .andExpect(jsonPath("$.runSuccessRate7d").value(0.75))
                .andExpect(jsonPath("$.avgRunDurationSeconds7d").value(25.0))
                .andExpect(jsonPath("$.recentRuns[0].productName").value("Acme Analytics"))
                .andExpect(jsonPath("$.recentRuns[0].runType").value("STANDARD"))
                .andExpect(jsonPath("$.recentRuns[0].status").value("partial"));
    }

    @Test
    void serialisesTheRatesAsNullOnAnUnusedInstanceRatherThanAsZero() throws Exception {
        when(stats.stats()).thenReturn(new AdminStatsResponse(1, 0, 0, 0, 0, null, null, List.of()));

        mockMvc.perform(get("/api/admin/stats").with(TestPrincipals.admin()))
                .andExpect(status().isOk())
                // A 0% success rate and an installation nobody has used look identical otherwise, and only one of them
                // is a reason to go and read the logs.
                .andExpect(jsonPath("$.runSuccessRate7d").isEmpty())
                .andExpect(jsonPath("$.avgRunDurationSeconds7d").isEmpty())
                .andExpect(jsonPath("$.recentRuns").isEmpty());
    }

    @Test
    void aReadOnlyUserCannotReadTheInstanceStatistics() throws Exception {
        mockMvc.perform(get("/api/admin/stats").with(TestPrincipals.readOnly()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(stats);
    }

    @Test
    void anAnonymousCallerCannotReadTheInstanceStatistics() throws Exception {
        mockMvc.perform(get("/api/admin/stats")).andExpect(status().isForbidden());

        verifyNoInteractions(stats);
    }
}
