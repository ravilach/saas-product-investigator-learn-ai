package com.saasinvestigator.report;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

/**
 * Spring Data repository over the {@code change_reports} collection.
 *
 * <p>Every finder leads with {@code saasProductId} and sorts on {@code runAt} descending, matching the compound
 * index in {@code MongoIndexInitializer}. Reports are the collection most often read and the one whose reads are
 * user-facing, so an unindexed query here is one a person waits on.
 */
public interface ChangeReportRepository extends MongoRepository<ChangeReport, String> {

    /**
     * The History timeline for one product, newest first.
     *
     * @param saasProductId the owning product
     * @param pageable page, size, and sort
     * @return one page of reports
     */
    Page<ChangeReport> findBySaasProductIdOrderByRunAtDesc(String saasProductId, Pageable pageable);

    /**
     * The most recent report for one product.
     *
     * <p>Two callers, both of which matter: the {@code lastRun} summary on
     * {@code GET /api/saas-products/{id}}, which is computed from this rather than stored, and the "Since last run"
     * preset on the compare date picker.
     *
     * @param saasProductId the owning product
     * @return the newest report, or empty if the product has never been run
     */
    Optional<ChangeReport> findFirstBySaasProductIdOrderByRunAtDesc(String saasProductId);

    /**
     * Reports for one product within a window, newest first.
     *
     * <p>This is how a custom-range compare answers for MCP sources, which have no stored content of their own to
     * compare: their portion of the new report is aggregated from what earlier reports said within the window. The
     * window is <b>exclusive of {@code from} and inclusive of {@code to}</b> - a report written exactly at the
     * start of the range describes the state the range begins from, so including it would report changes that
     * happened before the window as if they happened inside it.
     *
     * <p>Written as an explicit {@code @Query} rather than derived from the method name, and not by preference:
     * Spring Data cannot derive <em>two</em> conditions on the same property. A name like
     * {@code ...AndRunAtGreaterThanAndRunAtLessThanEqual...} compiles and then throws
     * {@code InvalidMongoDbApiUsageException} at call time - "you can't add a second 'runAt' expression" - because
     * each clause becomes its own {@code Criteria} on the same key. {@code Between} would build one clause but is
     * inclusive at both ends, which is the wrong window. So: one explicit query, with the asymmetry visible.
     *
     * @param saasProductId the owning product
     * @param from start of the window, exclusive
     * @param to end of the window, inclusive
     * @return matching reports, newest first
     */
    @Query(value = "{ 'saasProductId': ?0, 'runAt': { '$gt': ?1, '$lte': ?2 } }", sort = "{ 'runAt': -1 }")
    List<ChangeReport> findWithinWindow(String saasProductId, Instant from, Instant to);

    /**
     * Removes all reports belonging to a product, for cleanup when the product is deleted.
     *
     * @param saasProductId the owning product
     * @return how many documents were removed
     */
    long deleteBySaasProductId(String saasProductId);

    /**
     * @param saasProductId the owning product
     * @return how many reports exist for that product, for the dashboard card
     */
    long countBySaasProductId(String saasProductId);
}
