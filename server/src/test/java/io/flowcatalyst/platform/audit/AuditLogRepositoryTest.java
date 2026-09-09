package io.flowcatalyst.platform.audit;

import io.flowcatalyst.platform.audit.AuditLogFixture.OtherCommand;
import io.flowcatalyst.platform.audit.AuditLogFixture.SeedCommand;
import io.flowcatalyst.platform.audit.AuditLogRepository.CursorFilter;
import io.flowcatalyst.platform.audit.AuditLogRepository.Facet;
import io.flowcatalyst.platform.audit.AuditLogRepository.ListFilter;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import org.jooq.JSONB;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static io.flowcatalyst.db.generated.Tables.AUD_LOGS;
import static io.flowcatalyst.platform.audit.AuditLogFixture.DB;
import static io.flowcatalyst.platform.audit.AuditLogFixture.DS;
import static io.flowcatalyst.platform.audit.AuditLogFixture.aggregate;
import static io.flowcatalyst.platform.audit.AuditLogFixture.entityId;
import static io.flowcatalyst.platform.audit.AuditLogFixture.entityType;
import static io.flowcatalyst.platform.audit.AuditLogFixture.seed;
import static org.assertj.core.api.Assertions.assertThat;

/// The reads over genuine sink output (spec §1, §3, §5–7): the principal-name
/// join, the JSON column, the keyset order and cursor, the offset filters and
/// the facets. Rows are seeded through a real unit of work; nothing here
/// writes `aud_logs` directly except the foreign-shape rows of
/// [#jsonColumnReadsWhateverAnotherWriterProduced].
@SuppressWarnings("deprecation")
class AuditLogRepositoryTest {

    private static final AuditLogRepository repo = new AuditLogRepository(DS);
    private static final Instant BASE = Instant.parse("2026-01-10T12:00:00.250000Z");

    private static final String AGG = aggregate("repo");
    private static final String TYPE = entityType("repo");
    private static String principal;
    private static String entityA;
    private static String entityB;
    /// Five rows on `entityA`, newest first: BASE, BASE-1s, BASE-2s, BASE-3s (x2: a tie).
    private static List<String> idsNewestFirst;

    @BeforeAll
    static void seedRows() {
        principal = AuditLogFixture.principal("Ada Lovelace " + AuditLogFixture.RUN);
        entityA = entityId();
        entityB = entityId();
        String r0 = seed(AGG, entityA, principal, BASE, new SeedCommand("zero", 0));
        String r1 = seed(AGG, entityA, principal, BASE.minusSeconds(1), new SeedCommand("one", 1));
        String r2 = seed(AGG, entityA, null, BASE.minusSeconds(2), new OtherCommand("two"));
        String r3a = seed(AGG, entityA, principal, BASE.minusSeconds(3), new SeedCommand("three-a", 3));
        String r3b = seed(AGG, entityA, principal, BASE.minusSeconds(3), new SeedCommand("three-b", 3));
        // the tie is ordered by id desc
        String tieHigh = r3a.compareTo(r3b) > 0 ? r3a : r3b;
        String tieLow = tieHigh.equals(r3a) ? r3b : r3a;
        idsNewestFirst = List.of(r0, r1, r2, tieHigh, tieLow);
        seed(AGG, entityB, principal, BASE.minusSeconds(10), new SeedCommand("other entity", 10));
    }

    private static CursorFilter typeOnly() {
        return new CursorFilter(TYPE, null, null, null, null, null);
    }

    // ── findById ───────────────────────────────────────────────────────────

    @Test
    void findByIdReadsTheSinkRowWithThePrincipalNameJoined() {
        var a = repo.findById(idsNewestFirst.get(0)).orElseThrow();
        assertThat(a.entityType()).isEqualTo(TYPE);
        assertThat(a.entityId()).isEqualTo(entityA);
        assertThat(a.operation()).isEqualTo("SeedCommand");
        assertThat(a.operationJson().get("note").asText()).isEqualTo("zero");
        assertThat(a.operationJson().get("n").asInt()).isZero();
        assertThat(a.principalId()).isEqualTo(principal);
        assertThat(a.principalName()).isEqualTo("Ada Lovelace " + AuditLogFixture.RUN);
        assertThat(a.applicationId()).isNull();
        assertThat(a.clientId()).isNull();
        assertThat(a.performedAt()).isEqualTo(BASE);
    }

    @Test
    void findByIdWithoutPrincipalHasNoName() {
        var a = repo.findById(idsNewestFirst.get(2)).orElseThrow();
        assertThat(a.operation()).isEqualTo("OtherCommand");
        assertThat(a.principalId()).isNull();
        assertThat(a.principalName()).isNull();
    }

    @Test
    void findByIdUnknownIsEmpty() {
        assertThat(repo.findById(EntityType.AUDIT_LOG.generate())).isEmpty();
    }

    // ── Keyset read ────────────────────────────────────────────────────────

    @Test
    void cursorReadIsNewestFirstWithIdAsTiebreakAndTheCursorContinuesExactly() {
        var first = repo.findWithCursor(typeOnly(), null, 3);
        assertThat(first).extracting(AuditLog::id).containsExactlyElementsOf(idsNewestFirst.subList(0, 3));
        assertThat(first).extracting(AuditLog::principalName).containsExactly(
                "Ada Lovelace " + AuditLogFixture.RUN, "Ada Lovelace " + AuditLogFixture.RUN, null);

        var second = repo.findWithCursor(typeOnly(), first.getLast().cursor(), 3);
        assertThat(second).extracting(AuditLog::id).startsWith(idsNewestFirst.get(3), idsNewestFirst.get(4));
        assertThat(second).hasSize(3);

        // a cursor inside the tie returns only the lower id of the pair, then entityB's row
        var inTie = repo.findWithCursor(typeOnly(), second.getFirst().cursor(), 3);
        assertThat(inTie).extracting(AuditLog::id).containsExactly(idsNewestFirst.get(4), second.get(2).id());
        assertThat(second.get(2).entityId()).isEqualTo(entityB);
    }

    @Test
    void cursorFiltersAreEqualityAndInLists() {
        assertThat(repo.findWithCursor(new CursorFilter(TYPE, entityB, null, null, null, null), null, 10))
                .extracting(AuditLog::entityId).containsExactly(entityB);
        assertThat(repo.findWithCursor(new CursorFilter(TYPE, null, null, "OtherCommand", null, null), null, 10))
                .extracting(AuditLog::id).containsExactly(idsNewestFirst.get(2));
        assertThat(repo.findWithCursor(new CursorFilter(TYPE, entityA, principal, null, null, null), null, 10))
                .hasSize(4).allSatisfy(a -> assertThat(a.principalId()).isEqualTo(principal));
        // the sink writes NULL application/client ids, so any IN list excludes every row
        assertThat(repo.findWithCursor(new CursorFilter(TYPE, null, null, null, List.of("app_x"), null), null, 10)).isEmpty();
        assertThat(repo.findWithCursor(new CursorFilter(TYPE, null, null, null, null, List.of("cli_x")), null, 10)).isEmpty();
        // an empty list is no filter
        assertThat(repo.findWithCursor(new CursorFilter(TYPE, null, null, null, List.of(), List.of()), null, 10)).hasSize(6);
    }

    @Test
    void outOfRangeLimitsFallBackInsteadOfFailing() {
        assertThat(repo.findWithCursor(typeOnly(), null, 0)).hasSize(6);
        assertThat(repo.findWithCursor(typeOnly(), null, 9999)).hasSize(6);
        assertThat(repo.findWithFilters(new ListFilter(TYPE, null, null, null, null, null), -1, 0)).hasSize(6);
        assertThat(repo.distinctValues(Facet.ENTITY_TYPE, 0)).contains(TYPE);
    }

    // ── Offset read ────────────────────────────────────────────────────────

    @Test
    void filteredReadHonoursEveryFilterTheWindowAndTheOrder() {
        var all = repo.findWithFilters(new ListFilter(TYPE, null, null, null, null, null), 500, 0);
        assertThat(all).extracting(AuditLog::performedAt).isSortedAccordingTo((x, y) -> y.compareTo(x));
        assertThat(all).extracting(AuditLog::id).containsExactlyInAnyOrderElementsOf(
                List.of(idsNewestFirst.get(0), idsNewestFirst.get(1), idsNewestFirst.get(2), idsNewestFirst.get(3), idsNewestFirst.get(4),
                        repo.findWithFilters(new ListFilter(TYPE, entityB, null, null, null, null), 1, 0).getFirst().id()));

        assertThat(repo.findWithFilters(new ListFilter(TYPE, entityA, null, null, null, null), 500, 0)).hasSize(5);
        assertThat(repo.findWithFilters(new ListFilter(TYPE, null, principal, null, null, null), 500, 0)).hasSize(5);
        assertThat(repo.findWithFilters(new ListFilter(TYPE, null, null, "cli_none", null, null), 500, 0)).isEmpty();
        // since / until are inclusive
        var window = repo.findWithFilters(new ListFilter(TYPE, null, null, null, BASE.minusSeconds(2), BASE.minusSeconds(1)), 500, 0);
        assertThat(window).extracting(AuditLog::id).containsExactly(idsNewestFirst.get(1), idsNewestFirst.get(2));
        // limit + offset window newest-first
        assertThat(repo.findWithFilters(new ListFilter(TYPE, entityA, null, null, null, null), 2, 1))
                .extracting(AuditLog::id).containsExactly(idsNewestFirst.get(1), idsNewestFirst.get(2));
    }

    // ── Facets ─────────────────────────────────────────────────────────────

    @Test
    void facetsAreDistinctNonNullAscending() {
        var types = repo.distinctValues(Facet.ENTITY_TYPE, 1000);
        assertThat(types).contains(TYPE).doesNotHaveDuplicates().doesNotContainNull();
        assertThat(types).isEqualTo(asTheDatabaseOrdersThem(types));
        var ops = repo.distinctValues(Facet.OPERATION, 1000);
        assertThat(ops).contains("SeedCommand", "OtherCommand").doesNotHaveDuplicates();
        assertThat(ops).isEqualTo(asTheDatabaseOrdersThem(ops));
        var apps = repo.distinctValues(Facet.APPLICATION_ID, 1000);
        assertThat(apps).doesNotContainNull();
        assertThat(apps).isEqualTo(asTheDatabaseOrdersThem(apps));
        var clients = repo.distinctValues(Facet.CLIENT_ID, 1000);
        assertThat(clients).doesNotContainNull();
        assertThat(clients).isEqualTo(asTheDatabaseOrdersThem(clients));
    }

    /// These exact values, ordered the way the database orders them.
    ///
    /// `distinctValues` is `ORDER BY <column>`, so the contract is Postgres'
    /// collation — not the JVM's. AssertJ's `isSorted()` compares by code
    /// point, where `'R'` (0x52) precedes `'f'` (0x66); Postgres puts
    /// `fanout` before `Raw`. Asserting `isSorted()` therefore held only
    /// while every row in the table happened to share a case, and failed the
    /// moment another test seeded a capitalised one — a full-suite failure
    /// that passed in isolation.
    ///
    /// Re-ordering the *returned* values rather than re-running the query
    /// keeps this independent of what `distinctValues` selects or filters: it
    /// still fails outright if the `ORDER BY` is dropped.
    private static List<String> asTheDatabaseOrdersThem(List<String> values) {
        return DB.fetch("select v from unnest(?::text[]) as t(v) order by v",
                        (Object) values.toArray(String[]::new))
                .map(r -> r.get(0, String.class));
    }

    // ── JSON column: pin the read ──────────────────────────────────────────

    /// `operation_json` is a foreign shape: another writer may store `NULL`,
    /// the JSON literal `null`, or a loosely formatted document — all must read.
    @Test
    void jsonColumnReadsWhateverAnotherWriterProduced() {
        String nullColumn = raw(null);
        String nullLiteral = raw("null");
        String loose = raw("{ \"a\" : [1, 2],\n \"b\" : \"x\" }");
        assertThat(repo.findById(nullColumn).orElseThrow().operationJson()).isNull();
        assertThat(repo.findById(nullLiteral).orElseThrow().operationJson()).isNull();
        var doc = repo.findById(loose).orElseThrow().operationJson();
        assertThat(doc.get("a")).hasSize(2);
        assertThat(doc.get("b").asText()).isEqualTo("x");
    }

    private static String raw(String operationJson) {
        String id = EntityType.AUDIT_LOG.generate();
        DB.insertInto(AUD_LOGS)
                .set(AUD_LOGS.ID, id)
                .set(AUD_LOGS.ENTITY_TYPE, entityType("raw"))
                .set(AUD_LOGS.ENTITY_ID, entityId())
                .set(AUD_LOGS.OPERATION, "ForeignCommand")
                .set(AUD_LOGS.OPERATION_JSON, operationJson == null ? null : JSONB.jsonb(operationJson))
                .set(AUD_LOGS.PERFORMED_AT, BASE.truncatedTo(ChronoUnit.SECONDS).atOffset(ZoneOffset.UTC))
                .execute();
        return id;
    }
}
