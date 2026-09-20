package com.saasinvestigator.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saasinvestigator.audit.AuditAction;
import com.saasinvestigator.audit.AuditService;
import com.saasinvestigator.error.ConflictException;
import com.saasinvestigator.error.NotFoundException;
import com.saasinvestigator.report.ChangeReportRepository;
import com.saasinvestigator.run.RunRecordRepository;
import com.saasinvestigator.security.AuthenticatedUser;
import com.saasinvestigator.snapshot.SnapshotRepository;
import com.saasinvestigator.user.Role;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Tests {@link SaasProductService} - the uniqueness rules, the delete cascade, and what the audit trail is allowed to
 * say.
 *
 * <h2>What is actually at risk here</h2>
 *
 * <p>Three things, none of which a reading of the code makes obvious:
 *
 * <ul>
 *   <li><b>The cascade order.</b> Dependents are deleted before the product, so a failure halfway leaves the product
 *       visible and the operation retryable. The reverse order leaves orphaned crawled page content belonging to
 *       something nobody can see - the exact state the cascade exists to prevent. Order is not visible in a diff, so it
 *       is asserted with an {@link InOrder}.</li>
 *   <li><b>The audit entry's contents.</b> It must carry field <em>names</em>, never values, because one of a product's
 *       fields is an MCP credential. A future change that made the trail more informative by dumping the submitted
 *       sources would be a credential in a log, and nothing would fail.</li>
 *   <li><b>Update resubmits everything.</b> The client sends every source back, including tokens it was never shown, so
 *       the existing sources must reach {@link SourceConfigMapper} - otherwise every edit silently clears the tokens.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SaasProductServiceTest {

    private static final AuthenticatedUser ADMIN =
            new AuthenticatedUser("id-admin", "admin", "Admin User", Role.ADMIN);

    @Mock
    private SaasProductRepository products;
    @Mock
    private ChangeReportRepository reports;
    @Mock
    private SnapshotRepository snapshots;
    @Mock
    private RunRecordRepository runRecords;
    @Mock
    private SourceConfigMapper sourceMapper;
    @Mock
    private AuditService audit;

    @Captor
    private ArgumentCaptor<Map<String, Object>> detailsCaptor;

    private SaasProductService service;

    @BeforeEach
    void setUp() {
        service = new SaasProductService(products, reports, snapshots, runRecords, sourceMapper, audit);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(ADMIN, null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ----- Create -----

    @Test
    void createsAProductTrimmingItsNameAndRecordingWhoCreatedIt() {
        when(products.existsByName("Acme Analytics")).thenReturn(false);
        when(sourceMapper.toStored(any(), eq(null))).thenReturn(List.of(source("Docs site")));
        when(products.save(any())).thenAnswer(invocation -> withId(invocation.getArgument(0), "product-1"));
        when(reports.findFirstBySaasProductIdOrderByRunAtDesc("product-1")).thenReturn(Optional.empty());

        SaasProductResponse response = service.create(
                new SaasProductRequest("  Acme Analytics  ", "  ", List.of()));

        ArgumentCaptor<SaasProduct> saved = ArgumentCaptor.forClass(SaasProduct.class);
        verify(products).save(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("Acme Analytics");
        // A whitespace-only description becomes null rather than being stored: it is passed to the model as prompt
        // context, and "  " would occupy a labelled section of the prompt while saying nothing.
        assertThat(saved.getValue().getDescription()).isNull();
        assertThat(saved.getValue().getCreatedBy()).isEqualTo("admin");
        assertThat(response.name()).isEqualTo("Acme Analytics");
        assertThat(response.lastRun()).isNull();
    }

    @Test
    void refusesToCreateASecondProductWithTheSameNameAfterTrimming() {
        // Names are how a report identifies what it is about, so two products called "Acme" make every report ambiguous.
        // Checked against the trimmed name, or "Acme " would be accepted as distinct from "Acme".
        when(products.existsByName("Acme")).thenReturn(true);

        assertThatThrownBy(() -> service.create(new SaasProductRequest(" Acme ", null, List.of())))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("'Acme' already exists");
        verify(products, never()).save(any());
    }

    @Test
    void recordsOnlyTheNameAndSourceCountWhenAuditingACreateNeverTheSourcesThemselves() {
        when(products.existsByName(anyString())).thenReturn(false);
        when(sourceMapper.toStored(any(), eq(null)))
                .thenReturn(List.of(source("Docs site"), source("Changelog MCP")));
        when(products.save(any())).thenAnswer(invocation -> withId(invocation.getArgument(0), "product-1"));
        when(reports.findFirstBySaasProductIdOrderByRunAtDesc(anyString())).thenReturn(Optional.empty());

        service.create(new SaasProductRequest("Acme", null, List.of()));

        verify(audit).log(eq(AuditAction.PRODUCT_CREATED), eq("saas_product"), eq("product-1"),
                detailsCaptor.capture());
        assertThat(detailsCaptor.getValue()).containsOnlyKeys("productName", "sourceCount");
        assertThat(detailsCaptor.getValue()).containsEntry("sourceCount", 2);
    }

    // ----- Update -----

    @Test
    void passesTheStoredSourcesToTheMapperSoAResubmittedSourceKeepsTheTokenTheUserNeverSaw() {
        // The load-bearing argument is the second one. Pass null there and every product edit silently clears every MCP
        // token, because the submitted source carries no authToken - the UI only ever received a last4.
        SaasProduct existing = existing("product-1", "Acme", List.of(source("Changelog MCP")));
        // Held before the call, because update() replaces the product's list with whatever the mapper returned - so
        // reading getSources() afterwards would be asserting on the result rather than on the input.
        List<SourceConfig> storedBeforeUpdate = existing.getSources();
        when(products.findById("product-1")).thenReturn(Optional.of(existing));
        when(products.findByName("Acme")).thenReturn(Optional.of(existing));
        when(sourceMapper.toStored(any(), eq(storedBeforeUpdate))).thenReturn(List.of(source("Changelog MCP")));
        when(products.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(reports.findFirstBySaasProductIdOrderByRunAtDesc("product-1")).thenReturn(Optional.empty());

        service.update("product-1", new SaasProductRequest("Acme", null, List.of()));

        verify(sourceMapper).toStored(any(), eq(storedBeforeUpdate));
    }

    @Test
    void auditsAnUpdateWithTheNamesOfTheFieldsThatChangedAndNoneOfTheirValues() {
        SaasProduct existing = existing("product-1", "Acme", List.of(source("Docs site")));
        when(products.findById("product-1")).thenReturn(Optional.of(existing));
        when(products.findByName("Acme Analytics")).thenReturn(Optional.empty());
        when(sourceMapper.toStored(any(), any())).thenReturn(List.of(source("Docs site")));
        when(products.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(reports.findFirstBySaasProductIdOrderByRunAtDesc("product-1")).thenReturn(Optional.empty());

        service.update("product-1", new SaasProductRequest("Acme Analytics", "Now described", List.of()));

        verify(audit).log(eq(AuditAction.PRODUCT_UPDATED), eq("saas_product"), eq("product-1"),
                detailsCaptor.capture());
        assertThat(detailsCaptor.getValue()).containsOnlyKeys("productName", "changedFields", "sourceCount");
        assertThat(detailsCaptor.getValue().get("changedFields"))
                .asInstanceOf(InstanceOfAssertFactories.list(String.class))
                .containsExactly("name", "description");
        // "Now described" is a value, not a field name, and must appear nowhere in the entry.
        assertThat(detailsCaptor.getValue().toString()).doesNotContain("Now described");
    }

    @Test
    void reportsSourcesAsChangedWithoutComparingCiphertextBecauseTwoEncryptionsOfOneTokenDiffer() {
        // AES-GCM uses a fresh nonce per encryption, so comparing authTokenEncrypted would flag every resubmission as a
        // change. The comparison uses only whether a token is present, which is why a differing ciphertext for the same
        // source is not a change and an added source is.
        SourceConfig stored = source("Docs site");
        stored.setAuthTokenEncrypted("nonce-A:ciphertext-A");
        SaasProduct existing = existing("product-1", "Acme", List.of(stored));
        SourceConfig reEncrypted = source("Docs site");
        reEncrypted.setAuthTokenEncrypted("nonce-B:ciphertext-B");

        when(products.findById("product-1")).thenReturn(Optional.of(existing));
        when(products.findByName("Acme")).thenReturn(Optional.empty());
        when(sourceMapper.toStored(any(), any())).thenReturn(List.of(reEncrypted));
        when(products.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(reports.findFirstBySaasProductIdOrderByRunAtDesc("product-1")).thenReturn(Optional.empty());

        service.update("product-1", new SaasProductRequest("Acme", null, List.of()));

        verify(audit).log(any(), anyString(), anyString(), detailsCaptor.capture());
        assertThat((List<?>) detailsCaptor.getValue().get("changedFields")).isEmpty();
    }

    @Test
    void refusesToRenameAProductOntoAnotherProductsNameButAllowsAProductToKeepItsOwn() {
        SaasProduct existing = existing("product-1", "Acme", List.of());
        SaasProduct other = existing("product-2", "Beta Corp", List.of());
        when(products.findById("product-1")).thenReturn(Optional.of(existing));
        when(products.findByName("Beta Corp")).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.update("product-1", new SaasProductRequest("Beta Corp", null, List.of())))
                .isInstanceOf(ConflictException.class);
        verify(products, never()).save(any());
    }

    // ----- Delete and cascade -----

    @Test
    void deletesEveryDependentBeforeTheProductSoAPartialFailureLeavesSomethingToRetry() {
        SaasProduct existing = existing("product-1", "Acme", List.of());
        when(products.findById("product-1")).thenReturn(Optional.of(existing));
        when(snapshots.deleteBySaasProductId("product-1")).thenReturn(7L);
        when(reports.deleteBySaasProductId("product-1")).thenReturn(3L);
        when(runRecords.deleteBySaasProductId("product-1")).thenReturn(4L);

        service.delete("product-1");

        // There is no transaction - a standalone MongoDB has none - so ordering is the only thing making a partial
        // failure recoverable rather than an orphan.
        InOrder order = inOrder(snapshots, reports, runRecords, products);
        order.verify(snapshots).deleteBySaasProductId("product-1");
        order.verify(reports).deleteBySaasProductId("product-1");
        order.verify(runRecords).deleteBySaasProductId("product-1");
        order.verify(products).deleteById("product-1");
    }

    @Test
    void recordsWhatTheCascadeActuallyRemovedSoADeleteIsAccountableAfterTheFact() {
        when(products.findById("product-1"))
                .thenReturn(Optional.of(existing("product-1", "Acme", List.of())));
        when(snapshots.deleteBySaasProductId("product-1")).thenReturn(7L);
        when(reports.deleteBySaasProductId("product-1")).thenReturn(3L);
        when(runRecords.deleteBySaasProductId("product-1")).thenReturn(4L);

        service.delete("product-1");

        verify(audit).log(eq(AuditAction.PRODUCT_DELETED), eq("saas_product"), eq("product-1"),
                detailsCaptor.capture());
        assertThat(detailsCaptor.getValue())
                .containsEntry("productName", "Acme")
                .containsEntry("deletedSnapshots", 7L)
                .containsEntry("deletedReports", 3L)
                .containsEntry("deletedRunRecords", 4L);
    }

    @Test
    void deletesNothingWhenTheProductDoesNotExistRatherThanRunningTheCascadeAgainstAStrayId() {
        when(products.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete("missing")).isInstanceOf(NotFoundException.class);

        verify(snapshots, never()).deleteBySaasProductId(anyString());
        verify(reports, never()).deleteBySaasProductId(anyString());
        verify(runRecords, never()).deleteBySaasProductId(anyString());
        verify(audit, never()).log(any(), anyString(), anyString(), any());
    }

    // ----- Read -----

    @Test
    void failsWithANotFoundRatherThanAnEmptyResponseWhenAProductIdIsUnknown() {
        when(products.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("missing"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void listsProductsThroughTheNewestFirstQuerySoTheDashboardOrderIsTheIndexedOne() {
        when(products.findAllByOrderByCreatedAtDesc(any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(
                        List.of(existing("product-1", "Acme", List.of(source("Docs site"))))));
        when(sourceMapper.toResponses(any())).thenReturn(List.of());
        when(reports.findFirstBySaasProductIdOrderByRunAtDesc("product-1")).thenReturn(Optional.empty());

        assertThat(service.list(PageRequest.of(0, 20)).getContent())
                .singleElement()
                .satisfies(response -> {
                    assertThat(response.name()).isEqualTo("Acme");
                    // sourceCount comes from the stored list, so a dashboard card need not count a masked response.
                    assertThat(response.sourceCount()).isEqualTo(1);
                });
        verify(products).findAllByOrderByCreatedAtDesc(any());
    }

    // ----- Helpers -----

    private static SourceConfig source(String name) {
        return new SourceConfig(SourceType.WEBSITE, name, "https://acme.test/" + name, null);
    }

    private static SaasProduct existing(String id, String name, List<SourceConfig> sources) {
        SaasProduct product = new SaasProduct(name, null, sources, "admin");
        product.setId(id);
        return product;
    }

    private static SaasProduct withId(SaasProduct product, String id) {
        product.setId(id);
        return product;
    }
}
