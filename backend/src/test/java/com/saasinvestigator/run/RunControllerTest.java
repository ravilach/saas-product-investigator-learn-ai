package com.saasinvestigator.run;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saasinvestigator.error.BadRequestException;
import com.saasinvestigator.error.GlobalExceptionHandler;
import com.saasinvestigator.error.NotFoundException;
import com.saasinvestigator.error.ProviderUnavailableException;
import com.saasinvestigator.report.AnalysisDepth;
import com.saasinvestigator.report.RunType;
import com.saasinvestigator.testsupport.MethodSecuritySliceConfig;
import com.saasinvestigator.testsupport.TestPrincipals;
import java.time.Instant;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Tests for {@link RunController}: the status codes, the bodies it accepts, and who may call it.
 *
 * <p>The role assertions are the ones worth having, and they assert a permission rather than a restriction: both roles
 * can run, compare, watch and ask. {@code READ_ONLY} limits <em>configuration</em>, not use, so a test that pinned
 * these endpoints to {@code ADMIN} would be enforcing a login that shows you a dashboard you may never use. Written
 * down here because "read-only" reads like it should mean "cannot press Run", and the next person to tighten a role
 * check will look for a test before they decide.
 *
 * <p>The two status codes are equally deliberate. A run is {@code 202 Accepted} with a {@code runId}, because the work
 * has been scheduled and not done - answering {@code 200} would claim a report exists. A bad compare range is a
 * {@code 400} from the request thread rather than a failure event on a stream that was already handed back as a
 * success.
 */
@WebMvcTest(RunController.class)
@Import({MethodSecuritySliceConfig.class, GlobalExceptionHandler.class})
class RunControllerTest {

    private static final Instant JUNE_1_START = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant JUNE_30_END = Instant.parse("2026-06-30T23:59:59.999Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RunOrchestrator orchestrator;

    @MockitoBean
    private RunEventStream eventStream;

    @MockitoBean
    private AskService askService;

    // ---------------------------------------------------------------------
    // Running
    // ---------------------------------------------------------------------

    @Test
    void triggeringARunIsAcceptedRatherThanOkAndReturnsTheStreamToWatch() throws Exception {
        when(orchestrator.startRun(eq("product-1"), isNull())).thenReturn("run-7");

        mockMvc.perform(post("/api/saas-products/product-1/run").with(TestPrincipals.admin()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value("run-7"));
    }

    @Test
    void aRunNeedsNoBodyAtAllBecauseTheProductIdIsTheWholeRequest() throws Exception {
        when(orchestrator.startRun(anyString(), any())).thenReturn("run-7");

        mockMvc.perform(post("/api/saas-products/product-1/run").with(TestPrincipals.readOnly()))
                .andExpect(status().isAccepted());

        // The dashboard's Run button sends nothing, so a required body would make the most common action in the
        // application need a payload to say nothing.
        verify(orchestrator).startRun("product-1", null);
    }

    @Test
    void aRequestedDepthIsPassedThroughRatherThanIgnored() throws Exception {
        when(orchestrator.startRun(anyString(), any())).thenReturn("run-7");

        mockMvc.perform(post("/api/saas-products/product-1/run")
                        .with(TestPrincipals.readOnly())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"analysisDepth\":\"NUCLEAR\"}"))
                .andExpect(status().isAccepted());

        verify(orchestrator).startRun("product-1", AnalysisDepth.NUCLEAR);
    }

    @Test
    void anUnknownDepthIsRejectedWithAReadableMessageRatherThanSilentlyDefaulted() throws Exception {
        mockMvc.perform(post("/api/saas-products/product-1/run")
                        .with(TestPrincipals.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"analysisDepth\":\"EXHAUSTIVE\"}"))
                .andExpect(status().isBadRequest());

        // Quietly running at REGULAR would bill for one depth while the user believed they had asked for another.
        verify(orchestrator, never()).startRun(anyString(), any());
    }

    @Test
    void aRunForAProductThatDoesNotExistIsA404() throws Exception {
        when(orchestrator.startRun(anyString(), any()))
                .thenThrow(new NotFoundException("No SaaS product with id ghost"));

        mockMvc.perform(post("/api/saas-products/ghost/run").with(TestPrincipals.admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("No SaaS product with id ghost")));
    }

    @Test
    void aServerAlreadyAtItsRunLimitAnswers503RatherThanQueueingForever() throws Exception {
        when(orchestrator.startRun(anyString(), any()))
                .thenThrow(new ProviderUnavailableException("This server is already running as many analyses as it is "
                        + "configured to run at once. Try again in a few minutes."));

        mockMvc.perform(post("/api/saas-products/product-1/run").with(TestPrincipals.admin()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("Try again in a few minutes")));
    }

    @Test
    void anAnonymousCallerCannotRunAProduct() throws Exception {
        mockMvc.perform(post("/api/saas-products/product-1/run")).andExpect(status().isForbidden());

        verifyNoInteractions(orchestrator);
    }

    // ---------------------------------------------------------------------
    // Comparing
    // ---------------------------------------------------------------------

    @Test
    void aCompareRangeArrivesAsDatesAndReachesTheOrchestratorAsAFullDayWindow() throws Exception {
        when(orchestrator.startCompare(anyString(), any(), any(), any())).thenReturn("run-8");

        mockMvc.perform(post("/api/saas-products/product-1/compare")
                        .with(TestPrincipals.readOnly())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromDate\":\"2026-06-01\",\"toDate\":\"2026-06-30\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value("run-8"));

        // The end of the window is the end of 30 June, not its midnight. This is the seam where the off-by-one-day bug
        // described in CompareRequest would reach the query that loads the snapshots.
        verify(orchestrator).startCompare("product-1", JUNE_1_START, JUNE_30_END, null);
    }

    @Test
    void aCompareWithNoDatesIsA400NamingTheMissingFields() throws Exception {
        mockMvc.perform(post("/api/saas-products/product-1/compare")
                        .with(TestPrincipals.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("yyyy-MM-dd")));

        verify(orchestrator, never()).startCompare(anyString(), any(), any(), any());
    }

    @Test
    void aCompareWithNoBodyAtAllIsA400RatherThanA500() throws Exception {
        // Unlike a run, a compare cannot default: there is no sensible window to invent on the user's behalf.
        mockMvc.perform(post("/api/saas-products/product-1/compare")
                        .with(TestPrincipals.admin())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aMalformedDateIsA400RatherThanAnUnhandledParseFailure() throws Exception {
        mockMvc.perform(post("/api/saas-products/product-1/compare")
                        .with(TestPrincipals.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromDate\":\"01/06/2026\",\"toDate\":\"2026-06-30\"}"))
                .andExpect(status().isBadRequest());

        verify(orchestrator, never()).startCompare(anyString(), any(), any(), any());
    }

    @Test
    void anImpossibleRangeIsA400FromTheRequestThreadRatherThanAFailureEventOnAStream() throws Exception {
        when(orchestrator.startCompare(anyString(), any(), any(), any()))
                .thenThrow(new BadRequestException("There is no stored data for this product from before 1 Jun 2026. "
                        + "The earliest data available is from 20 Jun 2026."));

        mockMvc.perform(post("/api/saas-products/product-1/compare")
                        .with(TestPrincipals.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromDate\":\"2026-06-01\",\"toDate\":\"2026-06-30\"}"))
                .andExpect(status().isBadRequest())
                // The earliest available date is in the message because "no data" alone leaves the user guessing at
                // which range would have worked.
                .andExpect(jsonPath("$.message").value(Matchers.containsString("earliest data available")));
    }

    @Test
    void anAnonymousCallerCannotCompare() throws Exception {
        mockMvc.perform(post("/api/saas-products/product-1/compare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromDate\":\"2026-06-01\",\"toDate\":\"2026-06-30\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(orchestrator);
    }

    // ---------------------------------------------------------------------
    // Watching
    // ---------------------------------------------------------------------

    @Test
    void watchingALiveRunOpensAnEventStream() throws Exception {
        when(eventStream.find("run-7"))
                .thenReturn(Optional.of(new RunSession("run-7", "product-1", RunType.STANDARD)));

        mockMvc.perform(get("/api/saas-products/product-1/runs/run-7/events").with(TestPrincipals.readOnly()))
                .andExpect(status().isOk())
                .andExpect(request -> org.assertj.core.api.Assertions
                        .assertThat(request.getRequest().isAsyncStarted()).isTrue());
    }

    @Test
    void aRunIdThatBelongsToAnotherProductIsA404RatherThanSomeoneElsesNarration() throws Exception {
        when(eventStream.find("run-7"))
                .thenReturn(Optional.of(new RunSession("run-7", "product-2", RunType.STANDARD)));

        // Guessing a runId must not reveal another product's run, and the id alone would be enough to subscribe if the
        // product were not checked.
        mockMvc.perform(get("/api/saas-products/product-1/runs/run-7/events").with(TestPrincipals.admin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void aRunThatHasAgedOutOfMemoryIsA404ThatPointsAtTheHistoryInstead() throws Exception {
        when(eventStream.find("run-7")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/saas-products/product-1/runs/run-7/events").with(TestPrincipals.admin()))
                .andExpect(status().isNotFound())
                // The report outlives its narration, so the 404 has to say where the durable thing is rather than
                // reading as data loss.
                .andExpect(jsonPath("$.message").value(Matchers.containsString("its report is on the product's "
                        + "history")));
    }

    @Test
    void anAnonymousCallerCannotWatchARun() throws Exception {
        mockMvc.perform(get("/api/saas-products/product-1/runs/run-7/events"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(eventStream);
    }

    // ---------------------------------------------------------------------
    // Asking
    // ---------------------------------------------------------------------

    @Test
    void askingAQuestionStreamsTheAnswerOnThePostItself() throws Exception {
        when(askService.ask(eq("product-1"), anyString())).thenReturn(new SseEmitter());

        mockMvc.perform(post("/api/saas-products/product-1/ask")
                        .with(TestPrincipals.readOnly())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Did pricing change?\"}"))
                .andExpect(status().isOk());

        // An ask persists nothing and is meaningless to a second viewer, so handing back an id to fetch it with would
        // be two round trips to reach the same bytes.
        verify(askService).ask("product-1", "Did pricing change?");
    }

    @Test
    void aBlankQuestionIsRejectedBeforeAnyProviderIsContacted() throws Exception {
        mockMvc.perform(post("/api/saas-products/product-1/ask")
                        .with(TestPrincipals.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("question must not be blank")));

        verifyNoInteractions(askService);
    }

    @Test
    void anOverlongQuestionIsRejectedWithItsLimitInTheMessage() throws Exception {
        mockMvc.perform(post("/api/saas-products/product-1/ask")
                        .with(TestPrincipals.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"" + "a".repeat(AskService.MAX_QUESTION_CHARS + 1) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(Matchers.containsString(String.valueOf(AskService.MAX_QUESTION_CHARS))));

        // Unbounded, the question alone would consume the prompt budget and leave no room for the snapshots needed to
        // answer it.
        verifyNoInteractions(askService);
    }

    @Test
    void aQuestionAboutAnUnknownProductIsA404() throws Exception {
        when(askService.ask(anyString(), anyString()))
                .thenThrow(new NotFoundException("No SaaS product with id ghost"));

        mockMvc.perform(post("/api/saas-products/ghost/ask")
                        .with(TestPrincipals.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"What changed?\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void anAnonymousCallerCannotAskAQuestion() throws Exception {
        mockMvc.perform(post("/api/saas-products/product-1/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"What changed?\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(askService);
    }
}
