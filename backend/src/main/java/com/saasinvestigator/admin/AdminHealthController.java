package com.saasinvestigator.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The detailed health view, distinct from {@code /actuator/health}.
 *
 * <h2>Why there are two health endpoints</h2>
 *
 * <p>They have different readers. {@code /actuator/health} is read by a Kubernetes or ECS probe that cannot present a
 * JWT, so it is unauthenticated and must stay minimal - {@code management.endpoint.health.show-details=never} keeps it
 * to a bare status, because an anonymous caller has no business learning which database this is or whether an LLM
 * credential is configured. This endpoint is read by a person who has already proved they are an administrator, which is
 * what makes the detail safe to show.
 *
 * <p>Always {@code 200}, even when the status is {@code DOWN}. This is a report about health rather than a health check
 * itself: a 503 here would mean the console could not render the page that explains why the instance is unwell.
 */
@RestController
@RequestMapping("/api/admin/health")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Health", description = "Detailed health detail for the Admin Console")
public class AdminHealthController {

    private final AdminHealthService health;

    /**
     * @param health runs the checks
     */
    public AdminHealthController(AdminHealthService health) {
        this.health = health;
    }

    /**
     * @return infrastructure status from Spring Boot's own contributors, plus provider credential resolvability and the
     *     last successful run
     */
    @GetMapping
    @Operation(summary = "Infrastructure health, LLM credential resolvability, and the last successful run")
    public AdminHealthResponse health() {
        return health.health();
    }
}
