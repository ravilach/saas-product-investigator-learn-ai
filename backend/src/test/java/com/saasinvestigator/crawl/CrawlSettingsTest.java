package com.saasinvestigator.crawl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saasinvestigator.error.BadRequestException;
import com.saasinvestigator.systemconfig.SystemConfigService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests the three-layer resolution of crawl limits: the source's own value, then the Admin Console default, then
 * the shipped default - clamped in all cases to a ceiling nothing can raise.
 *
 * <p>The clamp is the part worth a test rather than a code read. It is what makes {@code maxPages} a limit
 * instead of a suggestion, and it has to hold for values arriving from three different directions, one of which
 * (an existing {@code SourceConfig}) may have been written before the ceiling existed.
 *
 * <p>The asymmetry between clamping and rejecting is also asserted here, because it looks inconsistent until you
 * ask who is being protected: an admin typing 500 into a form gets an error, because silently storing 200 while
 * showing them 500 is worse than a rejection. A value already in the database gets clamped, because there is
 * nobody there to tell.
 */
class CrawlSettingsTest {

    private final CrawlerProperties properties = CrawlerProperties.defaults();
    private SystemConfigService systemConfig;
    private CrawlSettings settings;

    @BeforeEach
    void setUp() {
        systemConfig = mock(SystemConfigService.class);
        when(systemConfig.getValue(CrawlSettings.CONFIG_KEY)).thenReturn(Optional.empty());
        settings = new CrawlSettings(systemConfig, properties);
    }

    private void storedDefaults(Object maxDepth, Object maxPages) {
        Map<String, Object> value = new LinkedHashMap<>();
        if (maxDepth != null) {
            value.put("defaultMaxDepth", maxDepth);
        }
        if (maxPages != null) {
            value.put("defaultMaxPages", maxPages);
        }
        when(systemConfig.getValue(CrawlSettings.CONFIG_KEY)).thenReturn(Optional.of(value));
    }

    @Test
    void aSourceWithNoOverridesGetsTheShippedDefaults() {
        assertThat(settings.resolveMaxDepth(null)).isEqualTo(2);
        assertThat(settings.resolveMaxPages(null)).isEqualTo(20);
    }

    @Test
    void aSourcesOwnValuesWinOverTheDefaults() {
        storedDefaults(3, 50);

        assertThat(settings.resolveMaxDepth(1)).isEqualTo(1);
        assertThat(settings.resolveMaxPages(5)).isEqualTo(5);
    }

    @Test
    void theAdminConsoleDefaultWinsOverTheShippedOne() {
        storedDefaults(4, 100);

        assertThat(settings.resolveMaxDepth(null)).isEqualTo(4);
        assertThat(settings.resolveMaxPages(null)).isEqualTo(100);
    }

    @Test
    void raisingTheAdminConsoleDefaultTakesEffectForExistingSourcesImmediately() {
        // The reason the stored defaults are read per crawl rather than cached at startup. A setting whose
        // effect only appears after an unrelated restart is a setting that gets changed twice.
        assertThat(settings.resolveMaxPages(null)).isEqualTo(20);

        storedDefaults(2, 40);

        assertThat(settings.resolveMaxPages(null)).isEqualTo(40);
    }

    @Test
    void aDepthOfZeroIsRespectedRatherThanTreatedAsUnset() {
        // maxDepth 0 means "just this one page", which is a perfectly reasonable thing to configure for a
        // changelog. A falsy-means-absent check here would silently crawl the whole site instead.
        assertThat(settings.resolveMaxDepth(0)).isZero();
    }

    @Test
    void aSourceCannotExceedTheHardCeilings() {
        // Including - especially - a source that was configured before the ceiling existed.
        assertThat(settings.resolveMaxDepth(99)).isEqualTo(properties.maxAllowedDepth());
        assertThat(settings.resolveMaxPages(10_000)).isEqualTo(properties.maxAllowedPages());
    }

    @Test
    void anAdminConsoleDefaultCannotExceedTheHardCeilingsEither() {
        // Belt and braces: updateDefaults rejects out-of-range values, but a document written directly into
        // Mongo - which the Data Explorer makes entirely possible - has not been through that check.
        storedDefaults(99, 10_000);

        assertThat(settings.resolveMaxDepth(null)).isEqualTo(properties.maxAllowedDepth());
        assertThat(settings.resolveMaxPages(null)).isEqualTo(properties.maxAllowedPages());
    }

    @Test
    void negativeLimitsAreClampedToSomethingTheCrawlerCanActuallyRun() {
        assertThat(settings.resolveMaxDepth(-1)).isZero();
        // Not 0: a page budget of zero would crawl nothing and store an empty snapshot, which the next run
        // would compare against and report as the whole site having been removed.
        assertThat(settings.resolveMaxPages(-1)).isEqualTo(1);
    }

    @Test
    void aPartiallyWrittenSettingsDocumentFallsBackFieldByField() {
        storedDefaults(3, null);

        assertThat(settings.resolveMaxDepth(null)).isEqualTo(3);
        assertThat(settings.resolveMaxPages(null)).isEqualTo(20);
    }

    @Test
    void aSettingsFieldOfTheWrongTypeFallsBackInsteadOfFailing() {
        // The Data Explorer can write a string into a numeric field. That should degrade to the default, not
        // throw a ClassCastException from inside a crawl three layers away.
        storedDefaults("lots", 30);

        assertThat(settings.resolveMaxDepth(null)).isEqualTo(2);
        assertThat(settings.resolveMaxPages(null)).isEqualTo(30);
    }

    @Test
    void currentDefaultsReportsWhatIsActuallyInForce() {
        assertThat(settings.currentDefaults()).isEqualTo(new CrawlSettings.CrawlDefaults(2, 20));

        storedDefaults(1, 5);

        assertThat(settings.currentDefaults()).isEqualTo(new CrawlSettings.CrawlDefaults(1, 5));
    }

    @Test
    void updatingDefaultsStoresThemAsPlainNonSecretConfig() {
        CrawlSettings.CrawlDefaults updated = settings.updateDefaults(3, 40);

        assertThat(updated).isEqualTo(new CrawlSettings.CrawlDefaults(3, 40));
        verify(systemConfig).putValue(eq(CrawlSettings.CONFIG_KEY),
                eq(Map.of("defaultMaxDepth", 3, "defaultMaxPages", 40)));
    }

    @Test
    void anOutOfRangeUpdateIsRejectedRatherThanQuietlyClamped() {
        assertThatThrownBy(() -> settings.updateDefaults(2, 10_000))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("defaultMaxPages")
                .hasMessageContaining("200");

        assertThatThrownBy(() -> settings.updateDefaults(99, 20))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("defaultMaxDepth")
                .hasMessageContaining("5");
    }

    @Test
    void theCeilingsAreReadableSoTheUiCanStateTheAllowedRange() {
        // Better than letting an admin discover the range by being rejected by it.
        assertThat(settings.ceilings()).isEqualTo(new CrawlSettings.CrawlDefaults(5, 200));
    }
}
