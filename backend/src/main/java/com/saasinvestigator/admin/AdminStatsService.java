package com.saasinvestigator.admin;

import com.saasinvestigator.run.RunOutcome;
import com.saasinvestigator.run.RunRecord;
import com.saasinvestigator.run.RunRecordRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.aggregation.ArrayOperators;
import org.springframework.data.mongodb.core.aggregation.ConditionalOperators;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

/**
 * Computes the Admin Console's Overview numbers.
 *
 * <h2>Aggregation, not Prometheus</h2>
 *
 * <p>The same counts exist as metrics, and reading them back from {@code /actuator/prometheus} would be both a
 * roundabout way to ask this database a question it can answer directly and, worse, wrong: the meter registry lives in
 * this process's memory and resets on every restart, so "runs in the last 7 days" would silently mean "runs since the
 * last deploy". The metrics are for a time-series system to scrape and alert on; these numbers are for a person
 * looking at a page.
 *
 * <h2>Why a {@link MongoTemplate} alongside the repository</h2>
 *
 * <p>Two of these figures cannot be expressed as derived queries. The seven-day success rate and mean duration are one
 * pass over one window - a repository would need three separate counts and a full document load to average a field -
 * and {@code totalSourcesConfigured} is a sum of array lengths, which has no derived-query form at all. The plain
 * counts stay on the repositories, where they read better.
 */
@Service
public class AdminStatsService {

    /** How many rows the recent-activity list shows. Matches the repository's {@code findTop10...} method. */
    static final int RECENT_RUN_LIMIT = 10;

    private final MongoTemplate mongo;
    private final RunRecordRepository runRecords;

    /**
     * @param mongo runs the two aggregations
     * @param runRecords supplies the plain counts and the recent-activity list
     */
    AdminStatsService(MongoTemplate mongo, RunRecordRepository runRecords) {
        this.mongo = mongo;
        this.runRecords = runRecords;
    }

    /**
     * Gathers every figure on the Overview tab.
     *
     * @return the stats, with {@code null} rather than zero for the two averages when the window holds no runs
     */
    public AdminStatsResponse stats() {
        Instant now = Instant.now();
        RunWindow week = runWindow(now.minus(Duration.ofDays(7)));

        return new AdminStatsResponse(
                mongo.getCollection("users").countDocuments(),
                mongo.getCollection("saas_products").countDocuments(),
                totalSourcesConfigured(),
                runRecords.countByStartedAtGreaterThanEqual(now.minus(Duration.ofHours(24))),
                week.total(),
                week.total() == 0 ? null : (double) week.succeeded() / week.total(),
                week.total() == 0 ? null : week.averageDurationMillis() / 1000.0,
                recentRuns());
    }

    /**
     * Counts, succeeded-counts and averages the runs started on or after {@code since} in a single pass.
     *
     * <p>{@code PARTIAL} counts as succeeded. A run that read three of four sources reached the model and stored a
     * report; calling that a failure would report an instance as unhealthy for having one unreachable website, which is
     * a fact about that website.
     *
     * @param since inclusive lower bound on {@link RunRecord}'s start time
     * @return the window's totals, all zero when it is empty
     */
    private RunWindow runWindow(Instant since) {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("startedAt").gte(since)),
                Aggregation.group()
                        .count().as("total")
                        // .name() rather than the enum itself: this aggregation is untyped, so Spring Data has no
                        // entity to consult for how the field was written and would hand Mongo an object it cannot
                        // compare. Spring Data stores an enum as its constant name, and this says so out loud.
                        .sum(ConditionalOperators.when(Criteria.where("outcome").is(RunOutcome.FAILURE.name()))
                                .then(0)
                                .otherwise(1)).as("succeeded")
                        .avg("durationMillis").as("avgDurationMillis"));

        Document result = mongo.aggregate(aggregation, "run_records", Document.class).getUniqueMappedResult();
        if (result == null) {
            // An empty window produces no group at all rather than a group of zeroes - $group emits one document per
            // bucket, and there are no buckets.
            return new RunWindow(0, 0, 0);
        }
        return new RunWindow(
                number(result, "total"),
                number(result, "succeeded"),
                result.get("avgDurationMillis") instanceof Number n ? n.doubleValue() : 0);
    }

    /**
     * Sums the length of every product's {@code sources} array.
     *
     * <p>{@code $ifNull} guards a product document written before the field existed, or written by hand through the
     * Data Explorer: {@code $size} of a missing field is a runtime error that would take the whole stats page down
     * rather than reporting one product as having no sources.
     *
     * @return the total number of configured sources across all products
     */
    private long totalSourcesConfigured() {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.project().and(ArrayOperators.Size.lengthOfArray(
                        ConditionalOperators.ifNull("sources").then(List.of()))).as("count"),
                Aggregation.group().sum("count").as("total"));

        AggregationResults<Document> results =
                mongo.aggregate(aggregation, "saas_products", Document.class);
        Document result = results.getUniqueMappedResult();
        return result == null ? 0 : number(result, "total");
    }

    private List<AdminStatsResponse.RecentRun> recentRuns() {
        return runRecords.findTop10ByOrderByStartedAtDesc().stream()
                .map(record -> new AdminStatsResponse.RecentRun(
                        record.getProductName(),
                        record.getRunType(),
                        // The wire spelling, so the badge in the console and the status in an export agree.
                        record.getOutcome() == null ? null : record.getOutcome().wireName(),
                        record.getStartedAt()))
                .toList();
    }

    /**
     * Reads a numeric aggregation result field.
     *
     * <p>Typed as {@code Number} rather than cast to {@code Integer} or {@code Long} because which of the two Mongo
     * returns depends on the accumulator and on whether a sum overflowed - {@code $sum} of ints is an int until it
     * isn't, and a {@code ClassCastException} on a busy instance would be a stats page that works until it matters.
     */
    private static long number(Document document, String field) {
        return document.get(field) instanceof Number n ? n.longValue() : 0;
    }

    /**
     * One time window's run totals.
     *
     * @param total runs started in the window
     * @param succeeded how many of them produced a report
     * @param averageDurationMillis mean wall-clock duration in milliseconds
     */
    private record RunWindow(long total, long succeeded, double averageDurationMillis) {
    }
}
