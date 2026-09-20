package com.saasinvestigator.product;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A SaaS product being tracked, stored in the {@code saas_products} collection.
 *
 * <p>This is the unit of self-containment the whole application is organised around: a product owns its own list of
 * sources and, through {@code saasProductId}, its own history of snapshots and change reports. Two products never
 * share either. That is what makes "run this product" a complete, meaningful operation and what keeps one
 * product's misconfigured source from affecting another's history.
 *
 * <p>Deliberately absent: any field describing the last run. The {@code lastRun} summary that
 * {@code GET /api/saas-products/{id}} returns is <b>computed</b> from the most recent change report on every read,
 * not stored here. A stored copy would be a second source of truth that goes stale the moment a run fails
 * part-way, or a report is deleted, or two runs overlap - and it would go stale silently, which is the worst
 * version of that problem. The date picker depends on this value being right, so it is derived from the thing that
 * is definitionally right.
 */
@Document(collection = "saas_products")
public class SaasProduct {

    @Id
    private String id;

    private String name;

    /** Free text, for whoever reads the dashboard in six months. Optional. */
    private String description;

    /**
     * Every source this product is watched through - unbounded, any mix of types, in the order configured.
     *
     * <p>Order is preserved and meaningful: it is the order sources are fetched in and the order they appear in
     * the UI, so a user's arrangement survives a round trip.
     */
    private List<SourceConfig> sources = new ArrayList<>();

    private Instant createdAt;

    /** Username of whoever created this product. Denormalised for the same reason as on an audit entry. */
    private String createdBy;

    /** Required by Spring Data's mapping layer. */
    public SaasProduct() {}

    /**
     * Creates a product with {@code createdAt} stamped.
     *
     * @param name the display name
     * @param description free-text description, may be {@code null}
     * @param sources the configured sources; may be empty, and copied defensively
     * @param createdBy username of the creator
     */
    public SaasProduct(String name, String description, List<SourceConfig> sources, String createdBy) {
        this.name = name;
        this.description = description;
        this.sources = sources == null ? new ArrayList<>() : new ArrayList<>(sources);
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    /**
     * Finds a source by its label.
     *
     * <p>Names are unique within a product, which is what lets a snapshot and a reported change refer to their
     * source by name rather than by index - an index would silently point at a different source the moment
     * someone reorders or deletes one, retroactively rewriting history.
     *
     * @param sourceName the source label to look for
     * @return the matching source, or empty if this product has no such source
     */
    public Optional<SourceConfig> findSource(String sourceName) {
        if (sourceName == null) {
            return Optional.empty();
        }
        return sources.stream().filter(s -> sourceName.equals(s.getName())).findFirst();
    }

    /** @return only the sources the backend fetches itself, in configured order */
    public List<SourceConfig> crawledSources() {
        return sources.stream().filter(SourceConfig::isCrawled).toList();
    }

    /** @return only the sources declared to the LLM as remote MCP tools, in configured order */
    public List<SourceConfig> mcpSources() {
        return sources.stream().filter(SourceConfig::isMcp).toList();
    }

    /** @return the Mongo document id */
    public String getId() {
        return id;
    }

    /** @param id the Mongo document id */
    public void setId(String id) {
        this.id = id;
    }

    /** @return the display name */
    public String getName() {
        return name;
    }

    /** @param name the display name */
    public void setName(String name) {
        this.name = name;
    }

    /** @return the free-text description, possibly {@code null} */
    public String getDescription() {
        return description;
    }

    /** @param description the free-text description */
    public void setDescription(String description) {
        this.description = description;
    }

    /** @return the configured sources, in order. Never {@code null}. */
    public List<SourceConfig> getSources() {
        return sources;
    }

    /** @param sources the configured sources; {@code null} is treated as empty */
    public void setSources(List<SourceConfig> sources) {
        this.sources = sources == null ? new ArrayList<>() : sources;
    }

    /** @return when this product was created */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** @param createdAt when this product was created */
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    /** @return username of the creator */
    public String getCreatedBy() {
        return createdBy;
    }

    /** @param createdBy username of the creator */
    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }
}
