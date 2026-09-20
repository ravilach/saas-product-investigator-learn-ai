package com.saasinvestigator.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Tests {@link SaasProductRepository} against a real MongoDB, and that an embedded source list survives storage.
 *
 * <p>The round-trip test is the one that earns its place. {@code sources} is an embedded array of mutable objects
 * holding an enum, two nullable {@code Integer}s, and a field whose contents are ciphertext - and its <b>order</b> is
 * meaningful, because it is both the fetch order and the order the user arranged. Order surviving a save is the kind
 * of thing that is obviously true until the day it isn't.
 *
 * <p>Requires a running Docker daemon; fails rather than skips without one (see {@code docs/SETUP.md}).
 */
@Testcontainers
@DataMongoTest
class SaasProductRepositoryMongoTest {

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");

    @Autowired
    private SaasProductRepository products;

    @Autowired
    private MongoTemplate mongoTemplate;

    private static final Instant T0 = Instant.parse("2026-09-01T12:00:00Z");

    @BeforeEach
    void seed() {
        mongoTemplate.getCollection("saas_products").drop();
        save("Oldest product", T0);
        save("Middle product", T0.plus(1, ChronoUnit.DAYS));
        save("Newest product", T0.plus(2, ChronoUnit.DAYS));
    }

    @Test
    void theDashboardListIsNewestFirstAndPaged() {
        var page = products.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 2));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(SaasProduct::getName)
                .containsExactly("Newest product", "Middle product");
        assertThat(page.isLast()).isFalse();
    }

    @Test
    void lookupByNameFindsTheProductAndMissesCleanly() {
        assertThat(products.findByName("Middle product")).isPresent();
        assertThat(products.findByName("No such product")).isEmpty();
    }

    @Test
    void existsByNameIsWhatRejectsADuplicateBeforeItIsCreated() {
        // Enforced in the service rather than by a unique index, so the rejection can be a 409 with a readable
        // message instead of a driver exception surfacing as a 500.
        assertThat(products.existsByName("Newest product")).isTrue();
        assertThat(products.existsByName("Newest Product")).isFalse();
    }

    @Test
    void anEmbeddedSourceListRoundTripsInOrderWithAllOfItsFields() {
        SourceConfig docs = new SourceConfig(SourceType.DOCS_MCP, "API docs",
                "https://mcp.example.com/docs", "encrypted-token-ciphertext");
        SourceConfig site = new SourceConfig(SourceType.WEBSITE, "Marketing site", "https://example.com", null);
        site.setMaxDepth(3);
        site.setMaxPages(50);
        SourceConfig changelog = new SourceConfig(SourceType.SAAS_URL, "Changelog",
                "https://example.com/changelog", null);

        SaasProduct saved = products.save(
                new SaasProduct("Example", "An example", List.of(docs, site, changelog), "admin"));

        SaasProduct reloaded = products.findById(saved.getId()).orElseThrow();

        // Order preserved: it is the fetch order and the display order.
        assertThat(reloaded.getSources()).extracting(SourceConfig::getName)
                .containsExactly("API docs", "Marketing site", "Changelog");
        assertThat(reloaded.getSources().getFirst().getType()).isEqualTo(SourceType.DOCS_MCP);
        assertThat(reloaded.getSources().getFirst().getAuthTokenEncrypted())
                .isEqualTo("encrypted-token-ciphertext");
        // Overrides persist as given, and an absent override stays null rather than becoming 0 - which would mean
        // "crawl nothing" instead of "use the system default".
        assertThat(reloaded.getSources().get(1).getMaxDepth()).isEqualTo(3);
        assertThat(reloaded.getSources().get(1).getMaxPages()).isEqualTo(50);
        assertThat(reloaded.getSources().get(2).getMaxDepth()).isNull();
        assertThat(reloaded.getSources().get(2).getMaxPages()).isNull();
    }

    @Test
    void familyHelpersStillWorkOnAReloadedProduct() {
        // The helpers read the enum, so they only work if the enum actually round-tripped - which is the point.
        SaasProduct saved = products.save(new SaasProduct("Mixed", null, List.of(
                new SourceConfig(SourceType.GENERIC_MCP, "Something", "https://mcp.example.com", null),
                new SourceConfig(SourceType.WEBSITE, "Site", "https://example.com", null)), "admin"));

        SaasProduct reloaded = products.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.mcpSources()).extracting(SourceConfig::getName).containsExactly("Something");
        assertThat(reloaded.crawledSources()).extracting(SourceConfig::getName).containsExactly("Site");
    }

    @Test
    void aProductWithNoSourcesYetIsStorableAndReloadsWithAnEmptyList() {
        // The state a product is in between "created" and "first source added", so it has to be representable.
        SaasProduct saved = products.save(new SaasProduct("Empty", null, List.of(), "admin"));

        assertThat(products.findById(saved.getId()).orElseThrow().getSources()).isEmpty();
    }

    private void save(String name, Instant createdAt) {
        SaasProduct product = new SaasProduct(name, "seeded", List.of(
                new SourceConfig(SourceType.SAAS_URL, "Changelog", "https://example.com/changelog", null)),
                "admin");
        product.setCreatedAt(createdAt);
        mongoTemplate.save(product);
    }
}
