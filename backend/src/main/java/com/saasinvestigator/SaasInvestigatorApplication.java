package com.saasinvestigator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Entry point for the SaaS Product Investigator backend.
 *
 * <p>The application is an orchestrator, not a diffing engine: it fetches source content, hands the
 * current and previous versions to an LLM, and persists whatever structured "what changed" report
 * comes back. There is deliberately no hand-written comparison logic anywhere in this codebase - see
 * {@code /docs/ARCHITECTURE.md} for why that tradeoff was made.
 *
 * <p>{@link EnableAsync} is switched on here because a run is kicked off as a background task and
 * reported on over Server-Sent Events, rather than blocking the HTTP request for the whole
 * crawl-plus-LLM pipeline.
 *
 * <p>{@link ConfigurationPropertiesScan} picks up the handful of settings groups that are bound as
 * records rather than injected with {@code @Value} - currently
 * {@link com.saasinvestigator.crawl.CrawlerProperties}. Individual values stay on {@code @Value}
 * where they genuinely are individual; a record is used where a group of settings is one subject and
 * gets passed around as a unit.
 */
@SpringBootApplication
@EnableAsync
@ConfigurationPropertiesScan
public class SaasInvestigatorApplication {

    /**
     * Boots the Spring application context.
     *
     * @param args standard JVM command-line arguments, forwarded to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(SaasInvestigatorApplication.class, args);
    }
}
