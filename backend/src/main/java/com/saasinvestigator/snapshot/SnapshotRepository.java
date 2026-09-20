package com.saasinvestigator.snapshot;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Spring Data repository over the {@code snapshots} collection.
 *
 * <p>Every finder here leads with {@code saasProductId} and {@code sourceName} and sorts on {@code fetchedAt}
 * descending, which is precisely the shape of the compound index declared in {@code MongoIndexInitializer}. That
 * is not a coincidence to preserve by luck: a finder added here that sorts ascending, or omits
 * {@code sourceName}, is a collection scan over the largest collection in the database.
 */
public interface SnapshotRepository extends MongoRepository<Snapshot, String> {

    /**
     * The single hottest read in a run: what this source looked like last time.
     *
     * <p>Called once per crawled source, before the new snapshot for this run is saved - so "most recent" means
     * the previous run's, which is the thing being compared against.
     *
     * @param saasProductId the owning product
     * @param sourceName the source's label
     * @return the most recent snapshot for that source, or empty on a product's first-ever run
     */
    Optional<Snapshot> findFirstBySaasProductIdAndSourceNameOrderByFetchedAtDesc(
            String saasProductId, String sourceName);

    /**
     * The nearest snapshot at or before a given instant, for a custom date-range compare.
     *
     * <p>"At or before" rather than "nearest in either direction" on purpose: a compare anchored at a date must
     * describe the state as of that date, and the nearest snapshot <em>after</em> it may already contain the very
     * changes the compare is meant to find. Erring backwards means the window can only be too wide, never
     * misattributed.
     *
     * @param saasProductId the owning product
     * @param sourceName the source's label
     * @param at the anchor instant, inclusive
     * @return the latest snapshot no newer than {@code at}, or empty if the source has none that old
     */
    Optional<Snapshot> findFirstBySaasProductIdAndSourceNameAndFetchedAtLessThanEqualOrderByFetchedAtDesc(
            String saasProductId, String sourceName, Instant at);

    /**
     * Every snapshot for one source, newest first - for the report detail view's "what was actually fetched"
     * drill-down.
     *
     * @param saasProductId the owning product
     * @param sourceName the source's label
     * @param pageable page, size, and sort
     * @return one page of snapshots
     */
    Page<Snapshot> findBySaasProductIdAndSourceNameOrderByFetchedAtDesc(
            String saasProductId, String sourceName, Pageable pageable);

    /**
     * The oldest snapshot for a product - the earliest date a compare can possibly be anchored at.
     *
     * <p>Exists for one error message. A compare whose {@code fromDate} predates all stored data cannot be answered,
     * and "no data exists that far back" is a much worse response than "the earliest data for this product is 4
     * June 2026". The only way to say the second is to look it up.
     *
     * <p>Sorts ascending, which the compound index on {@code (saasProductId, sourceName, fetchedAt desc)} cannot
     * serve without a {@code sourceName} equality match - so this is a small scan within one product. Accepted
     * knowingly: it runs once, only on a request that is already being rejected, and the alternative is caching a
     * value that every run invalidates.
     *
     * @param saasProductId the owning product
     * @return the product's oldest snapshot, or empty if it has never been run
     */
    Optional<Snapshot> findFirstBySaasProductIdOrderByFetchedAtAsc(String saasProductId);

    /**
     * Every snapshot written for one product, newest first.
     *
     * @param saasProductId the owning product
     * @param pageable page, size, and sort
     * @return one page of snapshots across all of the product's sources
     */
    Page<Snapshot> findBySaasProductIdOrderByFetchedAtDesc(String saasProductId, Pageable pageable);

    /**
     * The distinct sources this product has ever produced a snapshot for.
     *
     * <p>Not the same as the product's current source list, which is the point: a source deleted last month still
     * has history worth finding, and this is how the UI can offer it.
     *
     * @param saasProductId the owning product
     * @return every snapshot's source name for that product, with duplicates - callers reduce
     */
    List<Snapshot> findBySaasProductId(String saasProductId);

    /**
     * Removes all snapshots belonging to a product.
     *
     * <p>Called when a product is deleted. Mongo has no foreign keys, so orphan cleanup is the application's
     * responsibility; skipping it leaves history that no longer belongs to anything and cannot be reached or
     * removed through the UI.
     *
     * @param saasProductId the owning product
     * @return how many documents were removed
     */
    long deleteBySaasProductId(String saasProductId);
}
