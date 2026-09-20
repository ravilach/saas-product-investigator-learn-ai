package com.saasinvestigator.crawl;

import com.saasinvestigator.error.BadRequestException;
import com.saasinvestigator.systemconfig.SystemConfigService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Decides the {@code maxDepth} and {@code maxPages} a given crawl actually runs with.
 *
 * <p>Three layers, in order: the source's own override, then the Admin Console default stored in
 * {@code system_config}, then the value shipped in {@code application.properties}. This mirrors the resolution
 * model used for secrets - most specific wins, with a working fallback at the bottom - so there is one shape to
 * learn rather than one per subsystem.
 *
 * <p>Whatever that resolves to is then <b>clamped to the hard ceilings</b> in {@link CrawlerProperties}. That
 * clamp is the difference between a limit and a suggestion: crawling is a safety boundary, and a boundary an
 * admin can type past in a text field protects nobody. See {@code docs/ARCHITECTURE.md}.
 *
 * <p>The stored defaults are read on every crawl rather than cached at startup, which is one small indexed
 * Mongo read per source. That is the price of the build prompt's requirement that raising the default in the
 * Admin Console take effect for existing sources - a cached value would mean "effective after a restart", and a
 * setting whose effect is invisible until an unrelated event is a setting that gets changed twice.
 */
@Service
public class CrawlSettings {

    private static final Logger log = LoggerFactory.getLogger(CrawlSettings.class);

    /** The {@code system_config} key holding the Admin Console crawl defaults. */
    public static final String CONFIG_KEY = "CRAWL_DEFAULTS";

    private static final String MAX_DEPTH_FIELD = "defaultMaxDepth";
    private static final String MAX_PAGES_FIELD = "defaultMaxPages";

    private final SystemConfigService systemConfig;
    private final CrawlerProperties properties;

    /**
     * @param systemConfig the keyed config store holding the Admin Console overrides
     * @param properties the shipped defaults and the hard ceilings
     */
    public CrawlSettings(SystemConfigService systemConfig, CrawlerProperties properties) {
        this.systemConfig = systemConfig;
        this.properties = properties;
    }

    /**
     * The effective link-hop limit for one source.
     *
     * <p>"Depth" counts hops from the starting URL: {@code 0} fetches only {@code endpointUrl}, {@code 1} adds
     * the pages it links to, {@code 2} adds theirs. The build prompt's default of {@code 2} therefore reaches
     * three levels of pages, which is the reading that makes {@code maxDepth: 0} mean something useful
     * ("this one page") rather than nothing at all.
     *
     * @param sourceOverride the source's {@code maxDepth}, or {@code null} to use the current default
     * @return a depth between 0 and {@code app.crawler.max-allowed-depth}
     */
    public int resolveMaxDepth(Integer sourceOverride) {
        int requested = sourceOverride != null ? sourceOverride : currentDefaults().defaultMaxDepth();
        return clamp(requested, 0, properties.maxAllowedDepth(), "maxDepth");
    }

    /**
     * The effective page budget for one source.
     *
     * <p>Counted in pages <em>requested</em>, not pages successfully read. A site answering half its URLs with
     * {@code 500}s should not thereby earn twice as many requests from us.
     *
     * @param sourceOverride the source's {@code maxPages}, or {@code null} to use the current default
     * @return a budget between 1 and {@code app.crawler.max-allowed-pages}
     */
    public int resolveMaxPages(Integer sourceOverride) {
        int requested = sourceOverride != null ? sourceOverride : currentDefaults().defaultMaxPages();
        return clamp(requested, 1, properties.maxAllowedPages(), "maxPages");
    }

    /**
     * The defaults currently in force, for {@code GET /api/admin/settings}.
     *
     * <p>A malformed or partial stored document falls back field by field rather than wholesale, so a bad write
     * to one field cannot silently reset the other.
     *
     * @return the Admin Console defaults if set, otherwise the shipped ones
     */
    public CrawlDefaults currentDefaults() {
        Optional<Map<String, Object>> stored = systemConfig.getValue(CONFIG_KEY);
        if (stored.isEmpty()) {
            return new CrawlDefaults(properties.defaultMaxDepth(), properties.defaultMaxPages());
        }
        Map<String, Object> value = stored.get();
        return new CrawlDefaults(
                readInt(value, MAX_DEPTH_FIELD, properties.defaultMaxDepth()),
                readInt(value, MAX_PAGES_FIELD, properties.defaultMaxPages()));
    }

    /**
     * Stores new Admin Console crawl defaults, for {@code PUT /api/admin/settings}.
     *
     * <p>Rejected rather than clamped, unlike everywhere else in this class. The difference is who is being
     * protected from what: a stored value silently clamped from 500 to 200 would show 500 back in the form the
     * admin typed it into, and they would reasonably believe it took effect. An interactive edit gets an error;
     * only values arriving from config or from existing documents get clamped, because there is nobody there to
     * tell.
     *
     * <p>Audit logging is the caller's job - the controller knows who is acting, and this service does not.
     *
     * @param defaultMaxDepth the new default link-hop limit
     * @param defaultMaxPages the new default page budget
     * @return the defaults now in force
     * @throws BadRequestException if either value is outside the hard ceilings, naming the limit it exceeded
     */
    public CrawlDefaults updateDefaults(int defaultMaxDepth, int defaultMaxPages) {
        require(defaultMaxDepth, 0, properties.maxAllowedDepth(), MAX_DEPTH_FIELD);
        require(defaultMaxPages, 1, properties.maxAllowedPages(), MAX_PAGES_FIELD);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put(MAX_DEPTH_FIELD, defaultMaxDepth);
        value.put(MAX_PAGES_FIELD, defaultMaxPages);
        systemConfig.putValue(CONFIG_KEY, value);
        log.info("Crawl defaults updated to maxDepth={} maxPages={}", defaultMaxDepth, defaultMaxPages);
        return new CrawlDefaults(defaultMaxDepth, defaultMaxPages);
    }

    /**
     * @return the hard ceilings, so the Admin Console can state the allowed range instead of letting an admin
     *     discover it by being rejected
     */
    public CrawlDefaults ceilings() {
        return new CrawlDefaults(properties.maxAllowedDepth(), properties.maxAllowedPages());
    }

    private int clamp(int requested, int min, int max, String what) {
        int clamped = Math.clamp(requested, min, max);
        if (clamped != requested) {
            log.warn("Configured {} of {} is outside the allowed range {}-{}; using {}.",
                    what, requested, min, max, clamped);
        }
        return clamped;
    }

    private void require(int value, int min, int max, String field) {
        if (value < min || value > max) {
            throw new BadRequestException(
                    field + " must be between " + min + " and " + max + ", but was " + value);
        }
    }

    private static int readInt(Map<String, Object> value, String field, int fallback) {
        Object raw = value.get(field);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw != null) {
            log.warn("system_config {}.{} is {} rather than a number; falling back to {}.",
                    CONFIG_KEY, field, raw.getClass().getSimpleName(), fallback);
        }
        return fallback;
    }

    /**
     * The pair of crawl limits, used both as the current defaults and as the ceilings on them.
     *
     * @param defaultMaxDepth link-hops from the starting URL
     * @param defaultMaxPages pages that may be requested per source
     */
    public record CrawlDefaults(int defaultMaxDepth, int defaultMaxPages) {
    }
}
