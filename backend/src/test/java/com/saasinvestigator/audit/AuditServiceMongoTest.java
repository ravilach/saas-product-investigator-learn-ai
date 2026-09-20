package com.saasinvestigator.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Tests {@link AuditService#search} against a real MongoDB.
 *
 * <p>Mocks cannot carry this one. The behaviour worth protecting lives in how the query is assembled and executed -
 * that optional filters combine with AND, that the total count reflects the whole match rather than the page, and
 * that ordering is newest-first - and a mocked {@code MongoTemplate} would simply return whatever the test told it
 * to, proving only that the test agrees with itself.
 *
 * <p>Requires a running Docker daemon. If Docker is unavailable this class fails rather than skipping: a coverage
 * gap that announces itself as a pass is worse than a red build (see {@code docs/SETUP.md}).
 */
@Testcontainers
@DataMongoTest
@Import(AuditService.class)
class AuditServiceMongoTest {

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");

    @Autowired
    private AuditService auditService;

    @Autowired
    private MongoTemplate mongoTemplate;

    private static final Instant T0 = Instant.parse("2026-09-01T12:00:00Z");

    @BeforeEach
    void seed() {
        mongoTemplate.getCollection("audit_logs").drop();
        // Four entries, two actors, three actions, spread over three days.
        save("admin", AuditAction.AUTH_LOGIN_SUCCESS, T0);
        save("admin", AuditAction.USER_CREATED, T0.plus(1, ChronoUnit.DAYS));
        save("dana", AuditAction.AUTH_LOGIN_FAILURE, T0.plus(2, ChronoUnit.DAYS));
        save("dana", AuditAction.AUTH_LOGIN_SUCCESS, T0.plus(3, ChronoUnit.DAYS));
    }

    @Test
    void returnsEverythingNewestFirstWhenUnfiltered() {
        Page<AuditLog> result = auditService.search(null, null, null, null, page(0, 10));

        assertThat(result.getTotalElements()).isEqualTo(4);
        assertThat(result.getContent())
                .extracting(AuditLog::getTimestamp)
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    void filtersByActor() {
        Page<AuditLog> result = auditService.search("dana", null, null, null, page(0, 10));

        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent()).allSatisfy(e ->
                assertThat(e.getActorUsername()).isEqualTo("dana"));
    }

    @Test
    void trimsTheActorFilterRatherThanMissingEverything() {
        // A trailing space from a copy-pasted username is a silent zero-result query otherwise.
        assertThat(auditService.search("  dana  ", null, null, null, page(0, 10)).getTotalElements())
                .isEqualTo(2);
    }

    @Test
    void treatsABlankActorFilterAsNoFilter() {
        assertThat(auditService.search("   ", null, null, null, page(0, 10)).getTotalElements())
                .isEqualTo(4);
    }

    @Test
    void filtersByAction() {
        Page<AuditLog> result = auditService.search(null, AuditAction.AUTH_LOGIN_SUCCESS, null, null,
                page(0, 10));

        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    @Test
    void combinesActorAndActionWithAnd() {
        Page<AuditLog> result = auditService.search("dana", AuditAction.AUTH_LOGIN_SUCCESS, null, null,
                page(0, 10));

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().getFirst().getActorUsername()).isEqualTo("dana");
    }

    @Test
    void filtersByAClosedDateRangeInclusively() {
        Page<AuditLog> result = auditService.search(null, null,
                T0.plus(1, ChronoUnit.DAYS), T0.plus(2, ChronoUnit.DAYS), page(0, 10));

        // Both bounds are inclusive, so an exact-boundary entry is included rather than mysteriously absent.
        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    @Test
    void filtersByAnOpenEndedRangeInEitherDirection() {
        assertThat(auditService.search(null, null, T0.plus(2, ChronoUnit.DAYS), null, page(0, 10))
                .getTotalElements()).isEqualTo(2);
        assertThat(auditService.search(null, null, null, T0.plus(1, ChronoUnit.DAYS), page(0, 10))
                .getTotalElements()).isEqualTo(2);
    }

    @Test
    void totalElementsCountsTheWholeMatchNotJustThePage() {
        Page<AuditLog> firstPage = auditService.search(null, null, null, null, page(0, 2));

        // The regression this guards against is real and easy to reintroduce: Spring Data's Query is mutable,
        // and calling with(pageable) before counting applies skip/limit in place, making the total equal the
        // page size and the pager show one page of everything.
        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(firstPage.getTotalElements()).isEqualTo(4);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(firstPage.isLast()).isFalse();
    }

    @Test
    void pagesThroughWithoutOverlapOrGaps() {
        var first = auditService.search(null, null, null, null, page(0, 2)).getContent();
        var second = auditService.search(null, null, null, null, page(1, 2)).getContent();

        assertThat(first).hasSize(2);
        assertThat(second).hasSize(2);
        assertThat(first).extracting(AuditLog::getId)
                .doesNotContainAnyElementsOf(second.stream().map(AuditLog::getId).toList());
    }

    @Test
    void writesAnEntryWithSystemAsTheActorWhenNobodyIsAuthenticated() {
        // Background work and the pre-authentication login path both hit this.
        auditService.log(AuditAction.SYSTEM_SETTINGS_UPDATED, "SystemConfig", "CRAWL_DEFAULTS",
                Map.of("fieldsChanged", java.util.List.of("maxPages")));

        Page<AuditLog> result = auditService.search("system", null, null, null, page(0, 10));

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().getFirst().getActorUserId()).isNull();
    }

    private static PageRequest page(int number, int size) {
        return PageRequest.of(number, size, Sort.by(Sort.Direction.DESC, "timestamp"));
    }

    private void save(String actorUsername, AuditAction action, Instant timestamp) {
        AuditLog entry = new AuditLog("id-" + actorUsername, actorUsername, action, "User",
                "id-" + actorUsername, Map.of("role", "ADMIN"));
        entry.setTimestamp(timestamp);
        mongoTemplate.save(entry);
    }
}
