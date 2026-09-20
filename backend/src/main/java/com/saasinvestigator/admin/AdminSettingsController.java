package com.saasinvestigator.admin;

import com.saasinvestigator.audit.AuditAction;
import com.saasinvestigator.audit.AuditService;
import com.saasinvestigator.crawl.CrawlSettings;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The non-secret half of system configuration: the crawl defaults.
 *
 * <p>Separate from the Secrets tab on purpose. Everything here is safe to show in full and to log the value of;
 * everything there is not, and a single "Settings" screen mixing the two would make that distinction a matter of
 * remembering which row you were on.
 *
 * <p>The defaults live in {@code system_config} rather than in a properties file so that changing them does not need a
 * restart, and so that the change is attributable - which is what the {@code SYSTEM_SETTINGS_UPDATED} audit entry below
 * is for. Values here are deliberately logged: a crawl depth is not a secret, and an audit entry saying only that "the
 * crawl settings changed" would leave nobody able to answer what they changed from.
 */
@RestController
@RequestMapping("/api/admin/settings")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Settings", description = "Non-secret system configuration: crawl defaults")
public class AdminSettingsController {

    /** Audit target type for a settings change. There is one settings document, so there is one target id. */
    private static final String AUDIT_TARGET = "system_settings";

    private final CrawlSettings crawlSettings;
    private final AuditService audit;

    /**
     * @param crawlSettings reads and writes the stored defaults, and enforces the ceilings
     * @param audit records the change
     */
    public AdminSettingsController(CrawlSettings crawlSettings, AuditService audit) {
        this.crawlSettings = crawlSettings;
        this.audit = audit;
    }

    /**
     * @return the defaults currently in force, together with the hard ceilings on them
     */
    @GetMapping
    @Operation(summary = "Current crawl defaults and the ceilings they may not exceed")
    public AdminSettingsResponse get() {
        return AdminSettingsResponse.of(crawlSettings.currentDefaults(), crawlSettings.ceilings());
    }

    /**
     * Replaces the crawl defaults.
     *
     * <p>Takes effect on the next run rather than on runs already in flight - a crawl that has started has already
     * resolved its limits, and changing them underneath it would make a run's own log disagree with the run.
     *
     * <p>Out-of-range values are rejected by {@link CrawlSettings#updateDefaults(int, int)} with a message naming the
     * actual ceiling, not clamped: an admin who typed 500 and sees 500 in the form afterwards would reasonably believe
     * 500 took effect.
     *
     * @param request the new defaults; both fields required
     * @return the defaults now in force
     */
    @PutMapping
    @Operation(summary = "Update the crawl defaults used by sources that set no override")
    public AdminSettingsResponse update(@Valid @RequestBody AdminSettingsRequest request) {
        CrawlSettings.CrawlDefaults previous = crawlSettings.currentDefaults();
        CrawlSettings.CrawlDefaults updated =
                crawlSettings.updateDefaults(request.defaultMaxDepth(), request.defaultMaxPages());

        audit.log(AuditAction.SYSTEM_SETTINGS_UPDATED, AUDIT_TARGET, CrawlSettings.CONFIG_KEY, Map.of(
                "previousMaxDepth", previous.defaultMaxDepth(),
                "previousMaxPages", previous.defaultMaxPages(),
                "newMaxDepth", updated.defaultMaxDepth(),
                "newMaxPages", updated.defaultMaxPages()));

        return AdminSettingsResponse.of(updated, crawlSettings.ceilings());
    }
}
