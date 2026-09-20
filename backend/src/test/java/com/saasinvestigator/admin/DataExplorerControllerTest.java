package com.saasinvestigator.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saasinvestigator.common.PageResponse;
import com.saasinvestigator.common.Paging;
import com.saasinvestigator.error.BadRequestException;
import com.saasinvestigator.error.GlobalExceptionHandler;
import com.saasinvestigator.error.NotFoundException;
import com.saasinvestigator.testsupport.MethodSecuritySliceConfig;
import com.saasinvestigator.testsupport.TestPrincipals;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tests for {@link DataExplorerController} - the HTTP surface over the Mongo browser.
 *
 * <h2>What this class is responsible for, and what it is not</h2>
 *
 * <p>Masking and the write guardrail are {@link SecretFieldMasker}'s, tested there and again against a real database in
 * {@link DataExplorerServiceMongoTest}. Repeating them here would assert on a mock's return value and prove nothing. What
 * only this layer can get wrong:
 *
 * <ul>
 *   <li><b>The role.</b> This is the one endpoint in the application that can read any document in any of its
 *       collections. A missing role check here is a bigger hole than on any other route, and the annotation is on the
 *       class - so it is removed from all four endpoints at once or none.</li>
 *   <li><b>The page-size parameter.</b> The documented name is {@code pageSize}; {@code size} is accepted as a synonym
 *       because that is what every other paginated endpoint calls it. Both spellings have to work, and one of them has
 *       to win when both arrive, or the behaviour is whichever Spring bound last.</li>
 *   <li><b>That paging goes through {@link Paging}.</b> This endpoint does its own skip and limit rather than taking a
 *       {@code Pageable}, which makes it the one place where the shared ceiling can be bypassed by accident.</li>
 *   <li><b>That the body parses as BSON.</b> The update is typed {@code Document} so the service inspects exactly what
 *       arrived. Whether the JSON mapper can actually produce one is a fact about the framework, not a design choice,
 *       and a test is the only way to know it.</li>
 * </ul>
 */
@WebMvcTest(DataExplorerController.class)
@Import({MethodSecuritySliceConfig.class, GlobalExceptionHandler.class})
class DataExplorerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DataExplorerService explorer;

    // ----- Collections -----

    @Test
    void listsTheCollectionsWithTheirCountsAndTheirMaskedFieldNames() throws Exception {
        when(explorer.collections()).thenReturn(List.of(
                new DataExplorerService.CollectionSummary("users", 3, List.of("passwordHash")),
                new DataExplorerService.CollectionSummary("snapshots", 0, List.of())));

        mockMvc.perform(get("/api/admin/data-explorer/collections").with(TestPrincipals.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("users"))
                .andExpect(jsonPath("$[0].documentCount").value(3))
                // The UI disables these inputs rather than letting someone type into a field whose save is refused.
                .andExpect(jsonPath("$[0].secretFields[0]").value("passwordHash"))
                .andExpect(jsonPath("$[1].documentCount").value(0));
    }

    // ----- Paging -----

    @Test
    void usesTheSharedDefaultPageSizeWhenNeitherSpellingIsGiven() throws Exception {
        when(explorer.documents(anyString(), anyInt(), anyInt())).thenReturn(emptyPage());

        mockMvc.perform(get("/api/admin/data-explorer/collections/users/documents")
                        .with(TestPrincipals.admin()))
                .andExpect(status().isOk());

        verify(explorer).documents("users", 0, Paging.DEFAULT_PAGE_SIZE);
    }

    @Test
    void acceptsTheDocumentedPageSizeParameterName() throws Exception {
        when(explorer.documents(anyString(), anyInt(), anyInt())).thenReturn(emptyPage());

        mockMvc.perform(get("/api/admin/data-explorer/collections/users/documents")
                        .param("page", "2").param("pageSize", "5")
                        .with(TestPrincipals.admin()))
                .andExpect(status().isOk());

        verify(explorer).documents("users", 2, 5);
    }

    @Test
    void alsoAcceptsSizeAsASynonymSoTheParameterIsSpelledTheSameWayAsEverywhereElse() throws Exception {
        when(explorer.documents(anyString(), anyInt(), anyInt())).thenReturn(emptyPage());

        mockMvc.perform(get("/api/admin/data-explorer/collections/users/documents")
                        .param("size", "5")
                        .with(TestPrincipals.admin()))
                .andExpect(status().isOk());

        verify(explorer).documents("users", 0, 5);
    }

    @Test
    void prefersPageSizeOverSizeWhenBothArriveBecauseItIsTheNameInTheContract() throws Exception {
        // Otherwise the answer is whichever parameter Spring happened to bind last, which is not an answer.
        when(explorer.documents(anyString(), anyInt(), anyInt())).thenReturn(emptyPage());

        mockMvc.perform(get("/api/admin/data-explorer/collections/users/documents")
                        .param("pageSize", "5").param("size", "100")
                        .with(TestPrincipals.admin()))
                .andExpect(status().isOk());

        verify(explorer).documents("users", 0, 5);
    }

    @Test
    void capsAnOversizedRequestAtTheSharedCeilingRatherThanStreamingAWholeCollection() throws Exception {
        // This endpoint does its own skip and limit instead of taking a Pageable, which is exactly why the ceiling has
        // to be asserted here: it is the one route where forgetting Paging would compile and work.
        when(explorer.documents(anyString(), anyInt(), anyInt())).thenReturn(emptyPage());

        mockMvc.perform(get("/api/admin/data-explorer/collections/audit_logs/documents")
                        .param("pageSize", "100000")
                        .with(TestPrincipals.admin()))
                .andExpect(status().isOk());

        verify(explorer).documents("audit_logs", 0, Paging.MAX_PAGE_SIZE);
    }

    @Test
    void rejectsANegativePageBeforeTouchingTheDatabase() throws Exception {
        mockMvc.perform(get("/api/admin/data-explorer/collections/users/documents")
                        .param("page", "-1")
                        .with(TestPrincipals.admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", Matchers.containsString("page must be 0 or greater")));

        verifyNoInteractions(explorer);
    }

    @Test
    void rejectsANonNumericPageSizeWithA400RatherThanA500() throws Exception {
        mockMvc.perform(get("/api/admin/data-explorer/collections/users/documents")
                        .param("pageSize", "twenty")
                        .with(TestPrincipals.admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PARAMETER"));

        verifyNoInteractions(explorer);
    }

    @Test
    void returnsThePageInTheSharedEnvelopeRatherThanSpringsOwnPageJson() throws Exception {
        when(explorer.documents("users", 0, Paging.DEFAULT_PAGE_SIZE)).thenReturn(new PageResponse<>(
                List.of(Map.of("username", "dana", "passwordHash", SecretFieldMasker.MASK)),
                0, 20, 1, 1, true, true));

        mockMvc.perform(get("/api/admin/data-explorer/collections/users/documents")
                        .with(TestPrincipals.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].username").value("dana"))
                .andExpect(jsonPath("$.content[0].passwordHash").value(SecretFieldMasker.MASK))
                .andExpect(jsonPath("$.totalElements").value(1))
                // Spring's own Page serialisation is unstable across versions and leaks a `pageable` object nobody
                // asked for; every paginated endpoint in this application answers in the same envelope instead.
                .andExpect(jsonPath("$.pageable").doesNotExist());
    }

    // ----- One document -----

    @Test
    void returnsASingleDocumentByItsId() throws Exception {
        when(explorer.document("users", "6ab0501786b24277cd03f042"))
                .thenReturn(Map.of("username", "dana", "passwordHash", SecretFieldMasker.MASK));

        mockMvc.perform(get("/api/admin/data-explorer/collections/users/documents/6ab0501786b24277cd03f042")
                        .with(TestPrincipals.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("dana"));
    }

    @Test
    void passesAnUnknownCollectionThroughAsA404() throws Exception {
        // 404 rather than 403, so the response does not confirm which other collections share the database.
        when(explorer.document(eq("system.version"), anyString()))
                .thenThrow(new NotFoundException("No browsable collection named system.version."));

        mockMvc.perform(get("/api/admin/data-explorer/collections/system.version/documents/anything")
                        .with(TestPrincipals.admin()))
                .andExpect(status().isNotFound());
    }

    // ----- Updating -----

    @Test
    void parsesTheRequestBodyAsBsonSoTheServiceInspectsExactlyWhatArrived() throws Exception {
        when(explorer.update(anyString(), anyString(), any()))
                .thenReturn(Map.of("lastName", "Reyes-Smith"));

        mockMvc.perform(put("/api/admin/data-explorer/collections/users/documents/doc-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lastName\":\"Reyes-Smith\",\"loginCount\":4}")
                        .with(TestPrincipals.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastName").value("Reyes-Smith"));

        ArgumentCaptor<Document> body = ArgumentCaptor.captor();
        verify(explorer).update(eq("users"), eq("doc-1"), body.capture());
        // Typed as a Document rather than a Map so there is no conversion between arriving and being inspected that
        // could change what a value is on the way through.
        assertThat(body.getValue())
                .containsEntry("lastName", "Reyes-Smith")
                .containsEntry("loginCount", 4);
    }

    @Test
    void passesTheGuardrailsRejectionThroughAsA400WithTheMessageThatSaysWhereToGoInstead() throws Exception {
        when(explorer.update(anyString(), anyString(), any())).thenThrow(new BadRequestException(
                "passwordHash can't be edited here - use Users > Reset password."));

        mockMvc.perform(put("/api/admin/data-explorer/collections/users/documents/doc-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"passwordHash\":\"" + SecretFieldMasker.MASK + "\"}")
                        .with(TestPrincipals.admin()))
                .andExpect(status().isBadRequest())
                // A refusal that does not say where the change does belong reads as the feature being broken.
                .andExpect(jsonPath("$.message", Matchers.containsString("Users > Reset password")));
    }

    @Test
    void rejectsAMalformedBodyAsA400RatherThanA500() throws Exception {
        mockMvc.perform(put("/api/admin/data-explorer/collections/users/documents/doc-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json")
                        .with(TestPrincipals.admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));

        verifyNoInteractions(explorer);
    }

    // ----- Roles -----

    @Test
    void aReadOnlyUserCannotReachAnyOfTheFourDataExplorerEndpoints() throws Exception {
        // Enumerated one by one rather than trusting the class-level annotation, because the annotation is exactly what
        // could be removed - and a read-only account with this tab reads every document the application holds.
        mockMvc.perform(get("/api/admin/data-explorer/collections").with(TestPrincipals.readOnly()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/data-explorer/collections/users/documents").with(TestPrincipals.readOnly()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/data-explorer/collections/users/documents/doc-1")
                        .with(TestPrincipals.readOnly()))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/admin/data-explorer/collections/users/documents/doc-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lastName\":\"Reyes-Smith\"}")
                        .with(TestPrincipals.readOnly()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(explorer);
    }

    @Test
    void anAnonymousCallerCannotBrowseOrEditAnyDocument() throws Exception {
        mockMvc.perform(get("/api/admin/data-explorer/collections")).andExpect(status().isForbidden());
        mockMvc.perform(put("/api/admin/data-explorer/collections/users/documents/doc-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lastName\":\"Reyes-Smith\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(explorer);
    }

    // ----- Helpers -----

    private static PageResponse<Map<String, Object>> emptyPage() {
        return new PageResponse<>(List.of(), 0, 20, 0, 0, true, true);
    }
}
