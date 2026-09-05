package io.flowcatalyst.platform.process.operations;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.process.Process;
import io.flowcatalyst.platform.process.ProcessRepository;
import io.flowcatalyst.platform.process.ProcessRepository.ListFilter;
import io.flowcatalyst.platform.process.ProcessSource;
import io.flowcatalyst.platform.process.ProcessStatus;
import io.flowcatalyst.platform.process.operations.ProcessEvents.ProcessCreated;
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

/// The process use cases against the embedded Postgres (spec §4–9):
/// validation, the sync's application-level authorization, persistence, and
/// the envelope's guarantee that an aggregate write lands together with its
/// `msg_events` and `aud_logs` rows. The pure transition rules are covered
/// by `ProcessTest`; here each one is exercised once through the envelope.
///
/// The fixture never truncates (and the seeder leaves one example row), so
/// every test owns its rows: codes are namespaced by a per-JVM suffix on the
/// application segment.
@SuppressWarnings("deprecation")
class ProcessOperationsTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final ProcessRepository repo = new ProcessRepository(DS);
    private static final UnitOfWork uow = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));

    /// Per-JVM namespace so codes never collide with another run on the same database.
    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final String PRINCIPAL = EntityType.PRINCIPAL.generate();
    private static final AuthContext ANCHOR = new AuthContext(PRINCIPAL, Scope.ANCHOR, "anchor@x.io",
            List.of("*"), List.of(), List.of(), true, List.of());
    private static final ExecutionContext EC = ExecutionContext.of(PRINCIPAL);

    // ── Fixture ────────────────────────────────────────────────────────────

    /// Drives `op` through the full envelope as an all-applications anchor —
    /// the common case; the sync authorization test binds its own context.
    private static <C, E extends DomainEvent> E runAsAnchor(Operation<C, E> op, C cmd) {
        return Auth.runAs(ANCHOR, () -> op.run(uow, cmd, EC));
    }

    /// `{tag}{RUN}` — the application segment namespaces the test.
    private static String app(String tag) {
        return tag + RUN;
    }

    private static String code(String tag, String processName) {
        return app(tag) + ":orders:" + processName;
    }

    private static ProcessCreated created(String code, String name) {
        return runAsAnchor(CreateProcess.of(repo), new CreateCommand(code, name, null, null, null, null));
    }

    private static Process reload(String id) {
        return repo.findById(id).orElseThrow(() -> new AssertionError("process " + id + " not found"));
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
    private static Result<Record> eventsFor(String processId, String type) {
        return DB.fetch("SELECT type, subject, source, message_group, data::text AS data, deduplication_id FROM msg_events WHERE subject = ? AND type = ?",
                ProcessEvents.subjectFor(processId), type);
    }

    /// `aud_logs` rows for one aggregate and command.
    private static Result<Record> auditsFor(String processId, String operation) {
        return DB.fetch("SELECT entity_type, entity_id, operation, operation_json::text AS operation_json, principal_id FROM aud_logs WHERE entity_id = ? AND operation = ?",
                processId, operation);
    }

    // ── Create ─────────────────────────────────────────────────────────────

    @Test
    void createWritesTheRowTheEventAndTheAuditTogether() {
        String code = code("prcreate", "fulfilment");
        var ev = runAsAnchor(CreateProcess.of(repo), new CreateCommand(code, "  Order Fulfilment  ",
                "How orders get fulfilled", "graph TD; A-->B", null, List.of("orders", "core")));

        assertThat(ev.processId()).startsWith("prc_");
        assertThat(ev.code()).isEqualTo(code);
        assertThat(ev.name()).as("name is trimmed").isEqualTo("Order Fulfilment");
        assertThat(ev.eventType()).isEqualTo(ProcessEvents.CREATED);
        assertThat(ev.source()).isEqualTo(ProcessEvents.SOURCE);
        assertThat(ev.subject()).isEqualTo(ProcessEvents.subjectFor(ev.processId()));
        assertThat(ev.messageGroup()).isEqualTo("platform:process:" + ev.processId());

        var got = reload(ev.processId());
        assertThat(got.status()).isEqualTo(ProcessStatus.CURRENT);
        assertThat(got.source()).isEqualTo(ProcessSource.UI);
        assertThat(got.application()).isEqualTo(app("prcreate"));
        assertThat(got.subdomain()).isEqualTo("orders");
        assertThat(got.processName()).isEqualTo("fulfilment");
        assertThat(got.description()).isEqualTo("How orders get fulfilled");
        assertThat(got.body()).isEqualTo("graph TD; A-->B");
        assertThat(got.diagramType()).as("diagram type defaults to mermaid").isEqualTo("mermaid");
        assertThat(got.tags()).containsExactly("orders", "core");
        assertThat(got.createdBy()).as("no column — never persisted (spec §1)").isNull();

        var events = eventsFor(ev.processId(), ProcessEvents.CREATED);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().get("source")).isEqualTo(ProcessEvents.SOURCE);
        assertThat(events.getFirst().get("message_group")).isEqualTo("platform:process:" + ev.processId());
        assertThat(events.getFirst().get("deduplication_id")).isEqualTo(ProcessEvents.CREATED + "-" + ev.eventId());
        var data = json(events.getFirst().get("data", String.class));
        assertThat(data.get("processId").asText()).isEqualTo(ev.processId());
        assertThat(data.get("code").asText()).isEqualTo(code);
        assertThat(data.get("name").asText()).isEqualTo("Order Fulfilment");
        assertThat(data.propertyNames()).containsExactlyInAnyOrder("processId", "code", "name");

        var audits = auditsFor(ev.processId(), "CreateCommand");
        assertThat(audits).hasSize(1);
        assertThat(audits.getFirst().get("entity_type")).isEqualTo("Process");
        assertThat(audits.getFirst().get("principal_id")).isEqualTo(PRINCIPAL);
        var opJson = json(audits.getFirst().get("operation_json", String.class));
        assertThat(opJson.get("code").asText()).isEqualTo(code);
        assertThat(opJson.get("tags")).extracting(JsonNode::asText).containsExactly("orders", "core");
    }

    /// Deliberately tagless and bodiless: the `NOT NULL` columns get their
    /// defaults from the aggregate, not from the caller.
    @Test
    void createAppliesTheDomainDefaults() {
        var ev = created(code("prdefaults", "bare"), "Bare");
        var got = reload(ev.processId());
        assertThat(got.body()).isEmpty();
        assertThat(got.diagramType()).isEqualTo(Process.DEFAULT_DIAGRAM_TYPE);
        assertThat(got.tags()).isEmpty();
        assertThat(got.description()).isNull();

        var explicit = runAsAnchor(CreateProcess.of(repo),
                new CreateCommand(code("prdefaults", "plantuml"), "X", null, null, "plantuml", null));
        assertThat(reload(explicit.processId()).diagramType()).isEqualTo("plantuml");
    }

    static Stream<Arguments> malformedCreateCommands() {
        return Stream.of(
                Arguments.of("null code", new CreateCommand(null, "X", null, null, null, null), "CODE_REQUIRED"),
                Arguments.of("blank code", new CreateCommand("  ", "X", null, null, null, null), "CODE_REQUIRED"),
                Arguments.of("four segments", new CreateCommand("a:b:c:d", "X", null, null, null, null), "INVALID_CODE_FORMAT"),
                Arguments.of("two segments", new CreateCommand("a:b", "X", null, null, null, null), "INVALID_CODE_FORMAT"),
                Arguments.of("blank segment", new CreateCommand("a: :c", "X", null, null, null, null), "INVALID_CODE_FORMAT"),
                Arguments.of("null name", new CreateCommand("a:b:c", null, null, null, null, null), "NAME_REQUIRED"),
                Arguments.of("blank name", new CreateCommand("a:b:c", "  ", null, null, null, null), "NAME_REQUIRED"),
                Arguments.of("bad code beats missing name", new CreateCommand("a:b", null, null, null, null, null), "INVALID_CODE_FORMAT"));
    }

    @ParameterizedTest(name = "{0} → {2}")
    @MethodSource("malformedCreateCommands")
    void createRejectsAMalformedCommand(String label, CreateCommand cmd, String expectedCode) {
        assertUseCaseError(() -> runAsAnchor(CreateProcess.of(repo), cmd), UseCaseError.Validation.class, expectedCode);
    }

    @Test
    void createRejectsADuplicateCode() {
        String code = code("prdup", "flow");
        created(code, "First");
        assertThatThrownBy(() -> runAsAnchor(CreateProcess.of(repo), new CreateCommand(code, "Second", null, null, null, null)))
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).isInstanceOf(UseCaseError.Conflict.class);
                    assertThat(err.code()).isEqualTo("CODE_EXISTS");
                    assertThat(err.message()).isEqualTo("Process with code '" + code + "' already exists");
                });
    }

    // ── Update ─────────────────────────────────────────────────────────────

    @Test
    void updateReplacesTheSuppliedFieldsButNotTheCode() {
        String code = code("prupd", "flow");
        var seeded = created(code, "Before");

        var ev = runAsAnchor(UpdateProcess.of(repo), new UpdateCommand(seeded.processId(), "  After  ", "after",
                "graph LR; X-->Y", "plantuml", List.of("updated")));
        assertThat(ev.processId()).isEqualTo(seeded.processId());
        assertThat(ev.name()).as("name is trimmed").isEqualTo("After");
        assertThat(ev.eventType()).isEqualTo(ProcessEvents.UPDATED);

        var got = reload(seeded.processId());
        assertThat(got.name()).isEqualTo("After");
        assertThat(got.description()).isEqualTo("after");
        assertThat(got.body()).isEqualTo("graph LR; X-->Y");
        assertThat(got.diagramType()).isEqualTo("plantuml");
        assertThat(got.tags()).containsExactly("updated");
        assertThat(got.code()).as("code is immutable on update").isEqualTo(code);

        // Absent fields stay; an empty tag list clears.
        runAsAnchor(UpdateProcess.of(repo), new UpdateCommand(seeded.processId(), null, null, null, null, List.of()));
        var partial = reload(seeded.processId());
        assertThat(partial.name()).isEqualTo("After");
        assertThat(partial.body()).isEqualTo("graph LR; X-->Y");
        assertThat(partial.tags()).isEmpty();

        assertThat(eventsFor(seeded.processId(), ProcessEvents.UPDATED)).hasSize(2);
        assertThat(json(eventsFor(seeded.processId(), ProcessEvents.UPDATED).getFirst().get("data", String.class)).propertyNames())
                .containsExactlyInAnyOrder("processId", "name");
        assertThat(auditsFor(seeded.processId(), "UpdateCommand")).hasSize(2);
    }

    @Test
    void updateRejectsMissingIdBlankNameOrRow() {
        assertUseCaseError(() -> runAsAnchor(UpdateProcess.of(repo), new UpdateCommand(null, "X", null, null, null, null)),
                UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(UpdateProcess.of(repo), new UpdateCommand("prc_doesnotexist1", " ", null, null, null, null)),
                UseCaseError.Validation.class, "NAME_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(UpdateProcess.of(repo), new UpdateCommand("prc_doesnotexist1", "X", null, null, null, null)),
                UseCaseError.NotFound.class, "Process_NOT_FOUND");
        assertUseCaseError(() -> runAsAnchor(UpdateProcess.of(repo), new UpdateCommand("prc_doesnotexist1", null, null, null, null, null)),
                UseCaseError.NotFound.class, "Process_NOT_FOUND");
    }

    // ── Delete ─────────────────────────────────────────────────────────────

    @Test
    void deleteRemovesTheRowAndWritesTheEventAndAudit() {
        String code = code("prdel", "flow");
        var seeded = created(code, "Doomed");

        var ev = runAsAnchor(DeleteProcess.of(repo), new DeleteCommand(seeded.processId()));
        assertThat(ev.processId()).isEqualTo(seeded.processId());
        assertThat(ev.code()).isEqualTo(code);
        assertThat(ev.eventType()).isEqualTo(ProcessEvents.DELETED);

        assertThat(repo.findById(seeded.processId())).as("deleted row must be gone").isEmpty();
        var events = eventsFor(seeded.processId(), ProcessEvents.DELETED);
        assertThat(events).hasSize(1);
        assertThat(json(events.getFirst().get("data", String.class)).get("code").asText()).isEqualTo(code);
        assertThat(auditsFor(seeded.processId(), "DeleteCommand")).hasSize(1);
    }

    @Test
    void deleteRejectsMissingIdOrRow() {
        assertUseCaseError(() -> runAsAnchor(DeleteProcess.of(repo), new DeleteCommand("")),
                UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(DeleteProcess.of(repo), new DeleteCommand("prc_doesnotexist1")),
                UseCaseError.NotFound.class, "Process_NOT_FOUND");
    }

    // ── Archive ────────────────────────────────────────────────────────────

    /// Deliberately tagless: the reload → persist round-trip must write the
    /// `NOT NULL` tags column as an empty array.
    @Test
    void archiveIsPersistedAuditedAndRepeatable() {
        var seeded = created(code("prarc", "flow"), "Archive Me");

        var ev = runAsAnchor(ArchiveProcess.of(repo), new ArchiveCommand(seeded.processId()));
        assertThat(ev.processId()).isEqualTo(seeded.processId());
        assertThat(ev.code()).isEqualTo(seeded.code());
        assertThat(ev.eventType()).isEqualTo(ProcessEvents.ARCHIVED);
        assertThat(reload(seeded.processId()).status()).isEqualTo(ProcessStatus.ARCHIVED);
        assertThat(auditsFor(seeded.processId(), "ArchiveCommand")).hasSize(1);

        // Unconditional (spec §2, open question 2): a second archive succeeds and is recorded again.
        runAsAnchor(ArchiveProcess.of(repo), new ArchiveCommand(seeded.processId()));
        assertThat(eventsFor(seeded.processId(), ProcessEvents.ARCHIVED)).hasSize(2);

        assertUseCaseError(() -> runAsAnchor(ArchiveProcess.of(repo), new ArchiveCommand(" ")),
                UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(ArchiveProcess.of(repo), new ArchiveCommand("prc_doesnotexist1")),
                UseCaseError.NotFound.class, "Process_NOT_FOUND");
    }

    // ── Sync ───────────────────────────────────────────────────────────────

    @Test
    void syncCreatesUpdatesAndRemovesOnlySyncManagedRows() {
        String application = app("prsync");
        String appId = EntityType.APPLICATION.generate();
        // UI-sourced row in the same application scope: sync must NEVER touch it.
        var uiRow = created(application + ":ui:kept", "UI Kept");

        var first = runAsAnchor(SyncProcesses.of(repo), new SyncProcessesCommand(application, appId, List.of(
                new SyncProcessInput(application + ":orders:flow-a", "A", "desc a", "graph TD; A", null, List.of("a")),
                new SyncProcessInput(application + ":orders:flow-b", "B", null, null, "plantuml", null),
                new SyncProcessInput(application + ":ui:kept", "UI renamed?", null, null, null, null)), false));
        assertThat(first.applicationCode()).isEqualTo(application);
        assertThat(first.created()).isEqualTo(2);
        assertThat(first.updated()).as("the UI row is skipped, not counted").isZero();
        assertThat(first.deleted()).isZero();
        assertThat(first.syncedCodes()).as("every input code, skipped ones included")
                .containsExactly(application + ":orders:flow-a", application + ":orders:flow-b", application + ":ui:kept");
        assertThat(first.eventType()).isEqualTo(ProcessEvents.SYNCED);
        assertThat(first.subject()).isEqualTo(ProcessEvents.syncSubjectFor(application));
        // X-08 (ruled 2026-09-01): one FIFO lane per application.
        assertThat(first.messageGroup()).isEqualTo(ProcessEvents.SYNC_MESSAGE_GROUP + ":" + application);

        var a = repo.findByCode(application + ":orders:flow-a").orElseThrow();
        assertThat(a.source()).as("sync-created rows are API-sourced").isEqualTo(ProcessSource.API);
        assertThat(a.description()).isEqualTo("desc a");
        assertThat(a.body()).isEqualTo("graph TD; A");
        assertThat(a.diagramType()).isEqualTo("mermaid");
        assertThat(a.tags()).containsExactly("a");
        var b = repo.findByCode(application + ":orders:flow-b").orElseThrow();
        assertThat(b.body()).isEmpty();
        assertThat(b.diagramType()).isEqualTo("plantuml");
        assertThat(b.tags()).isEmpty();
        assertThat(reload(uiRow.processId()).name()).as("UI row untouched by a matching input").isEqualTo("UI Kept");

        // Declarative overwrite (absent tags ⇒ none, blank diagram type ⇒ kept) + removeUnlisted.
        var second = runAsAnchor(SyncProcesses.of(repo), new SyncProcessesCommand(application, appId, List.of(
                new SyncProcessInput(application + ":orders:flow-b", " B renamed ", null, null, " ", null)), true));
        assertThat(second.created()).isZero();
        assertThat(second.updated()).isEqualTo(1);
        assertThat(second.deleted()).isEqualTo(1);

        var kept = repo.findByCode(application + ":orders:flow-b").orElseThrow();
        assertThat(kept.name()).as("sync stores the name verbatim").isEqualTo(" B renamed ");
        assertThat(kept.diagramType()).as("blank diagram type keeps the stored value").isEqualTo("plantuml");
        assertThat(kept.source()).isEqualTo(ProcessSource.API);
        assertThat(repo.findByCode(application + ":orders:flow-a")).as("unlisted API row must be deleted").isEmpty();
        assertThat(repo.findById(uiRow.processId())).as("removeUnlisted must never touch UI-sourced rows").isPresent();

        // Per-row events + the rollup, each with an audit row naming the sync command.
        assertThat(eventsFor(kept.id(), ProcessEvents.CREATED)).hasSize(1);
        assertThat(eventsFor(kept.id(), ProcessEvents.UPDATED)).hasSize(1);
        assertThat(eventsFor(a.id(), ProcessEvents.DELETED)).hasSize(1);
        assertThat(auditsFor(kept.id(), "SyncProcessesCommand")).hasSize(2);
        var rollups = DB.fetch("SELECT type, message_group, data::text AS data FROM msg_events WHERE subject = ? AND type = ?",
                ProcessEvents.syncSubjectFor(application), ProcessEvents.SYNCED);
        assertThat(rollups).hasSize(2);
        assertThat(rollups.getFirst().get("message_group")).isEqualTo("platform:processes:" + application);
        assertThat(json(rollups.getFirst().get("data", String.class)).propertyNames())
                .containsExactlyInAnyOrder("applicationCode", "created", "updated", "deleted", "syncedCodes");
    }

    /// X-08: two applications' syncs land in two different FIFO lanes.
    @Test
    void syncOfDifferentApplicationsProduceDifferentMessageGroups() {
        String appOne = app("prgrp1");
        String appTwo = app("prgrp2");
        var one = runAsAnchor(SyncProcesses.of(repo), new SyncProcessesCommand(appOne, EntityType.APPLICATION.generate(),
                List.of(new SyncProcessInput(appOne + ":sub:a", "A", null, null, null, null)), false));
        var two = runAsAnchor(SyncProcesses.of(repo), new SyncProcessesCommand(appTwo, EntityType.APPLICATION.generate(),
                List.of(new SyncProcessInput(appTwo + ":sub:a", "A", null, null, null, null)), false));
        assertThat(one.messageGroup()).isEqualTo(ProcessEvents.SYNC_MESSAGE_GROUP + ":" + appOne);
        assertThat(two.messageGroup()).isEqualTo(ProcessEvents.SYNC_MESSAGE_GROUP + ":" + appTwo);
        assertThat(one.messageGroup()).as("mutant: revert ProcessesSynced#messageGroup to the shared constant")
                .isNotEqualTo(two.messageGroup());
    }

    /// `CODE`-sourced rows (the seeded catalogue) are sync-managed too (spec §7, open question 3).
    @Test
    void syncManagesCodeSourcedRows() {
        String application = app("prsynccode");
        String appId = EntityType.APPLICATION.generate();
        var seeded = created(application + ":seed:flow", "Seeded");
        // Flip the row to CODE through the repository (the seeder is the only production writer of CODE rows).
        uow.inTransaction(tx -> {
            repo.persist(reload(seeded.processId()).withSource(ProcessSource.CODE), tx.dbTx());
            return null;
        });

        var ev = runAsAnchor(SyncProcesses.of(repo), new SyncProcessesCommand(application, appId, List.of(
                new SyncProcessInput(application + ":seed:flow", "Seeded v2", null, "graph", null, null)), false));
        assertThat(ev.updated()).isEqualTo(1);
        var got = reload(seeded.processId());
        assertThat(got.name()).isEqualTo("Seeded v2");
        assertThat(got.source()).as("source is not rewritten to API").isEqualTo(ProcessSource.CODE);

        var removed = runAsAnchor(SyncProcesses.of(repo), new SyncProcessesCommand(application, appId, List.of(), true));
        assertThat(removed.deleted()).isEqualTo(1);
        assertThat(repo.findById(seeded.processId())).isEmpty();
    }

    @Test
    void syncRejectsAMissingApplicationCodeAndAbortsOnTheFirstBadRow() {
        assertUseCaseError(() -> runAsAnchor(SyncProcesses.of(repo), new SyncProcessesCommand(null, "app_x", List.of(), false)),
                UseCaseError.Validation.class, "APPLICATION_CODE_REQUIRED");
        assertThatThrownBy(() -> runAsAnchor(SyncProcesses.of(repo), new SyncProcessesCommand(app("prsyncbad"), "app_x",
                List.of(new SyncProcessInput(app("prsyncbad") + ":ok:row", "OK", null, null, null, null),
                        new SyncProcessInput("not-three-parts", "X", null, null, null, null)), false)))
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).isInstanceOf(UseCaseError.Validation.class);
                    assertThat(err.code()).isEqualTo("INVALID_PROCESS_CODE");
                    assertThat(err.message()).contains("(offending code: \"not-three-parts\")");
                });
        assertThat(repo.findByApplication(app("prsyncbad"))).as("a bad row aborts the whole sync").isEmpty();
    }

    /// The use case's resource-level authorization: a principal without
    /// access to the target application is denied before any write (the
    /// coarse "may sync" permission is the handler's separate gate).
    @Test
    void syncRequiresAccessToTheTargetApplication() {
        String application = app("prsyncna");
        String appId = EntityType.APPLICATION.generate();
        var cmd = new SyncProcessesCommand(application, appId,
                List.of(new SyncProcessInput(application + ":orders:x", "X", null, null, null, null)), false);

        var otherApp = new AuthContext(EntityType.PRINCIPAL.generate(), Scope.CLIENT, "c@x.io", List.of("cli_x"),
                List.of(), List.of("app_other"), false, List.of("platform:messaging:process:sync"));
        assertThatThrownBy(() -> Auth.runAs(otherApp, () -> SyncProcesses.of(repo).run(uow, cmd, ExecutionContext.of(otherApp.principalId()))))
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).isInstanceOf(UseCaseError.Authorization.class);
                    assertThat(err.code()).isEqualTo("FORBIDDEN");
                    assertThat(err.message()).isEqualTo("Not authorised for application '" + application + "'");
                });

        assertUseCaseError(() -> SyncProcesses.of(repo).run(uow, cmd, ExecutionContext.of(null)),
                UseCaseError.Authorization.class, "UNAUTHENTICATED");
        assertThat(repo.findByApplication(application)).as("denied before anything is written").isEmpty();

        // Explicit access to exactly this application suffices.
        var thisApp = new AuthContext(EntityType.PRINCIPAL.generate(), Scope.CLIENT, "c@x.io", List.of("cli_x"),
                List.of(), List.of(appId), false, List.of("platform:messaging:process:sync"));
        var ev = Auth.runAs(thisApp, () -> SyncProcesses.of(repo).run(uow, cmd, ExecutionContext.of(thisApp.principalId())));
        assertThat(ev.created()).isEqualTo(1);
    }

    // ── Repository reads ───────────────────────────────────────────────────

    @Test
    void listFiltersCombineAndResultsAreOrderedByCode() {
        String application = app("prlist");
        var a = created(application + ":orders:created", "A");
        var b = created(application + ":orders:updated", "B");
        var c = created(application + ":billing:sent", "C");
        runAsAnchor(ArchiveProcess.of(repo), new ArchiveCommand(b.processId()));

        var all = repo.findWithFilters(new ListFilter(application, null, null));
        assertThat(all).extracting(Process::code).as("ordered by code")
                .containsExactly(application + ":billing:sent", application + ":orders:created", application + ":orders:updated");

        assertThat(repo.findWithFilters(new ListFilter(application, null, "CURRENT"))).extracting(Process::id)
                .containsExactlyInAnyOrder(a.processId(), c.processId());
        assertThat(repo.findWithFilters(new ListFilter(application, null, "ARCHIVED"))).extracting(Process::id)
                .containsExactly(b.processId());
        assertThat(repo.findWithFilters(new ListFilter(application, "orders", null))).hasSize(2);
        assertThat(repo.findWithFilters(new ListFilter(application, "billing", "ARCHIVED"))).isEmpty();
        assertThat(repo.findByApplication(application)).hasSize(3);
        assertThat(repo.findByApplication(application + "none")).isEmpty();
        assertThat(repo.findWithFilters(new ListFilter(null, null, null))).extracting(Process::id)
                .as("unfiltered lists archived rows too (no implied default)")
                .contains(a.processId(), b.processId(), c.processId());
    }
}
