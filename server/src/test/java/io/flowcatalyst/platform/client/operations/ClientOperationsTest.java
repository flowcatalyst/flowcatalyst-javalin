package io.flowcatalyst.platform.client.operations;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.client.ClientStatus;
import io.flowcatalyst.platform.client.operations.ClientEvents.ClientCreated;
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
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The client use cases against the embedded Postgres (spec §4–8):
/// validation, normalisation, persistence, and the envelope's guarantee that
/// an aggregate write lands together with its `msg_events` and `aud_logs`
/// rows. The pure transition rules are covered by `ClientTest`; here each
/// operation is exercised once through the envelope.
///
/// The fixture never truncates, so every test owns its rows: identifiers
/// are namespaced by a per-JVM suffix.
@SuppressWarnings("deprecation")
class ClientOperationsTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final ClientRepository repo = new ClientRepository(DS);
    private static final UnitOfWork uow = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));

    /// Per-JVM namespace so identifiers never collide with another run on the same database.
    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final String PRINCIPAL = EntityType.PRINCIPAL.generate();
    private static final AuthContext ANCHOR = new AuthContext(PRINCIPAL, Scope.ANCHOR, "anchor@x.io",
            List.of("*"), List.of(), List.of(), true, List.of());
    private static final ExecutionContext EC = ExecutionContext.of(PRINCIPAL);

    // ── Fixture ────────────────────────────────────────────────────────────

    /// Drives `op` through the full envelope as an anchor principal (the
    /// coarse anchor gate is the handler's, so every use case is public).
    private static <C, E extends DomainEvent> E runAsAnchor(Operation<C, E> op, C cmd) {
        return Auth.runAs(ANCHOR, () -> op.run(uow, cmd, EC));
    }

    /// `{tag}-{RUN}` — a valid, namespaced identifier.
    private static String ident(String tag) {
        return tag + "-" + RUN;
    }

    private static ClientCreated created(String name, String identifier) {
        return runAsAnchor(CreateClient.of(repo), new CreateCommand(name, identifier));
    }

    private static Client reload(String id) {
        return repo.findById(id).orElseThrow(() -> new AssertionError("client " + id + " not found"));
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
    private static Result<Record> eventsFor(String clientId, String type) {
        return DB.fetch("SELECT type, subject, source, message_group, data::text AS data, deduplication_id FROM msg_events WHERE subject = ? AND type = ?",
                ClientEvents.subjectFor(clientId), type);
    }

    /// `aud_logs` rows for one aggregate and command.
    private static Result<Record> auditsFor(String clientId, String operation) {
        return DB.fetch("SELECT entity_type, entity_id, operation, operation_json::text AS operation_json, principal_id FROM aud_logs WHERE entity_id = ? AND operation = ?",
                clientId, operation);
    }

    // ── Create ─────────────────────────────────────────────────────────────

    @Test
    void createWritesTheRowTheEventAndTheAuditTogether() {
        var ev = runAsAnchor(CreateClient.of(repo), new CreateCommand("  Acme Corp  ", "  " + ident("CL-Create").toUpperCase(Locale.ROOT) + "  "));

        assertThat(ev.clientId()).startsWith("clt_");
        assertThat(ev.name()).as("name is trimmed").isEqualTo("Acme Corp");
        assertThat(ev.identifier()).as("identifier is trimmed and lower-cased").isEqualTo(ident("cl-create"));
        assertThat(ev.eventType()).isEqualTo(ClientEvents.CREATED);
        assertThat(ev.source()).isEqualTo(ClientEvents.SOURCE);
        assertThat(ev.subject()).isEqualTo(ClientEvents.subjectFor(ev.clientId()));
        assertThat(ev.messageGroup()).isEqualTo("platform:client:" + ev.clientId());

        var got = reload(ev.clientId());
        assertThat(got.name()).isEqualTo("Acme Corp");
        assertThat(got.identifier()).isEqualTo(ident("cl-create"));
        assertThat(got.status()).isEqualTo(ClientStatus.ACTIVE);
        assertThat(got.statusReason()).isNull();
        assertThat(got.statusChangedAt()).isNull();
        assertThat(got.notes()).isEmpty();

        var events = eventsFor(ev.clientId(), ClientEvents.CREATED);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().get("source")).isEqualTo(ClientEvents.SOURCE);
        assertThat(events.getFirst().get("message_group")).isEqualTo("platform:client:" + ev.clientId());
        assertThat(events.getFirst().get("deduplication_id")).isEqualTo(ClientEvents.CREATED + "-" + ev.eventId());
        var data = json(events.getFirst().get("data", String.class));
        assertThat(data.get("clientId").asText()).isEqualTo(ev.clientId());
        assertThat(data.get("name").asText()).isEqualTo("Acme Corp");
        assertThat(data.get("identifier").asText()).isEqualTo(ident("cl-create"));
        assertThat(data.propertyNames()).as("jsonb reorders keys; the set is the contract")
                .containsExactlyInAnyOrder("clientId", "name", "identifier");

        var audits = auditsFor(ev.clientId(), "CreateCommand");
        assertThat(audits).hasSize(1);
        assertThat(audits.getFirst().get("entity_type")).isEqualTo("Client");
        assertThat(audits.getFirst().get("principal_id")).isEqualTo(PRINCIPAL);
        var opJson = json(audits.getFirst().get("operation_json", String.class));
        assertThat(opJson.get("name").asText()).isEqualTo("  Acme Corp  ");
        assertThat(opJson.get("identifier").asText()).as("the audit stores the command as sent").contains("CL-CREATE");
    }

    static Stream<Arguments> malformedCreateCommands() {
        return Stream.of(
                Arguments.of("null name", new CreateCommand(null, "cl-noname"), "NAME_REQUIRED"),
                Arguments.of("blank name", new CreateCommand("   ", "cl-noname"), "NAME_REQUIRED"),
                Arguments.of("null identifier", new CreateCommand("X", null), "IDENTIFIER_REQUIRED"),
                Arguments.of("blank identifier", new CreateCommand("X", "  "), "IDENTIFIER_REQUIRED"),
                Arguments.of("underscore", new CreateCommand("X", "my_client"), "INVALID_IDENTIFIER"),
                Arguments.of("leading hyphen", new CreateCommand("X", "-abc"), "INVALID_IDENTIFIER"),
                Arguments.of("trailing hyphen", new CreateCommand("X", "abc-"), "INVALID_IDENTIFIER"),
                Arguments.of("inner space", new CreateCommand("X", "a b"), "INVALID_IDENTIFIER"),
                Arguments.of("reserved platform identifier", new CreateCommand("X", "platform"), "RESERVED_IDENTIFIER"),
                Arguments.of("reserved platform identifier, mixed case", new CreateCommand("X", "Platform"), "RESERVED_IDENTIFIER"));
    }

    @ParameterizedTest(name = "{0} → {2}")
    @MethodSource("malformedCreateCommands")
    void createRejectsAMalformedCommand(String label, CreateCommand cmd, String expectedCode) {
        assertUseCaseError(() -> runAsAnchor(CreateClient.of(repo), cmd), UseCaseError.Validation.class, expectedCode);
    }

    /// Uniqueness applies to the normalised identifier: the duplicate is submitted upper-case.
    @Test
    void createRejectsADuplicateIdentifierCaseInsensitively() {
        created("First", ident("cl-dup"));
        assertUseCaseError(() -> runAsAnchor(CreateClient.of(repo), new CreateCommand("Second", ident("cl-dup").toUpperCase(Locale.ROOT))),
                UseCaseError.Conflict.class, "IDENTIFIER_EXISTS");
        assertThatThrownBy(() -> runAsAnchor(CreateClient.of(repo), new CreateCommand("Second", ident("cl-dup"))))
                .hasMessageContaining("Client with identifier '" + ident("cl-dup") + "' already exists");
    }

    // ── Update ─────────────────────────────────────────────────────────────

    @Test
    void updateRenamesTrimmedButNeverTouchesTheIdentifier() {
        var seeded = created("Before", ident("cl-upd"));

        var ev = runAsAnchor(UpdateClient.of(repo), new UpdateCommand(seeded.clientId(), "  After  "));
        assertThat(ev.clientId()).isEqualTo(seeded.clientId());
        assertThat(ev.name()).as("name is trimmed").isEqualTo("After");
        assertThat(ev.eventType()).isEqualTo(ClientEvents.UPDATED);
        assertThat(ev.messageGroup()).isEqualTo("platform:client:" + seeded.clientId());

        var got = reload(seeded.clientId());
        assertThat(got.name()).isEqualTo("After");
        assertThat(got.identifier()).as("identifier is immutable on update").isEqualTo(ident("cl-upd"));

        assertThat(eventsFor(seeded.clientId(), ClientEvents.UPDATED)).hasSize(1);
        assertThat(auditsFor(seeded.clientId(), "UpdateCommand")).hasSize(1);
    }

    /// Spec §3, open question 4: a name-less update is a no-op write that still emits.
    @Test
    void updateWithoutANameKeepsTheNameAndStillEmits() {
        var seeded = created("Keep Me", ident("cl-updnoop"));
        var ev = runAsAnchor(UpdateClient.of(repo), new UpdateCommand(seeded.clientId(), null));
        assertThat(ev.name()).isEqualTo("Keep Me");
        assertThat(reload(seeded.clientId()).name()).isEqualTo("Keep Me");
        assertThat(eventsFor(seeded.clientId(), ClientEvents.UPDATED)).hasSize(1);
    }

    static Stream<Arguments> badUpdateCommands() {
        return Stream.of(
                Arguments.of("missing id", new UpdateCommand(null, "X"), UseCaseError.Validation.class, "ID_REQUIRED"),
                Arguments.of("blank name", new UpdateCommand("clt_doesnotexist1", " "), UseCaseError.Validation.class, "NAME_REQUIRED"),
                Arguments.of("unknown id", new UpdateCommand("clt_doesnotexist1", "X"), UseCaseError.NotFound.class, "Client_NOT_FOUND"));
    }

    @ParameterizedTest(name = "{0} → {3}")
    @MethodSource("badUpdateCommands")
    void updateRejectsMissingIdBlankNameOrUnknownRow(String label, UpdateCommand cmd, Class<? extends UseCaseError> kind, String code) {
        assertUseCaseError(() -> runAsAnchor(UpdateClient.of(repo), cmd), kind, code);
    }

    // ── Delete ─────────────────────────────────────────────────────────────

    @Test
    void deleteRemovesTheRowAndEmits() {
        var seeded = created("Doomed", ident("cl-del"));

        var ev = runAsAnchor(DeleteClient.of(repo), new DeleteCommand(seeded.clientId()));
        assertThat(ev.clientId()).isEqualTo(seeded.clientId());
        assertThat(ev.identifier()).isEqualTo(ident("cl-del"));
        assertThat(ev.eventType()).isEqualTo(ClientEvents.DELETED);

        assertThat(repo.findById(seeded.clientId())).as("deleted row must be gone").isEmpty();
        assertThat(repo.findByIdentifier(ident("cl-del"))).isEmpty();
        assertThat(eventsFor(seeded.clientId(), ClientEvents.DELETED)).hasSize(1);
        assertThat(auditsFor(seeded.clientId(), "DeleteCommand")).hasSize(1);
    }

    @Test
    void deleteRejectsMissingIdOrUnknownRow() {
        assertUseCaseError(() -> runAsAnchor(DeleteClient.of(repo), new DeleteCommand("")),
                UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(DeleteClient.of(repo), new DeleteCommand("clt_doesnotexist1")),
                UseCaseError.NotFound.class, "Client_NOT_FOUND");
    }

    // ── Suspend → Activate ─────────────────────────────────────────────────

    @Test
    void suspendThenActivateRoundTripsThroughTheEnvelope() {
        var seeded = created("Suspend Me", ident("cl-susp"));

        var suspended = runAsAnchor(SuspendClient.of(repo), new SuspendCommand(seeded.clientId(), "billing overdue"));
        assertThat(suspended.clientId()).isEqualTo(seeded.clientId());
        assertThat(suspended.reason()).isEqualTo("billing overdue");
        assertThat(suspended.eventType()).isEqualTo(ClientEvents.SUSPENDED);

        var got = reload(seeded.clientId());
        assertThat(got.status()).isEqualTo(ClientStatus.SUSPENDED);
        assertThat(got.statusReason()).isEqualTo("billing overdue");
        assertThat(got.statusChangedAt()).isNotNull();
        var suspendedData = json(eventsFor(seeded.clientId(), ClientEvents.SUSPENDED).getFirst().get("data", String.class));
        assertThat(suspendedData.get("reason").asText()).isEqualTo("billing overdue");
        assertThat(auditsFor(seeded.clientId(), "SuspendCommand")).hasSize(1);

        var activated = runAsAnchor(ActivateClient.of(repo), new ActivateCommand(seeded.clientId()));
        assertThat(activated.clientId()).isEqualTo(seeded.clientId());
        assertThat(activated.eventType()).isEqualTo(ClientEvents.ACTIVATED);

        got = reload(seeded.clientId());
        assertThat(got.status()).isEqualTo(ClientStatus.ACTIVE);
        assertThat(got.statusReason()).as("activation clears the suspension reason").isNull();
        assertThat(got.statusChangedAt()).isNotNull();
        assertThat(eventsFor(seeded.clientId(), ClientEvents.ACTIVATED)).hasSize(1);
        assertThat(auditsFor(seeded.clientId(), "ActivateCommand")).hasSize(1);
    }

    static Stream<Arguments> badSuspendCommands() {
        return Stream.of(
                Arguments.of("missing id", new SuspendCommand(null, "r"), UseCaseError.Validation.class, "ID_REQUIRED"),
                Arguments.of("missing reason", new SuspendCommand("clt_doesnotexist1", null), UseCaseError.Validation.class, "REASON_REQUIRED"),
                Arguments.of("blank reason", new SuspendCommand("clt_doesnotexist1", "  "), UseCaseError.Validation.class, "REASON_REQUIRED"),
                Arguments.of("unknown id", new SuspendCommand("clt_doesnotexist1", "r"), UseCaseError.NotFound.class, "Client_NOT_FOUND"));
    }

    @ParameterizedTest(name = "{0} → {3}")
    @MethodSource("badSuspendCommands")
    void suspendRejectsMissingFieldsOrUnknownRow(String label, SuspendCommand cmd, Class<? extends UseCaseError> kind, String code) {
        assertUseCaseError(() -> runAsAnchor(SuspendClient.of(repo), cmd), kind, code);
    }

    @Test
    void activateRejectsMissingIdOrUnknownRow() {
        assertUseCaseError(() -> runAsAnchor(ActivateClient.of(repo), new ActivateCommand(" ")),
                UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(ActivateClient.of(repo), new ActivateCommand("clt_doesnotexist1")),
                UseCaseError.NotFound.class, "Client_NOT_FOUND");
    }

    // ── AddNote ────────────────────────────────────────────────────────────

    @Test
    void addNotePersistsTheNoteWithTheActingPrincipal() {
        var seeded = created("Note Me", ident("cl-note"));

        var ev = runAsAnchor(AddNote.of(repo), new AddNoteCommand(seeded.clientId(), "billing", "switched to annual plan"));
        assertThat(ev.clientId()).isEqualTo(seeded.clientId());
        assertThat(ev.category()).isEqualTo("billing");
        assertThat(ev.text()).isEqualTo("switched to annual plan");
        assertThat(ev.eventType()).isEqualTo(ClientEvents.NOTE_ADDED);

        runAsAnchor(AddNote.of(repo), new AddNoteCommand(seeded.clientId(), "support", "ticket 42"));

        var got = reload(seeded.clientId());
        assertThat(got.notes()).as("notes persist on reload, in order").hasSize(2);
        assertThat(got.notes().getFirst().category()).isEqualTo("billing");
        assertThat(got.notes().getFirst().text()).isEqualTo("switched to annual plan");
        assertThat(got.notes().getFirst().addedBy()).isEqualTo(PRINCIPAL);
        assertThat(got.notes().getFirst().addedAt()).isNotNull();
        assertThat(got.notes().get(1).category()).isEqualTo("support");

        // The stored JSON carries the note field names verbatim (jsonb reorders keys).
        var stored = json(DB.fetchOne("SELECT notes::text AS notes FROM tnt_clients WHERE id = ?", seeded.clientId()).get("notes", String.class));
        assertThat(stored.isArray()).isTrue();
        assertThat(stored.get(0).propertyNames()).containsExactlyInAnyOrder("category", "text", "addedBy", "addedAt");

        // Two NOTE_ADDED events, and eventsFor has no ORDER BY (msg_events is
        // partitioned; Postgres promises no row order) — so find the billing
        // one by content rather than assuming it comes back first.
        var events = eventsFor(seeded.clientId(), ClientEvents.NOTE_ADDED);
        assertThat(events).hasSize(2);
        var data = events.stream().map(r -> json(r.get("data", String.class)))
                .filter(d -> "billing".equals(d.get("category").asText()))
                .findFirst().orElseThrow(() -> new AssertionError("no billing NOTE_ADDED event"));
        assertThat(data.get("text").asText()).isEqualTo("switched to annual plan");
        assertThat(auditsFor(seeded.clientId(), "AddNoteCommand")).hasSize(2);
    }

    static Stream<Arguments> badAddNoteCommands() {
        return Stream.of(
                Arguments.of("missing id", new AddNoteCommand(null, "c", "t"), UseCaseError.Validation.class, "ID_REQUIRED"),
                Arguments.of("missing category", new AddNoteCommand("clt_doesnotexist1", null, "t"), UseCaseError.Validation.class, "CATEGORY_REQUIRED"),
                Arguments.of("missing text", new AddNoteCommand("clt_doesnotexist1", "c", " "), UseCaseError.Validation.class, "TEXT_REQUIRED"),
                Arguments.of("unknown id", new AddNoteCommand("clt_doesnotexist1", "c", "t"), UseCaseError.NotFound.class, "Client_NOT_FOUND"));
    }

    @ParameterizedTest(name = "{0} → {3}")
    @MethodSource("badAddNoteCommands")
    void addNoteRejectsMissingFieldsOrUnknownRow(String label, AddNoteCommand cmd, Class<? extends UseCaseError> kind, String code) {
        assertUseCaseError(() -> runAsAnchor(AddNote.of(repo), cmd), kind, code);
    }

    // ── Repository reads ───────────────────────────────────────────────────

    @Test
    void searchIsCaseInsensitiveContainsOnNameOrIdentifierOrderedByIdentifier() {
        var b = created("Zeta Widgets " + RUN, ident("srch") + "-b");
        var a = created("Alpha Gadgets " + RUN, ident("srch") + "-a");

        assertThat(repo.search(ident("SRCH"))).extracting(Client::id).as("ordered by identifier")
                .containsExactly(a.clientId(), b.clientId());
        assertThat(repo.search("gadgets " + RUN)).extracting(Client::id).as("matches the name, any case")
                .containsExactly(a.clientId());
        assertThat(repo.search("nomatch-" + RUN)).isEmpty();
        assertThat(repo.search("")).as("empty term → up to the limit").hasSizeLessThanOrEqualTo(ClientRepository.SEARCH_LIMIT);
        assertThat(repo.findAll()).extracting(Client::id).contains(a.clientId(), b.clientId());
        assertThat(repo.findByIdentifier(ident("srch") + "-a")).map(Client::id).contains(a.clientId());
        assertThat(repo.findByIdentifier(ident("SRCH") + "-A")).as("by-identifier is exact on the stored form").isEmpty();
    }

    /// Spec §1/§8: the stored `notes` JSON is the schema's, not ours — a row
    /// written by another writer (RFC 3339 with an offset or nanoseconds, no
    /// `addedBy`, a `NULL` column) must read back, and a `NULL` / `[]` column
    /// is an empty list.
    @Test
    void notesColumnReadsAnyRfc3339ShapeAndNullAsEmpty() {
        String id = EntityType.CLIENT.generate();
        String notes = """
                [{"category":"billing","text":"annual","addedBy":"prn_go","addedAt":"2025-01-02T03:04:05.123456789+02:00"},
                 {"category":"support","text":"ticket 42","addedAt":"2025-01-02T03:04:05Z"}]""";
        DB.execute("INSERT INTO tnt_clients (id, name, identifier, status, notes) VALUES (?, ?, ?, 'ACTIVE', ?::jsonb)",
                id, "Foreign Notes", ident("cl-foreign"), notes);

        var got = reload(id);
        assertThat(got.notes()).hasSize(2);
        assertThat(got.notes().getFirst().addedBy()).isEqualTo("prn_go");
        assertThat(got.notes().getFirst().addedAt()).as("offset applied, fraction truncated to microseconds")
                .isEqualTo(Instant.parse("2025-01-02T01:04:05.123456Z"));
        assertThat(got.notes().get(1).addedBy()).as("absent addedBy reads as null").isNull();
        assertThat(got.notes().get(1).addedAt()).isEqualTo(Instant.parse("2025-01-02T03:04:05Z"));

        DB.execute("UPDATE tnt_clients SET notes = NULL WHERE id = ?", id);
        assertThat(reload(id).notes()).as("NULL notes column → empty list").isEmpty();
    }
}
