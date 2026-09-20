package com.saasinvestigator.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests {@link SaasProduct} and {@link SourceConfig}, whose behaviour is small but load-bearing.
 *
 * <p>The MCP-versus-crawled split tested here decides, for every source, whether the backend fetches it, whether a
 * snapshot exists for it, whether {@code maxDepth} means anything, and whether a custom-range compare can answer
 * about it honestly. A source landing in the wrong family would not throw - it would just quietly never be fetched.
 */
class SaasProductTest {

    @ParameterizedTest
    @EnumSource(SourceType.class)
    void everyTypeIsExactlyOneOfMcpOrCrawled(SourceType type) {
        // Not a tautology of the implementation: it pins that no future type can be added as neither (silently never
        // fetched and never declared to the model) or as both.
        assertThat(type.isMcp()).isNotEqualTo(type.isCrawled());
    }

    @Test
    void theThreeMcpTypesAreMcpAndTheTwoUrlTypesAreCrawled() {
        assertThat(List.of(SourceType.DOCS_MCP, SourceType.ATLASSIAN_MCP, SourceType.GENERIC_MCP))
                .allMatch(SourceType::isMcp);
        assertThat(List.of(SourceType.WEBSITE, SourceType.SAAS_URL)).allMatch(SourceType::isCrawled);
    }

    @Test
    void sourcesArePartitionedByFamilyInConfiguredOrder() {
        SaasProduct product = productWith(
                source(SourceType.DOCS_MCP, "Docs"),
                source(SourceType.WEBSITE, "Marketing site"),
                source(SourceType.ATLASSIAN_MCP, "Jira"),
                source(SourceType.SAAS_URL, "Changelog"),
                source(SourceType.GENERIC_MCP, "Something else"));

        // Order is preserved because it is the fetch order and the display order; a partition that reordered would
        // silently rearrange the user's list every time they reloaded the page.
        assertThat(product.crawledSources()).extracting(SourceConfig::getName)
                .containsExactly("Marketing site", "Changelog");
        assertThat(product.mcpSources()).extracting(SourceConfig::getName)
                .containsExactly("Docs", "Jira", "Something else");
    }

    @Test
    void multipleSourcesOfTheSameTypeAreSupported() {
        // Three Docs MCP servers for three doc sets is a documented configuration, not an edge case.
        SaasProduct product = productWith(
                source(SourceType.DOCS_MCP, "API docs"),
                source(SourceType.DOCS_MCP, "SDK docs"),
                source(SourceType.DOCS_MCP, "Admin guide"));

        assertThat(product.mcpSources()).hasSize(3);
        assertThat(product.findSource("SDK docs")).isPresent();
    }

    @Test
    void aSourceIsFoundByNameAndAnUnknownNameIsEmptyRatherThanNull() {
        SaasProduct product = productWith(source(SourceType.WEBSITE, "Changelog"));

        assertThat(product.findSource("Changelog")).isPresent()
                .get().extracting(SourceConfig::getType).isEqualTo(SourceType.WEBSITE);
        assertThat(product.findSource("Nope")).isEmpty();
        assertThat(product.findSource(null)).isEmpty();
    }

    @Test
    void theSourceListIsCopiedOnConstructionSoTheCallersListCannotMutateIt() {
        List<SourceConfig> mutable = new ArrayList<>(List.of(source(SourceType.WEBSITE, "One")));
        SaasProduct product = new SaasProduct("Thing", "desc", mutable, "admin");

        mutable.add(source(SourceType.DOCS_MCP, "Two"));

        assertThat(product.getSources()).hasSize(1);
    }

    @Test
    void nullSourcesBecomeAnEmptyListNotANullField() {
        // Every consumer streams over this list; one null makes every one of them a potential NPE.
        assertThat(new SaasProduct("Thing", null, null, "admin").getSources()).isEmpty();

        SaasProduct product = productWith();
        product.setSources(null);
        assertThat(product.getSources()).isEmpty();
    }

    @Test
    void createdAtIsStampedOnConstruction() {
        assertThat(new SaasProduct("Thing", null, List.of(), "admin").getCreatedAt()).isNotNull();
    }

    @Test
    void aSourceConfigWithNoTypeIsNeitherFamilyRatherThanThrowing() {
        // Reachable from a partially-populated document or a half-built request object. Returning false from both is
        // preferable to an NPE inside a stream over a product's sources.
        SourceConfig untyped = new SourceConfig();

        assertThat(untyped.isCrawled()).isFalse();
        assertThat(untyped.isMcp()).isFalse();
    }

    @Test
    void hasAuthTokenIsFalseForNullAndBlank() {
        SourceConfig source = source(SourceType.DOCS_MCP, "Docs");
        assertThat(source.hasAuthToken()).isFalse();

        source.setAuthTokenEncrypted("   ");
        assertThat(source.hasAuthToken()).isFalse();

        source.setAuthTokenEncrypted("ciphertext");
        assertThat(source.hasAuthToken()).isTrue();
    }

    @Test
    void crawlLimitsDefaultToNullMeaningUseTheSystemDefault() {
        // Null rather than a value copied at creation time, so raising the Admin Console default takes effect for
        // existing sources that never overrode it.
        SourceConfig source = source(SourceType.WEBSITE, "Site");

        assertThat(source.getMaxDepth()).isNull();
        assertThat(source.getMaxPages()).isNull();
    }

    private static SaasProduct productWith(SourceConfig... sources) {
        return new SaasProduct("Example", "An example product", List.of(sources), "admin");
    }

    private static SourceConfig source(SourceType type, String name) {
        return new SourceConfig(type, name, "https://example.com/" + name.hashCode(), null);
    }
}
