package com.saasinvestigator.product;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saasinvestigator.common.Paging;
import com.saasinvestigator.error.ConflictException;
import com.saasinvestigator.error.GlobalExceptionHandler;
import com.saasinvestigator.error.NotFoundException;
import com.saasinvestigator.testsupport.MethodSecuritySliceConfig;
import com.saasinvestigator.testsupport.TestPrincipals;
import java.time.Instant;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tests for {@link SaasProductController} - almost entirely about the role line.
 *
 * <h2>Why every verb is asserted separately</h2>
 *
 * <p>The split this controller draws is the whole meaning of the {@code READ_ONLY} role: such a user may run an analysis
 * and read every report, but may not change <em>what</em> is tracked or, more to the point, <em>which URLs this server
 * will fetch</em>. Adding a source is closer to editing the deployment's configuration than to using the product, and a
 * missing {@code @PreAuthorize} on any one of the three mutations would hand that ability to every account. Annotations
 * are per-method here, so there is no class-level default to fall back on - each one is its own opportunity to be
 * forgotten, and each therefore gets its own test.
 *
 * <p>{@code verifyNoInteractions} follows each rejection deliberately: a 403 produced after the service already ran
 * would still be a 403 in the response and a completed write in the database.
 */
@WebMvcTest(SaasProductController.class)
@Import({MethodSecuritySliceConfig.class, GlobalExceptionHandler.class})
class SaasProductControllerTest {

    private static final String BODY = """
            {"name":"Acme Analytics","description":"Analytics suite","sources":[]}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SaasProductService service;

    // ----- Role enforcement -----

    @Test
    void readOnlyUserCannotCreateAProduct() throws Exception {
        mockMvc.perform(post("/api/saas-products").with(TestPrincipals.readOnly())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void readOnlyUserCannotChangeWhichUrlsTheServerWillFetch() throws Exception {
        mockMvc.perform(put("/api/saas-products/product-1").with(TestPrincipals.readOnly())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void readOnlyUserCannotDeleteAProductOrItsHistory() throws Exception {
        mockMvc.perform(delete("/api/saas-products/product-1").with(TestPrincipals.readOnly()))
                .andExpect(status().isForbidden());

        // A delete cascades to every snapshot, report and run record, so this is the most destructive verb in the API.
        verifyNoInteractions(service);
    }

    @Test
    void readOnlyUserCanListAndReadProductsBecauseThatIsWhatTheRoleIsFor() throws Exception {
        when(service.list(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(response())));
        when(service.get("product-1")).thenReturn(response());

        mockMvc.perform(get("/api/saas-products").with(TestPrincipals.readOnly()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Acme Analytics"));
        mockMvc.perform(get("/api/saas-products/product-1").with(TestPrincipals.readOnly()))
                .andExpect(status().isOk());
    }

    @Test
    void anonymousCallerGetsNothingAtAll() throws Exception {
        mockMvc.perform(get("/api/saas-products")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/saas-products/product-1")).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/saas-products/product-1")).andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    // ----- Status codes -----

    @Test
    void createReturns201AndDeleteReturns204SoAClientCanTellWhichHappened() throws Exception {
        when(service.create(any())).thenReturn(response());

        mockMvc.perform(post("/api/saas-products").with(TestPrincipals.admin())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("product-1"));
        mockMvc.perform(delete("/api/saas-products/product-1").with(TestPrincipals.admin()))
                .andExpect(status().isNoContent());

        verify(service).delete("product-1");
    }

    @Test
    void aDuplicateNameIsA409RatherThanA500SoTheFormCanSayWhatWentWrong() throws Exception {
        when(service.create(any()))
                .thenThrow(new ConflictException("A SaaS product named 'Acme Analytics' already exists."));

        mockMvc.perform(post("/api/saas-products").with(TestPrincipals.admin())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", Matchers.containsString("already exists")));
    }

    @Test
    void anUnknownProductIdIsA404RatherThanAnEmptyBody() throws Exception {
        when(service.get("missing")).thenThrow(new NotFoundException("No SaaS product with id missing"));

        mockMvc.perform(get("/api/saas-products/missing").with(TestPrincipals.readOnly()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", Matchers.containsString("missing")));
    }

    // ----- Validation and pagination -----

    @Test
    void rejectsAProductWithNoNameNamingTheFieldRatherThanFailingInsideTheService() throws Exception {
        mockMvc.perform(post("/api/saas-products").with(TestPrincipals.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \",\"sources\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", Matchers.containsString("name is required")));

        verifyNoInteractions(service);
    }

    @Test
    void rejectsASourceMissingItsOwnFieldsBecauseTheSourceListIsValidatedElementByElement() throws Exception {
        // Without @Valid on the list, Bean Validation stops at the list itself and a nameless source reaches the mapper
        // to be rejected there - with a message about one source instead of a field-by-field list.
        mockMvc.perform(post("/api/saas-products").with(TestPrincipals.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Acme","sources":[{"type":"WEBSITE","name":"","endpointUrl":""}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", Matchers.containsString("each source needs a name")))
                .andExpect(jsonPath("$.message", Matchers.containsString("each source needs an endpointUrl")));

        verifyNoInteractions(service);
    }

    @Test
    void rejectsANonsensicalPageRatherThanServingTheFirstOne() throws Exception {
        mockMvc.perform(get("/api/saas-products").param("page", "-1").with(TestPrincipals.readOnly()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", Matchers.containsString("page must be 0 or greater")));

        verifyNoInteractions(service);
    }

    @Test
    void rejectsANonNumericPageWithA400RatherThanA500() throws Exception {
        mockMvc.perform(get("/api/saas-products").param("size", "twenty").with(TestPrincipals.readOnly()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PARAMETER"));

        verifyNoInteractions(service);
    }

    @Test
    void capsAnOversizedPageSizeInsteadOfRefusingTheRequest() throws Exception {
        when(service.list(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/saas-products").param("size", "100000").with(TestPrincipals.readOnly()))
                .andExpect(status().isOk());

        verify(service).list(argThat(pageable -> pageable.getPageSize() == Paging.MAX_PAGE_SIZE));
    }

    @Test
    void returnsTheSharedPageEnvelopeRatherThanSpringDatasOwnPageShape() throws Exception {
        when(service.list(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(response())));

        mockMvc.perform(get("/api/saas-products").with(TestPrincipals.readOnly()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(true))
                // Spring Data's Page serialises an unstable "pageable" object; the fixed envelope must not carry it.
                .andExpect(jsonPath("$.pageable").doesNotExist());
    }

    // ----- Helpers -----

    private static SaasProductResponse response() {
        return new SaasProductResponse("product-1", "Acme Analytics", "Analytics suite", List.of(), 0,
                Instant.parse("2026-06-01T09:30:00Z"), "admin", null);
    }
}
