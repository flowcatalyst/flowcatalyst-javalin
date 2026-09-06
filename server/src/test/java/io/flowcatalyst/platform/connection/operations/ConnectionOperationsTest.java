package io.flowcatalyst.platform.connection.operations;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.connection.Connection;
import io.flowcatalyst.platform.connection.ConnectionRepository;
import io.flowcatalyst.platform.connection.ConnectionRepository.ListFilter;
import io.flowcatalyst.platform.connection.ConnectionStatus;
import io.flowcatalyst.platform.connection.operations.ConnectionEvents.ConnectionCreated;
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

/// The connection use cases against the embedded Postgres (spec §4–8):
/// validation, the per-resource authorization, persistence, and the
/// envelope's guarantee that an aggregate write lands together with its
/// `msg_events` and `aud_logs` rows. The pure transition rules are covered
/// by `ConnectionTest`; here each operation is exercised once through the
/// envelope.
///
/// The fixture never truncates, so every test owns its rows: codes carry a
/// per-JVM suffix.
@SuppressWarnings("deprecation")
class ConnectionOperationsTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final ConnectionRepository repo = new ConnectionRepository(DS);
    private static final UnitOfWork uow = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));

    /// Per-JVM namespace so codes never collide with another run on the same database.
    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final String PRINCIPAL = EntityType.PRINCIPAL.generate();
    private static final AuthContext ANCHOR = new AuthContext(PRINCIPAL, Scope.ANCHOR, "anchor@x.io",
            List.of("*"), List.of(), List.of(), true, List.of());
    private static final ExecutionContext EC = ExecutionContext.of(PRINCIPAL);
    private static final String SERVICE_ACCOUNT = "sva_conntestseed1";
    private static final String DUP_CLIENT = EntityType.CLIENT.generate();

    // ── Fixture ────────────────────────────────────────────────────────────

    /// Drives `op` through the full envelope as an anchor principal — the
    /// common case here; `createEnforcesClientScopeOnTheTargetClient` and
    /// `byIdWritesEnforceScopeOnTheLoadedRow` exercise authorization itself.
    private static <C, E extends DomainEvent> E runAsAnchor(Operation<C, E> op, C cmd) {
        return Auth.runAs(ANCHOR, () -> op.run(uow, cmd, EC));
    }

    /// `{tag}-{RUN}` — a code namespaced to this test run.
    private static String code(String tag) {
        return tag + "-" + RUN;
    }

    private static ConnectionCreated created(String code, String name) {
        return runAsAnchor(CreateConnection.of(repo), new CreateCommand(code, name, null, SERVICE_ACCOUNT, null, null));
    }

    private static Connection reload(String id) {
        return repo.findById(id).orElseThrow(() -> new AssertionError("connection " + id + " not found"));
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
    private static Result<Record> eventsFor(String connectionId, String type) {
        return DB.fetch("SELECT type, subject, source, message_group, data::text AS data, deduplication_id FROM msg_events WHERE subject = ? AND type = ?",
                ConnectionEvents.subjectFor(connectionId), type);
    }

    /// `aud_logs` rows for one aggregate and command.
    private static Result<Record> auditsFor(String connectionId, String operation) {
        return DB.fetch("SELECT entity_type, entity_id, operation, operation_json::text AS operation_json, principal_id FROM aud_logs WHERE entity_id = ? AND operation = ?",
                connectionId, operation);
    }

    // ── Create ─────────────────────────────────────────────────────────────

    @Test
    void createWritesTheRowTheEventAndTheAuditTogether() {
        String code = code("conncrt-happy");
        var ev = runAsAnchor(CreateConnection.of(repo),
                new CreateCommand("  " + code.toUpperCase(Locale.ROOT) + "  ", "  Conn Create Happy  ",
                        "outbound webhook target", "sva_conncrthappy1", "ext-conncrt-1", null));

        assertThat(ev.connectionId()).startsWith("con_");
        assertThat(ev.code()).as("code is trimmed + lower-cased").isEqualTo(code);
        assertThat(ev.name()).as("name is trimmed").isEqualTo("Conn Create Happy");
        assertThat(ev.eventType()).isEqualTo(ConnectionEvents.CREATED);
        assertThat(ev.source()).isEqualTo(ConnectionEvents.SOURCE);
        assertThat(ev.subject()).isEqualTo(ConnectionEvents.subjectFor(ev.connectionId()));
        assertThat(ev.messageGroup()).isEqualTo(ConnectionEvents.groupFor(ev.connectionId()));

        var got = reload(ev.connectionId());
        assertThat(got.code()).isEqualTo(code);
        assertThat(got.name()).isEqualTo("Conn Create Happy");
        assertThat(got.status()).as("new connections start ACTIVE").isEqualTo(ConnectionStatus.ACTIVE);
        assertThat(got.serviceAccountId()).isEqualTo("sva_conncrthappy1");
        assertThat(got.description()).isEqualTo("outbound webhook target");
        assertThat(got.externalId()).isEqualTo("ext-conncrt-1");
        assertThat(got.clientId()).isNull();
        assertThat(got.clientIdentifier()).isNull();

        var events = eventsFor(ev.connectionId(), ConnectionEvents.CREATED);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().get("source")).isEqualTo(ConnectionEvents.SOURCE);
        assertThat(events.getFirst().get("message_group")).isEqualTo(ConnectionEvents.groupFor(ev.connectionId()));
        assertThat(events.getFirst().get("deduplication_id")).isEqualTo(ConnectionEvents.CREATED + "-" + ev.eventId());
        var data = json(events.getFirst().get("data", String.class));
        assertThat(data.get("connectionId").asText()).isEqualTo(ev.connectionId());
        assertThat(data.get("code").asText()).isEqualTo(code);
        assertThat(data.get("name").asText()).isEqualTo("Conn Create Happy");
        assertThat(data.propertyNames()).containsExactlyInAnyOrder("connectionId", "code", "name");

        var audits = auditsFor(ev.connectionId(), "CreateCommand");
        assertThat(audits).hasSize(1);
        assertThat(audits.getFirst().get("entity_type")).isEqualTo("Connection");
        assertThat(audits.getFirst().get("principal_id")).isEqualTo(PRINCIPAL);
        var opJson = json(audits.getFirst().get("operation_json", String.class));
        assertThat(opJson.get("code").asText()).isEqualTo("  " + code.toUpperCase(Locale.ROOT) + "  ");
        assertThat(opJson.get("serviceAccountId").asText()).isEqualTo("sva_conncrthappy1");
    }

    static Stream<Arguments> malformedCreateCommands() {
        return Stream.of(
                Arguments.of("null code", new CreateCommand(null, "X", null, "sva_x", null, null), "CODE_REQUIRED"),
                Arguments.of("blank code", new CreateCommand("  ", "X", null, "sva_x", null, null), "CODE_REQUIRED"),
                Arguments.of("code starts with digit", new CreateCommand("1conncrt-bad", "X", null, "sva_x", null, null), "INVALID_CODE_FORMAT"),
                Arguments.of("code with underscore", new CreateCommand("conncrt_bad", "X", null, "sva_x", null, null), "INVALID_CODE_FORMAT"),
                Arguments.of("null name", new CreateCommand("conncrt-noname", null, null, "sva_x", null, null), "NAME_REQUIRED"),
                Arguments.of("blank name", new CreateCommand("conncrt-noname", "  ", null, "sva_x", null, null), "NAME_REQUIRED"),
                Arguments.of("missing service account", new CreateCommand("conncrt-nosa", "X", null, null, null, null), "SERVICE_ACCOUNT_REQUIRED"),
                Arguments.of("blank service account", new CreateCommand("conncrt-nosa", "X", null, " ", null, null), "SERVICE_ACCOUNT_REQUIRED"));
    }

    @ParameterizedTest(name = "{0} → {2}")
    @MethodSource("malformedCreateCommands")
    void createRejectsAMalformedCommand(String label, CreateCommand cmd, String expectedCode) {
        assertUseCaseError(() -> runAsAnchor(CreateConnection.of(repo), cmd), UseCaseError.Validation.class, expectedCode);
    }

    @Test
    void createRejectsADuplicateCodeWithinTheSameScopeOnly() {
        String code = code("conndup");
        created(code, "First");
        assertUseCaseError(() -> runAsAnchor(CreateConnection.of(repo), new CreateCommand(code, "Second", null, "sva_conndup2", null, null)),
                UseCaseError.Conflict.class, "CODE_EXISTS");
        assertThatThrownBy(() -> runAsAnchor(CreateConnection.of(repo), new CreateCommand(code, "Second", null, "sva_conndup2", null, null)))
                .hasMessageContaining("Connection with code '" + code + "' already exists");

        // Same code under a client is a different (code, clientId) pair — allowed (spec §6).
        var scoped = runAsAnchor(CreateConnection.of(repo),
                new CreateCommand(code, "Client copy", null, "sva_conndup3", null, DUP_CLIENT));
        assertThat(reload(scoped.connectionId()).clientId()).isEqualTo(DUP_CLIENT);
    }

    /// The use case's per-resource authorization: the coarse "may create
    /// connections" permission is the handler's job, but the use case enforces
    /// that you can only bind a connection to a client you can access (and that
    /// platform-wide connections require anchor).
    @Test
    void createEnforcesClientScopeOnTheTargetClient() {
        String ownClient = EntityType.CLIENT.generate();
        var clientCtx = new AuthContext(EntityType.PRINCIPAL.generate(), Scope.CLIENT, "c@x.io", List.of(ownClient),
                List.of(), List.of(), true, List.of("platform:messaging:connection:create"));
        var clientEc = ExecutionContext.of(clientCtx.principalId());

        assertUseCaseError(() -> Auth.runAs(clientCtx, () -> CreateConnection.of(repo).run(uow,
                        new CreateCommand(code("connscope-platform"), "X", null, "sva_x", null, null), clientEc)),
                UseCaseError.Authorization.class, "SCOPE_FORBIDDEN");

        assertUseCaseError(() -> Auth.runAs(clientCtx, () -> CreateConnection.of(repo).run(uow,
                        new CreateCommand(code("connscope-other"), "X", null, "sva_x", null, EntityType.CLIENT.generate()), clientEc)),
                UseCaseError.Authorization.class, "SCOPE_FORBIDDEN");

        var ev = Auth.runAs(clientCtx, () -> CreateConnection.of(repo).run(uow,
                new CreateCommand(code("connscope-own"), "Mine", null, "sva_x", null, ownClient), clientEc));
        assertThat(ev.code()).isEqualTo(code("connscope-own"));
        assertThat(reload(ev.connectionId()).clientId()).isEqualTo(ownClient);
    }

    // ── Update ─────────────────────────────────────────────────────────────

    @Test
    void updateReplacesTheMutableFieldsAndMayFlipTheStatus() {
        var seeded = created(code("connupd-happy"), "Before");

        var ev = runAsAnchor(UpdateConnection.of(repo),
                new UpdateCommand(seeded.connectionId(), "  After  ", "after", "ext-connupd-1", "PAUSED"));
        assertThat(ev.connectionId()).isEqualTo(seeded.connectionId());
        assertThat(ev.name()).isEqualTo("After");
        assertThat(ev.eventType()).isEqualTo(ConnectionEvents.UPDATED);

        var got = reload(seeded.connectionId());
        assertThat(got.name()).isEqualTo("After");
        assertThat(got.description()).isEqualTo("after");
        assertThat(got.externalId()).isEqualTo("ext-connupd-1");
        assertThat(got.status()).as("status flip via update must persist").isEqualTo(ConnectionStatus.PAUSED);
        assertThat(got.code()).as("code is immutable on update").isEqualTo(code("connupd-happy"));

        // Absent status leaves it alone; absent description/externalId clear them (full replace).
        runAsAnchor(UpdateConnection.of(repo), new UpdateCommand(seeded.connectionId(), "Again", null, null, null));
        got = reload(seeded.connectionId());
        assertThat(got.status()).isEqualTo(ConnectionStatus.PAUSED);
        assertThat(got.description()).isNull();
        assertThat(got.externalId()).isNull();

        // Owner ruling 2026-09-06 #19 (X-06 at the wire): an unknown status is refused, nothing changes.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                runAsAnchor(UpdateConnection.of(repo), new UpdateCommand(seeded.connectionId(), "Again", null, null, "garbage")))
                .isInstanceOf(io.flowcatalyst.sdk.usecase.UseCaseException.class)
                .extracting(t -> ((io.flowcatalyst.sdk.usecase.UseCaseException) t).error().code())
                .isEqualTo("INVALID_STATUS");
        assertThat(reload(seeded.connectionId()).status()).isEqualTo(ConnectionStatus.PAUSED);

        var events = eventsFor(seeded.connectionId(), ConnectionEvents.UPDATED);
        assertThat(events).hasSize(2);
        var data = json(events.getFirst().get("data", String.class));
        assertThat(data.propertyNames()).containsExactlyInAnyOrder("connectionId", "name");
        assertThat(auditsFor(seeded.connectionId(), "UpdateCommand")).hasSize(2);
    }

    static Stream<Arguments> malformedUpdateCommands() {
        return Stream.of(
                Arguments.of("null id", new UpdateCommand(null, "X", null, null, null), UseCaseError.Validation.class, "ID_REQUIRED"),
                Arguments.of("blank name", new UpdateCommand("con_doesnotexist1", " ", null, null, null), UseCaseError.Validation.class, "NAME_REQUIRED"),
                Arguments.of("unknown id", new UpdateCommand("con_doesnotexist1", "X", null, null, null), UseCaseError.NotFound.class, "Connection_NOT_FOUND"));
    }

    @ParameterizedTest(name = "{0} → {3}")
    @MethodSource("malformedUpdateCommands")
    void updateRejectsAMalformedOrUnknownCommand(String label, UpdateCommand cmd, Class<? extends UseCaseError> kind, String expectedCode) {
        assertUseCaseError(() -> runAsAnchor(UpdateConnection.of(repo), cmd), kind, expectedCode);
    }

    // ── Pause / Activate ───────────────────────────────────────────────────

    @Test
    void pauseThenActivateFlipTheStatusAndEmitUpdated() {
        var seeded = created(code("connsts-happy"), "Flip Me");

        var paused = runAsAnchor(PauseConnection.of(repo), new PauseCommand(seeded.connectionId()));
        assertThat(paused.connectionId()).isEqualTo(seeded.connectionId());
        assertThat(paused.name()).isEqualTo("Flip Me");
        assertThat(paused.eventType()).isEqualTo(ConnectionEvents.UPDATED);
        assertThat(reload(seeded.connectionId()).status()).as("pause flips ACTIVE → PAUSED").isEqualTo(ConnectionStatus.PAUSED);

        var activated = runAsAnchor(ActivateConnection.of(repo), new ActivateCommand(seeded.connectionId()));
        assertThat(activated.connectionId()).isEqualTo(seeded.connectionId());
        assertThat(reload(seeded.connectionId()).status()).as("activate flips PAUSED → ACTIVE").isEqualTo(ConnectionStatus.ACTIVE);

        // Idempotent: a second activate is not a conflict and still emits + audits (spec §2).
        runAsAnchor(ActivateConnection.of(repo), new ActivateCommand(seeded.connectionId()));
        assertThat(reload(seeded.connectionId()).status()).isEqualTo(ConnectionStatus.ACTIVE);

        assertThat(eventsFor(seeded.connectionId(), ConnectionEvents.UPDATED)).hasSize(3);
        assertThat(auditsFor(seeded.connectionId(), "PauseCommand")).hasSize(1);
        assertThat(auditsFor(seeded.connectionId(), "ActivateCommand")).hasSize(2);
    }

    @Test
    void pauseAndActivateRejectABlankOrUnknownId() {
        assertUseCaseError(() -> runAsAnchor(PauseConnection.of(repo), new PauseCommand("")), UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(PauseConnection.of(repo), new PauseCommand("con_doesnotexist1")), UseCaseError.NotFound.class, "Connection_NOT_FOUND");
        assertUseCaseError(() -> runAsAnchor(ActivateConnection.of(repo), new ActivateCommand(null)), UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(ActivateConnection.of(repo), new ActivateCommand("con_doesnotexist1")), UseCaseError.NotFound.class, "Connection_NOT_FOUND");
    }

    // ── Delete ─────────────────────────────────────────────────────────────

    @Test
    void deleteRemovesTheRowAndEmitsDeleted() {
        var seeded = created(code("conndel-happy"), "Doomed");

        var ev = runAsAnchor(DeleteConnection.of(repo), new DeleteCommand(seeded.connectionId()));
        assertThat(ev.connectionId()).isEqualTo(seeded.connectionId());
        assertThat(ev.code()).isEqualTo(code("conndel-happy"));
        assertThat(ev.eventType()).isEqualTo(ConnectionEvents.DELETED);
        assertThat(repo.findById(seeded.connectionId())).as("deleted row must be gone").isEmpty();

        var events = eventsFor(seeded.connectionId(), ConnectionEvents.DELETED);
        assertThat(events).hasSize(1);
        var data = json(events.getFirst().get("data", String.class));
        assertThat(data.propertyNames()).containsExactlyInAnyOrder("connectionId", "code");
        assertThat(auditsFor(seeded.connectionId(), "DeleteCommand")).hasSize(1);

        assertUseCaseError(() -> runAsAnchor(DeleteConnection.of(repo), new DeleteCommand(seeded.connectionId())),
                UseCaseError.NotFound.class, "Connection_NOT_FOUND");
        assertUseCaseError(() -> runAsAnchor(DeleteConnection.of(repo), new DeleteCommand("  ")),
                UseCaseError.Validation.class, "ID_REQUIRED");
    }

    // ── Per-resource scope on by-id writes ─────────────────────────────────

    /// A CLIENT-scoped principal holding the write permissions may act on a
    /// connection bound to its own client, but not on a platform-wide one or
    /// another client's (spec §5).
    @Test
    void byIdWritesEnforceScopeOnTheLoadedRow() {
        String ownClient = EntityType.CLIENT.generate();
        var clientCtx = new AuthContext(EntityType.PRINCIPAL.generate(), Scope.CLIENT, "c@x.io", List.of(ownClient),
                List.of(), List.of(), true, List.of("platform:messaging:connection:update", "platform:messaging:connection:delete"));
        var clientEc = ExecutionContext.of(clientCtx.principalId());

        var platformWide = created(code("connbyid-platform"), "Platform");
        var own = runAsAnchor(CreateConnection.of(repo), new CreateCommand(code("connbyid-own"), "Own", null, "sva_x", null, ownClient));
        var other = runAsAnchor(CreateConnection.of(repo), new CreateCommand(code("connbyid-other"), "Other", null, "sva_x", null, EntityType.CLIENT.generate()));

        assertUseCaseError(() -> Auth.runAs(clientCtx, () -> PauseConnection.of(repo).run(uow, new PauseCommand(platformWide.connectionId()), clientEc)),
                UseCaseError.Authorization.class, "SCOPE_FORBIDDEN");
        assertUseCaseError(() -> Auth.runAs(clientCtx, () -> UpdateConnection.of(repo).run(uow, new UpdateCommand(other.connectionId(), "X", null, null, null), clientEc)),
                UseCaseError.Authorization.class, "SCOPE_FORBIDDEN");
        assertUseCaseError(() -> Auth.runAs(clientCtx, () -> DeleteConnection.of(repo).run(uow, new DeleteCommand(other.connectionId()), clientEc)),
                UseCaseError.Authorization.class, "SCOPE_FORBIDDEN");

        Auth.runAs(clientCtx, () -> PauseConnection.of(repo).run(uow, new PauseCommand(own.connectionId()), clientEc));
        assertThat(reload(own.connectionId()).status()).isEqualTo(ConnectionStatus.PAUSED);
        Auth.runAs(clientCtx, () -> DeleteConnection.of(repo).run(uow, new DeleteCommand(own.connectionId()), clientEc));
        assertThat(repo.findById(own.connectionId())).isEmpty();
    }

    // ── Repository reads ───────────────────────────────────────────────────

    @Test
    void listFiltersByStatusAndClientAndOrdersByCode() {
        String client = EntityType.CLIENT.generate();
        var b = runAsAnchor(CreateConnection.of(repo), new CreateCommand(code("connlist-b"), "B", null, "sva_x", null, client));
        var a = runAsAnchor(CreateConnection.of(repo), new CreateCommand(code("connlist-a"), "A", null, "sva_x", null, client));
        runAsAnchor(PauseConnection.of(repo), new PauseCommand(b.connectionId()));

        assertThat(repo.findWithFilters(new ListFilter(null, client))).extracting(Connection::id)
                .containsExactly(a.connectionId(), b.connectionId());
        assertThat(repo.findWithFilters(new ListFilter("PAUSED", client))).extracting(Connection::id)
                .containsExactly(b.connectionId());
        assertThat(repo.findWithFilters(new ListFilter("ACTIVE", client))).extracting(Connection::id)
                .containsExactly(a.connectionId());
        assertThat(repo.findByCodeAndClient(code("connlist-a"), client)).map(Connection::id).contains(a.connectionId());
        assertThat(repo.findByCodeAndClient(code("connlist-a"), null)).as("null client matches platform-wide rows only").isEmpty();
    }
}
