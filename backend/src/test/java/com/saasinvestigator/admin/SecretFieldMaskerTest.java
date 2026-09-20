package com.saasinvestigator.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.saasinvestigator.error.BadRequestException;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.Test;

/**
 * Tests the Data Explorer's guardrail - the single class standing between a generic Mongo browser and every secret this
 * application otherwise protects.
 *
 * <h2>Why this class is worth more tests than its size suggests</h2>
 *
 * <p>Every other protection in the application is enforced at the point the secret is used: a password is compared, never
 * read; a credential is decrypted inside the provider call; the JWT secret never leaves its service. The Data Explorer is
 * the one feature that reads raw documents, so it is the one place those protections are re-stated rather than inherited -
 * and a gap here is silent. Nothing fails, nothing logs; a ciphertext simply appears in a table.
 *
 * <p>The nested cases below are the reason for the recursion, and they are not hypothetical. An MCP source's
 * {@code authTokenEncrypted} lives inside the {@code sources} array of a {@code saas_products} document, so a
 * top-level-only implementation would render the product list safely and leak the token the moment somebody opened a row -
 * and worse, would accept an update carrying the mask back in the token's place, overwriting real ciphertext with the
 * string {@code [encrypted]}. That turns a masking feature into a data-destroying one, so both directions are asserted.
 */
class SecretFieldMaskerTest {

    private final SecretFieldMasker masker = new SecretFieldMasker();

    // ----- Masking -----

    @Test
    void replacesAPasswordHashWithTheMaskWhileLeavingEveryOtherFieldReadable() {
        Document masked = masker.mask("users", new Document()
                .append("_id", "user-1")
                .append("username", "dana")
                .append("passwordHash", "$2a$10$abcdefghijklmnopqrstuv")
                .append("role", "READ_ONLY"));

        assertThat(masked.getString("passwordHash")).isEqualTo(SecretFieldMasker.MASK);
        assertThat(masked.getString("username")).isEqualTo("dana");
        assertThat(masked.getString("role")).isEqualTo("READ_ONLY");
        assertThat(masked.getString("_id")).isEqualTo("user-1");
    }

    @Test
    void masksTheMcpAuthTokenNestedInsideTheSourcesArrayOfAProduct() {
        // The case that makes recursion necessary rather than tidy. A top-level scan finds nothing to mask in this
        // document and would return the real ciphertext to the browser.
        Document product = new Document()
                .append("name", "Acme Analytics")
                .append("sources", List.of(
                        new Document().append("name", "Docs site").append("url", "https://acme.test/docs"),
                        new Document()
                                .append("name", "Changelog MCP")
                                .append("authTokenEncrypted", "AAAA:BBBB:real-ciphertext")));

        Document masked = masker.mask("saas_products", product);

        List<?> sources = masked.getList("sources", Object.class);
        assertThat(((Document) sources.get(1)).getString("authTokenEncrypted"))
                .isEqualTo(SecretFieldMasker.MASK);
        assertThat(((Document) sources.get(1)).getString("name")).isEqualTo("Changelog MCP");
        assertThat(((Document) sources.get(0)).getString("url")).isEqualTo("https://acme.test/docs");
        assertThat(masked.getString("name")).isEqualTo("Acme Analytics");
    }

    @Test
    void neverMutatesTheDocumentItWasGivenSoTheCiphertextSurvivesBeingDisplayed() {
        // The same Document instance can be read again by the caller - and in the update path it is, to re-read the
        // document after writing. A masker that edited in place would return the mask as if it were stored data.
        Document original = new Document().append("username", "dana").append("passwordHash", "real-hash");

        masker.mask("users", original);

        assertThat(original.getString("passwordHash")).isEqualTo("real-hash");
    }

    @Test
    void masksAPlainMapNotJustAMongoDocumentBecauseUnknownWritersProduceUnknownShapes() {
        // This tool exists to inspect documents the application did not necessarily write, so the traversal cannot
        // assume every sub-object arrived as a Document.
        Document credential = new Document()
                .append("owner", "dana")
                .append("wrapper", Map.of("apiKeyEncrypted", "real-ciphertext"));

        Document masked = masker.mask("user_llm_credentials", credential);

        assertThat(((Document) masked.get("wrapper")).getString("apiKeyEncrypted"))
                .isEqualTo(SecretFieldMasker.MASK);
    }

    @Test
    void leavesANullSecretAsNullRatherThanClaimingSomethingIsStoredThere() {
        Document masked = masker.mask("system_llm_credentials",
                new Document().append("provider", "ANTHROPIC").append("apiKeyEncrypted", null));

        assertThat(masked.get("apiKeyEncrypted")).isNull();
        assertThat(masked.getString("provider")).isEqualTo("ANTHROPIC");
    }

    @Test
    void treatsAFieldNameAsSecretOnlyInTheCollectionWhereItIsOneRatherThanGlobally() {
        // A global blocklist of field names would be the obvious implementation and the wrong one. "value" in
        // system_config is public configuration sitting beside "valueEncrypted", which is the JWT signing secret.
        Document config = new Document()
                .append("key", "CRAWL_DEFAULTS")
                .append("value", "{\"defaultMaxDepth\":2}")
                .append("valueEncrypted", "real-ciphertext");

        Document masked = masker.mask("system_config", config);

        assertThat(masked.getString("value")).isEqualTo("{\"defaultMaxDepth\":2}");
        assertThat(masked.getString("valueEncrypted")).isEqualTo(SecretFieldMasker.MASK);
        // The same name in a collection that has no secrets is left alone entirely.
        assertThat(masker.mask("audit_logs", new Document().append("value", "kept")).getString("value"))
                .isEqualTo("kept");
    }

    // ----- Rejecting writes -----

    @Test
    void rejectsAnUpdateToAPasswordHashWithAMessageNamingWhereThePasswordIsActuallyChanged() {
        // The message is the assertion. A bare "field is read-only" leaves an admin with a task and no route to finish
        // it, and the likeliest next move is to try the same edit somewhere more dangerous.
        assertThatThrownBy(() -> masker.rejectProtectedFields("users",
                new Document().append("passwordHash", "hunter2")))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("passwordHash can't be edited directly - use Users > Reset password.");
    }

    @Test
    void rejectsAnUpdateCarryingTheMaskBackInsideTheSourcesArraySoRealCiphertextIsNeverOverwritten() {
        // The exact shape a UI produces by round-tripping what it was shown: the browser received "[encrypted]", the
        // admin edited the source's name, and the whole array came back including the mask. Writing it would replace a
        // working MCP token with a literal string and break the product's next run with nothing to point at.
        Document updates = new Document().append("sources", List.of(
                new Document().append("name", "Changelog MCP (renamed)")
                        .append("authTokenEncrypted", SecretFieldMasker.MASK)));

        assertThatThrownBy(() -> masker.rejectProtectedFields("saas_products", updates))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("edit the product's MCP source");
    }

    @Test
    void rejectsAnUpdateToASecretNestedInsideASubDocument() {
        Document updates = new Document().append("settings",
                new Document().append("nested", new Document().append("valueEncrypted", "anything")));

        assertThatThrownBy(() -> masker.rejectProtectedFields("system_config", updates))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Admin Console > Secrets");
    }

    @Test
    void rejectsTheStructuralFieldsAtAnyDepthBecauseMongoWouldFailWithAnErrorExplainingNothing() {
        assertThatThrownBy(() -> masker.rejectProtectedFields("users", new Document().append("_id", "other-id")))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("_id is managed by the database and can't be edited here.");
        assertThatThrownBy(() -> masker.rejectProtectedFields("saas_products",
                new Document().append("sources", List.of(new Document().append("_class", "java.lang.String")))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("_class is managed by the database");
    }

    @Test
    void allowsAnUpdateThatTouchesOnlyEditableFieldsIncludingNestedOnes() {
        assertThatCode(() -> masker.rejectProtectedFields("saas_products", new Document()
                .append("name", "Acme Analytics")
                .append("sources", List.of(new Document()
                        .append("name", "Docs site")
                        .append("maxDepth", 3)))))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsAnyFieldInACollectionThatHoldsNoSecrets() {
        assertThatCode(() -> masker.rejectProtectedFields("run_records",
                new Document().append("outcome", "SUCCESS").append("passwordHash", "not secret here")))
                .doesNotThrowAnyException();
    }

    // ----- Field names for the UI -----

    @Test
    void reportsTheSecretFieldNamesSoTheUiCanDisableThemInsteadOfDiscoveringThemByRejection() {
        assertThat(masker.secretFieldNames("users")).containsExactly("passwordHash");
        assertThat(masker.secretFieldNames("saas_products")).containsExactly("authTokenEncrypted");
        assertThat(masker.secretFieldNames("audit_logs")).isEmpty();
    }

    @Test
    void namesASecretFieldForEveryCollectionThatActuallyStoresOne() {
        // Written as a checklist rather than a loop because the thing being tested is the completeness of the list, and
        // a loop over the list under test would pass however short it became. Each of these is a field established
        // elsewhere in the application as encrypted or one-way; a collection missing here is a leak.
        assertThat(masker.secretFieldNames("users")).isNotEmpty();
        assertThat(masker.secretFieldNames("user_llm_credentials")).isNotEmpty();
        assertThat(masker.secretFieldNames("system_llm_credentials")).isNotEmpty();
        assertThat(masker.secretFieldNames("system_config")).isNotEmpty();
        assertThat(masker.secretFieldNames("saas_products")).isNotEmpty();
    }
}
