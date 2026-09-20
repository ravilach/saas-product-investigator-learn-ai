package com.saasinvestigator.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.saasinvestigator.crawl.CrawlSettings;
import com.saasinvestigator.crawl.CrawlerProperties;
import com.saasinvestigator.crypto.CryptoService;
import com.saasinvestigator.error.BadRequestException;
import com.saasinvestigator.systemconfig.SystemConfigService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests the source-token encryption boundary and the source-shape validation that sits with it.
 *
 * <p>A real {@link CryptoService} is used deliberately. The property under test - that what reaches the database
 * is not what the user typed - cannot be asserted against a stubbed {@code encrypt}, which would only prove the
 * mapper calls a method.
 *
 * <p>The three-way meaning of {@code authToken} on update gets a test each. It is the kind of logic that looks
 * obviously right in code and is wrong in exactly one of the three cases, and the case that breaks is the common
 * one: a user edits a source's URL, the form resubmits an {@code authToken} it never had the plaintext of, and
 * the working credential is silently destroyed.
 */
class SourceConfigMapperTest {

    private static final String TOKEN = "mcp-token-0123456789abcd";

    private final CryptoService crypto = new CryptoService("test-encryption-key-please-do-not-reuse");
    private SourceConfigMapper mapper;

    @BeforeEach
    void setUp() {
        SystemConfigService systemConfig = mock(SystemConfigService.class);
        when(systemConfig.getValue(CrawlSettings.CONFIG_KEY)).thenReturn(Optional.empty());
        mapper = new SourceConfigMapper(crypto, new CrawlSettings(systemConfig, CrawlerProperties.defaults()));
    }

    private static SourceConfigRequest website(String name, String url, String authToken,
            Integer maxDepth, Integer maxPages) {
        return new SourceConfigRequest(SourceType.WEBSITE, name, url, authToken, maxDepth, maxPages);
    }

    private static SourceConfigRequest mcp(String name, String url, String authToken) {
        return new SourceConfigRequest(SourceType.DOCS_MCP, name, url, authToken, null, null);
    }

    private SourceConfig storedMcp(String name, String plaintextToken) {
        SourceConfig source = new SourceConfig();
        source.setType(SourceType.DOCS_MCP);
        source.setName(name);
        source.setEndpointUrl("https://mcp.example.com/sse");
        source.setAuthTokenEncrypted(plaintextToken == null ? null : crypto.encrypt(plaintextToken));
        return source;
    }

    @Test
    void encryptsASubmittedTokenSoTheStoredValueIsNotTheTypedOne() {
        List<SourceConfig> stored = mapper.toStored(List.of(mcp("Docs", "https://mcp.example.com/sse", TOKEN)),
                null);

        assertThat(stored).singleElement().satisfies(source -> {
            assertThat(source.getAuthTokenEncrypted()).isNotEqualTo(TOKEN).doesNotContain(TOKEN);
            assertThat(crypto.decrypt(source.getAuthTokenEncrypted())).isEqualTo(TOKEN);
        });
    }

    @Test
    void anAbsentTokenLeavesAnExistingOneUntouched() {
        // The case that matters most. A user editing the source's URL resubmits the form without a token,
        // because they were never shown one - and their working credential must survive that.
        List<SourceConfig> existing = List.of(storedMcp("Docs", TOKEN));

        List<SourceConfig> stored = mapper.toStored(
                List.of(mcp("Docs", "https://mcp.example.com/new-path", null)), existing);

        assertThat(stored).singleElement().satisfies(source -> {
            assertThat(crypto.decrypt(source.getAuthTokenEncrypted())).isEqualTo(TOKEN);
            assertThat(source.getEndpointUrl()).isEqualTo("https://mcp.example.com/new-path");
        });
    }

    @Test
    void anEmptyTokenClearsTheStoredOne() {
        List<SourceConfig> existing = List.of(storedMcp("Docs", TOKEN));

        List<SourceConfig> stored = mapper.toStored(
                List.of(mcp("Docs", "https://mcp.example.com/sse", "")), existing);

        assertThat(stored).singleElement()
                .satisfies(source -> assertThat(source.hasAuthToken()).isFalse());
    }

    @Test
    void aSubmittedTokenReplacesTheStoredOne() {
        List<SourceConfig> existing = List.of(storedMcp("Docs", TOKEN));

        List<SourceConfig> stored = mapper.toStored(
                List.of(mcp("Docs", "https://mcp.example.com/sse", "mcp-token-rotated-9999")), existing);

        assertThat(crypto.decrypt(stored.get(0).getAuthTokenEncrypted()))
                .isEqualTo("mcp-token-rotated-9999");
    }

    @Test
    void aTokenIsTrimmedBecausePastingOnePicksUpWhitespace() {
        List<SourceConfig> stored = mapper.toStored(
                List.of(mcp("Docs", "https://mcp.example.com/sse", "  " + TOKEN + "\n")), null);

        assertThat(crypto.decrypt(stored.get(0).getAuthTokenEncrypted())).isEqualTo(TOKEN);
    }

    @Test
    void anAbsentTokenOnCreateSimplyMeansNoToken() {
        List<SourceConfig> stored = mapper.toStored(
                List.of(website("Marketing site", "https://example.com", null, null, null)), null);

        assertThat(stored.get(0).hasAuthToken()).isFalse();
    }

    @Test
    void existingSourcesAreMatchedByNameCaseInsensitively() {
        List<SourceConfig> existing = List.of(storedMcp("Docs", TOKEN));

        List<SourceConfig> stored = mapper.toStored(
                List.of(mcp("docs", "https://mcp.example.com/sse", null)), existing);

        assertThat(crypto.decrypt(stored.get(0).getAuthTokenEncrypted())).isEqualTo(TOKEN);
    }

    @Test
    void renamingASourceTreatsItAsANewOneWithNoInheritedToken() {
        // The name is the identity a source's snapshots and report history are recorded against, so a rename
        // is a replacement. Carrying the token across would be a guess about which old source was meant.
        List<SourceConfig> existing = List.of(storedMcp("Docs", TOKEN));

        List<SourceConfig> stored = mapper.toStored(
                List.of(mcp("Product docs", "https://mcp.example.com/sse", null)), existing);

        assertThat(stored.get(0).hasAuthToken()).isFalse();
    }

    @Test
    void theResponseMasksTheTokenToItsTail() {
        SourceConfig source = storedMcp("Docs", TOKEN);

        SourceConfigResponse response = mapper.toResponse(source);

        assertThat(response.authTokenConfigured()).isTrue();
        assertThat(response.authTokenLast4()).isEqualTo("abcd");
        // A record with no field that could hold a token is what makes this structural rather than habitual,
        // but the ciphertext must not leak through either.
        assertThat(response.toString()).doesNotContain(source.getAuthTokenEncrypted());
    }

    @Test
    void theResponseReportsNoTokenAsAbsentRatherThanMasked() {
        SourceConfigResponse response = mapper.toResponse(storedMcp("Docs", null));

        assertThat(response.authTokenConfigured()).isFalse();
        assertThat(response.authTokenLast4()).isNull();
    }

    @Test
    void anUndecryptableTokenMasksRatherThanFailingTheWholeProductPage() {
        SourceConfig source = storedMcp("Docs", null);
        source.setAuthTokenEncrypted("not-ciphertext");

        // A rotated CREDENTIAL_ENCRYPTION_KEY must not make a product unviewable - the page is where the user
        // goes to re-enter the token.
        assertThat(mapper.toResponse(source).authTokenLast4()).isEqualTo("****");
    }

    @Test
    void theResponseReportsTheCrawlLimitsThatWillActuallyBeUsed() {
        SourceConfig source = new SourceConfig();
        source.setType(SourceType.WEBSITE);
        source.setName("Marketing site");
        source.setEndpointUrl("https://example.com");
        source.setMaxPages(5);

        SourceConfigResponse response = mapper.toResponse(source);

        assertThat(response.maxDepth()).isNull();
        assertThat(response.effectiveMaxDepth()).isEqualTo(2);
        assertThat(response.maxPages()).isEqualTo(5);
        assertThat(response.effectiveMaxPages()).isEqualTo(5);
    }

    @Test
    void anMcpSourceReportsNoEffectiveCrawlLimitsBecauseNothingCrawlsIt() {
        SourceConfigResponse response = mapper.toResponse(storedMcp("Docs", TOKEN));

        assertThat(response.effectiveMaxDepth()).isNull();
        assertThat(response.effectiveMaxPages()).isNull();
    }

    @Test
    void aStoredLimitAboveTheCeilingIsClampedWhenRead() {
        // Written before the ceiling existed, or straight into Mongo by the Data Explorer. Nobody is present
        // to be told, so it is clamped - the opposite of how a submitted value is treated.
        SourceConfig source = new SourceConfig();
        source.setType(SourceType.WEBSITE);
        source.setName("Marketing site");
        source.setEndpointUrl("https://example.com");
        source.setMaxPages(10_000);

        assertThat(mapper.toResponse(source).effectiveMaxPages()).isEqualTo(200);
    }

    @Test
    void aSubmittedLimitAboveTheCeilingIsRejectedRatherThanClamped() {
        // Storing 200 while the form still shows the 10,000 they typed makes the form lie about what will
        // happen on the next run.
        assertThatThrownBy(() -> mapper.toStored(
                List.of(website("Marketing site", "https://example.com", null, null, 10_000)), null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("200");

        assertThatThrownBy(() -> mapper.toStored(
                List.of(website("Marketing site", "https://example.com", null, 9, null)), null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("5");
    }

    @Test
    void aZeroDepthIsAllowedBecauseItMeansTheStartPageAlone() {
        List<SourceConfig> stored = mapper.toStored(
                List.of(website("Pricing", "https://example.com/pricing", null, 0, 1)), null);

        assertThat(stored.get(0).getMaxDepth()).isZero();
    }

    @Test
    void negativeAndZeroLimitsAreRejected() {
        assertThatThrownBy(() -> mapper.toStored(
                List.of(website("Site", "https://example.com", null, -1, null)), null))
                .isInstanceOf(BadRequestException.class);

        // Zero pages would crawl nothing, produce an empty snapshot, and make the next run report the whole
        // site as deleted.
        assertThatThrownBy(() -> mapper.toStored(
                List.of(website("Site", "https://example.com", null, null, 0)), null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void anMcpSourceCarryingCrawlLimitsIsRejectedRatherThanQuietlyIgnored() {
        SourceConfigRequest request =
                new SourceConfigRequest(SourceType.GENERIC_MCP, "Tools", "https://mcp.example.com", null, 3, 30);

        assertThatThrownBy(() -> mapper.toStored(List.of(request), null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("MCP");
    }

    @Test
    void duplicateNamesAreRejected() {
        // Two sources called "Docs" would make a report's changes ambiguous about which one changed, and
        // would make this class's own token-preservation matching ambiguous too.
        assertThatThrownBy(() -> mapper.toStored(List.of(
                mcp("Docs", "https://mcp.example.com/a", null),
                mcp("docs", "https://mcp.example.com/b", null)), null))
                .isInstanceOf(BadRequestException.class)
                // The message has to explain the case-insensitivity, or it reads as a complaint about a name
                // that appears only once in the form.
                .hasMessageContaining("ignoring case");
    }

    @Test
    void aNonHttpUrlIsRejected() {
        assertThatThrownBy(() -> mapper.toStored(
                List.of(website("Site", "ftp://example.com", null, null, null)), null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("https");

        assertThatThrownBy(() -> mapper.toStored(
                List.of(website("Site", "example.com", null, null, null)), null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void aUrlWithNoHostIsRejected() {
        assertThatThrownBy(() -> mapper.toStored(
                List.of(website("Site", "https:///docs", null, null, null)), null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("host");
    }

    @Test
    void aMalformedUrlIsRejectedWithAMessageAUserCanActuallyUse() {
        assertThatThrownBy(() -> mapper.toStored(
                List.of(website("Site", "https://exa mple.com", null, null, null)), null))
                .isInstanceOf(BadRequestException.class)
                // Not "Illegal character in authority at index 8" - accurate and useless to someone who
                // mistyped a URL in a form.
                .hasMessageContaining("not a valid URL");
    }

    @Test
    void namesAndUrlsAreTrimmed() {
        List<SourceConfig> stored = mapper.toStored(
                List.of(website("  Marketing site  ", "  https://example.com  ", null, null, null)), null);

        assertThat(stored.get(0).getName()).isEqualTo("Marketing site");
        assertThat(stored.get(0).getEndpointUrl()).isEqualTo("https://example.com");
    }

    @Test
    void noSourcesIsAnEmptyListRatherThanAnError() {
        // A product with no sources cannot produce a report, but it is a legitimate half-finished state - the
        // run endpoint is where that gets refused, not here.
        assertThat(mapper.toStored(null, null)).isEmpty();
        assertThat(mapper.toStored(List.of(), null)).isEmpty();
        assertThat(mapper.toResponses(null)).isEmpty();
    }

    @Test
    void decryptsATokenForTheLlmLayerAndNothingElse() {
        SourceConfig source = storedMcp("Docs", TOKEN);

        assertThat(mapper.decryptAuthToken(source)).contains(TOKEN);
        assertThat(mapper.decryptAuthToken(storedMcp("Docs", null))).isEmpty();
        assertThat(mapper.decryptAuthToken(null)).isEmpty();
    }

    @Test
    void decryptionFailureIsEmptyRatherThanAnExceptionThatWouldEndTheRun() {
        SourceConfig source = storedMcp("Docs", null);
        source.setAuthTokenEncrypted("not-ciphertext");

        // The source then gets declared without credentials and fails at the MCP server with a clear
        // authentication error, while the product's other sources still run.
        assertThat(mapper.decryptAuthToken(source)).isEmpty();
    }
}
