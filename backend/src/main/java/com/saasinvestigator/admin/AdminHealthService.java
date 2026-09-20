package com.saasinvestigator.admin;

import com.saasinvestigator.credential.SystemCredentialService;
import com.saasinvestigator.run.RunOutcome;
import com.saasinvestigator.run.RunRecord;
import com.saasinvestigator.run.RunRecordRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.IndicatedHealthDescriptor;
import org.springframework.stereotype.Service;

/**
 * Assembles the detailed, ADMIN-only health view.
 *
 * <h2>Boot's checks are reused, not reimplemented</h2>
 *
 * <p>Spring Boot already ships a Mongo health contributor that runs a real command against the server and a disk-space
 * contributor that knows the configured threshold. Calling {@link HealthEndpoint#health()} programmatically gets both -
 * and anything else that arrives later on the classpath - with their statuses already rolled up. Writing a second Mongo
 * check here would be a check that can disagree with the one the liveness probe uses, which is the worst possible
 * property for a health page to have.
 *
 * <p>Note that {@code management.endpoint.health.show-details=never} does not hide anything from this call. That
 * property governs what the <em>web</em> endpoint shows an unauthenticated caller; the endpoint bean itself always
 * reports in full. That separation is the whole reason this design works: the public probe stays terse, and the detail
 * appears only behind the ADMIN check on the controller.
 *
 * <h2>What only this application can answer</h2>
 *
 * <p>Two things, and they are the reason the tab exists. Whether a system-wide LLM credential resolves right now and
 * from which channel - infrastructure has no opinion on that - and when a run last actually succeeded, which is the one
 * signal that distinguishes "everything is up" from "everything is up and working".
 */
@Service
public class AdminHealthService {

    private final HealthEndpoint healthEndpoint;
    private final SystemCredentialService systemCredentials;
    private final RunRecordRepository runRecords;
    private final String swaggerUiPath;

    /**
     * @param healthEndpoint Boot's own health aggregation
     * @param systemCredentials reports which providers have a resolvable system-wide key, and from where
     * @param runRecords supplies the last successful run
     * @param swaggerUiPath read from configuration rather than hardcoded, so the link cannot rot if the path is moved
     */
    AdminHealthService(HealthEndpoint healthEndpoint,
                       SystemCredentialService systemCredentials,
                       RunRecordRepository runRecords,
                       @Value("${springdoc.swagger-ui.path:/swagger-ui.html}") String swaggerUiPath) {
        this.healthEndpoint = healthEndpoint;
        this.systemCredentials = systemCredentials;
        this.runRecords = runRecords;
        this.swaggerUiPath = swaggerUiPath;
    }

    /**
     * Runs every check and returns the combined picture.
     *
     * <p>Each call performs the checks afresh. A cached health report is a report about the past, and the reason someone
     * opened this page is that they suspect the present is different.
     *
     * @return the detailed health view
     */
    public AdminHealthResponse health() {
        HealthDescriptor root = healthEndpoint.health();
        return new AdminHealthResponse(
                root.getStatus().getCode(),
                components(root),
                providers(),
                lastSuccessfulRun(),
                new AdminHealthResponse.Links(swaggerUiPath, "/actuator/prometheus", "/actuator/health"));
    }

    /**
     * Flattens Boot's health tree into one row per contributor.
     *
     * <p>Only the top level is walked. A nested composite - a contributor that is itself a group - contributes its
     * rolled-up status and no details, which is the right trade for a page meant to be read at a glance: the status is
     * what tells you where to look, and {@code /actuator/health} is where to look in full.
     */
    private static List<AdminHealthResponse.ComponentHealth> components(HealthDescriptor root) {
        List<AdminHealthResponse.ComponentHealth> components = new ArrayList<>();
        if (root instanceof CompositeHealthDescriptor composite) {
            composite.getComponents().forEach((name, descriptor) ->
                    components.add(new AdminHealthResponse.ComponentHealth(
                            name, descriptor.getStatus().getCode(), details(descriptor))));
        } else {
            // A single-contributor instance reports no composite at all. Naming it "application" keeps the response
            // shape identical rather than making the frontend handle an empty list as a special case.
            components.add(new AdminHealthResponse.ComponentHealth(
                    "application", root.getStatus().getCode(), details(root)));
        }
        components.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return components;
    }

    private static Map<String, Object> details(HealthDescriptor descriptor) {
        if (descriptor instanceof IndicatedHealthDescriptor indicated) {
            return indicated.getDetails() == null ? Map.of() : indicated.getDetails();
        }
        return Map.of();
    }

    private List<AdminHealthResponse.ProviderHealth> providers() {
        return systemCredentials.statuses().stream()
                .map(status -> new AdminHealthResponse.ProviderHealth(
                        status.provider(), status.configured(), status.source(), status.last4()))
                .toList();
    }

    /**
     * Finds the newest run that produced a report.
     *
     * <p>{@code PARTIAL} qualifies: it stored a report, so the model was reached and the pipeline works. Excluding it
     * would show "last successful run: never" on an instance that is running fine but has one source behind a firewall.
     */
    private AdminHealthResponse.LastSuccessfulRun lastSuccessfulRun() {
        return runRecords.findFirstByOutcomeInOrderByStartedAtDesc(
                        List.of(RunOutcome.SUCCESS, RunOutcome.PARTIAL))
                .map(this::toLastSuccessfulRun)
                .orElse(null);
    }

    private AdminHealthResponse.LastSuccessfulRun toLastSuccessfulRun(RunRecord record) {
        return new AdminHealthResponse.LastSuccessfulRun(
                record.getProductName(),
                record.getStartedAt(),
                record.getOutcome() == null ? null : record.getOutcome().wireName());
    }
}
