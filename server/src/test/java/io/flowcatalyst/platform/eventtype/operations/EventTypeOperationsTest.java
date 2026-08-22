package io.flowcatalyst.platform.eventtype.operations;

import com.fasterxml.jackson.databind.JsonNode;
import io.flowcatalyst.platform.eventtype.EventType;
import io.flowcatalyst.platform.eventtype.EventTypeRepository;
import io.flowcatalyst.platform.eventtype.EventTypeRepository.ListFilter;
import io.flowcatalyst.platform.eventtype.EventTypeSource;
import io.flowcatalyst.platform.eventtype.EventTypeStatus;
import io.flowcatalyst.platform.eventtype.SpecVersion;
import io.flowcatalyst.platform.eventtype.SpecVersionStatus;
import io.flowcatalyst.platform.eventtype.operations.EventTypeEvents.EventTypeCreated;
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

/// The event-type use cases against the embedded Postgres (spec §4–9):
/// validation, the per-resource authorization, persistence, and the
/// envelope's guarantee that an aggregate write lands together with its
/// `msg_events` and `aud_logs` rows. The pure transition rules are covered
/// by `EventTypeTest`; here each one is exercised once through the envelope.
///
/// The fixture never truncates, so every test owns its rows: codes are
/// namespaced by a per-JVM suffix on the application segment.
class EventTypeOperationsTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final EventTypeRepository repo = new EventTypeRepository(DS);
    private static final UnitOfWork uow = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));

    /// Per-JVM namespace so codes never collide with another run on the same database.
    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final String PRINCIPAL = EntityType.PRINCIPAL.generate();
    private static final AuthContext ANCHOR = new AuthContext(PRINCIPAL, Scope.ANCHOR, "anchor@x.io",
            List.of("*"), List.of(), List.of(), true, List.of());
    private static final ExecutionContext EC = ExecutionContext.of(PRINCIPAL);

    private static final JsonNode SCHEMA = json("{\"type\":\"object\"}");

    // ── Fixture ────────────────────────────────────────────────────────────

    /// Drives `op` through the full envelope as an anchor principal — the
    /// common case here; `createResourceScope` exercises authorization itself.
    private static <C, E extends DomainEvent> E runAsAnchor(Operation<C, E> op, C cmd) {
        return Auth.runAs(ANCHOR, () -> op.run(uow, cmd, EC));
    }

    /// `{tag}{RUN}` — the application segment namespaces the test.
    private static String app(String tag) {
        return tag + RUN;
    }

    private static String code(String tag, String event) {
        return app(tag) + ":orders:order:" + event;
    }

    private static EventTypeCreated created(String code, String name) {
        return runAsAnchor(CreateEventType.of(repo), new CreateCommand(code, name, null, null, null));
    }

    private static EventTypeCreated createdWithSchema(String code, String name) {
        return runAsAnchor(CreateEventType.of(repo), new CreateCommand(code, name, null, null, SCHEMA));
    }

    private static EventType reload(String id) {
        return repo.findById(id).orElseThrow(() -> new AssertionError("event type " + id + " not found"));
    }

    private static SpecVersion specVersion(EventType et, String version) {
        return et.specVersion(version).orElseThrow(() -> new AssertionError("spec version " + version + " not found"));
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
    private static Result<Record> eventsFor(String eventTypeId, String type) {
        return DB.fetch("SELECT type, subject, source, data::text AS data, deduplication_id FROM msg_events WHERE subject = ? AND type = ?",
                EventTypeEvents.subjectFor(eventTypeId), type);
    }

    /// `aud_logs` rows for one aggregate and command.
    private static Result<Record> auditsFor(String eventTypeId, String operation) {
        return DB.fetch("SELECT entity_type, entity_id, operation, operation_json::text AS operation_json, principal_id FROM aud_logs WHERE entity_id = ? AND operation = ?",
                eventTypeId, operation);
    }

    // ── Create ─────────────────────────────────────────────────────────────

    @Test
    void createWritesTheRowTheEventAndTheAuditTogether() {
        String code = code("etcreate", "created");
        var ev = runAsAnchor(CreateEventType.of(repo),
                new CreateCommand(code, "Order Created", "Order was created", null, SCHEMA));

        assertThat(ev.eventTypeId()).startsWith("evt_");
        assertThat(ev.code()).isEqualTo(code);
        assertThat(ev.name()).isEqualTo("Order Created");
        assertThat(ev.application()).isEqualTo(app("etcreate"));
        assertThat(ev.subdomain()).isEqualTo("orders");
        assertThat(ev.aggregate()).isEqualTo("order");
        assertThat(ev.eventName()).isEqualTo("created");
        assertThat(ev.description()).isEqualTo("Order was created");
        assertThat(ev.eventType()).isEqualTo(EventTypeEvents.CREATED);
        assertThat(ev.source()).isEqualTo(EventTypeEvents.SOURCE);
        assertThat(ev.subject()).isEqualTo(EventTypeEvents.subjectFor(ev.eventTypeId()));

        var got = reload(ev.eventTypeId());
        assertThat(got.status()).isEqualTo(EventTypeStatus.CURRENT);
        assertThat(got.source()).isEqualTo(EventTypeSource.UI);
        assertThat(got.createdBy()).isEqualTo(PRINCIPAL);
        assertThat(got.description()).isEqualTo("Order was created");
        assertThat(got.specVersions()).as("a schema on create mints version 1.0").hasSize(1);
        assertThat(specVersion(got, "1.0").status()).isEqualTo(SpecVersionStatus.FINALISING);
        assertThat(specVersion(got, "1.0").schemaContent()).isEqualTo(SCHEMA);

        var events = eventsFor(ev.eventTypeId(), EventTypeEvents.CREATED);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().get("source")).isEqualTo(EventTypeEvents.SOURCE);
        assertThat(events.getFirst().get("deduplication_id")).isEqualTo(EventTypeEvents.CREATED + "-" + ev.eventId());
        var data = json(events.getFirst().get("data", String.class));
        assertThat(data.get("eventTypeId").asText()).isEqualTo(ev.eventTypeId());
        assertThat(data.get("code").asText()).isEqualTo(code);
        assertThat(data.get("eventName").asText()).isEqualTo("created");
        assertThat(data.has("clientId")).as("null clientId is omitted from the payload").isFalse();

        var audits = auditsFor(ev.eventTypeId(), "CreateCommand");
        assertThat(audits).hasSize(1);
        assertThat(audits.getFirst().get("entity_type")).isEqualTo("Eventtype");
        assertThat(audits.getFirst().get("principal_id")).isEqualTo(PRINCIPAL);
        var opJson = json(audits.getFirst().get("operation_json", String.class));
        assertThat(opJson.get("code").asText()).isEqualTo(code);
        assertThat(opJson.get("name").asText()).isEqualTo("Order Created");
    }

    static Stream<Arguments> malformedCreateCommands() {
        return Stream.of(
                Arguments.of("null code", new CreateCommand(null, "X", null, null, null), "CODE_REQUIRED"),
                Arguments.of("blank code", new CreateCommand("  ", "X", null, null, null), "CODE_REQUIRED"),
                Arguments.of("null name", new CreateCommand("a:b:c:d", null, null, null, null), "NAME_REQUIRED"),
                Arguments.of("three segments", new CreateCommand("a:b:c", "X", null, null, null), "INVALID_CODE_FORMAT"),
                Arguments.of("blank segment", new CreateCommand("a: :c:d", "X", null, null, null), "INVALID_CODE_FORMAT"));
    }

    @ParameterizedTest(name = "{0} → {2}")
    @MethodSource("malformedCreateCommands")
    void createRejectsAMalformedCommand(String label, CreateCommand cmd, String expectedCode) {
        assertUseCaseError(() -> runAsAnchor(CreateEventType.of(repo), cmd), UseCaseError.Validation.class, expectedCode);
    }

    @Test
    void createNamesTheBlankCodeSegment() {
        assertThatThrownBy(() -> runAsAnchor(CreateEventType.of(repo), new CreateCommand("a:b:c", "X", null, null, null)))
                .hasMessageContaining("Event type code must follow format: application:subdomain:aggregate:event");
        assertThatThrownBy(() -> runAsAnchor(CreateEventType.of(repo), new CreateCommand("a: :c:d", "X", null, null, null)))
                .hasMessageContaining("Event type code part 'subdomain' cannot be empty");
    }

    @Test
    void createRejectsADuplicateCode() {
        String code = code("etdup", "created");
        created(code, "First");
        assertUseCaseError(() -> runAsAnchor(CreateEventType.of(repo), new CreateCommand(code, "Second", null, null, null)),
                UseCaseError.Conflict.class, "CODE_EXISTS");
    }

    /// The use case's per-resource authorization: the coarse "may create event
    /// types" permission is the handler's job, but the use case enforces that
    /// you can only bind an event type to a client you can access (and that
    /// platform-wide event types require anchor).
    @Test
    void createEnforcesClientScopeOnTheTargetClient() {
        String ownClient = "cli_etscope_own_" + RUN;
        var clientCtx = new AuthContext(EntityType.PRINCIPAL.generate(), Scope.CLIENT, "c@x.io", List.of(ownClient),
                List.of(), List.of(), true, List.of("platform:messaging:event-type:create"));
        var clientEc = ExecutionContext.of(clientCtx.principalId());

        // Platform-wide (null clientId) → anchor required → denied.
        assertUseCaseError(() -> Auth.runAs(clientCtx, () -> CreateEventType.of(repo).run(uow,
                        new CreateCommand(code("etscope", "platform"), "X", null, null, null), clientEc)),
                UseCaseError.Authorization.class, "SCOPE_FORBIDDEN");

        // Bound to a client the principal cannot access → denied.
        assertUseCaseError(() -> Auth.runAs(clientCtx, () -> CreateEventType.of(repo).run(uow,
                        new CreateCommand(code("etscope", "other"), "X", null, "cli_etscope_other_" + RUN, null), clientEc)),
                UseCaseError.Authorization.class, "SCOPE_FORBIDDEN");

        // Unauthenticated (no bound principal) → denied before anything is written.
        assertUseCaseError(() -> CreateEventType.of(repo).run(uow,
                        new CreateCommand(code("etscope", "anon"), "X", null, null, null), ExecutionContext.of(null)),
                UseCaseError.Authorization.class, "UNAUTHENTICATED");
        assertThat(repo.findByCode(code("etscope", "anon"))).isEmpty();

        // Bound to the principal's own client → allowed.
        var ev = Auth.runAs(clientCtx, () -> CreateEventType.of(repo).run(uow,
                new CreateCommand(code("etscope", "own"), "Mine", null, ownClient, null), clientEc));
        assertThat(ev.code()).isEqualTo(code("etscope", "own"));
        assertThat(ev.clientId()).isEqualTo(ownClient);
    }

    // ── Update ─────────────────────────────────────────────────────────────

    @Test
    void updateReplacesNameAndDescriptionButNotTheCode() {
        String code = code("etupd", "created");
        var seeded = created(code, "Before");

        var ev = runAsAnchor(UpdateEventType.of(repo), new UpdateCommand(seeded.eventTypeId(), "After", "after"));
        assertThat(ev.eventTypeId()).isEqualTo(seeded.eventTypeId());
        assertThat(ev.name()).isEqualTo("After");
        assertThat(ev.eventType()).isEqualTo(EventTypeEvents.UPDATED);

        var got = reload(seeded.eventTypeId());
        assertThat(got.name()).isEqualTo("After");
        assertThat(got.description()).isEqualTo("after");
        assertThat(got.code()).as("code is immutable on update").isEqualTo(code);

        assertThat(eventsFor(seeded.eventTypeId(), EventTypeEvents.UPDATED)).hasSize(1);
        assertThat(auditsFor(seeded.eventTypeId(), "UpdateCommand")).hasSize(1);
    }

    @Test
    void updateRejectsMissingIdNameOrRow() {
        assertUseCaseError(() -> runAsAnchor(UpdateEventType.of(repo), new UpdateCommand(null, "X", null)),
                UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(UpdateEventType.of(repo), new UpdateCommand("evt_doesnotexist1", " ", null)),
                UseCaseError.Validation.class, "NAME_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(UpdateEventType.of(repo), new UpdateCommand("evt_doesnotexist1", "X", null)),
                UseCaseError.NotFound.class, "EventType_NOT_FOUND");
    }

    // ── Delete ─────────────────────────────────────────────────────────────

    @Test
    void deleteRemovesTheRowAndItsSpecVersions() {
        String code = code("etdel", "created");
        var seeded = createdWithSchema(code, "Doomed");

        var ev = runAsAnchor(DeleteEventType.of(repo), new DeleteCommand(seeded.eventTypeId()));
        assertThat(ev.eventTypeId()).isEqualTo(seeded.eventTypeId());
        assertThat(ev.code()).isEqualTo(code);

        assertThat(repo.findById(seeded.eventTypeId())).as("deleted row must be gone").isEmpty();
        assertThat(DB.fetch("SELECT id FROM msg_event_type_spec_versions WHERE event_type_id = ?", seeded.eventTypeId()))
                .as("spec versions are deleted with the event type").isEmpty();
        assertThat(eventsFor(seeded.eventTypeId(), EventTypeEvents.DELETED)).hasSize(1);
        assertThat(auditsFor(seeded.eventTypeId(), "DeleteCommand")).hasSize(1);
    }

    @Test
    void deleteRejectsMissingIdOrRow() {
        assertUseCaseError(() -> runAsAnchor(DeleteEventType.of(repo), new DeleteCommand("")),
                UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(DeleteEventType.of(repo), new DeleteCommand("evt_doesnotexist1")),
                UseCaseError.NotFound.class, "EventType_NOT_FOUND");
    }

    // ── Archive ────────────────────────────────────────────────────────────

    @Test
    void archiveIsPersistedAndAuditedAndRefusesASecondTime() {
        var seeded = created(code("etarc", "created"), "Archive Me");

        var ev = runAsAnchor(ArchiveEventType.of(repo), new ArchiveCommand(seeded.eventTypeId()));
        assertThat(ev.eventType()).isEqualTo(EventTypeEvents.ARCHIVED);
        assertThat(reload(seeded.eventTypeId()).status()).isEqualTo(EventTypeStatus.ARCHIVED);
        assertThat(auditsFor(seeded.eventTypeId(), "ArchiveCommand")).hasSize(1);

        assertUseCaseError(() -> runAsAnchor(ArchiveEventType.of(repo), new ArchiveCommand(seeded.eventTypeId())),
                UseCaseError.Conflict.class, "ALREADY_ARCHIVED");
        assertUseCaseError(() -> runAsAnchor(ArchiveEventType.of(repo), new ArchiveCommand(" ")),
                UseCaseError.Validation.class, "ID_REQUIRED");
    }

    // ── Sync ───────────────────────────────────────────────────────────────

    @Test
    void syncCreatesUpdatesAndRemovesOnlyApiSourcedRows() {
        String application = app("etsync");
        // UI-sourced row in the same application scope: sync must NEVER touch it.
        var uiRow = created(application + ":ui:thing:kept", "UI Kept");

        var first = runAsAnchor(SyncEventTypes.of(repo), new SyncEventTypesCommand(application, List.of(
                new SyncEventTypeInput(application + ":orders:order:created", "A", null, null),
                new SyncEventTypeInput(application + ":orders:order:updated", "B", null, null)), false));
        assertThat(first.created()).isEqualTo(2);
        assertThat(first.updated()).isZero();
        assertThat(first.deleted()).isZero();
        assertThat(first.syncedCodes()).containsExactly(application + ":orders:order:created", application + ":orders:order:updated");
        assertThat(first.eventType()).isEqualTo(EventTypeEvents.SYNCED);
        assertThat(first.subject()).isEqualTo(EventTypeEvents.syncSubjectFor(application));
        assertThat(first.messageGroup()).isEqualTo("platform:eventtypes:" + application);

        var second = runAsAnchor(SyncEventTypes.of(repo), new SyncEventTypesCommand(application, List.of(
                new SyncEventTypeInput(application + ":orders:order:created", "A renamed", null, null)), true));
        assertThat(second.created()).isZero();
        assertThat(second.updated()).isEqualTo(1);
        assertThat(second.deleted()).isEqualTo(1);

        var kept = repo.findByCode(application + ":orders:order:created").orElseThrow();
        assertThat(kept.name()).isEqualTo("A renamed");
        assertThat(kept.source()).isEqualTo(EventTypeSource.API);

        assertThat(repo.findByCode(application + ":orders:order:updated")).as("unlisted API row must be deleted").isEmpty();
        assertThat(repo.findById(uiRow.eventTypeId())).as("removeUnlisted must never touch UI-sourced rows").isPresent();

        // Per-row events + the rollup, each with an audit row naming the sync command.
        assertThat(eventsFor(kept.id(), EventTypeEvents.CREATED)).hasSize(1);
        assertThat(eventsFor(kept.id(), EventTypeEvents.UPDATED)).hasSize(1);
        assertThat(auditsFor(kept.id(), "SyncEventTypesCommand")).hasSize(2);
        var rollups = DB.fetch("SELECT type, message_group FROM msg_events WHERE subject = ? AND type = ?",
                EventTypeEvents.syncSubjectFor(application), EventTypeEvents.SYNCED);
        assertThat(rollups).hasSize(2);
        assertThat(rollups.getFirst().get("message_group")).isEqualTo("platform:eventtypes:" + application);
    }

    @Test
    void syncRejectsAMissingApplicationCodeAndAbortsOnTheFirstBadRow() {
        assertUseCaseError(() -> runAsAnchor(SyncEventTypes.of(repo), new SyncEventTypesCommand(null, List.of(), false)),
                UseCaseError.Validation.class, "APPLICATION_CODE_REQUIRED");
        assertThatThrownBy(() -> runAsAnchor(SyncEventTypes.of(repo), new SyncEventTypesCommand(app("etsyncbad"),
                List.of(new SyncEventTypeInput("not-four-parts", "X", null, null)), false)))
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).isInstanceOf(UseCaseError.Validation.class);
                    assertThat(err.code()).isEqualTo("INVALID_CODE");
                    assertThat(err.message()).contains("(offending code: \"not-four-parts\")");
                });
        assertThat(repo.findByApplication(app("etsyncbad"))).as("a bad row aborts the whole sync").isEmpty();
    }

    // ── Schema lifecycle (add / finalise / deprecate) ──────────────────────

    @Test
    void addSchemaAppendsAFinalisingVersion() {
        var seeded = createdWithSchema(code("etaddsch", "created"), "Add Schema");

        var ev = runAsAnchor(AddSchema.of(repo), new AddSchemaCommand(seeded.eventTypeId(), "2.0",
                json("{\"type\":\"object\",\"title\":\"v2\"}")));
        assertThat(ev.eventTypeId()).isEqualTo(seeded.eventTypeId());
        assertThat(ev.version()).isEqualTo("2.0");

        var got = reload(seeded.eventTypeId());
        assertThat(got.specVersions()).as("new version must be appended, not replace 1.0").hasSize(2);
        var added = specVersion(got, "2.0");
        assertThat(added.status()).as("added versions start FINALISING").isEqualTo(SpecVersionStatus.FINALISING);
        assertThat(added.schemaContent()).isEqualTo(json("{\"type\":\"object\",\"title\":\"v2\"}"));
        assertThat(specVersion(got, "1.0").status()).as("1.0 untouched").isEqualTo(SpecVersionStatus.FINALISING);

        var events = eventsFor(seeded.eventTypeId(), EventTypeEvents.SCHEMA_ADDED);
        assertThat(events).hasSize(1);
        assertThat(json(events.getFirst().get("data", String.class)).get("specVersion").asText()).isEqualTo("2.0");
        assertThat(auditsFor(seeded.eventTypeId(), "AddSchemaCommand")).hasSize(1);
    }

    @Test
    void addSchemaRejectsMissingFieldsRowOrDuplicateVersion() {
        assertUseCaseError(() -> runAsAnchor(AddSchema.of(repo), new AddSchemaCommand(null, "2.0", SCHEMA)),
                UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(AddSchema.of(repo), new AddSchemaCommand("evt_doesnotexist1", null, SCHEMA)),
                UseCaseError.Validation.class, "VERSION_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(AddSchema.of(repo), new AddSchemaCommand("evt_doesnotexist1", "2.0", null)),
                UseCaseError.Validation.class, "SCHEMA_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(AddSchema.of(repo), new AddSchemaCommand("evt_doesnotexist1", "2.0", SCHEMA)),
                UseCaseError.NotFound.class, "EventType_NOT_FOUND");

        // The create-time schema already minted 1.0, so re-adding 1.0 conflicts.
        var seeded = createdWithSchema(code("etadddup", "created"), "Dup Version");
        assertUseCaseError(() -> runAsAnchor(AddSchema.of(repo), new AddSchemaCommand(seeded.eventTypeId(), "1.0", SCHEMA)),
                UseCaseError.Conflict.class, "VERSION_EXISTS");
    }

    @Test
    void finaliseMovesTheVersionToCurrentAndRefusesASecondTime() {
        var seeded = createdWithSchema(code("etfin", "created"), "Finalise Me");

        var ev = runAsAnchor(FinaliseEventTypeSchema.of(repo), new FinaliseSchemaCommand(seeded.eventTypeId(), "1.0"));
        assertThat(ev.eventTypeId()).isEqualTo(seeded.eventTypeId());
        assertThat(ev.version()).isEqualTo("1.0");
        assertThat(ev.deprecatedVersion()).as("no same-major CURRENT sibling to auto-deprecate").isNull();

        assertThat(specVersion(reload(seeded.eventTypeId()), "1.0").status()).isEqualTo(SpecVersionStatus.CURRENT);

        var events = eventsFor(seeded.eventTypeId(), EventTypeEvents.SCHEMA_FINALISED);
        assertThat(events).hasSize(1);
        var data = json(events.getFirst().get("data", String.class));
        assertThat(data.get("specVersion").asText()).isEqualTo("1.0");
        assertThat(data.has("deprecatedVersion")).isFalse();
        assertThat(auditsFor(seeded.eventTypeId(), "FinaliseSchemaCommand")).hasSize(1);

        assertUseCaseError(() -> runAsAnchor(FinaliseEventTypeSchema.of(repo), new FinaliseSchemaCommand(seeded.eventTypeId(), "1.0")),
                UseCaseError.Conflict.class, "NOT_FINALISING");
    }

    /// Finalising a version whose major already has a CURRENT sibling
    /// auto-deprecates that sibling in the same transaction.
    @Test
    void finalisePersistsTheAutoDeprecatedSibling() {
        var seeded = createdWithSchema(code("etfinmaj", "created"), "Finalise Major");
        runAsAnchor(FinaliseEventTypeSchema.of(repo), new FinaliseSchemaCommand(seeded.eventTypeId(), "1.0"));
        runAsAnchor(AddSchema.of(repo), new AddSchemaCommand(seeded.eventTypeId(), "1.1", SCHEMA));
        runAsAnchor(AddSchema.of(repo), new AddSchemaCommand(seeded.eventTypeId(), "2.0", SCHEMA));

        var ev = runAsAnchor(FinaliseEventTypeSchema.of(repo), new FinaliseSchemaCommand(seeded.eventTypeId(), "1.1"));
        assertThat(ev.deprecatedVersion()).isEqualTo("1.0");

        var got = reload(seeded.eventTypeId());
        assertThat(specVersion(got, "1.0").status()).isEqualTo(SpecVersionStatus.DEPRECATED);
        assertThat(specVersion(got, "1.1").status()).isEqualTo(SpecVersionStatus.CURRENT);
        assertThat(specVersion(got, "2.0").status()).as("other majors untouched").isEqualTo(SpecVersionStatus.FINALISING);

        var data = json(eventsFor(seeded.eventTypeId(), EventTypeEvents.SCHEMA_FINALISED).stream()
                .map(r -> r.get("data", String.class)).filter(d -> d.contains("\"1.1\"")).findFirst().orElseThrow());
        assertThat(data.get("deprecatedVersion").asText()).isEqualTo("1.0");
    }

    @Test
    void finaliseRejectsMissingFieldsRowOrVersion() {
        assertUseCaseError(() -> runAsAnchor(FinaliseEventTypeSchema.of(repo), new FinaliseSchemaCommand(null, "1.0")),
                UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(FinaliseEventTypeSchema.of(repo), new FinaliseSchemaCommand("evt_doesnotexist1", null)),
                UseCaseError.Validation.class, "VERSION_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(FinaliseEventTypeSchema.of(repo), new FinaliseSchemaCommand("evt_doesnotexist1", "1.0")),
                UseCaseError.NotFound.class, "EventType_NOT_FOUND");

        var seeded = createdWithSchema(code("etfinerr", "created"), "Finalise Errors");
        assertUseCaseError(() -> runAsAnchor(FinaliseEventTypeSchema.of(repo), new FinaliseSchemaCommand(seeded.eventTypeId(), "9.9")),
                UseCaseError.NotFound.class, "SpecVersion_NOT_FOUND");
    }

    /// Full lifecycle through the envelope: FINALISING refuses direct
    /// deprecation, CURRENT deprecates, DEPRECATED refuses a second one.
    @Test
    void deprecateFollowsTheStateMachine() {
        var seeded = createdWithSchema(code("etdep", "created"), "Deprecate Me");

        assertUseCaseError(() -> runAsAnchor(DeprecateEventTypeSchema.of(repo), new DeprecateSchemaCommand(seeded.eventTypeId(), "1.0")),
                UseCaseError.Conflict.class, "STILL_FINALISING");

        runAsAnchor(FinaliseEventTypeSchema.of(repo), new FinaliseSchemaCommand(seeded.eventTypeId(), "1.0"));

        var ev = runAsAnchor(DeprecateEventTypeSchema.of(repo), new DeprecateSchemaCommand(seeded.eventTypeId(), "1.0"));
        assertThat(ev.eventTypeId()).isEqualTo(seeded.eventTypeId());
        assertThat(ev.version()).isEqualTo("1.0");
        assertThat(ev.eventType()).isEqualTo(EventTypeEvents.SCHEMA_DEPRECATED);

        assertThat(specVersion(reload(seeded.eventTypeId()), "1.0").status()).isEqualTo(SpecVersionStatus.DEPRECATED);
        assertThat(auditsFor(seeded.eventTypeId(), "DeprecateSchemaCommand")).hasSize(1);

        assertUseCaseError(() -> runAsAnchor(DeprecateEventTypeSchema.of(repo), new DeprecateSchemaCommand(seeded.eventTypeId(), "1.0")),
                UseCaseError.Conflict.class, "ALREADY_DEPRECATED");
    }

    @Test
    void deprecateRejectsMissingFieldsRowOrVersion() {
        assertUseCaseError(() -> runAsAnchor(DeprecateEventTypeSchema.of(repo), new DeprecateSchemaCommand(null, "1.0")),
                UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(DeprecateEventTypeSchema.of(repo), new DeprecateSchemaCommand("evt_doesnotexist1", null)),
                UseCaseError.Validation.class, "VERSION_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(DeprecateEventTypeSchema.of(repo), new DeprecateSchemaCommand("evt_doesnotexist1", "1.0")),
                UseCaseError.NotFound.class, "EventType_NOT_FOUND");

        var seeded = createdWithSchema(code("etdeperr", "created"), "Deprecate Errors");
        assertUseCaseError(() -> runAsAnchor(DeprecateEventTypeSchema.of(repo), new DeprecateSchemaCommand(seeded.eventTypeId(), "9.9")),
                UseCaseError.NotFound.class, "SpecVersion_NOT_FOUND");
    }

    // ── Repository reads ───────────────────────────────────────────────────

    @Test
    void listFiltersCombineAndResultsAreOrderedByCode() {
        String application = app("etlist");
        var a = created(application + ":orders:order:created", "A");
        var b = created(application + ":orders:order:updated", "B");
        var c = createdWithSchema(application + ":billing:invoice:sent", "C");
        runAsAnchor(ArchiveEventType.of(repo), new ArchiveCommand(b.eventTypeId()));

        var all = repo.findWithFilters(new ListFilter(application, null, null, null));
        assertThat(all).extracting(EventType::code).as("ordered by code")
                .containsExactly(application + ":billing:invoice:sent", application + ":orders:order:created",
                        application + ":orders:order:updated");
        assertThat(all.stream().filter(et -> et.id().equals(c.eventTypeId())).findFirst().orElseThrow().specVersions())
                .as("list hydrates spec versions").hasSize(1);

        assertThat(repo.findWithFilters(new ListFilter(application, "CURRENT", null, null))).extracting(EventType::id)
                .containsExactlyInAnyOrder(a.eventTypeId(), c.eventTypeId());
        assertThat(repo.findWithFilters(new ListFilter(application, "ARCHIVED", null, null))).extracting(EventType::id)
                .containsExactly(b.eventTypeId());
        assertThat(repo.findWithFilters(new ListFilter(application, null, "orders", null))).hasSize(2);
        assertThat(repo.findWithFilters(new ListFilter(application, null, null, "invoice"))).extracting(EventType::id)
                .containsExactly(c.eventTypeId());
        assertThat(repo.findByApplication(application)).hasSize(3);
        assertThat(repo.findByApplication(application + "none")).isEmpty();
    }
}
