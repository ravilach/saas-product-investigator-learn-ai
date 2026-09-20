package com.saasinvestigator.admin;

import com.saasinvestigator.credential.CredentialSource;
import com.saasinvestigator.llm.LlmProviderType;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The Admin Console's Health tab: the infrastructure checks Spring Boot already performs, plus the application-specific
 * ones only this app can answer.
 *
 * <p>Deliberately more detailed than {@code /actuator/health}. That endpoint is unauthenticated because a Kubernetes
 * probe cannot present a JWT, so it stays minimal on purpose - it must not tell an anonymous caller which database this
 * is or whether a credential is configured. This response is ADMIN-only, which is what makes the detail below
 * acceptable.
 *
 * @param status the overall roll-up, {@code UP} / {@code DOWN} / {@code OUT_OF_SERVICE} / {@code UNKNOWN}
 * @param components each Boot health contributor by name - {@code mongo}, {@code diskSpace}, {@code ping}, and
 *     whatever else is on the classpath - reusing Boot's own checks rather than reimplementing them
 * @param providers whether each LLM provider's system-wide credential resolves right now, and from where
 * @param lastSuccessfulRun the most recent run that produced a report, or {@code null} if none ever has
 * @param links where to find the other two observability surfaces, so nobody has to remember the paths
 */
public record AdminHealthResponse(
        String status,
        List<ComponentHealth> components,
        List<ProviderHealth> providers,
        LastSuccessfulRun lastSuccessfulRun,
        Links links) {

    /**
     * One of Spring Boot's health contributors.
     *
     * @param name the contributor's name as Boot registered it
     * @param status its status code
     * @param details whatever the contributor reported - free disk space, the Mongo version - or an empty map. Passed
     *     through rather than filtered: the point of this tab is to show what Boot already knows, and a contributor's
     *     details are the part worth reading. Nothing here can carry a credential, because a health contributor is
     *     given no access to one
     */
    public record ComponentHealth(String name, String status, Map<String, Object> details) {
    }

    /**
     * Whether an LLM provider could be used right now without anybody supplying a personal key.
     *
     * @param provider the provider
     * @param configured whether a system-wide credential resolves
     * @param source where it resolves from - admin override, environment variable, or host mount - because "it works"
     *     and "it works for the reason you think" are different facts, and the second is the one that matters before
     *     changing anything
     * @param last4 the last four characters of the key, so an admin can tell which key is in force without seeing it.
     *     Never the key itself, encrypted or not
     */
    public record ProviderHealth(LlmProviderType provider, boolean configured, CredentialSource source,
                                 String last4) {
    }

    /**
     * When this instance last actually worked end to end.
     *
     * <p>The single most useful line on the page, and the one no infrastructure check can produce: Mongo can be up, the
     * disk can have room, and every run can still have been failing for a week.
     *
     * @param productName the product that ran
     * @param runAt when it started
     * @param outcome {@code success} or {@code partial}
     */
    public record LastSuccessfulRun(String productName, Instant runAt, String outcome) {
    }

    /**
     * Pointers to the surfaces this page deliberately does not duplicate.
     *
     * @param apiDocs the Swagger UI path
     * @param prometheus the scrape endpoint - reachable only to whoever the network allows, since it is unauthenticated
     *     by design and restricted at the ingress layer instead
     * @param actuatorHealth the minimal public probe, for comparison with the detail above
     */
    public record Links(String apiDocs, String prometheus, String actuatorHealth) {
    }
}
