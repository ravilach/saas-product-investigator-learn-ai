package com.saasinvestigator.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saasinvestigator.audit.AuditAction;
import com.saasinvestigator.audit.AuditService;
import com.saasinvestigator.crawl.CrawlSettings;
import com.saasinvestigator.error.BadRequestException;
import com.saasinvestigator.error.GlobalExceptionHandler;
import com.saasinvestigator.testsupport.MethodSecuritySliceConfig;
import com.saasinvestigator.testsupport.TestPrincipals;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tests for {@link AdminSettingsController} - the crawl defaults.
 *
 * <h2>The two things worth pinning down</h2>
 *
 * <p><b>The ceilings are returned alongside the values.</b> They are what lets the form state the allowed range instead
 * of making an admin discover it by being rejected, and they are the easiest thing to drop from a response that still
 * looks complete.
 *
 * <p><b>The audit entry carries the previous values as well as the new ones.</b> This is the one place in the
 * application where an audit entry deliberately records values rather than field names, because a crawl depth is not a
 * secret and "the crawl settings changed" answers nothing. A later reader applying the no-values rule uniformly would
 * strip exactly the information this entry exists for, so a test states the exception.
 *
 * <p>Out-of-range values are rejected rather than clamped, which is {@link CrawlSettings}' own behaviour; the assertion
 * here is only that the controller lets the rejection through instead of turning it into a 500.
 */
@WebMvcTest(AdminSettingsController.class)
@Import({MethodSecuritySliceConfig.class, GlobalExceptionHandler.class})
class AdminSettingsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CrawlSettings crawlSettings;
    @MockitoBean
    private AuditService audit;

    // ----- Reading -----

    @Test
    void returnsTheCurrentDefaultsTogetherWithTheCeilingsTheFormNeedsToStateTheRange() throws Exception {
        when(crawlSettings.currentDefaults()).thenReturn(new CrawlSettings.CrawlDefaults(2, 50));
        when(crawlSettings.ceilings()).thenReturn(new CrawlSettings.CrawlDefaults(5, 300));

        mockMvc.perform(get("/api/admin/settings").with(TestPrincipals.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultMaxDepth").value(2))
                .andExpect(jsonPath("$.defaultMaxPages").value(50))
                // Without these the only way to learn 500 is too many is to type it and be refused.
                .andExpect(jsonPath("$.maxAllowedDepth").value(5))
                .andExpect(jsonPath("$.maxAllowedPages").value(300));
    }

    // ----- Writing -----

    @Test
    void appliesAnUpdateAndReturnsTheDefaultsNowInForce() throws Exception {
        when(crawlSettings.currentDefaults()).thenReturn(new CrawlSettings.CrawlDefaults(2, 50));
        when(crawlSettings.updateDefaults(3, 120)).thenReturn(new CrawlSettings.CrawlDefaults(3, 120));
        when(crawlSettings.ceilings()).thenReturn(new CrawlSettings.CrawlDefaults(5, 300));

        mockMvc.perform(put("/api/admin/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"defaultMaxDepth\":3,\"defaultMaxPages\":120}")
                        .with(TestPrincipals.admin()))
                .andExpect(status().isOk())
                // The response is what took effect, read back from the update rather than echoed from the request, so a
                // value the store rejected or adjusted cannot appear in the form as though it had been saved.
                .andExpect(jsonPath("$.defaultMaxDepth").value(3))
                .andExpect(jsonPath("$.defaultMaxPages").value(120));
    }

    @Test
    void auditsTheChangeWithBothTheOldAndTheNewValuesBecauseNeitherAloneAnswersAnything() throws Exception {
        when(crawlSettings.currentDefaults()).thenReturn(new CrawlSettings.CrawlDefaults(2, 50));
        when(crawlSettings.updateDefaults(3, 120)).thenReturn(new CrawlSettings.CrawlDefaults(3, 120));
        when(crawlSettings.ceilings()).thenReturn(new CrawlSettings.CrawlDefaults(5, 300));

        mockMvc.perform(put("/api/admin/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"defaultMaxDepth\":3,\"defaultMaxPages\":120}")
                        .with(TestPrincipals.admin()))
                .andExpect(status().isOk());

        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.captor();
        verify(audit).log(eq(AuditAction.SYSTEM_SETTINGS_UPDATED), eq("system_settings"),
                eq(CrawlSettings.CONFIG_KEY), details.capture());
        // Values, not field names - the deliberate exception to the rule the rest of the application follows, because a
        // crawl depth is not a secret and an entry saying only "the settings changed" leaves the question open.
        assertThat(details.getValue())
                .containsEntry("previousMaxDepth", 2)
                .containsEntry("previousMaxPages", 50)
                .containsEntry("newMaxDepth", 3)
                .containsEntry("newMaxPages", 120);
    }

    @Test
    void readsThePreviousValuesBeforeWritingRatherThanAfter() throws Exception {
        // Ordering that is invisible in the response and silently wrong if the two calls are swapped: reading the
        // defaults after the write records the new values twice and the audit trail loses what changed.
        when(crawlSettings.currentDefaults()).thenReturn(new CrawlSettings.CrawlDefaults(2, 50));
        when(crawlSettings.updateDefaults(3, 120)).thenReturn(new CrawlSettings.CrawlDefaults(3, 120));
        when(crawlSettings.ceilings()).thenReturn(new CrawlSettings.CrawlDefaults(5, 300));

        mockMvc.perform(put("/api/admin/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"defaultMaxDepth\":3,\"defaultMaxPages\":120}")
                        .with(TestPrincipals.admin()))
                .andExpect(status().isOk());

        InOrder order = inOrder(crawlSettings);
        order.verify(crawlSettings).currentDefaults();
        order.verify(crawlSettings).updateDefaults(3, 120);
    }

    @Test
    void letsAnOutOfRangeRejectionThroughAsA400NamingTheCeiling() throws Exception {
        when(crawlSettings.currentDefaults()).thenReturn(new CrawlSettings.CrawlDefaults(2, 50));
        when(crawlSettings.updateDefaults(anyInt(), anyInt()))
                .thenThrow(new BadRequestException("defaultMaxPages may not exceed 300."));

        mockMvc.perform(put("/api/admin/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"defaultMaxDepth\":2,\"defaultMaxPages\":5000}")
                        .with(TestPrincipals.admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", Matchers.containsString("may not exceed 300")));

        // Nothing changed, so nothing is logged: an audit trail recording a rejected change is worse than none.
        verifyNoInteractions(audit);
    }

    @Test
    void rejectsAMissingFieldRatherThanTreatingItAsAPartialUpdate() throws Exception {
        // Both limits interact - a depth of 4 over a large site is bounded only by the page budget - so a request that
        // changed one without stating the other would authorise a crawl nobody reviewed.
        mockMvc.perform(put("/api/admin/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"defaultMaxDepth\":3}")
                        .with(TestPrincipals.admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message", Matchers.containsString("defaultMaxPages is required")));

        verify(crawlSettings, never()).updateDefaults(anyInt(), anyInt());
        verifyNoInteractions(audit);
    }

    @Test
    void rejectsAPageBudgetOfZeroWhichWouldMakeEverySourceFetchNothing() throws Exception {
        mockMvc.perform(put("/api/admin/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"defaultMaxDepth\":0,\"defaultMaxPages\":0}")
                        .with(TestPrincipals.admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", Matchers.containsString("defaultMaxPages must be at least 1")));

        verify(crawlSettings, never()).updateDefaults(anyInt(), anyInt());
    }

    @Test
    void acceptsADepthOfZeroWhichMeansFetchOnlyTheGivenPage() throws Exception {
        // The boundary a @Min(1) copied from the page budget would quietly break: depth 0 is a meaningful setting, not
        // an unset one.
        when(crawlSettings.currentDefaults()).thenReturn(new CrawlSettings.CrawlDefaults(2, 50));
        when(crawlSettings.updateDefaults(0, 50)).thenReturn(new CrawlSettings.CrawlDefaults(0, 50));
        when(crawlSettings.ceilings()).thenReturn(new CrawlSettings.CrawlDefaults(5, 300));

        mockMvc.perform(put("/api/admin/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"defaultMaxDepth\":0,\"defaultMaxPages\":50}")
                        .with(TestPrincipals.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultMaxDepth").value(0));
    }

    // ----- Roles -----

    @Test
    void aReadOnlyUserCanNeitherSeeNorChangeTheCrawlSettings() throws Exception {
        // Read-only here means read the products, not read the instance's configuration: the crawl limits are the
        // controls on what this application is allowed to fetch from other people's servers.
        mockMvc.perform(get("/api/admin/settings").with(TestPrincipals.readOnly()))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/admin/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"defaultMaxDepth\":3,\"defaultMaxPages\":120}")
                        .with(TestPrincipals.readOnly()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(crawlSettings);
        verifyNoInteractions(audit);
    }

    @Test
    void anAnonymousCallerCannotReachTheSettingsEndpointAtAll() throws Exception {
        mockMvc.perform(get("/api/admin/settings")).andExpect(status().isForbidden());

        verifyNoInteractions(crawlSettings);
    }

}
