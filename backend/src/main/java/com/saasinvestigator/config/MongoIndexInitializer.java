package com.saasinvestigator.config;

import com.mongodb.MongoException;
import java.util.List;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Creates every MongoDB index this application relies on, explicitly, at startup.
 *
 * <p>{@code spring.data.mongodb.auto-index-creation} is switched off and the indexes are declared here
 * instead of as scattered {@code @Indexed} annotations. Two reasons: the compound indexes below exist to
 * serve specific query shapes, and having the index and the reason for it in one readable list is what makes
 * it possible to tell later whether a query is actually covered; and annotation-driven creation happens
 * lazily on first use of each collection, so a collection nobody has touched yet silently has no indexes.
 *
 * <p>Runs before {@code AdminUserSeeder} (see the {@code @Order} values) so the unique {@code username}
 * index exists before the first insert - otherwise two replicas booting simultaneously against an empty
 * database can each seed their own admin.
 *
 * <p>{@code createIndex} is idempotent: an index that already exists with the same keys and options is a
 * no-op, so this is safe on every boot.
 */
@Component
@Order(MongoIndexInitializer.ORDER)
public class MongoIndexInitializer implements ApplicationRunner {

    /** Runs first, so unique indexes exist before anything writes. */
    public static final int ORDER = 10;

    private static final Logger log = LoggerFactory.getLogger(MongoIndexInitializer.class);

    private final MongoTemplate mongoTemplate;

    /**
     * @param mongoTemplate used for raw {@code createIndex} calls
     */
    public MongoIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Login and uniqueness checks. Unique so a race between two "create user" calls cannot produce
        // two accounts with the same login.
        unique("users", new Document("username", 1));
        unique("users", new Document("email", 1));

        // "Give me the previous snapshot for this source of this product" - the single hottest read in a
        // run, executed once per source. Descending on fetchedAt so the newest is the first document
        // scanned rather than the last.
        index("snapshots", new Document("saasProductId", 1).append("sourceName", 1).append("fetchedAt", -1));

        // The report history timeline, and the "since last run" date-picker preset, both of which want the
        // newest reports for one product.
        index("change_reports", new Document("saasProductId", 1).append("runAt", -1));

        // The Admin Console's Stats tab: "runs in the last 24 hours / 7 days", newest-first recent activity, and
        // the cascade delete when a product is removed. Ascending on saasProductId and descending on startedAt
        // covers both the per-product delete and the global time-window scans, which start from the newest end.
        index("run_records", new Document("saasProductId", 1).append("startedAt", -1));
        index("run_records", new Document("startedAt", -1));

        // The Audit Log screen's filters: by actor, by action, newest first.
        index("audit_logs", new Document("actorUsername", 1).append("action", 1).append("timestamp", -1));

        // One stored credential per user per provider, and one system override per provider. Unique here is
        // doing real work: it is what makes "store or update my key" safe to implement as find-then-save
        // without a transaction.
        unique("user_llm_credentials", new Document("userId", 1).append("provider", 1));
        unique("system_llm_credentials", new Document("provider", 1));

        // One document per config key. The JWT secret's first-boot generation depends on this: it is what
        // turns a concurrent second write into a DuplicateKeyException the resolver can recover from,
        // instead of a second secret that invalidates the first replica's tokens.
        unique("system_config", new Document("key", 1));

        log.info("MongoDB indexes verified.");
    }

    private void index(String collection, Document keys) {
        create(collection, keys, false);
    }

    private void unique(String collection, Document keys) {
        create(collection, keys, true);
    }

    private void create(String collection, Document keys, boolean isUnique) {
        try {
            com.mongodb.client.model.IndexOptions options = new com.mongodb.client.model.IndexOptions()
                    .unique(isUnique)
                    .background(true);
            mongoTemplate.getCollection(collection).createIndex(keys, options);
        } catch (MongoException e) {
            // A pre-existing index with the same name but different options makes createIndex fail. That is
            // a migration problem for a human to resolve, not a reason to refuse to start - the app works
            // without the index, just slower, and refusing to boot would turn a performance issue into an
            // outage.
            log.warn("Could not create index {} on {} ({}). The application will run without it; see "
                    + "the troubleshoot-running-instance skill.", keys.toJson(), collection, e.getMessage());
        }
    }

    /**
     * The collections this application owns, in the order the Data Explorer should list them.
     *
     * <p>Lives here because this is the one place that already has to know the full set - keeping a second
     * hardcoded list in the Data Explorer is how the two drift apart.
     *
     * @return every collection name the application creates
     */
    public static List<String> knownCollections() {
        return List.of("users", "saas_products", "snapshots", "change_reports", "run_records",
                "user_llm_credentials", "system_llm_credentials", "system_config", "audit_logs");
    }
}
