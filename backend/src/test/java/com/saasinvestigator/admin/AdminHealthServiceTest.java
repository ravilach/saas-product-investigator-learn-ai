package com.saasinvestigator.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.saasinvestigator.credential.CredentialSource;
import com.saasinvestigator.credential.SystemCredentialService;
import com.saasinvestigator.credential.SystemCredentialStatus;
import com.saasinvestigator.llm.LlmProviderType;
import com.saasinvestigator.report.AnalysisDepth;
import com.saasinvestigator.report.RunType;
import com.saasinvestigator.run.RunOutcome;
import com.saasinvestigator.run.RunRecord;
import com.saasinvestigator.run.RunRecordRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.health.actuate.endpoint.AdditionalHealthEndpointPath;
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.actuate.endpoint.HttpCodeStatusMapper;
import org.springframework.boot.health.actuate.endpoint.IndicatedHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.StatusAggregator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.health.registry.DefaultHealthContributorRegistry;
import org.springframework.boot.health.registry.DefaultReactiveHealthContributorRegistry;

/**
 * Tests {@link AdminHealthService}, which turns Boot's health tree into one flat page.
 *
 * <h2>The claim worth testing</h2>
 *
 * <p>That this endpoint reports details {@code /actuator/health} deliberately does not. The public endpoint runs with
 * {@code management.endpoint.health.show-details=never} so an unauthenticated caller learns only UP or DOWN - but that
 * property governs the <em>web extension</em>, not the endpoint bean, which always reports in full. An admin console that
 * could show only "DOWN" would be a page with nothing on it at the moment it is most needed, so the distinction is the
 * feature and a test says so.
 *
 * <p>Boot's descriptor types all have package-private constructors, so most tests here mock them. That is a real cost:
 * those assertions describe how this service walks a tree of the shape Boot is <em>assumed</em> to return, and a Boot
 * upgrade that changed the shape would leave them all green. The last section closes that gap by building a real
 * {@link HealthEndpoint} over real contributors and asserting against whatever Boot actually produces.
 */
@ExtendWith(MockitoExtension.class)
class AdminHealthServiceTest {

    @Mock
    private HealthEndpoint healthEndpoint;
    @Mock
    private SystemCredentialService systemCredentials;
    @Mock
    private RunRecordRepository runRecords;

    private AdminHealthService service() {
        return new AdminHealthService(healthEndpoint, systemCredentials, runRecords, "/swagger-ui.html");
    }

    // ----- Components -----

    @Test
    void reportsOneRowPerContributorWithTheDetailsThePublicEndpointWithholds() {
        // Built into locals first, not inline in the when(...) argument. Stubbing a mock while another stubbing is in
        // progress leaves Mockito with an unfinished when() and fails the test before it reaches an assertion.
        HealthDescriptor mongo = indicated(Status.UP, Map.of("maxWireVersion", 25));
        HealthDescriptor diskSpace = indicated(Status.UP, Map.of("free", 12345L));
        CompositeHealthDescriptor root = composite(Status.UP, Map.of("mongo", mongo, "diskSpace", diskSpace));

        when(healthEndpoint.health()).thenReturn(root);
        when(systemCredentials.statuses()).thenReturn(List.of());
        when(runRecords.findFirstByOutcomeInOrderByStartedAtDesc(List.of(RunOutcome.SUCCESS, RunOutcome.PARTIAL)))
                .thenReturn(Optional.empty());

        AdminHealthResponse health = service().health();

        assertThat(health.status()).isEqualTo("UP");
        // Sorted by name so the page does not reorder itself between refreshes - Boot's map has no guaranteed order.
        assertThat(health.components())
                .extracting(AdminHealthResponse.ComponentHealth::name,
                        AdminHealthResponse.ComponentHealth::status)
                .containsExactly(tuple("diskSpace", "UP"), tuple("mongo", "UP"));
        assertThat(health.components().get(1).details()).containsEntry("maxWireVersion", 25);
    }

    @Test
    void surfacesADownComponentByNameSoTheReaderKnowsWhereToLook() {
        HealthDescriptor mongo = indicated(Status.DOWN, Map.of("error", "connection refused"));
        CompositeHealthDescriptor root = composite(Status.DOWN, Map.of("mongo", mongo));

        when(healthEndpoint.health()).thenReturn(root);
        when(systemCredentials.statuses()).thenReturn(List.of());
        when(runRecords.findFirstByOutcomeInOrderByStartedAtDesc(List.of(RunOutcome.SUCCESS, RunOutcome.PARTIAL)))
                .thenReturn(Optional.empty());

        AdminHealthResponse health = service().health();

        assertThat(health.status()).isEqualTo("DOWN");
        assertThat(health.components()).singleElement().satisfies(component -> {
            assertThat(component.name()).isEqualTo("mongo");
            assertThat(component.status()).isEqualTo("DOWN");
            assertThat(component.details()).containsEntry("error", "connection refused");
        });
    }

    @Test
    void namesTheSingleRowApplicationWhenTheInstanceReportsNoCompositeAtAll() {
        // An instance with one contributor reports a bare descriptor. Inventing a name keeps the response shape the same
        // rather than making the frontend handle an empty component list as a special case.
        IndicatedHealthDescriptor bare = indicated(Status.UP, Map.of());

        when(healthEndpoint.health()).thenReturn(bare);
        when(systemCredentials.statuses()).thenReturn(List.of());
        when(runRecords.findFirstByOutcomeInOrderByStartedAtDesc(List.of(RunOutcome.SUCCESS, RunOutcome.PARTIAL)))
                .thenReturn(Optional.empty());

        assertThat(service().health().components())
                .singleElement()
                .satisfies(component -> assertThat(component.name()).isEqualTo("application"));
    }

    @Test
    void reportsAnEmptyDetailMapRatherThanNullForAContributorThatSuppliesNone() {
        // The map lands in JSON the frontend iterates; null would be a crash in the UI rather than an empty row.
        IndicatedHealthDescriptor noDetails = mock(IndicatedHealthDescriptor.class);
        when(noDetails.getStatus()).thenReturn(Status.UP);
        when(noDetails.getDetails()).thenReturn(null);
        CompositeHealthDescriptor root = composite(Status.UP, Map.of("ping", noDetails));

        when(healthEndpoint.health()).thenReturn(root);
        when(systemCredentials.statuses()).thenReturn(List.of());
        when(runRecords.findFirstByOutcomeInOrderByStartedAtDesc(List.of(RunOutcome.SUCCESS, RunOutcome.PARTIAL)))
                .thenReturn(Optional.empty());

        assertThat(service().health().components().get(0).details()).isEmpty();
    }

    // ----- Providers -----

    @Test
    void reportsEachProvidersConfiguredStateAndItsLastFourCharactersButNeverAKey() {
        endpointReportsOneHealthyContributor();
        when(systemCredentials.statuses()).thenReturn(List.of(
                new SystemCredentialStatus(LlmProviderType.ANTHROPIC, true, "wxyz", CredentialSource.HOST_MOUNT),
                new SystemCredentialStatus(LlmProviderType.OPENAI, false, null, null)));
        when(runRecords.findFirstByOutcomeInOrderByStartedAtDesc(List.of(RunOutcome.SUCCESS, RunOutcome.PARTIAL)))
                .thenReturn(Optional.empty());

        AdminHealthResponse health = service().health();

        assertThat(health.providers())
                .extracting(AdminHealthResponse.ProviderHealth::provider,
                        AdminHealthResponse.ProviderHealth::configured,
                        AdminHealthResponse.ProviderHealth::last4,
                        AdminHealthResponse.ProviderHealth::source)
                .containsExactly(
                        tuple(LlmProviderType.ANTHROPIC, true, "wxyz", CredentialSource.HOST_MOUNT),
                        tuple(LlmProviderType.OPENAI, false, null, null));
    }

    // ----- Last successful run -----

    @Test
    void countsAPartialRunAsTheLastSuccessfulOneBecauseItStillProducedAReport() {
        // Excluding PARTIAL would show "last successful run: never" on an instance running fine with one source behind a
        // firewall - the single most misleading thing this page could say.
        Instant startedAt = Instant.parse("2026-06-01T09:30:00Z");
        endpointReportsOneHealthyContributor();
        when(systemCredentials.statuses()).thenReturn(List.of());
        when(runRecords.findFirstByOutcomeInOrderByStartedAtDesc(List.of(RunOutcome.SUCCESS, RunOutcome.PARTIAL)))
                .thenReturn(Optional.of(new RunRecord("product-1", "Acme Analytics", RunType.STANDARD,
                        AnalysisDepth.REGULAR, RunOutcome.PARTIAL, "dana", startedAt,
                        Duration.ofSeconds(20), "report-1", null)));

        assertThat(service().health().lastSuccessfulRun()).satisfies(last -> {
            assertThat(last.productName()).isEqualTo("Acme Analytics");
            assertThat(last.runAt()).isEqualTo(startedAt);
            assertThat(last.outcome()).isEqualTo("partial");
        });
    }

    @Test
    void reportsNullRatherThanAPlaceholderWhenNoRunHasEverSucceeded() {
        endpointReportsOneHealthyContributor();
        when(systemCredentials.statuses()).thenReturn(List.of());
        when(runRecords.findFirstByOutcomeInOrderByStartedAtDesc(List.of(RunOutcome.SUCCESS, RunOutcome.PARTIAL)))
                .thenReturn(Optional.empty());

        assertThat(service().health().lastSuccessfulRun()).isNull();
    }

    // ----- Links -----

    @Test
    void carriesTheConfiguredSwaggerPathRatherThanAHardcodedOneSoTheLinkStillWorksWhenItIsMoved() {
        endpointReportsOneHealthyContributor();
        when(systemCredentials.statuses()).thenReturn(List.of());
        when(runRecords.findFirstByOutcomeInOrderByStartedAtDesc(List.of(RunOutcome.SUCCESS, RunOutcome.PARTIAL)))
                .thenReturn(Optional.empty());

        AdminHealthResponse.Links links = new AdminHealthService(healthEndpoint, systemCredentials, runRecords,
                "/docs/api").health().links();

        assertThat(links.apiDocs()).isEqualTo("/docs/api");
        assertThat(links.prometheus()).isEqualTo("/actuator/prometheus");
        assertThat(links.actuatorHealth()).isEqualTo("/actuator/health");
    }

    // ----- Against a real Boot health tree -----

    @Test
    void readsDetailsOutOfARealBootTreeEvenThoughTheGroupItselfSaysToHideThem() {
        // The claim in this service's Javadoc, tested rather than asserted: show-details=never governs the web
        // extension, not the endpoint bean. The group below hides both components and details, which is the posture the
        // property produces - and the details still arrive, because health() does not consult it. If Boot ever changed
        // that, this test fails and the Admin Console's health tab becomes a page of bare statuses without anyone
        // noticing otherwise.
        HealthEndpoint endpoint = realEndpoint(Map.of(
                "mongo", () -> Health.up().withDetail("maxWireVersion", 25).build(),
                "diskSpace", () -> Health.up().withDetail("free", 12345L).build()));
        when(systemCredentials.statuses()).thenReturn(List.of());
        when(runRecords.findFirstByOutcomeInOrderByStartedAtDesc(List.of(RunOutcome.SUCCESS, RunOutcome.PARTIAL)))
                .thenReturn(Optional.empty());

        AdminHealthResponse health = serviceOver(endpoint).health();

        assertThat(health.status()).isEqualTo("UP");
        assertThat(health.components())
                .extracting(AdminHealthResponse.ComponentHealth::name)
                .containsExactly("diskSpace", "mongo");
        assertThat(health.components().get(1).details()).containsEntry("maxWireVersion", 25);
    }

    @Test
    void rollsUpToDownAndKeepsTheFailingContributorsErrorDetailWhenBootBuildsTheTree() {
        // The aggregate status is Boot's own StatusAggregator at work, not arithmetic this service does - and "error" is
        // a detail Boot synthesises from the exception, so it only appears in a test that lets Boot build the descriptor.
        HealthEndpoint endpoint = realEndpoint(Map.of(
                "mongo", () -> Health.down(new IllegalStateException("connection refused")).build(),
                "diskSpace", () -> Health.up().build()));
        when(systemCredentials.statuses()).thenReturn(List.of());
        when(runRecords.findFirstByOutcomeInOrderByStartedAtDesc(List.of(RunOutcome.SUCCESS, RunOutcome.PARTIAL)))
                .thenReturn(Optional.empty());

        AdminHealthResponse health = serviceOver(endpoint).health();

        assertThat(health.status()).isEqualTo("DOWN");
        assertThat(health.components())
                .filteredOn(component -> component.name().equals("mongo"))
                .singleElement()
                .satisfies(component -> {
                    assertThat(component.status()).isEqualTo("DOWN");
                    assertThat(component.details().get("error").toString()).contains("connection refused");
                });
    }

    /**
     * Builds a genuine {@link HealthEndpoint} over the given indicators.
     *
     * <p>Assembled by hand rather than through an application context because the endpoint's four collaborators are all
     * constructible and none of them needs a running server - which keeps the real-tree tests as fast as the mocked ones
     * and means there is no reason to have only mocked ones.
     */
    private static HealthEndpoint realEndpoint(Map<String, HealthIndicator> contributors) {
        DefaultHealthContributorRegistry registry = new DefaultHealthContributorRegistry();
        contributors.forEach((name, indicator) -> registry.registerContributor(name, indicator));
        return new HealthEndpoint(registry, new DefaultReactiveHealthContributorRegistry(),
                HealthEndpointGroups.of(new DetailsHiddenGroup(), Map.of()), Duration.ofSeconds(10));
    }

    private AdminHealthService serviceOver(HealthEndpoint endpoint) {
        return new AdminHealthService(endpoint, systemCredentials, runRecords, "/swagger-ui.html");
    }

    /**
     * A group that reports the posture {@code management.endpoint.health.show-details=never} produces.
     *
     * <p>Both flags are deliberately {@code false}: the test above exists to prove they do not reach the endpoint bean's
     * own {@code health()} call, so a group that permitted details would prove nothing.
     */
    private static final class DetailsHiddenGroup implements HealthEndpointGroup {

        @Override
        public boolean isMember(String name) {
            return true;
        }

        @Override
        public boolean showComponents(SecurityContext securityContext) {
            return false;
        }

        @Override
        public boolean showDetails(SecurityContext securityContext) {
            return false;
        }

        @Override
        public StatusAggregator getStatusAggregator() {
            return StatusAggregator.getDefault();
        }

        @Override
        public HttpCodeStatusMapper getHttpCodeStatusMapper() {
            return HttpCodeStatusMapper.getDefault();
        }

        @Override
        public AdditionalHealthEndpointPath getAdditionalPath() {
            return null;
        }
    }

    // ----- Descriptor fixtures -----

    /**
     * Stubs the endpoint with one healthy contributor, for the tests whose subject is not the component tree.
     *
     * <p>A method rather than four copies of the same two lines, and a method rather than a {@code @BeforeEach}, because
     * the four tests above it stub the endpoint differently and a shared setup they all overrode would be an unnecessary
     * stubbing under strict stubs.
     */
    private void endpointReportsOneHealthyContributor() {
        IndicatedHealthDescriptor descriptor = indicated(Status.UP, Map.of());
        when(healthEndpoint.health()).thenReturn(descriptor);
    }

    /**
     * Builds a composite descriptor.
     *
     * <p>Mocked because every constructor in Boot's descriptor hierarchy is package-private - there is no supported way
     * to build one from outside the framework, and copying the package to get at them would be asserting against a
     * fabrication of Boot's tree rather than Boot's tree.
     */
    private static CompositeHealthDescriptor composite(Status status, Map<String, HealthDescriptor> components) {
        CompositeHealthDescriptor descriptor = mock(CompositeHealthDescriptor.class);
        when(descriptor.getStatus()).thenReturn(status);
        // A copy, so the iteration order the service sorts away is this map's rather than the caller's literal.
        when(descriptor.getComponents()).thenReturn(new LinkedHashMap<>(components));
        return descriptor;
    }

    private static IndicatedHealthDescriptor indicated(Status status, Map<String, Object> details) {
        IndicatedHealthDescriptor descriptor = mock(IndicatedHealthDescriptor.class);
        when(descriptor.getStatus()).thenReturn(status);
        when(descriptor.getDetails()).thenReturn(details);
        return descriptor;
    }
}
