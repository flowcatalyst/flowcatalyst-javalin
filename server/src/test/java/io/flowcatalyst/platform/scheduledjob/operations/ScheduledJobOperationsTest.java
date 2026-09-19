package io.flowcatalyst.platform.scheduledjob.operations;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.NullNode;
import io.flowcatalyst.platform.scheduledjob.CorruptScheduledJobException;
import io.flowcatalyst.platform.scheduledjob.InstanceStatus;
import io.flowcatalyst.platform.scheduledjob.ScheduledJob;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobInstance;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobInstanceLog;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobInstanceRepository;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository.ClientFilter;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository.ListFilter;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobStatus;
import io.flowcatalyst.platform.scheduledjob.TriggerKind;
import io.flowcatalyst.platform.scheduledjob.operations.ScheduledJobEvents.ScheduledJobCreated;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Visibility;
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

/// The scheduled-job use cases against the embedded Postgres (spec §4–11):
/// validation, the per-resource authorization, persistence, the instance
/// projection, and the envelope's guarantee that an aggregate write lands
/// together with its `msg_events` and `aud_logs` rows. The pure rules are
/// covered by `ScheduledJobTest`; here each operation runs once through the
/// envelope.
///
/// The fixture never truncates, so every test owns its rows: codes carry a
/// per-JVM suffix and sync tests own a fresh client scope.
@SuppressWarnings("deprecation")
class ScheduledJobOperationsTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final ScheduledJobRepository repo = new ScheduledJobRepository(DS);
    private static final ScheduledJobInstanceRepository instances = new ScheduledJobInstanceRepository(DS);
    private static final UnitOfWork uow = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final String PRINCIPAL = EntityType.PRINCIPAL.generate();
    private static final AuthContext ANCHOR = new AuthContext(PRINCIPAL, Scope.ANCHOR, "anchor@x.io",
            List.of("*"), List.of(), List.of(), true, List.of());
    private static final ExecutionContext EC = ExecutionContext.of(PRINCIPAL);

    private static final List<String> HOURLY = List.of("0 0 * * * *");

    // ── Fixture ────────────────────────────────────────────────────────────

    private static <C, E extends DomainEvent> E runAsAnchor(Operation<C, E> op, C cmd) {
        return Auth.runAs(ANCHOR, () -> op.run(uow, cmd, EC));
    }

    /// `sj{RUN}-{tag}` — a code that is unique to this JVM run.
    private static String code(String tag) {
        return "sj" + RUN + "-" + tag;
    }

    private static CreateCommand createCommand(String code, String clientId) {
        return new CreateCommand(code, "Job " + code, HOURLY, null, clientId, null, null, null, false, false, null, null, null);
    }

    private static ScheduledJobCreated created(String tag) {
        return runAsAnchor(CreateScheduledJob.of(repo), createCommand(code(tag), null));
    }

    private static ScheduledJob reload(String id) {
        return repo.findById(id).orElseThrow(() -> new AssertionError("scheduled job " + id + " not found"));
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
    private static Result<Record> eventsFor(String jobId, String type) {
        return DB.fetch("SELECT type, subject, source, message_group, data::text AS data, deduplication_id FROM msg_events WHERE subject = ? AND type = ?",
                ScheduledJobEvents.subjectFor(jobId), type);
    }

    /// `aud_logs` rows for one aggregate and command.
    private static Result<Record> auditsFor(String jobId, String operation) {
        return DB.fetch("SELECT entity_type, entity_id, operation, operation_json::text AS operation_json, principal_id FROM aud_logs WHERE entity_id = ? AND operation = ?",
                jobId, operation);
    }

    // ── Create ─────────────────────────────────────────────────────────────

    @Test
    void createWritesTheRowTheEventAndTheAuditTogether() {
        String raw = "  SJ" + RUN.toUpperCase(Locale.ROOT) + "-Create-Happy  ";
        String code = code("create-happy");
        var ev = runAsAnchor(CreateScheduledJob.of(repo), new CreateCommand(raw, "  SJ Create Happy  ",
                List.of("0 0 3 * * *", "0 30 9 * * 1-5"), "Europe/Amsterdam", null, "app_" + RUN, "nightly refresh",
                json("{\"kind\":\"refresh\"}"), true, true, 120, 5, "https://jobs.example.test/fire"));

        assertThat(ev.scheduledJobId()).startsWith("sjb_");
        assertThat(ev.code()).as("code is trimmed + lower-cased").isEqualTo(code);
        assertThat(ev.eventType()).isEqualTo(ScheduledJobEvents.CREATED);
        assertThat(ev.source()).isEqualTo(ScheduledJobEvents.SOURCE);
        assertThat(ev.subject()).isEqualTo(ScheduledJobEvents.subjectFor(ev.scheduledJobId()));
        assertThat(ev.messageGroup()).isEqualTo("platform:scheduledjob:" + ev.scheduledJobId());

        var got = reload(ev.scheduledJobId());
        assertThat(got.code()).isEqualTo(code);
        assertThat(got.name()).as("name is trimmed").isEqualTo("SJ Create Happy");
        assertThat(got.status()).isEqualTo(ScheduledJobStatus.ACTIVE);
        assertThat(got.crons()).containsExactly("0 0 3 * * *", "0 30 9 * * 1-5");
        assertThat(got.timezone()).isEqualTo("Europe/Amsterdam");
        assertThat(got.applicationId()).isEqualTo("app_" + RUN);
        assertThat(got.description()).isEqualTo("nightly refresh");
        assertThat(got.payload()).isEqualTo(json("{\"kind\":\"refresh\"}"));
        assertThat(got.concurrent()).isTrue();
        assertThat(got.tracksCompletion()).isTrue();
        assertThat(got.timeoutSeconds()).isEqualTo(120);
        assertThat(got.deliveryMaxAttempts()).isEqualTo(5);
        assertThat(got.targetUrl()).isEqualTo("https://jobs.example.test/fire");
        assertThat(got.createdBy()).isEqualTo(PRINCIPAL);
        assertThat(got.updatedBy()).isNull();
        assertThat(got.version()).isEqualTo(1);
        assertThat(got.lastFiredAt()).isNull();

        var events = eventsFor(ev.scheduledJobId(), ScheduledJobEvents.CREATED);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().get("source")).isEqualTo(ScheduledJobEvents.SOURCE);
        assertThat(events.getFirst().get("message_group")).isEqualTo("platform:scheduledjob:" + ev.scheduledJobId());
        assertThat(events.getFirst().get("deduplication_id")).isEqualTo(ScheduledJobEvents.CREATED + "-" + ev.eventId());
        var data = json(events.getFirst().get("data", String.class));
        assertThat(data.get("scheduledJobId").asText()).isEqualTo(ev.scheduledJobId());
        assertThat(data.get("code").asText()).isEqualTo(code);
        assertThat(data.size()).as("the lifecycle payload is exactly {scheduledJobId, code}").isEqualTo(2);

        var audits = auditsFor(ev.scheduledJobId(), "CreateCommand");
        assertThat(audits).hasSize(1);
        assertThat(audits.getFirst().get("entity_type")).isEqualTo("Scheduledjob");
        assertThat(audits.getFirst().get("principal_id")).isEqualTo(PRINCIPAL);
        var opJson = json(audits.getFirst().get("operation_json", String.class));
        assertThat(opJson.get("code").asText()).isEqualTo(raw);
        assertThat(opJson.get("crons")).hasSize(2);
    }

    @Test
    void createAppliesTheDomainDefaults() {
        var ev = created("defaults");
        var got = reload(ev.scheduledJobId());
        assertThat(got.timezone()).isEqualTo("UTC");
        assertThat(got.deliveryMaxAttempts()).isEqualTo(3);
        assertThat(got.payload()).isNull();
        assertThat(got.isPlatformScoped()).isTrue();
    }

    static Stream<Arguments> malformedCreateCommands() {
        return Stream.of(
                Arguments.of("null code", new CreateCommand(null, "X", HOURLY, null, null, null, null, null, false, false, null, null, null), "CODE_REQUIRED"),
                Arguments.of("blank code", new CreateCommand("  ", "X", HOURLY, null, null, null, null, null, false, false, null, null, null), "CODE_REQUIRED"),
                Arguments.of("underscore code", new CreateCommand("sj_underscore", "X", HOURLY, null, null, null, null, null, false, false, null, null, null), "INVALID_CODE_FORMAT"),
                Arguments.of("digit-leading code", new CreateCommand("1sjcrt-bad", "X", HOURLY, null, null, null, null, null, false, false, null, null, null), "INVALID_CODE_FORMAT"),
                Arguments.of("null name", new CreateCommand("sjcrt-noname", null, HOURLY, null, null, null, null, null, false, false, null, null, null), "NAME_REQUIRED"),
                Arguments.of("blank name", new CreateCommand("sjcrt-noname", " ", HOURLY, null, null, null, null, null, false, false, null, null, null), "NAME_REQUIRED"),
                Arguments.of("null crons", new CreateCommand("sjcrt-nocron", "X", null, null, null, null, null, null, false, false, null, null, null), "CRONS_REQUIRED"),
                Arguments.of("empty crons", new CreateCommand("sjcrt-nocron", "X", List.of(), null, null, null, null, null, false, false, null, null, null), "CRONS_REQUIRED"),
                Arguments.of("blank cron", new CreateCommand("sjcrt-blankcron", "X", List.of("   "), null, null, null, null, null, false, false, null, null, null), "INVALID_CRON"),
                Arguments.of("three-field cron", new CreateCommand("sjcrt-shape3", "X", List.of("* * *"), null, null, null, null, null, false, false, null, null, null), "CRON_INVALID_SHAPE"),
                Arguments.of("five-field cron (ruling)", new CreateCommand("sjcrt-shape5", "X", List.of("* * * * *"), null, null, null, null, null, false, false, null, null, null), "CRON_INVALID_SHAPE"),
                Arguments.of("seven-field cron (ruling)", new CreateCommand("sjcrt-shape7", "X", List.of("0 0 0 * * * 2030"), null, null, null, null, null, false, false, null, null, null), "CRON_INVALID_SHAPE"),
                Arguments.of("eight-field cron", new CreateCommand("sjcrt-shape8", "X", List.of("0 0 0 1 1 1 2026 extra"), null, null, null, null, null, false, false, null, null, null), "CRON_INVALID_SHAPE"),
                Arguments.of("bad cron grammar", new CreateCommand("sjcrt-grammar", "X", List.of("60 * * * * *"), null, null, null, null, null, false, false, null, null, null), "INVALID_CRON"),
                Arguments.of("second bad cron", new CreateCommand("sjcrt-second", "X", List.of("0 0 * * * *", "@daily"), null, null, null, null, null, false, false, null, null, null), "INVALID_CRON"));
    }

    @ParameterizedTest(name = "{0} → {2}")
    @MethodSource("malformedCreateCommands")
    void createRejectsAMalformedCommand(String label, CreateCommand cmd, String expectedCode) {
        assertUseCaseError(() -> runAsAnchor(CreateScheduledJob.of(repo), cmd), UseCaseError.Validation.class, expectedCode);
    }

    @Test
    void createRejectsADuplicateCodeWithinTheSameScopeOnly() {
        String code = code("dup");
        created("dup");
        assertUseCaseError(() -> runAsAnchor(CreateScheduledJob.of(repo), createCommand(code, null)),
                UseCaseError.Conflict.class, "CODE_EXISTS");
        // The same code in a client scope is a different job.
        var inClient = runAsAnchor(CreateScheduledJob.of(repo), createCommand(code, "cli_" + RUN + "_dup"));
        assertThat(reload(inClient.scheduledJobId()).clientId()).isEqualTo("cli_" + RUN + "_dup");
        assertThat(repo.findByCode(code, null)).isPresent();
        assertThat(repo.findByCode(code, "cli_" + RUN + "_dup")).isPresent();
        assertThat(repo.findByCode(code, "cli_" + RUN + "_oth")).isEmpty();
    }

    @Test
    void createEnforcesClientScopeOnTheTargetClient() {
        String ownClient = "cli_" + RUN + "_own";
        var clientCtx = new AuthContext(EntityType.PRINCIPAL.generate(), Scope.CLIENT, "c@x.io", List.of(ownClient),
                List.of(), List.of(), true, List.of("platform:messaging:scheduled-job:create"));
        var clientEc = ExecutionContext.of(clientCtx.principalId());

        assertUseCaseError(() -> Auth.runAs(clientCtx, () -> CreateScheduledJob.of(repo).run(uow,
                        createCommand(code("scope-platform"), null), clientEc)),
                UseCaseError.Authorization.class, "FORBIDDEN"); // Go's own code for the platform-scoped refusal
        assertUseCaseError(() -> Auth.runAs(clientCtx, () -> CreateScheduledJob.of(repo).run(uow,
                        createCommand(code("scope-other"), "cli_" + RUN + "_so"), clientEc)),
                UseCaseError.Authorization.class, "SCOPE_FORBIDDEN");
        assertUseCaseError(() -> CreateScheduledJob.of(repo).run(uow, createCommand(code("scope-anon"), null), ExecutionContext.of(null)),
                UseCaseError.Authorization.class, "UNAUTHENTICATED");
        assertThat(repo.findByCode(code("scope-anon"), null)).isEmpty();

        var ev = Auth.runAs(clientCtx, () -> CreateScheduledJob.of(repo).run(uow, createCommand(code("scope-own"), ownClient), clientEc));
        assertThat(reload(ev.scheduledJobId()).clientId()).isEqualTo(ownClient);
    }

    // ── Update ─────────────────────────────────────────────────────────────

    @Test
    void updateAppliesOnlyThePresentFieldsAndBumpsTheVersion() {
        var seeded = created("upd-happy");
        var ev = runAsAnchor(UpdateScheduledJob.of(repo), new UpdateCommand(seeded.scheduledJobId(), "  After  ", "after",
                List.of("0 15 3 * * *"), "Europe/Amsterdam", json("{\"after\":true}"), true, true, 45, 9, "https://after.example.test/job"));
        assertThat(ev.scheduledJobId()).isEqualTo(seeded.scheduledJobId());
        assertThat(ev.code()).isEqualTo(code("upd-happy"));

        var got = reload(seeded.scheduledJobId());
        assertThat(got.code()).as("code is immutable").isEqualTo(code("upd-happy"));
        assertThat(got.name()).isEqualTo("After");
        assertThat(got.description()).isEqualTo("after");
        assertThat(got.crons()).containsExactly("0 15 3 * * *");
        assertThat(got.timezone()).isEqualTo("Europe/Amsterdam");
        assertThat(got.payload()).isEqualTo(json("{\"after\":true}"));
        assertThat(got.concurrent()).isTrue();
        assertThat(got.tracksCompletion()).isTrue();
        assertThat(got.timeoutSeconds()).isEqualTo(45);
        assertThat(got.deliveryMaxAttempts()).isEqualTo(9);
        assertThat(got.targetUrl()).isEqualTo("https://after.example.test/job");
        assertThat(got.updatedBy()).isEqualTo(PRINCIPAL);
        assertThat(got.version()).isEqualTo(2);

        // Absent = untouched; JSON null clears the payload.
        runAsAnchor(UpdateScheduledJob.of(repo), new UpdateCommand(seeded.scheduledJobId(), null, null, null, null,
                NullNode.getInstance(), null, null, null, null, null));
        var again = reload(seeded.scheduledJobId());
        assertThat(again.name()).isEqualTo("After");
        assertThat(again.crons()).containsExactly("0 15 3 * * *");
        assertThat(again.payload()).isNull();
        assertThat(again.version()).isEqualTo(3);

        assertThat(eventsFor(seeded.scheduledJobId(), ScheduledJobEvents.UPDATED)).hasSize(2);
        assertThat(auditsFor(seeded.scheduledJobId(), "UpdateCommand")).hasSize(2);
    }

    static Stream<Arguments> malformedUpdateCommands() {
        return Stream.of(
                Arguments.of("missing id", new UpdateCommand(null, "X", null, null, null, null, null, null, null, null, null), UseCaseError.Validation.class, "ID_REQUIRED"),
                Arguments.of("blank name", new UpdateCommand("sjb_doesnotexist1", " ", null, null, null, null, null, null, null, null, null), UseCaseError.Validation.class, "NAME_REQUIRED"),
                Arguments.of("empty crons", new UpdateCommand("sjb_doesnotexist1", null, null, List.of(), null, null, null, null, null, null, null), UseCaseError.Validation.class, "CRONS_REQUIRED"),
                Arguments.of("blank cron", new UpdateCommand("sjb_doesnotexist1", null, null, List.of(" "), null, null, null, null, null, null, null), UseCaseError.Validation.class, "INVALID_CRON"),
                Arguments.of("bad cron shape", new UpdateCommand("sjb_doesnotexist1", null, null, List.of("1 2 3"), null, null, null, null, null, null, null), UseCaseError.Validation.class, "CRON_INVALID_SHAPE"),
                Arguments.of("unknown id", new UpdateCommand("sjb_doesnotexist1", "X", null, null, null, null, null, null, null, null, null), UseCaseError.NotFound.class, "ScheduledJob_NOT_FOUND"));
    }

    @ParameterizedTest(name = "{0} → {3}")
    @MethodSource("malformedUpdateCommands")
    void updateRejectsAMalformedCommandOrAMissingRow(String label, UpdateCommand cmd, Class<? extends UseCaseError> kind, String code) {
        assertUseCaseError(() -> runAsAnchor(UpdateScheduledJob.of(repo), cmd), kind, code);
    }

    // ── Pause / resume / archive / delete ──────────────────────────────────

    @Test
    void pauseResumeArchiveRoundTripIsPersistedAndAudited() {
        var seeded = created("flip");
        String id = seeded.scheduledJobId();

        var paused = runAsAnchor(PauseScheduledJob.of(repo), new PauseCommand(id));
        assertThat(paused.code()).isEqualTo(code("flip"));
        assertThat(reload(id).status()).isEqualTo(ScheduledJobStatus.PAUSED);
        assertThat(reload(id).version()).isEqualTo(2);

        runAsAnchor(ResumeScheduledJob.of(repo), new ResumeCommand(id));
        assertThat(reload(id).status()).isEqualTo(ScheduledJobStatus.ACTIVE);

        runAsAnchor(ArchiveScheduledJob.of(repo), new ArchiveCommand(id));
        assertThat(reload(id).status()).isEqualTo(ScheduledJobStatus.ARCHIVED);
        assertThat(reload(id).version()).isEqualTo(4);
        assertThat(reload(id).updatedBy()).isEqualTo(PRINCIPAL);

        assertThat(eventsFor(id, ScheduledJobEvents.PAUSED)).hasSize(1);
        assertThat(eventsFor(id, ScheduledJobEvents.RESUMED)).hasSize(1);
        assertThat(eventsFor(id, ScheduledJobEvents.ARCHIVED)).hasSize(1);
        assertThat(auditsFor(id, "PauseCommand")).hasSize(1);
        assertThat(auditsFor(id, "ResumeCommand")).hasSize(1);
        assertThat(auditsFor(id, "ArchiveCommand")).hasSize(1);
    }

    @Test
    void deleteRemovesTheRowAndLeavesTheInstances() {
        var seeded = created("delete");
        String id = seeded.scheduledJobId();
        var fired = runAsAnchor(FireNow.of(repo, instances), new FireNowCommand(id, null));

        var ev = runAsAnchor(DeleteScheduledJob.of(repo), new DeleteCommand(id));
        assertThat(ev.code()).isEqualTo(code("delete"));
        assertThat(repo.findById(id)).isEmpty();
        assertThat(instances.findById(fired.instanceId())).as("instances are not cascaded (spec §4, open question 4)").isPresent();
        assertThat(eventsFor(id, ScheduledJobEvents.DELETED)).hasSize(1);
        assertThat(auditsFor(id, "DeleteCommand")).hasSize(1);
    }

    /// A by-id operation run as `ac`, so the scope rule can be exercised per principal.
    @FunctionalInterface
    interface ByIdOperation {
        void run(AuthContext ac, String id);
    }

    static Stream<Arguments> byIdOperations() {
        return Stream.of(
                Arguments.of("pause", (ByIdOperation) (ac, id) -> Auth.runAs(ac, () -> PauseScheduledJob.of(repo).run(uow, new PauseCommand(id), EC))),
                Arguments.of("resume", (ByIdOperation) (ac, id) -> Auth.runAs(ac, () -> ResumeScheduledJob.of(repo).run(uow, new ResumeCommand(id), EC))),
                Arguments.of("archive", (ByIdOperation) (ac, id) -> Auth.runAs(ac, () -> ArchiveScheduledJob.of(repo).run(uow, new ArchiveCommand(id), EC))),
                Arguments.of("delete", (ByIdOperation) (ac, id) -> Auth.runAs(ac, () -> DeleteScheduledJob.of(repo).run(uow, new DeleteCommand(id), EC))),
                Arguments.of("fire", (ByIdOperation) (ac, id) -> Auth.runAs(ac, () -> FireNow.of(repo, instances).run(uow, new FireNowCommand(id, null), EC))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("byIdOperations")
    void byIdOperationsRejectAMissingIdOrRowAndEnforceScope(String label, ByIdOperation op) {
        assertUseCaseError(() -> op.run(ANCHOR, null), UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(() -> op.run(ANCHOR, " "), UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(() -> op.run(ANCHOR, "sjb_doesnotexist1"), UseCaseError.NotFound.class, "ScheduledJob_NOT_FOUND");
        // A CLIENT principal may not touch a platform-scoped job by id; nothing is bound → UNAUTHENTICATED.
        var seeded = created("scope-" + label);
        var clientCtx = new AuthContext(EntityType.PRINCIPAL.generate(), Scope.CLIENT, "c@x.io", List.of("cli_" + RUN + "_x"),
                List.of(), List.of(), true, List.of("platform:messaging:scheduled-job:update"));
        assertUseCaseError(() -> op.run(clientCtx, seeded.scheduledJobId()), UseCaseError.Authorization.class, "SCOPE_FORBIDDEN");
        assertThat(reload(seeded.scheduledJobId()).version()).as("refused before any write").isEqualTo(1);
    }

    // ── FireNow ────────────────────────────────────────────────────────────

    @Test
    void fireNowInsertsAManualInstanceThenEmitsTheEvent() {
        var seeded = created("fire-happy");
        String id = seeded.scheduledJobId();
        var ev = runAsAnchor(FireNow.of(repo, instances), new FireNowCommand(id, "corr-" + RUN));

        assertThat(ev.scheduledJobId()).isEqualTo(id);
        assertThat(ev.code()).isEqualTo(code("fire-happy"));
        assertThat(ev.instanceId()).startsWith("sji_");
        assertThat(ev.messageGroup()).isEqualTo("platform:scheduledjob:" + id);

        ScheduledJobInstance inst = instances.findById(ev.instanceId()).orElseThrow();
        assertThat(inst.scheduledJobId()).isEqualTo(id);
        assertThat(inst.jobCode()).isEqualTo(code("fire-happy"));
        assertThat(inst.status()).isEqualTo(InstanceStatus.QUEUED);
        assertThat(inst.triggerKind()).isEqualTo(TriggerKind.MANUAL);
        assertThat(inst.deliveryAttempts()).isZero();
        assertThat(inst.correlationId()).isEqualTo("corr-" + RUN);
        assertThat(inst.scheduledFor()).isNull();
        assertThat(reload(id).version()).as("the job row is not changed by a fire").isEqualTo(1);

        var events = eventsFor(id, ScheduledJobEvents.FIRED_MANUALLY);
        assertThat(events).hasSize(1);
        var data = json(events.getFirst().get("data", String.class));
        assertThat(data.get("instanceId").asText()).isEqualTo(ev.instanceId());
        assertThat(data.get("code").asText()).isEqualTo(code("fire-happy"));
        var audits = auditsFor(id, "FireNowCommand");
        assertThat(audits).hasSize(1);
        assertThat(json(audits.getFirst().get("operation_json", String.class)).get("correlationId").asText()).isEqualTo("corr-" + RUN);
    }

    @Test
    void fireNowAcceptsAPausedJobAndRefusesAnArchivedOne() {
        var seeded = created("fire-paused");
        runAsAnchor(PauseScheduledJob.of(repo), new PauseCommand(seeded.scheduledJobId()));
        var ev = runAsAnchor(FireNow.of(repo, instances), new FireNowCommand(seeded.scheduledJobId(), null));
        assertThat(instances.findById(ev.instanceId()).orElseThrow().status()).isEqualTo(InstanceStatus.QUEUED);

        var archived = created("fire-archived");
        runAsAnchor(ArchiveScheduledJob.of(repo), new ArchiveCommand(archived.scheduledJobId()));
        assertUseCaseError(() -> runAsAnchor(FireNow.of(repo, instances), new FireNowCommand(archived.scheduledJobId(), null)),
                UseCaseError.Conflict.class, "ARCHIVED");
        assertThat(instances.count(ScheduledJobInstanceRepository.ListFilter.forJob(archived.scheduledJobId(), null)))
                .as("no instance is inserted for a refused fire").isZero();
    }

    // ── Sync ───────────────────────────────────────────────────────────────

    private static ScheduledJobSyncEntry entry(String code, String name) {
        return new ScheduledJobSyncEntry(code, name, null, HOURLY, "UTC", null, false, false, null, 3, null);
    }

    private static SyncScheduledJobsCommand sync(String clientId, boolean archiveUnlisted, ScheduledJobSyncEntry... jobs) {
        return sync("sjsyncapp" + RUN, clientId, archiveUnlisted, jobs);
    }

    /// Each test that counts rollups owns its application code (the rollup subject).
    private static SyncScheduledJobsCommand sync(String applicationCode, String clientId, boolean archiveUnlisted, ScheduledJobSyncEntry... jobs) {
        return new SyncScheduledJobsCommand(applicationCode, "app_sync_" + RUN, clientId, List.of(jobs), archiveUnlisted);
    }

    @Test
    void syncCreatesNoopsArchivesAndReactivatesWithinOneClientScope() {
        String clientId = "cli_" + RUN + "_sync";
        var first = runAsAnchor(SyncScheduledJobs.of(repo), sync(clientId, false,
                entry("sjsync-a", "A"), entry("sjsync-b", "B"), entry("sjsync-c", "C")));
        assertThat(first.created()).hasSize(3);
        assertThat(first.updated()).isEmpty();
        assertThat(first.archived()).isEmpty();
        assertThat(first.applicationCode()).isEqualTo("sjsyncapp" + RUN);
        assertThat(first.subject()).isEqualTo("platform.scheduledjobs.synced.sjsyncapp" + RUN);
        // X-08: a per-application group, not the bare fallback, since this sync names an application.
        assertThat(first.messageGroup()).isEqualTo("platform:scheduledjobs:sjsyncapp" + RUN);

        var jobA = repo.findByCode("sjsync-a", clientId).orElseThrow();
        assertThat(jobA.status()).isEqualTo(ScheduledJobStatus.ACTIVE);
        assertThat(jobA.clientId()).isEqualTo(clientId);
        assertThat(jobA.applicationId()).isEqualTo("app_sync_" + RUN);
        assertThat(jobA.createdBy()).isEqualTo(PRINCIPAL);
        assertThat(jobA.version()).isEqualTo(1);
        var jobB = repo.findByCode("sjsync-b", clientId).orElseThrow();
        var jobC = repo.findByCode("sjsync-c", clientId).orElseThrow();

        // An identical re-sync is a pure no-op — unchanged rows are neither persisted nor counted.
        var second = runAsAnchor(SyncScheduledJobs.of(repo), sync(clientId, false,
                entry("sjsync-a", "A"), entry("sjsync-b", "B"), entry("sjsync-c", "C")));
        assertThat(second.created()).isEmpty();
        assertThat(second.updated()).as("no-op rows must not be counted").isEmpty();
        assertThat(second.archived()).isEmpty();
        assertThat(reload(jobA.id()).version()).as("no-op rows must not be persisted").isEqualTo(1);

        // archiveUnlisted sweeps ACTIVE unlisted jobs (B) but leaves non-ACTIVE ones alone (C stays PAUSED).
        runAsAnchor(PauseScheduledJob.of(repo), new PauseCommand(jobC.id()));
        var third = runAsAnchor(SyncScheduledJobs.of(repo), sync(clientId, true, entry("sjsync-a", "A")));
        assertThat(third.created()).isEmpty();
        assertThat(third.updated()).isEmpty();
        assertThat(third.archived()).containsExactly(jobB.id());
        assertThat(reload(jobB.id()).status()).isEqualTo(ScheduledJobStatus.ARCHIVED);
        assertThat(reload(jobC.id()).status()).as("archiveUnlisted only sweeps ACTIVE jobs").isEqualTo(ScheduledJobStatus.PAUSED);

        // A reappearing archived job is re-activated and counted as updated.
        var fourth = runAsAnchor(SyncScheduledJobs.of(repo), sync(clientId, false, entry("sjsync-a", "A"), entry("sjsync-b", "B")));
        assertThat(fourth.created()).isEmpty();
        assertThat(fourth.updated()).containsExactly(jobB.id());
        assertThat(fourth.archived()).isEmpty();
        assertThat(reload(jobB.id()).status()).isEqualTo(ScheduledJobStatus.ACTIVE);

        // Per-row events + the rollup, each with an audit row naming the sync command.
        assertThat(eventsFor(jobB.id(), ScheduledJobEvents.CREATED)).hasSize(1);
        assertThat(eventsFor(jobB.id(), ScheduledJobEvents.ARCHIVED)).hasSize(1);
        assertThat(eventsFor(jobB.id(), ScheduledJobEvents.UPDATED)).hasSize(1);
        assertThat(auditsFor(jobB.id(), "SyncScheduledJobsCommand")).hasSize(3);
        var rollups = DB.fetch("SELECT message_group, data::text AS data FROM msg_events WHERE subject = ? AND type = ?",
                ScheduledJobEvents.syncSubjectFor("sjsyncapp" + RUN), ScheduledJobEvents.SYNCED);
        assertThat(rollups).hasSize(4);
        assertThat(rollups.getFirst().get("message_group")).isEqualTo("platform:scheduledjobs:sjsyncapp" + RUN);
        var rollupData = json(rollups.getFirst().get("data", String.class));
        assertThat(rollupData.get("created")).hasSize(3);
        assertThat(rollupData.get("updated").isArray()).as("empty lists serialise as arrays").isTrue();
    }

    @Test
    void syncBackfillsTheApplicationIdOnAnOtherwiseUnchangedRow() {
        String clientId = "cli_" + RUN + "_bf";
        var seeded = runAsAnchor(SyncScheduledJobs.of(repo), new SyncScheduledJobsCommand("sjbackfill" + RUN, null, clientId,
                List.of(entry("sjsync-backfill", "Backfill")), false));
        assertThat(seeded.created()).hasSize(1);
        assertThat(reload(seeded.created().getFirst()).applicationId()).isNull();

        var resync = runAsAnchor(SyncScheduledJobs.of(repo), new SyncScheduledJobsCommand("sjbackfill" + RUN, "app_bf_" + RUN, clientId,
                List.of(entry("sjsync-backfill", "Backfill")), false));
        assertThat(resync.updated()).as("NULL → set application linkage counts as a change").hasSize(1);
        assertThat(reload(seeded.created().getFirst()).applicationId()).isEqualTo("app_bf_" + RUN);
    }

    /// X-02(a) (ruled 2026-09-01): `archiveUnlisted` narrows to
    /// `clientId` + `applicationId` — a sibling application's job in the
    /// SAME client survives a sync that never mentions it. Asserts a count
    /// that must change (X's job archives) alongside one that must NOT
    /// (Y's job stays ACTIVE and its version is untouched) — a bug that
    /// swept every job in the client would still leave Y's row *present*,
    /// so presence alone would not catch it.
    @Test
    void archiveUnlistedNarrowsToTheSyncingApplicationNotTheWholeClient() {
        String clientId = "cli_" + RUN + "_x02a";
        var appX = runAsAnchor(SyncScheduledJobs.of(repo),
                new SyncScheduledJobsCommand("sjx02x" + RUN, "app_x02x_" + RUN, clientId, List.of(entry("sjx02-x", "X")), false));
        var appY = runAsAnchor(SyncScheduledJobs.of(repo),
                new SyncScheduledJobsCommand("sjx02y" + RUN, "app_x02y_" + RUN, clientId, List.of(entry("sjx02-y", "Y")), false));
        var jobY = repo.findByCode("sjx02-y", clientId).orElseThrow();
        assertThat(jobY.version()).isEqualTo(1);

        // App X syncs again with an EMPTY payload and archiveUnlisted — under
        // the old clientId-only sweep this would archive Y's job too.
        var swept = runAsAnchor(SyncScheduledJobs.of(repo),
                new SyncScheduledJobsCommand("sjx02x" + RUN, "app_x02x_" + RUN, clientId, List.of(), true));
        assertThat(swept.archived()).as("only X's own job may be swept").containsExactly(appX.created().getFirst());

        assertThat(reload(appX.created().getFirst()).status())
                .as("X's own unlisted job is archived").isEqualTo(ScheduledJobStatus.ARCHIVED);
        var jobYAfter = reload(jobY.id());
        assertThat(jobYAfter.status()).as("a sibling application's job must survive an unrelated app's sweep")
                .isEqualTo(ScheduledJobStatus.ACTIVE);
        assertThat(jobYAfter.version()).as("Y's row must not even be touched, not just left ACTIVE").isEqualTo(1);
    }

    /// V4 (`function-invocation.md` §4.2, §10): a job id in `protectedIds`
    /// (a function's own scheduled job) survives an `archiveUnlisted` sweep
    /// and is never reconciled/updated even when a batch happens to declare
    /// its code again — while an ordinary unlisted job in the same call is
    /// still archived, so this is not "archiveUnlisted stopped archiving
    /// anything".
    @Test
    void archiveUnlistedNeverTouchesAProtectedJob() {
        String clientId = "cli_" + RUN + "_fnp";
        String appId = "app_" + RUN + "_fnp";
        String appCode = "sjfnprotect" + RUN;
        var seeded = runAsAnchor(SyncScheduledJobs.of(repo),
                new SyncScheduledJobsCommand(appCode, appId, clientId, List.of(entry("sjfnprotect-owned", "Function Owned")), false));
        String fnJobId = seeded.created().getFirst();
        var protectedIds = java.util.Set.of(fnJobId);

        // The protected job's code happens to be declared again — must not be updated.
        var withMatchingCode = runAsAnchor(SyncScheduledJobs.of(repo),
                new SyncScheduledJobsCommand(appCode, appId, clientId,
                        List.of(entry("sjfnprotect-owned", "Renamed By Sync")), false, protectedIds));
        assertThat(withMatchingCode.updated()).as("the protected job is skipped, not reconciled").isEmpty();
        assertThat(reload(fnJobId).name()).isEqualTo("Function Owned");

        // archiveUnlisted, the protected job absent from this batch, plus an
        // ordinary job in the same call to prove the sweep still runs.
        var ordinaryCreate = runAsAnchor(SyncScheduledJobs.of(repo),
                new SyncScheduledJobsCommand(appCode, appId, clientId,
                        List.of(entry("sjfnprotect-ordinary", "Ordinary")), false));
        String ordinaryId = ordinaryCreate.created().getFirst();
        var swept = runAsAnchor(SyncScheduledJobs.of(repo),
                new SyncScheduledJobsCommand(appCode, appId, clientId, List.of(), true, protectedIds));
        assertThat(swept.archived()).as("only the ordinary job is archived; the protected job is skipped and not counted")
                .containsExactly(ordinaryId);
        assertThat(reload(fnJobId).status()).as("archiveUnlisted never archives a protected job")
                .isEqualTo(ScheduledJobStatus.ACTIVE);
        assertThat(reload(ordinaryId).status()).isEqualTo(ScheduledJobStatus.ARCHIVED);
    }

    /// X-02(d): the platform-scope refusal is anchor-tier only — an anchor
    /// caller may still perform a clientId-less sweep.
    @Test
    void anchorMaySweepThePlatformScope() {
        var ok = runAsAnchor(SyncScheduledJobs.of(repo),
                new SyncScheduledJobsCommand("sjx02dan" + RUN, "app_" + RUN, null, List.of(entry("sjx02d-anchor", "A")), true));
        assertThat(ok.created()).hasSize(1);
    }

    static Stream<Arguments> malformedSyncEntries() {
        return Stream.of(
                Arguments.of("missing code", new ScheduledJobSyncEntry(null, "X", null, HOURLY, null, null, false, false, null, null, null), "INVALID_SYNC_ENTRY"),
                Arguments.of("missing name", new ScheduledJobSyncEntry("sjsync-noname", " ", null, HOURLY, null, null, false, false, null, null, null), "INVALID_SYNC_ENTRY"),
                Arguments.of("missing crons", new ScheduledJobSyncEntry("sjsync-nocron", "X", null, null, null, null, false, false, null, null, null), "INVALID_SYNC_ENTRY"),
                Arguments.of("five-field cron (ruling)", new ScheduledJobSyncEntry("sjsync-shape", "X", null, List.of("* * * * *"), null, null, false, false, null, null, null), "CRON_INVALID_SHAPE"),
                Arguments.of("bad cron", new ScheduledJobSyncEntry("sjsync-bad", "X", null, List.of("0 0 25 * * *"), null, null, false, false, null, null, null), "INVALID_CRON"));
    }

    @ParameterizedTest(name = "{0} → {2}")
    @MethodSource("malformedSyncEntries")
    void syncRejectsAMalformedEntry(String label, ScheduledJobSyncEntry bad, String code) {
        assertUseCaseError(() -> runAsAnchor(SyncScheduledJobs.of(repo), sync("sjsyncbad" + RUN, "cli_" + RUN + "_sb", false, bad)),
                UseCaseError.Validation.class, code);
        assertUseCaseError(() -> runAsAnchor(SyncScheduledJobs.of(repo), new SyncScheduledJobsCommand(" ", null, null, List.of(), false)),
                UseCaseError.Validation.class, "APPLICATION_CODE_REQUIRED");
    }

    @Test
    void syncIsAuthorizedOnTheApplicationAndOnTheClientScope() {
        // Anchor tier but bound to other applications → not authorised for this one.
        var appScoped = new AuthContext(EntityType.PRINCIPAL.generate(), Scope.ANCHOR, "svc@x.io", List.of(),
                List.of(), List.of("app_other_" + RUN), false, List.of());
        assertUseCaseError(() -> Auth.runAs(appScoped, () -> SyncScheduledJobs.of(repo).run(uow,
                        sync("sjsyncauth" + RUN, "cli_" + RUN + "_sa", false, entry("sjsync-auth", "A")), ExecutionContext.of(appScoped.principalId()))),
                UseCaseError.Authorization.class, "FORBIDDEN");
        // A CLIENT principal may sync its own client but not the platform scope or another client.
        var clientCtx = new AuthContext(EntityType.PRINCIPAL.generate(), Scope.CLIENT, "c@x.io", List.of("cli_" + RUN + "_sa"),
                List.of(), List.of(), true, List.of());
        // X-02(d): a platform-scope (clientId-less) sync is refused for a
        // non-anchor with a dedicated code, not the generic SCOPE_FORBIDDEN.
        assertUseCaseError(() -> Auth.runAs(clientCtx, () -> SyncScheduledJobs.of(repo).run(uow,
                        sync("sjsyncauth" + RUN, null, false, entry("sjsync-auth", "A")), ExecutionContext.of(clientCtx.principalId()))),
                UseCaseError.Authorization.class, "ANCHOR_REQUIRED_FOR_PLATFORM_SWEEP");
        var ok = Auth.runAs(clientCtx, () -> SyncScheduledJobs.of(repo).run(uow,
                sync("sjsyncauth" + RUN, "cli_" + RUN + "_sa", false, entry("sjsync-auth", "A")), ExecutionContext.of(clientCtx.principalId())));
        assertThat(ok.created()).hasSize(1);
        assertThat(repo.findByCode("sjsync-auth", "cli_" + RUN + "_sa")).isPresent();
    }

    // ── Repository reads ───────────────────────────────────────────────────

    @Test
    void listFiltersAndVisibilityAreAppliedInSqlWithAConsistentCount() {
        String mine = "cli_" + RUN + "_lm";
        String other = "cli_" + RUN + "_lo";
        var platform = created("list-platform");
        var inMine = runAsAnchor(CreateScheduledJob.of(repo), createCommand(code("list-mine"), mine));
        runAsAnchor(CreateScheduledJob.of(repo), createCommand(code("list-other"), other));
        runAsAnchor(PauseScheduledJob.of(repo), new PauseCommand(inMine.scheduledJobId()));

        var everything = new ListFilter(new ClientFilter.Any(), null, code("list-"), Visibility.Everything.INSTANCE);
        assertThat(repo.findWithFilters(everything, 10, 0)).extracting(ScheduledJob::code)
                .containsExactly(code("list-mine"), code("list-other"), code("list-platform"));
        assertThat(repo.countWithFilters(everything)).isEqualTo(3);
        assertThat(repo.findWithFilters(everything, 2, 1)).extracting(ScheduledJob::code)
                .containsExactly(code("list-other"), code("list-platform"));

        var tenant = new ListFilter(new ClientFilter.Any(), null, code("list-"), new Visibility.Tenants(List.of(mine)));
        assertThat(repo.findWithFilters(tenant, 10, 0)).extracting(ScheduledJob::id)
                .containsExactly(inMine.scheduledJobId(), platform.scheduledJobId());
        assertThat(repo.countWithFilters(tenant)).isEqualTo(2);

        assertThat(repo.findWithFilters(new ListFilter(new ClientFilter.PlatformOnly(), null, code("list-"), Visibility.Everything.INSTANCE), 10, 0))
                .extracting(ScheduledJob::id).containsExactly(platform.scheduledJobId());
        assertThat(repo.findWithFilters(new ListFilter(new ClientFilter.Of(other), null, code("list-"), Visibility.Everything.INSTANCE), 10, 0))
                .extracting(ScheduledJob::code).containsExactly(code("list-other"));
        assertThat(repo.findWithFilters(new ListFilter(new ClientFilter.Any(), "PAUSED", code("list-"), Visibility.Everything.INSTANCE), 10, 0))
                .extracting(ScheduledJob::id).containsExactly(inMine.scheduledJobId());
        assertThat(repo.findWithFilters(new ListFilter(new ClientFilter.Any(), null, "Job " + code("list-other"), Visibility.Everything.INSTANCE), 10, 0))
                .as("search matches name too").extracting(ScheduledJob::code).containsExactly(code("list-other"));
        assertThat(repo.findInScope(new ClientFilter.Of(mine))).extracting(ScheduledJob::id).containsExactly(inMine.scheduledJobId());
    }

    @Test
    void legacyRowsReadBackLeniently() {
        // A row another writer produced: a 5-field cron, a JSON null payload, an unrecognised timezone.
        // The `status` column stays a recognised value here (X-06: unlike cron/timezone/payload, an
        // unrecognised status is no longer read leniently — see legacyRowWithAnUnrecognisedStatusFailsLoudly).
        String id = EntityType.SCHEDULED_JOB.generate();
        DB.execute("INSERT INTO msg_scheduled_jobs (id, code, name, status, crons, timezone, payload, concurrent, tracks_completion, delivery_max_attempts, version) "
                + "VALUES (?, ?, 'Legacy', 'ACTIVE', ARRAY['* * * * *', '0 0 * * * *'], 'Mars/Olympus', 'null'::jsonb, false, false, 3, 1)", id, code("legacy"));
        var got = reload(id);
        assertThat(got.status()).isEqualTo(ScheduledJobStatus.ACTIVE);
        assertThat(got.crons()).containsExactly("* * * * *", "0 0 * * * *");
        assertThat(got.payload()).isNull();
        assertThat(got.latestSlotInWindow(Instant.parse("2026-05-29T10:00:30Z"), Instant.parse("2026-05-29T11:00:00Z")))
                .as("the unparseable legacy cron is skipped, the zone falls back to UTC").contains(Instant.parse("2026-05-29T11:00:00Z"));
        assertThat(got.zoneId().getId()).isEqualTo("Z");
    }

    /// X-06: a corrupt `status` fails the read loudly instead of defaulting
    /// to `ACTIVE`. `chk_msg_scheduled_jobs_status` (migration 051) now
    /// blocks a fresh write of an unrecognised status, so the constraint is
    /// dropped for the seed insert AND the assertions, and the row is
    /// deleted again before restoring — otherwise restoring it would itself
    /// fail by re-validating against the row we just inserted
    /// (io.flowcatalyst.testpg.TestPg, ported from Go's
    /// testpg.WithConstraintDropped).
    @Test
    void legacyRowWithAnUnrecognisedStatusFailsLoudly() {
        String id = EntityType.SCHEDULED_JOB.generate();
        TestPg.withConstraintDropped(DS, "msg_scheduled_jobs", "chk_msg_scheduled_jobs_status", () -> {
            DB.execute("INSERT INTO msg_scheduled_jobs (id, code, name, status, crons, timezone, payload, concurrent, tracks_completion, delivery_max_attempts, version) "
                    + "VALUES (?, ?, 'Legacy', 'WEIRD', ARRAY['* * * * *'], 'UTC', 'null'::jsonb, false, false, 3, 1)", id, code("legacy-corrupt"));
            try {
                assertThatThrownBy(() -> reload(id))
                        .isInstanceOf(CorruptScheduledJobException.class)
                        .satisfies(e -> assertThat(((CorruptScheduledJobException) e).rowId()).isEqualTo(id));
            } finally {
                DB.execute("DELETE FROM msg_scheduled_jobs WHERE id = ?", id);
            }
        });
    }

    @Test
    void markFiredAdvancesMonotonically() {
        var seeded = created("markfired");
        repo.markFired(seeded.scheduledJobId(), Instant.parse("2026-05-29T10:00:00Z"));
        assertThat(reload(seeded.scheduledJobId()).lastFiredAt()).isEqualTo(Instant.parse("2026-05-29T10:00:00Z"));
        repo.markFired(seeded.scheduledJobId(), Instant.parse("2026-05-29T09:00:00Z"));
        assertThat(reload(seeded.scheduledJobId()).lastFiredAt()).as("never moves backwards").isEqualTo(Instant.parse("2026-05-29T10:00:00Z"));
        assertThat(reload(seeded.scheduledJobId()).version()).isEqualTo(1);
        assertThat(repo.findActive()).extracting(ScheduledJob::id).contains(seeded.scheduledJobId());
    }

    @Test
    void instanceProjectionRoundTripsLogsAndCompletion() {
        var seeded = created("inst");
        String id = seeded.scheduledJobId();
        var fired = runAsAnchor(FireNow.of(repo, instances), new FireNowCommand(id, null));
        ScheduledJobInstance inst = instances.findById(fired.instanceId()).orElseThrow();

        assertThat(instances.hasActiveInstance(id, false)).as("QUEUED counts").isTrue();
        assertThat(instances.list(ScheduledJobInstanceRepository.ListFilter.forJob(id, InstanceStatus.QUEUED), 10, 0))
                .extracting(ScheduledJobInstance::id).containsExactly(inst.id());
        assertThat(instances.list(ScheduledJobInstanceRepository.ListFilter.forJob(id, InstanceStatus.COMPLETED), 10, 0)).isEmpty();
        assertThat(instances.count(ScheduledJobInstanceRepository.ListFilter.forJob(id, null))).isEqualTo(1);

        instances.writeLog(ScheduledJobInstanceLog.on(inst, "INFO", "started", null));
        instances.writeLog(ScheduledJobInstanceLog.on(inst, "WARN", "slow", json("{\"ms\":1200}")));
        var logs = instances.listLogs(inst.id(), 0);
        assertThat(logs).extracting(ScheduledJobInstanceLog::message).containsExactly("started", "slow");
        assertThat(logs.get(1).metadata()).isEqualTo(json("{\"ms\":1200}"));
        assertThat(logs.getFirst().scheduledJobId()).isEqualTo(id);
        assertThat(instances.listLogs(inst.id(), 1)).hasSize(1);

        instances.markComplete(inst.id(), InstanceStatus.COMPLETED, "SUCCESS", json("{\"rows\":3}"));
        var done = instances.findById(inst.id()).orElseThrow();
        assertThat(done.status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(done.completionStatus()).isEqualTo("SUCCESS");
        assertThat(done.completionResult()).isEqualTo(json("{\"rows\":3}"));
        assertThat(done.completedAt()).isNotNull();
        assertThat(instances.hasActiveInstance(id, false)).isFalse();

        // DELIVERED is active only for a job that tracks completion and while completed_at is null.
        DB.execute("UPDATE msg_scheduled_job_instances SET status = 'DELIVERED', completed_at = NULL WHERE id = ?", inst.id());
        assertThat(instances.hasActiveInstance(id, false)).isFalse();
        assertThat(instances.hasActiveInstance(id, true)).isTrue();
        DB.execute("UPDATE msg_scheduled_job_instances SET completed_at = now() WHERE id = ?", inst.id());
        assertThat(instances.hasActiveInstance(id, true)).isFalse();
    }
}
