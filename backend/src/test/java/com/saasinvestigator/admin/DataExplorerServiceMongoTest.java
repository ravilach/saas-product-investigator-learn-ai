package com.saasinvestigator.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.saasinvestigator.audit.AuditAction;
import com.saasinvestigator.audit.AuditService;
import com.saasinvestigator.common.PageResponse;
import com.saasinvestigator.config.MongoIndexInitializer;
import com.saasinvestigator.error.BadRequestException;
import com.saasinvestigator.error.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Tests {@link DataExplorerService} against a real MongoDB.
 *
 * <h2>Why this one needs a real database</h2>
 *
 * <p>Because the behaviour under test is mostly about BSON, and BSON is exactly what a mock cannot reproduce. Three
 * specific things only appear against a real server: that an {@code ObjectId} id matches whether it was stored as an
 * {@code ObjectId} or as a plain string, that {@code $set} writes only the submitted fields and leaves the rest of the
 * document intact, and that a {@code Date} comes back as a type the application converts rather than as an {@code Instant}
 * it stored. A mocked {@code MongoTemplate} would let all three pass while broken.
 *
 * <p>The masking assertions are here as well as in {@link SecretFieldMaskerTest}, deliberately. That class proves the
 * masker works; this one proves nothing gets past it on the way out of the database - a service method that forgot to call
 * it would still satisfy every test in the other class.
 */
@Testcontainers
@DataMongoTest
class DataExplorerServiceMongoTest {

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");

    @Autowired
    private MongoTemplate mongo;

    @MockitoBean
    private AuditService audit;

    private DataExplorerService service;

    @BeforeEach
    void setUp() {
        service = new DataExplorerService(mongo, new SecretFieldMasker(), audit);
        mongo.getCollection("users").drop();
        mongo.getCollection("saas_products").drop();
    }

    // ----- Collections -----

    @Test
    void listsEveryKnownCollectionIncludingTheEmptyOnesWithItsSecretFieldNames() {
        mongo.getCollection("users").insertOne(user("dana"));

        List<DataExplorerService.CollectionSummary> collections = service.collections();

        // Every known collection, in declaration order: an empty collection is a fact worth showing, and hiding the
        // empty ones would make the picker change shape as the app is used.
        assertThat(collections).extracting(DataExplorerService.CollectionSummary::name)
                .containsExactlyElementsOf(MongoIndexInitializer.knownCollections());
        assertThat(collections)
                .filteredOn(summary -> summary.name().equals("users"))
                .singleElement()
                .satisfies(summary -> {
                    assertThat(summary.documentCount()).isEqualTo(1);
                    assertThat(summary.secretFields()).containsExactly("passwordHash");
                });
        assertThat(collections)
                .filteredOn(summary -> summary.name().equals("snapshots"))
                .singleElement()
                .satisfies(summary -> assertThat(summary.documentCount()).isZero());
    }

    // ----- Reading -----

    @Test
    void masksThePasswordHashOnTheWayOutOfTheDatabaseSoTheCiphertextNeverReachesTheBrowser() {
        mongo.getCollection("users").insertOne(user("dana"));

        PageResponse<Map<String, Object>> page = service.documents("users", 0, 20);

        assertThat(page.content()).singleElement().satisfies(document -> {
            assertThat(document).containsEntry("passwordHash", SecretFieldMasker.MASK);
            assertThat(document).containsEntry("username", "dana");
        });
        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.last()).isTrue();
    }

    @Test
    void masksTheMcpTokenNestedInsideAProductsSourcesArray() {
        mongo.getCollection("saas_products").insertOne(productWithMcpSource());

        Map<String, Object> document = service.documents("saas_products", 0, 20).content().get(0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sources = (List<Map<String, Object>>) document.get("sources");
        assertThat(sources.get(1)).containsEntry("authTokenEncrypted", SecretFieldMasker.MASK);
        assertThat(sources.get(1)).containsEntry("name", "Changelog MCP");
        assertThat(sources.get(0)).containsEntry("endpointUrl", "https://acme.test/docs");
    }

    @Test
    void convertsBsonTypesIntoWhatAReaderExpectsRatherThanIntoNumericObjects() {
        ObjectId id = new ObjectId();
        mongo.getCollection("users").insertOne(user("dana").append("_id", id));

        Map<String, Object> document = service.document("users", id.toHexString());

        // An ObjectId serialises as four numeric fields; the UI needs the hex string it can put back in a URL. A Date
        // becomes an Instant so timestamps read the same here as in every other response.
        assertThat(document.get("_id")).isEqualTo(id.toHexString());
        assertThat(document.get("createdAt")).isInstanceOf(Instant.class);
    }

    @Test
    void findsADocumentWhoseIdWasStoredAsAPlainStringAsWellAsOneStoredAsAnObjectId() {
        // Spring Data writes a String id as an ObjectId when it looks like one, so the same 24-character hex string can
        // be either type on disk. A single-type lookup would fail to open rows the explorer itself had just listed.
        String hex = new ObjectId().toHexString();
        mongo.getCollection("users").insertOne(user("stringid").append("_id", hex));

        assertThat(service.document("users", hex)).containsEntry("username", "stringid");
    }

    @Test
    void pagesThroughACollectionReportingWhichPageIsTheLast() {
        for (int i = 0; i < 5; i++) {
            mongo.getCollection("users").insertOne(user("user-" + i));
        }

        assertThat(service.documents("users", 0, 2)).satisfies(page -> {
            assertThat(page.content()).hasSize(2);
            assertThat(page.totalPages()).isEqualTo(3);
            assertThat(page.first()).isTrue();
            assertThat(page.last()).isFalse();
        });
        assertThat(service.documents("users", 2, 2)).satisfies(page -> {
            assertThat(page.content()).hasSize(1);
            assertThat(page.last()).isTrue();
        });
    }

    @Test
    void refusesToBrowseACollectionThisApplicationDoesNotOwn() {
        // 404 rather than 403, so the response does not confirm which other collections share the database.
        assertThatThrownBy(() -> service.documents("system.version", 0, 20))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("No browsable collection");
        assertThatThrownBy(() -> service.document("some_other_app", "any-id"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void returnsA404ForADocumentIdThatIsNotInTheCollection() {
        assertThatThrownBy(() -> service.document("users", new ObjectId().toHexString()))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("users");
    }

    // ----- Writing -----

    @Test
    void setsOnlyTheSubmittedFieldsAndLeavesEveryOtherFieldIntact() {
        ObjectId id = new ObjectId();
        mongo.getCollection("users").insertOne(user("dana").append("_id", id));

        Map<String, Object> updated = service.update("users", id.toHexString(),
                new Document().append("lastName", "Reyes-Smith"));

        // A whole-document replace would silently delete every field the UI did not render - including the password
        // hash, which the same request is forbidden from even mentioning.
        assertThat(updated).containsEntry("lastName", "Reyes-Smith");
        assertThat(updated).containsEntry("username", "dana");
        assertThat(updated).containsEntry("passwordHash", SecretFieldMasker.MASK);
        assertThat(stored("users", id).getString("passwordHash")).isEqualTo("$2a$10$realhash");
    }

    @Test
    void auditsAnEditWithTheCollectionAndTheChangedFieldNamesButNotTheirValues() {
        ObjectId id = new ObjectId();
        mongo.getCollection("users").insertOne(user("dana").append("_id", id));

        service.update("users", id.toHexString(),
                new Document().append("lastName", "Reyes-Smith").append("email", "dana@acme.test"));

        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.captor();
        verify(audit).log(eq(AuditAction.DATA_EXPLORER_DOCUMENT_UPDATED), eq("mongo_document"),
                eq(id.toHexString()), details.capture());
        assertThat(details.getValue()).containsEntry("collection", "users");
        assertThat(details.getValue().get("changedFields")).isEqualTo(List.of("email", "lastName"));
        // An audit trail that copied document contents would become a second, unmanaged store of them.
        assertThat(details.getValue().toString()).doesNotContain("dana@acme.test");
    }

    @Test
    void rejectsAnEditToAMaskedFieldWithoutWritingAnythingOrAuditingIt() {
        ObjectId id = new ObjectId();
        mongo.getCollection("users").insertOne(user("dana").append("_id", id));

        assertThatThrownBy(() -> service.update("users", id.toHexString(),
                new Document().append("lastName", "Reyes-Smith").append("passwordHash", SecretFieldMasker.MASK)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Users > Reset password");

        // The whole update is refused, not just the offending field: a partial application would report success for a
        // request the admin believes rotated a password.
        Document stored = stored("users", id);
        assertThat(stored.getString("lastName")).isEqualTo("Reyes");
        assertThat(stored.getString("passwordHash")).isEqualTo("$2a$10$realhash");
        verify(audit, never()).log(any(), anyString(), anyString(), any());
    }

    @Test
    void refusesAnEditCarryingTheMaskBackInsideASourcesArraySoRealCiphertextSurvives() {
        // The data-destroying case: the browser was shown "[encrypted]", the admin edited the source's name, and the UI
        // posted the whole array back. Writing it would replace a working MCP token with a literal string.
        Document product = productWithMcpSource();
        mongo.getCollection("saas_products").insertOne(product);
        ObjectId id = product.getObjectId("_id");

        assertThatThrownBy(() -> service.update("saas_products", id.toHexString(),
                new Document().append("sources", List.of(
                        new Document().append("name", "Changelog MCP")
                                .append("authTokenEncrypted", SecretFieldMasker.MASK)))))
                .isInstanceOf(BadRequestException.class);

        assertThat(stored("saas_products", id).getList("sources", Document.class).get(1)
                .getString("authTokenEncrypted"))
                .isEqualTo("real-ciphertext");
    }

    @Test
    void rejectsAnEmptyUpdateRatherThanReportingSuccessForDoingNothing() {
        ObjectId id = new ObjectId();
        mongo.getCollection("users").insertOne(user("dana").append("_id", id));

        assertThatThrownBy(() -> service.update("users", id.toHexString(), new Document()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("no fields to change");
    }

    @Test
    void returnsA404ForAnEditAgainstAMistypedIdInsteadOfReportingASilentNoOpAsSuccess() {
        assertThatThrownBy(() -> service.update("users", new ObjectId().toHexString(),
                new Document().append("lastName", "Nobody")))
                .isInstanceOf(NotFoundException.class);

        verify(audit, never()).log(any(), anyString(), anyString(), any());
    }

    // ----- Fixtures -----

    /**
     * Reads a document straight from the driver, bypassing the service.
     *
     * <p>The point of several assertions above is what is actually <em>on disk</em> after a masked read or a rejected
     * write, and the service's own accessors mask on the way out - so asking it would prove nothing.
     */
    private Document stored(String collection, ObjectId id) {
        Document found = mongo.getCollection(collection).find(new Document("_id", id)).first();
        assertThat(found).as("document %s in %s", id, collection).isNotNull();
        return found;
    }

    private static Document user(String username) {
        return new Document()
                .append("username", username)
                .append("firstName", "Dana")
                .append("lastName", "Reyes")
                .append("email", username + "@acme.test")
                .append("role", "READ_ONLY")
                .append("passwordHash", "$2a$10$realhash")
                .append("createdAt", java.util.Date.from(Instant.parse("2026-06-01T09:30:00Z")));
    }

    private static Document productWithMcpSource() {
        return new Document()
                .append("_id", new ObjectId())
                .append("name", "Acme Analytics")
                .append("sources", List.of(
                        new Document().append("name", "Docs site")
                                .append("type", "WEBSITE")
                                .append("endpointUrl", "https://acme.test/docs"),
                        new Document().append("name", "Changelog MCP")
                                .append("type", "GENERIC_MCP")
                                .append("endpointUrl", "https://mcp.acme.test")
                                .append("authTokenEncrypted", "real-ciphertext")));
    }
}
