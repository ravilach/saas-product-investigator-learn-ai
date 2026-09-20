package com.saasinvestigator.admin;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.saasinvestigator.audit.AuditAction;
import com.saasinvestigator.audit.AuditService;
import com.saasinvestigator.common.PageResponse;
import com.saasinvestigator.config.MongoIndexInitializer;
import com.saasinvestigator.error.BadRequestException;
import com.saasinvestigator.error.NotFoundException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * A read-mostly Mongo browser, scoped to this application's own collections.
 *
 * <h2>Why a learning app has a database browser</h2>
 *
 * <p>Because the interesting claim this application makes is about what it writes: that a password is a hash, that an
 * API key is ciphertext, that an audit entry carries field names and not values. A tour of the code asks you to take
 * that on trust. This tab lets someone check.
 *
 * <h2>Deliberately not a full Mongo client</h2>
 *
 * <p>No insert, no delete, no arbitrary query, no collection outside {@link MongoIndexInitializer#knownCollections()}.
 * Each of those is a considered subtraction rather than an unfinished feature:
 *
 * <ul>
 *   <li><b>No delete.</b> Removing a {@code saas_products} document without cascading its snapshots, reports, and run
 *       records leaves orphans that no screen in the application can reach or clean up - a much bigger feature than
 *       "see what's being written" asked for, and one that already exists, properly, as
 *       {@code DELETE /api/saas-products/{id}}.</li>
 *   <li><b>No insert.</b> A hand-written document skips every invariant the writing code enforces, starting with the
 *       unique username index's Java-side counterpart and ending with encryption.</li>
 *   <li><b>No arbitrary query.</b> A caller-supplied filter is an unindexed collection scan on request, and a
 *       caller-supplied aggregation pipeline is a much larger surface than a browser needs.</li>
 *   <li><b>A fixed collection list.</b> Without it, {@code system.*} and anything else sharing the database is
 *       browsable through an endpoint whose stated scope is "this application's data".</li>
 * </ul>
 *
 * <h2>Everything goes through {@link SecretFieldMasker}</h2>
 *
 * <p>Both read paths mask before returning and the write path rejects before writing. There is no way through this
 * service that skips it.
 */
@Service
public class DataExplorerService {

    private static final Logger log = LoggerFactory.getLogger(DataExplorerService.class);

    /** Audit target type for a document edited here. */
    private static final String AUDIT_TARGET = "mongo_document";

    private final MongoTemplate mongo;
    private final SecretFieldMasker masker;
    private final AuditService audit;

    /**
     * @param mongo the database
     * @param masker applies the secret-field guardrail
     * @param audit records edits
     */
    DataExplorerService(MongoTemplate mongo, SecretFieldMasker masker, AuditService audit) {
        this.mongo = mongo;
        this.masker = masker;
        this.audit = audit;
    }

    /**
     * Lists the browsable collections.
     *
     * <p>Every known collection appears whether or not it has documents. An empty collection is a fact worth showing -
     * "no runs have happened yet" is different from "there is no such thing as a run record" - and hiding the empty
     * ones would make the list change shape as the app is used.
     *
     * @return one entry per collection, in the order the initializer declares them
     */
    public List<CollectionSummary> collections() {
        List<CollectionSummary> summaries = new ArrayList<>();
        for (String name : MongoIndexInitializer.knownCollections()) {
            long count;
            try {
                count = mongo.getCollection(name).countDocuments();
            } catch (RuntimeException e) {
                // A collection that cannot be counted should not take the whole picker down; -1 says "unknown" and
                // the log line says why.
                log.warn("Could not count documents in collection '{}'.", name, e);
                count = -1;
            }
            summaries.add(new CollectionSummary(name, count, masker.secretFieldNames(name)));
        }
        return summaries;
    }

    /**
     * Returns a page of documents from one collection, secret fields masked.
     *
     * <p>Sorted by {@code _id} descending, which for this application's generated ids is newest-first: Mongo's
     * {@code ObjectId} begins with a timestamp. A caller-supplied sort field is not offered, for the reason in
     * {@link com.saasinvestigator.common.Paging} - it would be an unindexed scan on request.
     *
     * @param collection which collection
     * @param page zero-based page number
     * @param size page size
     * @return the page, with each document's secret fields replaced
     * @throws NotFoundException if the collection is not one this application owns
     */
    public PageResponse<Map<String, Object>> documents(String collection, int page, int size) {
        requireKnown(collection);

        long total = mongo.getCollection(collection).countDocuments();
        List<Document> raw = mongo.find(
                new Query().with(org.springframework.data.domain.Sort.by(
                                org.springframework.data.domain.Sort.Direction.DESC, "_id"))
                        .skip((long) page * size)
                        .limit(size),
                Document.class, collection);

        List<Map<String, Object>> content = raw.stream()
                .map(document -> present(collection, document))
                .toList();

        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) total / size);
        return new PageResponse<>(content, page, size, total, totalPages, page == 0,
                page >= totalPages - 1);
    }

    /**
     * Returns one document, secret fields masked.
     *
     * @param collection which collection
     * @param id the document's id
     * @return the masked document
     * @throws NotFoundException if the collection is unknown or the document does not exist
     */
    public Map<String, Object> document(String collection, String id) {
        requireKnown(collection);
        Document found = mongo.getCollection(collection).find(idFilter(id)).first();
        if (found == null) {
            throw new NotFoundException("No document " + id + " in collection " + collection + ".");
        }
        return present(collection, found);
    }

    /**
     * Applies an edit to the non-secret fields of one document.
     *
     * <p>A partial update: only the fields present in {@code updates} are written, using {@code $set}. Replacing the
     * whole document instead would mean any field the UI did not render - or did not know about - was silently deleted,
     * and a browser meant for inspecting data has no business removing fields nobody looked at.
     *
     * <p>The audit entry records which field <em>names</em> changed and not their values, which is the same rule every
     * other write in this application follows. The values here are ordinary application data rather than secrets - the
     * masker already refused anything else - but an audit trail that copies document contents becomes a second,
     * unmanaged store of them.
     *
     * @param collection which collection
     * @param id the document's id
     * @param updates the fields to set
     * @return the updated document, masked
     * @throws NotFoundException if the collection is unknown or the document does not exist
     * @throws BadRequestException if the update is empty or touches a protected field
     */
    public Map<String, Object> update(String collection, String id, Document updates) {
        requireKnown(collection);
        if (updates == null || updates.isEmpty()) {
            throw new BadRequestException("The update contained no fields to change.");
        }
        masker.rejectProtectedFields(collection, updates);

        // One atomic round trip that both writes and reports whether there was anything to write. The alternative -
        // check existence, then update - is the same operation with a gap in the middle, and its "no document matched"
        // answer arrives after the write rather than instead of it.
        Document updated = mongo.getCollection(collection).findOneAndUpdate(
                idFilter(id),
                new Document("$set", toBson(updates)),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (updated == null) {
            // Nothing matched, so nothing was written: an update against a mistyped id is a 404 rather than a silent
            // no-op reported as success.
            throw new NotFoundException("No document " + id + " in collection " + collection + ".");
        }

        audit.log(AuditAction.DATA_EXPLORER_DOCUMENT_UPDATED, AUDIT_TARGET, id, Map.of(
                "collection", collection,
                "changedFields", new ArrayList<>(new TreeSet<>(updates.keySet()))));

        return present(collection, updated);
    }

    /**
     * Converts a parsed request body into types the driver's default codec registry can certainly write.
     *
     * <p>The body arrives as a {@link Document} whose nested objects are whatever the JSON mapper chose - ordinarily a
     * plain {@code LinkedHashMap}. Spring Data's converter would have handled that; the driver relies on a codec being
     * registered for the exact runtime type, so a nested map is turned into a {@code Document} here rather than being
     * left to a registry lookup that may or may not find one.
     */
    @SuppressWarnings("unchecked")
    private static Object toBson(Object value) {
        if (value instanceof Map<?, ?> map) {
            Document converted = new Document();
            ((Map<String, Object>) map).forEach((key, nested) -> converted.put(key, toBson(nested)));
            return converted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(DataExplorerService::toBson).toList();
        }
        return value;
    }

    private static void requireKnown(String collection) {
        if (!MongoIndexInitializer.knownCollections().contains(collection)) {
            // 404 rather than 403: the collection is not part of this application's data, so from this endpoint's point
            // of view it genuinely does not exist. It also avoids confirming which other collections share the database.
            throw new NotFoundException("No browsable collection named " + collection + ".");
        }
    }

    /**
     * Matches a document by id regardless of how the id was stored.
     *
     * <p>Spring Data writes a {@code String} id as an {@code ObjectId} when it looks like one, so the same 24-character
     * hex string can be either type on disk - and a document written by some other tool can be a plain string. Matching
     * both in one filter means the explorer opens rows the explorer itself listed.
     *
     * <h2>Why this is a driver filter and not a Spring Data {@code Criteria}</h2>
     *
     * <p>Because a {@code Criteria} cannot express it. Spring Data's query mapper applies the same String-to-ObjectId
     * conversion to the <em>query</em> that it applies to writes, so {@code where("_id").is("6ab0...")} is sent to Mongo
     * as an {@code ObjectId} - which collapses the two branches of this {@code $or} into one and makes a string-stored id
     * unreachable through {@code MongoTemplate} entirely. Going to the driver keeps the two types distinct, which is the
     * whole point of the filter. A test covers the string-stored case; it failed before this was a raw filter.
     */
    private static Document idFilter(String id) {
        if (ObjectId.isValid(id)) {
            return new Document("$or", List.of(
                    new Document("_id", new ObjectId(id)),
                    new Document("_id", id)));
        }
        return new Document("_id", id);
    }

    /** Masks, then converts the BSON-specific types JSON has no good representation for. */
    private Map<String, Object> present(String collection, Document document) {
        Document masked = masker.mask(collection, document);
        return asDisplayable(masked);
    }

    /**
     * Rewrites BSON types into ones that serialise as a reader would expect.
     *
     * <p>Two conversions, both about the wire form rather than about the data: an {@code ObjectId} serialised as an
     * object is four numeric fields where the UI wants the hex string it can put in a URL, and a {@code Date} becomes
     * an ISO-8601 instant so timestamps read the same here as in every other response.
     */
    private static Map<String, Object> asDisplayable(Document document) {
        Map<String, Object> out = new LinkedHashMap<>();
        document.forEach((key, value) -> out.put(key, displayValue(value)));
        return out;
    }

    private static Object displayValue(Object value) {
        if (value instanceof ObjectId objectId) {
            return objectId.toHexString();
        }
        if (value instanceof Date date) {
            return date.toInstant();
        }
        if (value instanceof Instant) {
            return value;
        }
        if (value instanceof Document nested) {
            return asDisplayable(nested);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(DataExplorerService::displayValue).toList();
        }
        return value;
    }

    /**
     * One row in the collection picker.
     *
     * @param name the collection name
     * @param documentCount how many documents it holds, or {@code -1} when the count could not be taken
     * @param secretFields which of its fields are masked and read-only, so the UI can render them as disabled chips
     *     rather than letting someone type into a field whose save will be rejected
     */
    public record CollectionSummary(String name, long documentCount, List<String> secretFields) {
    }
}
