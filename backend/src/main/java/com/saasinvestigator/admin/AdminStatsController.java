package com.saasinvestigator.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Admin Console's Overview numbers.
 *
 * <p>ADMIN-only, like everything under {@code /api/admin}. The counts themselves are hardly sensitive, but taken
 * together they describe how much of the instance is configured and how often it works - which is reconnaissance, not
 * dashboard data, for anyone who should not have it.
 */
@RestController
@RequestMapping("/api/admin/stats")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Stats", description = "Usage statistics for the Admin Console overview")
public class AdminStatsController {

    private final AdminStatsService stats;

    /**
     * @param stats computes the figures
     */
    public AdminStatsController(AdminStatsService stats) {
        this.stats = stats;
    }

    /**
     * @return the Overview tab's figures, computed fresh on each request
     */
    @GetMapping
    @Operation(summary = "Usage statistics: totals, run counts, 7-day success rate, recent activity")
    public AdminStatsResponse stats() {
        return stats.stats();
    }
}
