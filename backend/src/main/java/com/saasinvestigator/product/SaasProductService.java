package com.saasinvestigator.product;

import com.saasinvestigator.audit.AuditAction;
import com.saasinvestigator.audit.AuditService;
import com.saasinvestigator.error.ConflictException;
import com.saasinvestigator.error.NotFoundException;
import com.saasinvestigator.report.ChangeReport;
import com.saasinvestigator.report.ChangeReportRepository;
import com.saasinvestigator.run.RunRecordRepository;
import com.saasinvestigator.security.AuthenticatedUser;
import com.saasinvestigator.security.CurrentUser;
import com.saasinvestigator.snapshot.SnapshotRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Create, read, update and delete for SaaS products, plus the cascade that keeps their data from outliving them.
 *
 * <h2>Why delete cascades here</h2>
 *
 * <p>A product owns its snapshots, its change reports, and its run records through nothing but a
 * {@code saasProductId} field - MongoDB has no foreign keys and will happily keep all three after the product is
 * gone. Orphaned snapshots are not merely untidy: they are crawled page content, kept indefinitely, belonging to
 * something nobody can see any more, and a later product reusing the same name would be compared against them.
 *
 * <p>The cascade is therefore part of the delete rather than a cleanup job, and it runs <b>before</b> the product
 * document is removed. In the other order, a failure halfway through would leave the dependents with no product to
 * find them from - the exact orphan state the cascade exists to prevent - whereas failing in this order leaves the
 * product visible and retryable. There is no transaction: a standalone MongoDB has none, and requiring a replica set
 * to delete a product would contradict the single-container quick start.
 *
 * <p>This is also why the Data Explorer deliberately cannot delete documents: doing it through a generic editor
 * would bypass everything above. See {@code docs/ARCHITECTURE.md}.
 */
@Service
public class SaasProductService {

    private static final Logger log = LoggerFactory.getLogger(SaasProductService.class);

    /** The audit {@code targetType} for everything about a product. Matches what {@code RunOrchestrator} writes. */
    private static final String AUDIT_TARGET = "saas_product";

    private final SaasProductRepository products;
    private final ChangeReportRepository reports;
    private final SnapshotRepository snapshots;
    private final RunRecordRepository runRecords;
    private final SourceConfigMapper sourceMapper;
    private final AuditService audit;

    /**
     * @param products the product collection
     * @param reports read for the derived {@code lastRun}, and deleted on cascade
     * @param snapshots deleted on cascade
     * @param runRecords deleted on cascade
     * @param sourceMapper validates and encrypts sources on the way in, masks them on the way out
     * @param audit records create, update and delete
     */
    public SaasProductService(SaasProductRepository products,
                             ChangeReportRepository reports,
                             SnapshotRepository snapshots,
                             RunRecordRepository runRecords,
                             SourceConfigMapper sourceMapper,
                             AuditService audit) {
        this.products = products;
        this.reports = reports;
        this.snapshots = snapshots;
        this.runRecords = runRecords;
        this.sourceMapper = sourceMapper;
        this.audit = audit;
    }

    /**
     * Creates a product.
     *
     * @param request the submitted product and its sources
     * @return the stored product, sources masked
     * @throws ConflictException if another product already uses this name
     * @throws com.saasinvestigator.error.BadRequestException if a source is invalid
     */
    public SaasProductResponse create(SaasProductRequest request) {
        AuthenticatedUser actor = CurrentUser.require();
        String name = request.name().trim();
        if (products.existsByName(name)) {
            throw new ConflictException("A SaaS product named '" + name + "' already exists.");
        }

        List<SourceConfig> sources = sourceMapper.toStored(request.sources(), null);
        SaasProduct product = products.save(
                new SaasProduct(name, trimToNull(request.description()), sources, actor.username()));

        audit.log(AuditAction.PRODUCT_CREATED, AUDIT_TARGET, product.getId(),
                Map.of("productName", name, "sourceCount", sources.size()));
        log.info("Product '{}' created by {} with {} source(s).", name, actor.username(), sources.size());
        return toResponse(product, latestReport(product.getId()));
    }

    /**
     * One page of products, newest first, each with its derived {@code lastRun}.
     *
     * <p>The newest report is fetched per product rather than in one aggregated query. That is a deliberate N+1: each
     * lookup is a single document read fully covered by the {@code (saasProductId, runAt desc)} index, N is bounded
     * by the page size, and the aggregation that would replace it is several lines of pipeline builder whose cost is
     * the same two index seeks per product. If the product count ever grows enough for this to matter, the fix is a
     * {@code $group} on {@code $first: $$ROOT} here, and nothing else changes.
     *
     * @param pageable page and size; sorting is fixed to newest-first
     * @return the page of products
     */
    public Page<SaasProductResponse> list(Pageable pageable) {
        return products.findAllByOrderByCreatedAtDesc(pageable)
                .map(product -> toResponse(product, latestReport(product.getId())));
    }

    /**
     * One product in full.
     *
     * @param id the product id
     * @return the product, sources masked
     * @throws NotFoundException if there is no such product
     */
    public SaasProductResponse get(String id) {
        SaasProduct product = require(id);
        return toResponse(product, latestReport(id));
    }

    /**
     * Replaces a product's name, description and sources.
     *
     * <p>The audit entry names the fields that changed rather than their values. For sources that is not only a
     * secrecy rule but a legibility one: "sources" is more useful in a trail than a JSON dump of five source
     * objects, and one of those objects holds a credential.
     *
     * @param id the product id
     * @param request the new state of the product
     * @return the updated product, sources masked
     * @throws NotFoundException if there is no such product
     * @throws ConflictException if the new name belongs to a different product
     */
    public SaasProductResponse update(String id, SaasProductRequest request) {
        SaasProduct product = require(id);
        String name = request.name().trim();
        products.findByName(name)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new ConflictException("A SaaS product named '" + name + "' already exists.");
                });

        // Validated and encrypted against the current sources, so a resubmitted source with no authToken keeps the
        // token the user was never shown. See SourceConfigRequest.
        List<SourceConfig> sources = sourceMapper.toStored(request.sources(), product.getSources());

        List<String> changedFields = new ArrayList<>();
        if (!name.equals(product.getName())) {
            changedFields.add("name");
        }
        String description = trimToNull(request.description());
        if (!java.util.Objects.equals(description, product.getDescription())) {
            changedFields.add("description");
        }
        if (!describe(sources).equals(describe(product.getSources()))) {
            changedFields.add("sources");
        }

        product.setName(name);
        product.setDescription(description);
        product.setSources(sources);
        SaasProduct saved = products.save(product);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("productName", name);
        details.put("changedFields", changedFields);
        details.put("sourceCount", sources.size());
        audit.log(AuditAction.PRODUCT_UPDATED, AUDIT_TARGET, id, details);

        return toResponse(saved, latestReport(id));
    }

    /**
     * Deletes a product and everything recorded against it.
     *
     * @param id the product id
     * @throws NotFoundException if there is no such product
     */
    public void delete(String id) {
        SaasProduct product = require(id);

        long deletedSnapshots = snapshots.deleteBySaasProductId(id);
        long deletedReports = reports.deleteBySaasProductId(id);
        long deletedRuns = runRecords.deleteBySaasProductId(id);
        products.deleteById(id);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("productName", product.getName());
        details.put("deletedSnapshots", deletedSnapshots);
        details.put("deletedReports", deletedReports);
        details.put("deletedRunRecords", deletedRuns);
        audit.log(AuditAction.PRODUCT_DELETED, AUDIT_TARGET, id, details);

        log.info("Product '{}' deleted along with {} snapshot(s), {} report(s) and {} run record(s).",
                product.getName(), deletedSnapshots, deletedReports, deletedRuns);
    }

    /**
     * Loads a product or fails with a 404.
     *
     * <p>Public because the report and export endpoints need the same "does this product exist" answer before they
     * go looking for something underneath it, and because they need the product's name for the export header.
     *
     * @param id the product id
     * @return the product
     * @throws NotFoundException if there is no such product
     */
    public SaasProduct require(String id) {
        return products.findById(id)
                .orElseThrow(() -> new NotFoundException("No SaaS product with id " + id));
    }

    private Optional<ChangeReport> latestReport(String productId) {
        return reports.findFirstBySaasProductIdOrderByRunAtDesc(productId);
    }

    private SaasProductResponse toResponse(SaasProduct product, Optional<ChangeReport> latest) {
        return new SaasProductResponse(
                product.getId(),
                product.getName(),
                product.getDescription(),
                sourceMapper.toResponses(product.getSources()),
                product.getSources().size(),
                product.getCreatedAt(),
                product.getCreatedBy(),
                latest.map(SaasProductResponse.LastRun::from).orElse(null));
    }

    /**
     * A comparable description of a source list, used only to decide whether {@code "sources"} changed.
     *
     * <p>Includes whether each source has a token but never the token itself, not even its ciphertext: two
     * encryptions of the same value differ (AES-GCM uses a fresh nonce each time), so comparing ciphertext would
     * report every resubmission as a change - and the value would then be a hair's breadth from the audit entry.
     */
    private static String describe(List<SourceConfig> sources) {
        StringBuilder text = new StringBuilder();
        for (SourceConfig source : sources) {
            text.append(source.getType()).append('|')
                    .append(source.getName() == null ? "" : source.getName().toLowerCase(Locale.ROOT)).append('|')
                    .append(source.getEndpointUrl()).append('|')
                    .append(source.hasAuthToken()).append('|')
                    .append(source.getMaxDepth()).append('|')
                    .append(source.getMaxPages()).append('\n');
        }
        return text.toString();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
