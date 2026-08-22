package io.flowcatalyst.platform.cors.operations;

import com.fasterxml.jackson.databind.JsonNode;
import io.flowcatalyst.platform.cors.CorsOrigin;
import io.flowcatalyst.platform.cors.CorsOriginRepository;
import io.flowcatalyst.platform.cors.operations.CorsOriginEvents.CorsOriginAdded;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Scope;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.ExecutionContext;
import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.testpg.TestPg;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.sql.DataSource;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The CORS allowlist use cases against the embedded Postgres (spec §4–8):
/// validation, uniqueness, persistence, and the envelope's guarantee that a
/// row write lands together with its `msg_events` and `aud_logs` rows. The
/// pure format rules are covered by `CorsOriginTest`; here each operation
/// is exercised once through the envelope.
///
/// The fixture never truncates, so every test owns its rows: origins are
/// namespaced by a per-JVM suffix on the host.
class CorsOriginOperationsTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final CorsOriginRepository repo = new CorsOriginRepository(DS);
    private static final UnitOfWork uow = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));

    /// Per-JVM namespace so origins never collide with another run on the same database.
    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final String PRINCIPAL = EntityType.PRINCIPAL.generate();
    private static final AuthContext ANCHOR = new AuthContext(PRINCIPAL, Scope.ANCHOR, "anchor@x.io",
            List.of("*"), List.of(), List.of(), true, List.of());
    private static final ExecutionContext EC = ExecutionContext.of(PRINCIPAL);

    // ── Fixture ────────────────────────────────────────────────────────────

    /// Drives `op` through the full envelope as an anchor principal — the
    /// use cases are `publicAccess`, the anchor gate is the handler's.
    private static <C, E extends DomainEvent> E runAsAnchor(Operation<C, E> op, C cmd) {
        return Auth.runAs(ANCHOR, () -> op.run(uow, cmd, EC));
    }

    /// `https://{tag}-{RUN}.example.com` — the host namespaces the test.
    private static String origin(String tag) {
        return "https://" + tag + "-" + RUN + ".example.com";
    }

    /// Seeds an origin through the public operation — the same path production uses.
    private static CorsOriginAdded added(String origin) {
        return runAsAnchor(AddOrigin.of(repo), new AddCommand(origin, null));
    }

    private static CorsOrigin reload(String id) {
        return repo.findById(id).orElseThrow(() -> new AssertionError("cors origin " + id + " not found"));
    }

    private static void assertUseCaseError(ThrowingCallable call, Class<? extends UseCaseError> kind, String code) {
        assertThatThrownBy(call)
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).as("error kind").isInstanceOf(kind);
                    assertThat(err.code()).as("error code").isEqualTo(code);
                });
    }

    private static JsonNode json(String s) {
        try {
            return Json.MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    /// `msg_events` rows of one type on the aggregate's subject.
    private static Result<Record> eventsFor(String originId, String type) {
        return DB.fetch("SELECT type, subject, source, message_group, data::text AS data, deduplication_id FROM msg_events WHERE subject = ? AND type = ?",
                CorsOriginEvents.subjectFor(originId), type);
    }

    /// `aud_logs` rows for one aggregate and command.
    private static Result<Record> auditsFor(String originId, String operation) {
        return DB.fetch("SELECT entity_type, entity_id, operation, operation_json::text AS operation_json, principal_id FROM aud_logs WHERE entity_id = ? AND operation = ?",
                originId, operation);
    }

    // ── Add ────────────────────────────────────────────────────────────────

    @Test
    void addWritesTheRowTheEventAndTheAuditTogether() {
        String origin = origin("corsadd") + ":3000";
        var ev = runAsAnchor(AddOrigin.of(repo), new AddCommand("  " + origin + "  ", "frontend dev origin"));

        assertThat(ev.originId()).startsWith("cor_");
        assertThat(ev.origin()).as("the stored origin is the trimmed form").isEqualTo(origin);
        assertThat(ev.eventType()).isEqualTo(CorsOriginEvents.ORIGIN_ADDED);
        assertThat(ev.source()).isEqualTo(CorsOriginEvents.SOURCE);
        assertThat(ev.subject()).isEqualTo(CorsOriginEvents.subjectFor(ev.originId()));
        assertThat(ev.messageGroup()).isEqualTo("platform:cors:" + ev.originId());

        var got = reload(ev.originId());
        assertThat(got.origin()).isEqualTo(origin);
        assertThat(got.description()).isEqualTo("frontend dev origin");
        assertThat(got.createdBy()).isEqualTo(PRINCIPAL);
        assertThat(repo.findByOrigin(origin)).as("findable by the trimmed origin").isPresent();
        assertThat(repo.allowedOrigins()).as("the filter's read sees the new origin").contains(origin);

        var events = eventsFor(ev.originId(), CorsOriginEvents.ORIGIN_ADDED);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().get("source")).isEqualTo(CorsOriginEvents.SOURCE);
        assertThat(events.getFirst().get("message_group")).isEqualTo("platform:cors:" + ev.originId());
        assertThat(events.getFirst().get("deduplication_id")).isEqualTo(CorsOriginEvents.ORIGIN_ADDED + "-" + ev.eventId());
        var data = json(events.getFirst().get("data", String.class));
        assertThat(data.get("originId").asText()).isEqualTo(ev.originId());
        assertThat(data.get("origin").asText()).isEqualTo(origin);
        assertThat(data.fieldNames()).toIterable().containsExactlyInAnyOrder("originId", "origin");

        var audits = auditsFor(ev.originId(), "AddCommand");
        assertThat(audits).hasSize(1);
        assertThat(audits.getFirst().get("entity_type")).isEqualTo("Cors");
        assertThat(audits.getFirst().get("principal_id")).isEqualTo(PRINCIPAL);
        var opJson = json(audits.getFirst().get("operation_json", String.class));
        assertThat(opJson.get("origin").asText()).isEqualTo("  " + origin + "  ");
        assertThat(opJson.get("description").asText()).isEqualTo("frontend dev origin");
    }

    static Stream<Arguments> malformedAddCommands() {
        return Stream.of(
                Arguments.of("null origin", new AddCommand(null, null), "ORIGIN_REQUIRED"),
                Arguments.of("empty origin", new AddCommand("", null), "ORIGIN_REQUIRED"),
                Arguments.of("whitespace origin", new AddCommand("   ", null), "ORIGIN_REQUIRED"),
                Arguments.of("no scheme", new AddCommand("corsadd-bad.example.com", null), "INVALID_ORIGIN_FORMAT"),
                Arguments.of("wrong scheme", new AddCommand("ftp://corsadd-bad.example.com", null), "INVALID_ORIGIN_FORMAT"),
                Arguments.of("trailing path", new AddCommand("https://corsadd-bad.example.com/path", null), "INVALID_ORIGIN_FORMAT"));
    }

    @ParameterizedTest(name = "{0} → {2}")
    @MethodSource("malformedAddCommands")
    void addRejectsAMalformedCommand(String label, AddCommand cmd, String expectedCode) {
        assertUseCaseError(() -> runAsAnchor(AddOrigin.of(repo), cmd), UseCaseError.Validation.class, expectedCode);
    }

    /// The first add IS the seed for the second; a differently-padded
    /// spelling of the same origin is the same origin.
    @Test
    void addRejectsADuplicateOrigin() {
        String origin = origin("corsdup");
        added(origin);
        assertUseCaseError(() -> runAsAnchor(AddOrigin.of(repo), new AddCommand(origin, "again")),
                UseCaseError.Conflict.class, "ORIGIN_ALREADY_EXISTS");
        assertUseCaseError(() -> runAsAnchor(AddOrigin.of(repo), new AddCommand(" " + origin + " ", null)),
                UseCaseError.Conflict.class, "ORIGIN_ALREADY_EXISTS");
        assertThatThrownBy(() -> runAsAnchor(AddOrigin.of(repo), new AddCommand(origin, null)))
                .hasMessageContaining("CORS origin '" + origin + "' already exists");
    }

    // ── Delete ─────────────────────────────────────────────────────────────

    @Test
    void deleteRemovesTheRowAndWritesTheEventAndTheAudit() {
        String origin = origin("corsdel");
        var seeded = added(origin);

        var ev = runAsAnchor(DeleteOrigin.of(repo), new DeleteCommand(seeded.originId()));
        assertThat(ev.originId()).isEqualTo(seeded.originId());
        assertThat(ev.origin()).isEqualTo(origin);
        assertThat(ev.eventType()).isEqualTo(CorsOriginEvents.ORIGIN_DELETED);
        assertThat(ev.messageGroup()).isEqualTo("platform:cors:" + seeded.originId());

        assertThat(repo.findById(seeded.originId())).as("deleted row must be gone").isEmpty();
        assertThat(repo.allowedOrigins()).as("the filter's read no longer sees it").doesNotContain(origin);

        var events = eventsFor(seeded.originId(), CorsOriginEvents.ORIGIN_DELETED);
        assertThat(events).hasSize(1);
        var data = json(events.getFirst().get("data", String.class));
        assertThat(data.get("originId").asText()).isEqualTo(seeded.originId());
        assertThat(data.get("origin").asText()).isEqualTo(origin);

        var audits = auditsFor(seeded.originId(), "DeleteCommand");
        assertThat(audits).hasSize(1);
        assertThat(audits.getFirst().get("entity_type")).isEqualTo("Cors");
        assertThat(json(audits.getFirst().get("operation_json", String.class)).get("originId").asText()).isEqualTo(seeded.originId());
    }

    @Test
    void deleteRejectsMissingIdOrRow() {
        assertUseCaseError(() -> runAsAnchor(DeleteOrigin.of(repo), new DeleteCommand(null)),
                UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(DeleteOrigin.of(repo), new DeleteCommand(" ")),
                UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(DeleteOrigin.of(repo), new DeleteCommand("cor_doesnotexist1")),
                UseCaseError.NotFound.class, "CorsOrigin_NOT_FOUND");
        assertThatThrownBy(() -> runAsAnchor(DeleteOrigin.of(repo), new DeleteCommand("cor_doesnotexist1")))
                .hasMessageContaining("CorsOrigin not found: cor_doesnotexist1");
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    @Test
    void listsAreOrderedByOriginAndTheFilterReadMatchesTheEntityRead() {
        String b = origin("corsord-b");
        String a = origin("corsord-a");
        added(b);
        added(a);

        var mine = repo.findAll().stream().map(CorsOrigin::origin).filter(o -> o.contains("corsord-") && o.contains(RUN)).toList();
        assertThat(mine).containsExactly(a, b);
        var strings = repo.allowedOrigins().stream().filter(o -> o.contains("corsord-") && o.contains(RUN)).toList();
        assertThat(strings).containsExactly(a, b);
    }
}
