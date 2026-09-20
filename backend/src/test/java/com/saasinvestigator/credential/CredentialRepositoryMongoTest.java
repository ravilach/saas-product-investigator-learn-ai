package com.saasinvestigator.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.client.model.IndexOptions;
import com.saasinvestigator.llm.LlmProviderType;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Tests both credential repositories against a real MongoDB.
 *
 * <p>Two things here can only be tested against a real database. The first is the <b>derived query method names</b>:
 * Spring Data resolves those at runtime, so a name that is subtly unresolvable compiles happily and throws on
 * first use - this project has already been bitten once by exactly that. The second is the <b>unique indexes</b>,
 * which are what actually guarantee one key per user per provider; without them "replace my key" would silently
 * become "accumulate keys" and resolution would start depending on document order.
 *
 * <p>The indexes are created here rather than relied upon, because a {@code @DataMongoTest} slice does not run
 * {@code MongoIndexInitializer}. They are declared with the same keys and options as the initializer uses - if the
 * two ever drift, the drift shows up as a test that passes while production does not have the index.
 *
 * <p>Requires a running Docker daemon; fails rather than skips without one (see {@code docs/SETUP.md}).
 */
@Testcontainers
@DataMongoTest
class CredentialRepositoryMongoTest {

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");

    @Autowired
    private UserLlmCredentialRepository userCredentials;

    @Autowired
    private SystemLlmCredentialRepository systemCredentials;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void seed() {
        mongoTemplate.getCollection("user_llm_credentials").drop();
        mongoTemplate.getCollection("system_llm_credentials").drop();
        mongoTemplate.getCollection("user_llm_credentials").createIndex(
                new Document("userId", 1).append("provider", 1), new IndexOptions().unique(true));
        mongoTemplate.getCollection("system_llm_credentials").createIndex(
                new Document("provider", 1), new IndexOptions().unique(true));
    }

    private UserLlmCredential save(String userId, LlmProviderType provider) {
        return userCredentials.save(new UserLlmCredential(userId, provider, "ciphertext-for-" + provider));
    }

    @Test
    void findsAUsersKeyForOneProviderWithoutSeeingTheirOther() {
        save("user-1", LlmProviderType.ANTHROPIC);
        save("user-1", LlmProviderType.OPENAI);

        assertThat(userCredentials.findByUserIdAndProvider("user-1", LlmProviderType.ANTHROPIC))
                .get()
                .satisfies(credential ->
                        assertThat(credential.getApiKeyEncrypted()).isEqualTo("ciphertext-for-ANTHROPIC"));
        assertThat(userCredentials.findByUserId("user-1")).hasSize(2);
    }

    @Test
    void oneUsersKeysAreNeverVisibleToAnother() {
        save("user-1", LlmProviderType.ANTHROPIC);
        save("user-2", LlmProviderType.ANTHROPIC);

        assertThat(userCredentials.findByUserId("user-2")).hasSize(1);
        assertThat(userCredentials.findByUserIdAndProvider("user-2", LlmProviderType.OPENAI)).isEmpty();
    }

    @Test
    void aSecondKeyForTheSameUserAndProviderIsRefusedByTheIndex() {
        save("user-1", LlmProviderType.ANTHROPIC);

        // This is why UserCredentialService does find-then-save rather than always inserting: without the
        // index a user would end up with two keys and resolution would depend on document order; with it, an
        // insert-always implementation would turn "paste a new key" into a 500.
        assertThatThrownBy(() -> mongoTemplate.insert(
                new UserLlmCredential("user-1", LlmProviderType.ANTHROPIC, "a-second-key")))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void replacingAKeyThroughTheSameDocumentIsFine() {
        UserLlmCredential existing = save("user-1", LlmProviderType.ANTHROPIC);
        existing.setApiKeyEncrypted("rotated-ciphertext");

        userCredentials.save(existing);

        assertThat(userCredentials.findByUserId("user-1")).singleElement()
                .satisfies(credential ->
                        assertThat(credential.getApiKeyEncrypted()).isEqualTo("rotated-ciphertext"));
    }

    @Test
    void deletingOneProvidersKeyLeavesTheOther() {
        save("user-1", LlmProviderType.ANTHROPIC);
        save("user-1", LlmProviderType.OPENAI);

        assertThat(userCredentials.deleteByUserIdAndProvider("user-1", LlmProviderType.ANTHROPIC))
                .isEqualTo(1L);

        assertThat(userCredentials.findByUserId("user-1")).singleElement()
                .satisfies(credential ->
                        assertThat(credential.getProvider()).isEqualTo(LlmProviderType.OPENAI));
    }

    @Test
    void deletingAKeyThatIsNotThereReportsZeroRatherThanFailing()  {
        assertThat(userCredentials.deleteByUserIdAndProvider("user-1", LlmProviderType.ANTHROPIC))
                .isZero();
    }

    @Test
    void forgettingAUserRemovesEveryKeyOfTheirsAndNobodyElses() {
        save("user-1", LlmProviderType.ANTHROPIC);
        save("user-1", LlmProviderType.OPENAI);
        save("user-2", LlmProviderType.ANTHROPIC);

        assertThat(userCredentials.deleteByUserId("user-1")).isEqualTo(2L);

        assertThat(userCredentials.findByUserId("user-1")).isEmpty();
        assertThat(userCredentials.findByUserId("user-2")).hasSize(1);
    }

    @Test
    void theProviderIsStoredAsItsNameSoTheEnumIsReadableInTheDatabase() {
        save("user-1", LlmProviderType.ANTHROPIC);

        // Someone reading system_llm_credentials or user_llm_credentials in the Data Explorer or in mongosh
        // needs to see ANTHROPIC, not an ordinal that silently changes meaning when a provider is added.
        Document stored = mongoTemplate.getCollection("user_llm_credentials").find().first();
        assertThat(stored).isNotNull();
        assertThat(stored.getString("provider")).isEqualTo("ANTHROPIC");
    }

    @Test
    void theSystemStoreHoldsAtMostOneOverridePerProvider() {
        systemCredentials.save(new SystemLlmCredential(LlmProviderType.ANTHROPIC, "ciphertext", "admin"));

        assertThatThrownBy(() -> mongoTemplate.insert(
                new SystemLlmCredential(LlmProviderType.ANTHROPIC, "another-ciphertext", "admin")))
                .isInstanceOf(DuplicateKeyException.class);

        assertThat(systemCredentials.findByProvider(LlmProviderType.ANTHROPIC)).isPresent();
        assertThat(systemCredentials.findByProvider(LlmProviderType.OPENAI)).isEmpty();
    }

    @Test
    void clearingASystemOverrideReportsWhetherThereWasOne() {
        systemCredentials.save(new SystemLlmCredential(LlmProviderType.OPENAI, "ciphertext", "admin"));

        assertThat(systemCredentials.deleteByProvider(LlmProviderType.OPENAI)).isEqualTo(1L);
        assertThat(systemCredentials.deleteByProvider(LlmProviderType.OPENAI)).isZero();
    }

    @Test
    void noPlaintextKeyEverReachesTheDatabaseThroughTheseRepositories() {
        // A shape assertion rather than a behavioural one: the documents carry a field named
        // apiKeyEncrypted and no field named apiKey, so there is nowhere for plaintext to end up by accident.
        save("user-1", LlmProviderType.ANTHROPIC);
        systemCredentials.save(new SystemLlmCredential(LlmProviderType.ANTHROPIC, "ciphertext", "admin"));

        Document user = mongoTemplate.getCollection("user_llm_credentials").find().first();
        Document system = mongoTemplate.getCollection("system_llm_credentials").find().first();
        assertThat(user).isNotNull().containsKey("apiKeyEncrypted").doesNotContainKey("apiKey");
        assertThat(system).isNotNull().containsKey("apiKeyEncrypted").doesNotContainKey("apiKey");
    }
}
