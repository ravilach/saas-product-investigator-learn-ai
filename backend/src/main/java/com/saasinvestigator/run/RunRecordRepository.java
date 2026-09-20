package com.saasinvestigator.run;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Spring Data repository over the {@code run_records} collection. */
public interface RunRecordRepository extends MongoRepository<RunRecord, String> {

    /**
     * Counts runs that began within a window, for the Stats tab's 24-hour and 7-day figures.
     *
     * @param since inclusive lower bound
     * @return how many runs began at or after {@code since}
     */
    long countByStartedAtGreaterThanEqual(Instant since);

    /**
     * The Stats tab's recent-activity list.
     *
     * <p>Includes failures, which is the point: a list of only successful runs would make a repeatedly failing
     * product look idle rather than broken.
     *
     * @return the ten most recent runs, newest first
     */
    List<RunRecord> findTop10ByOrderByStartedAtDesc();

    /**
     * The newest run that actually produced something, for the Admin Console's health page.
     *
     * <p>{@code PARTIAL} counts as successful here. A run that read three of four sources did reach the model and
     * did store a report, so treating it as a failure would report an instance as unhealthy for having one
     * unreachable website.
     *
     * @param outcomes the outcomes that count as having worked
     * @return the newest matching run, or empty if there has never been one
     */
    Optional<RunRecord> findFirstByOutcomeInOrderByStartedAtDesc(List<RunOutcome> outcomes);

    /**
     * Removes every run record for a product, as part of deleting it.
     *
     * @param saasProductId the product being deleted
     * @return how many records were removed
     */
    long deleteBySaasProductId(String saasProductId);
}
