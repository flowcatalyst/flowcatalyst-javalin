package io.flowcatalyst.platform.dispatchpool.operations;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.dispatchpool.DispatchPool;
import io.flowcatalyst.platform.dispatchpool.DispatchPoolRepository;
import io.flowcatalyst.platform.dispatchpool.DispatchPoolRepository.ListFilter;
import io.flowcatalyst.platform.dispatchpool.DispatchPoolStatus;
import io.flowcatalyst.platform.dispatchpool.operations.DispatchPoolEvents.DispatchPoolCreated;
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
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The dispatch-pool use cases against the embedded Postgres (spec §4–9):
/// validation, the per-resource authorization, persistence, and the
/// envelope's guarantee that an aggregate write lands together with its
/// `msg_events` and `aud_logs` rows. The pure rules are covered by
/// `DispatchPoolTest`; here each operation is exercised once through the envelope.
///
/// The fixture never truncates, so every test owns its rows: codes carry a
/// per-JVM suffix. Sync with `removeUnlisted` archives every non-listed pool
/// in the table (spec §7), so no test asserts another test's status afterwards.
@SuppressWarnings("deprecation")
class DispatchPoolOperationsTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final DispatchPoolRepository repo = new DispatchPoolRepository(DS);
    private static final UnitOfWork uow = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));

    /// Per-JVM namespace so codes never collide with another run on the same database.
    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final String PRINCIPAL = EntityType.PRINCIPAL.generate();
    private static final AuthContext ANCHOR = new AuthContext(PRINCIPAL, Scope.ANCHOR, "anchor@x.io",
            List.of("*"), List.of(), List.of(), true, List.of());
    private static final ExecutionContext EC = ExecutionContext.of(PRINCIPAL);
    private static final String APP_ID = EntityType.APPLICATION.generate();

    // ── Fixture ────────────────────────────────────────────────────────────

    /// Drives `op` through the full envelope as an all-applications anchor —
    /// the common case here; the scope tests exercise authorization itself.
    private static <C, E extends DomainEvent> E runAsAnchor(Operation<C, E> op, C cmd) {
        return Auth.runAs(ANCHOR, () -> op.run(uow, cmd, EC));
    }

    /// `{tag}-{RUN}` — a code unique to this run.
    private static String code(String tag) {
        return tag + "-" + RUN;
    }

    private static DispatchPoolCreated created(String code, String name) {
        return runAsAnchor(CreateDispatchPool.of(repo), new CreateCommand(code, name, null, null, null, null));
    }

    private static DispatchPool reload(String id) {
        return repo.findById(id).orElseThrow(() -> new AssertionError("dispatch pool " + id + " not found"));
    }

    private static SyncDispatchPoolsCommand sync(String appCode, boolean removeUnlisted, SyncDispatchPoolInput... pools) {
        return new SyncDispatchPoolsCommand(APP_ID, appCode, List.of(pools), removeUnlisted);
    }

    private static SyncDispatchPoolInput input(String code, String name, Integer rateLimit, Integer concurrency) {
        return new SyncDispatchPoolInput(code, name, null, rateLimit, concurrency);
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
    private static Result<Record> eventsFor(String poolId, String type) {
        return DB.fetch("SELECT type, subject, source, message_group, data::text AS data, deduplication_id FROM msg_events WHERE subject = ? AND type = ?",
                DispatchPoolEvents.subjectFor(poolId), type);
    }

    /// `aud_logs` rows for one aggregate and command.
    private static Result<Record> auditsFor(String poolId, String operation) {
        return DB.fetch("SELECT entity_type, entity_id, operation, operation_json::text AS operation_json, principal_id FROM aud_logs WHERE entity_id = ? AND operation = ?",
                poolId, operation);
    }

    // ── Create ─────────────────────────────────────────────────────────────

    @Test
    void createWritesTheRowTheEventAndTheAuditTogether() {
        String code = code("dpcreate");
        var ev = runAsAnchor(CreateDispatchPool.of(repo),
                new CreateCommand("  " + code.toUpperCase(Locale.ROOT) + "  ", "  DP Create  ", "router pool", null, null, null));

        assertThat(ev.poolId()).startsWith("dpl_");
        assertThat(ev.code()).as("code is trimmed + lowercased").isEqualTo(code);
        assertThat(ev.name()).as("name is trimmed").isEqualTo("DP Create");
        assertThat(ev.eventType()).isEqualTo(DispatchPoolEvents.CREATED);
        assertThat(ev.source()).isEqualTo(DispatchPoolEvents.SOURCE);
        assertThat(ev.subject()).isEqualTo(DispatchPoolEvents.subjectFor(ev.poolId()));
        assertThat(ev.messageGroup()).isEqualTo("platform:dispatchpool:" + ev.poolId());

        var got = reload(ev.poolId());
        assertThat(got.code()).isEqualTo(code);
        assertThat(got.status()).as("new pools start ACTIVE").isEqualTo(DispatchPoolStatus.ACTIVE);
        assertThat(got.concurrency()).as("absent concurrency defaults to 10").isEqualTo(10);
        assertThat(got.rateLimit()).as("absent rateLimit stays null (concurrency-only)").isNull();
        assertThat(got.clientId()).isNull();
        assertThat(got.description()).isEqualTo("router pool");

        var events = eventsFor(ev.poolId(), DispatchPoolEvents.CREATED);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().get("source")).isEqualTo(DispatchPoolEvents.SOURCE);
        assertThat(events.getFirst().get("message_group")).isEqualTo("platform:dispatchpool:" + ev.poolId());
        assertThat(events.getFirst().get("deduplication_id")).isEqualTo(DispatchPoolEvents.CREATED + "-" + ev.eventId());
        var data = json(events.getFirst().get("data", String.class));
        assertThat(data.get("poolId").asText()).isEqualTo(ev.poolId());
        assertThat(data.get("code").asText()).isEqualTo(code);
        assertThat(data.get("name").asText()).isEqualTo("DP Create");
        assertThat(data.propertyNames()).containsExactlyInAnyOrder("poolId", "code", "name");

        var audits = auditsFor(ev.poolId(), "CreateCommand");
        assertThat(audits).hasSize(1);
        assertThat(audits.getFirst().get("entity_type")).isEqualTo("Dispatchpool");
        assertThat(audits.getFirst().get("principal_id")).isEqualTo(PRINCIPAL);
        var opJson = json(audits.getFirst().get("operation_json", String.class));
        assertThat(opJson.get("name").asText()).isEqualTo("  DP Create  ");
    }

    /// `rateLimit = 0` is valid at create (bound ≥ 0 — sync's is ≥ 1),
    /// `concurrency = 1` is the lower bound.
    @Test
    void createPersistsExplicitSettingsIncludingAZeroRateLimit() {
        var ev = runAsAnchor(CreateDispatchPool.of(repo),
                new CreateCommand(code("dpexplicit"), "Explicit", null, 0, 1, null));
        var got = reload(ev.poolId());
        assertThat(got.rateLimit()).isEqualTo(0);
        assertThat(got.concurrency()).isEqualTo(1);
    }

    @Test
    void createAcceptsUnderscoresInTheCode() {
        String code = "dp_underscore_" + RUN;
        var ev = created(code, "Underscore Pool");
        assertThat(reload(ev.poolId()).code()).isEqualTo(code);
    }

    static Stream<Arguments> malformedCreateCommands() {
        return Stream.of(
                Arguments.of("null code", new CreateCommand(null, "X", null, null, null, null), "CODE_REQUIRED"),
                Arguments.of("blank code", new CreateCommand("   ", "X", null, null, null, null), "CODE_REQUIRED"),
                Arguments.of("code starts with digit", new CreateCommand("1bad", "X", null, null, null, null), "INVALID_CODE_FORMAT"),
                Arguments.of("code with space", new CreateCommand("bad code", "X", null, null, null, null), "INVALID_CODE_FORMAT"),
                Arguments.of("null name", new CreateCommand("dpcrt-noname", null, null, null, null, null), "NAME_REQUIRED"),
                Arguments.of("blank name", new CreateCommand("dpcrt-noname", "  ", null, null, null, null), "NAME_REQUIRED"),
                Arguments.of("zero concurrency", new CreateCommand("dpcrt-conc", "X", null, null, 0, null), "INVALID_CONCURRENCY"),
                Arguments.of("negative rate limit", new CreateCommand("dpcrt-rate", "X", null, -1, null, null), "INVALID_RATE_LIMIT"));
    }

    @ParameterizedTest(name = "{0} → {2}")
    @MethodSource("malformedCreateCommands")
    void createRejectsAMalformedCommand(String label, CreateCommand cmd, String expectedCode) {
        assertUseCaseError(() -> runAsAnchor(CreateDispatchPool.of(repo), cmd), UseCaseError.Validation.class, expectedCode);
    }

    /// Uniqueness is per `(code, clientId)`: the same code may exist
    /// platform-wide and for a client, but not twice in the same scope.
    @Test
    void createRejectsADuplicateCodeInTheSameScopeOnly() {
        String code = code("dpdup");
        created(code, "First");
        assertUseCaseError(() -> runAsAnchor(CreateDispatchPool.of(repo), new CreateCommand(code, "Second", null, null, null, null)),
                UseCaseError.Conflict.class, "CODE_EXISTS");
        assertThatThrownBy(() -> runAsAnchor(CreateDispatchPool.of(repo), new CreateCommand(code, "Second", null, null, null, null)))
                .hasMessageContaining("Dispatch pool with code '" + code + "' already exists");

        String client = EntityType.CLIENT.generate();
        var forClient = runAsAnchor(CreateDispatchPool.of(repo), new CreateCommand(code, "Client copy", null, null, null, client));
        assertThat(reload(forClient.poolId()).clientId()).isEqualTo(client);
        assertThat(repo.findByCode(code, client)).get().extracting(DispatchPool::id).isEqualTo(forClient.poolId());
        assertThat(repo.findByCode(code, null)).get().extracting(DispatchPool::clientId).isNull();
    }

    /// The use case's per-resource authorization: the coarse "may write
    /// dispatch pools" permission is the handler's job, but the use case
    /// enforces that you can only bind a pool to a client you can access (and
    /// that platform-wide pools require anchor).
    @Test
    void createEnforcesClientScopeOnTheTargetClient() {
        String ownClient = EntityType.CLIENT.generate();
        var clientCtx = new AuthContext(EntityType.PRINCIPAL.generate(), Scope.CLIENT, "c@x.io", List.of(ownClient),
                List.of(), List.of(), true, List.of("platform:messaging:dispatch-pool:create"));
        var clientEc = ExecutionContext.of(clientCtx.principalId());

        // Platform-wide (null clientId) → anchor required → denied.
        assertUseCaseError(() -> Auth.runAs(clientCtx, () -> CreateDispatchPool.of(repo).run(uow,
                        new CreateCommand(code("dpscope-platform"), "X", null, null, null, null), clientEc)),
                UseCaseError.Authorization.class, "SCOPE_FORBIDDEN");

        // Bound to a client the principal cannot access → denied.
        assertUseCaseError(() -> Auth.runAs(clientCtx, () -> CreateDispatchPool.of(repo).run(uow,
                        new CreateCommand(code("dpscope-other"), "X", null, null, null, EntityType.CLIENT.generate()), clientEc)),
                UseCaseError.Authorization.class, "SCOPE_FORBIDDEN");

        // Unauthenticated (no bound principal) → denied before anything is written.
        assertUseCaseError(() -> CreateDispatchPool.of(repo).run(uow,
                        new CreateCommand(code("dpscope-anon"), "X", null, null, null, null), ExecutionContext.of(null)),
                UseCaseError.Authorization.class, "UNAUTHENTICATED");
        assertThat(repo.findByCode(code("dpscope-anon"), null)).isEmpty();

        // Bound to the principal's own client → allowed.
        var ev = Auth.runAs(clientCtx, () -> CreateDispatchPool.of(repo).run(uow,
                new CreateCommand(code("dpscope-own"), "Mine", null, null, null, ownClient), clientEc));
        assertThat(ev.code()).isEqualTo(code("dpscope-own"));
        assertThat(reload(ev.poolId()).clientId()).isEqualTo(ownClient);
    }

    // ── Update ─────────────────────────────────────────────────────────────

    @Test
    void updateReplacesTheGivenSettingsAndKeepsTheRest() {
        String code = code("dpupd");
        var seeded = runAsAnchor(CreateDispatchPool.of(repo), new CreateCommand(code, "Before", "keep me", 30, 2, null));

        var ev = runAsAnchor(UpdateDispatchPool.of(repo), new UpdateCommand(seeded.poolId(), "  After  ", null, 60, 4));
        assertThat(ev.poolId()).isEqualTo(seeded.poolId());
        assertThat(ev.name()).as("name is trimmed").isEqualTo("After");
        assertThat(ev.eventType()).isEqualTo(DispatchPoolEvents.UPDATED);

        var got = reload(seeded.poolId());
        assertThat(got.name()).isEqualTo("After");
        assertThat(got.description()).as("absent description is kept").isEqualTo("keep me");
        assertThat(got.rateLimit()).isEqualTo(60);
        assertThat(got.concurrency()).isEqualTo(4);
        assertThat(got.code()).as("code is immutable on update").isEqualTo(code);

        var onlyDescription = runAsAnchor(UpdateDispatchPool.of(repo), new UpdateCommand(seeded.poolId(), null, "after", null, null));
        assertThat(onlyDescription.name()).as("event carries the current name").isEqualTo("After");
        assertThat(reload(seeded.poolId()).description()).isEqualTo("after");
        assertThat(reload(seeded.poolId()).rateLimit()).isEqualTo(60);

        assertThat(eventsFor(seeded.poolId(), DispatchPoolEvents.UPDATED)).hasSize(2);
        var data = json(eventsFor(seeded.poolId(), DispatchPoolEvents.UPDATED).getFirst().get("data", String.class));
        assertThat(data.propertyNames()).as("updated carries poolId + name only").containsExactlyInAnyOrder("poolId", "name");
        assertThat(auditsFor(seeded.poolId(), "UpdateCommand")).hasSize(2);
    }

    static Stream<Arguments> badUpdateCommands() {
        return Stream.of(
                Arguments.of("missing id", new UpdateCommand(null, "X", null, null, null), UseCaseError.Validation.class, "ID_REQUIRED"),
                Arguments.of("blank name", new UpdateCommand("dpl_doesnotexist1", " ", null, null, null), UseCaseError.Validation.class, "NAME_REQUIRED"),
                Arguments.of("zero concurrency", new UpdateCommand("dpl_doesnotexist1", null, null, null, 0), UseCaseError.Validation.class, "INVALID_CONCURRENCY"),
                Arguments.of("negative rate limit", new UpdateCommand("dpl_doesnotexist1", null, null, -1, null), UseCaseError.Validation.class, "INVALID_RATE_LIMIT"),
                Arguments.of("unknown id", new UpdateCommand("dpl_doesnotexist1", "X", null, null, null), UseCaseError.NotFound.class, "DispatchPool_NOT_FOUND"));
    }

    @ParameterizedTest(name = "{0} → {3}")
    @MethodSource("badUpdateCommands")
    void updateRejectsABadCommand(String label, UpdateCommand cmd, Class<? extends UseCaseError> kind, String expectedCode) {
        assertUseCaseError(() -> runAsAnchor(UpdateDispatchPool.of(repo), cmd), kind, expectedCode);
    }

    // ── Delete ─────────────────────────────────────────────────────────────

    @Test
    void deleteRemovesTheRowAndAuditsIt() {
        String code = code("dpdel");
        var seeded = created(code, "Doomed");

        var ev = runAsAnchor(DeleteDispatchPool.of(repo), new DeleteCommand(seeded.poolId()));
        assertThat(ev.poolId()).isEqualTo(seeded.poolId());
        assertThat(ev.code()).isEqualTo(code);

        assertThat(repo.findById(seeded.poolId())).as("deleted row must be gone").isEmpty();
        assertThat(eventsFor(seeded.poolId(), DispatchPoolEvents.DELETED)).hasSize(1);
        assertThat(auditsFor(seeded.poolId(), "DeleteCommand")).hasSize(1);
    }

    // ── Archive / Suspend / Activate ───────────────────────────────────────

    @Test
    void archiveFlipsToArchivedAndASecondArchiveIsAccepted() {
        var seeded = created(code("dparc"), "Archive Me");

        var ev = runAsAnchor(ArchiveDispatchPool.of(repo), new ArchiveCommand(seeded.poolId()));
        assertThat(ev.eventType()).isEqualTo(DispatchPoolEvents.ARCHIVED);
        assertThat(ev.code()).isEqualTo(code("dparc"));
        assertThat(reload(seeded.poolId()).status()).isEqualTo(DispatchPoolStatus.ARCHIVED);
        assertThat(auditsFor(seeded.poolId(), "ArchiveCommand")).hasSize(1);

        // Unconditional flip (spec §2): no ALREADY_ARCHIVED conflict.
        runAsAnchor(ArchiveDispatchPool.of(repo), new ArchiveCommand(seeded.poolId()));
        assertThat(eventsFor(seeded.poolId(), DispatchPoolEvents.ARCHIVED)).hasSize(2);
    }

    @Test
    void suspendThenActivateFlipTheStatusAndAreAudited() {
        var seeded = created(code("dpsts"), "Flip Me");

        var suspended = runAsAnchor(SuspendDispatchPool.of(repo), new SuspendCommand(seeded.poolId()));
        assertThat(suspended.poolId()).isEqualTo(seeded.poolId());
        assertThat(suspended.code()).isEqualTo(code("dpsts"));
        assertThat(suspended.eventType()).isEqualTo(DispatchPoolEvents.SUSPENDED);
        assertThat(reload(seeded.poolId()).status()).as("suspend flips ACTIVE → SUSPENDED").isEqualTo(DispatchPoolStatus.SUSPENDED);
        assertThat(auditsFor(seeded.poolId(), "SuspendCommand")).hasSize(1);

        var activated = runAsAnchor(ActivateDispatchPool.of(repo), new ActivateCommand(seeded.poolId()));
        assertThat(activated.poolId()).isEqualTo(seeded.poolId());
        assertThat(activated.eventType()).isEqualTo(DispatchPoolEvents.ACTIVATED);
        assertThat(reload(seeded.poolId()).status()).as("activate flips SUSPENDED → ACTIVE").isEqualTo(DispatchPoolStatus.ACTIVE);
        assertThat(eventsFor(seeded.poolId(), DispatchPoolEvents.ACTIVATED)).hasSize(1);
        assertThat(auditsFor(seeded.poolId(), "ActivateCommand")).hasSize(1);
    }

    /// All four id-only operations share the same guard rails.
    static Stream<Arguments> idOnlyOperations() {
        return Stream.of(
                Arguments.of("delete", (Function<String, ThrowingCallable>) id -> () -> runAsAnchor(DeleteDispatchPool.of(repo), new DeleteCommand(id))),
                Arguments.of("archive", (Function<String, ThrowingCallable>) id -> () -> runAsAnchor(ArchiveDispatchPool.of(repo), new ArchiveCommand(id))),
                Arguments.of("suspend", (Function<String, ThrowingCallable>) id -> () -> runAsAnchor(SuspendDispatchPool.of(repo), new SuspendCommand(id))),
                Arguments.of("activate", (Function<String, ThrowingCallable>) id -> () -> runAsAnchor(ActivateDispatchPool.of(repo), new ActivateCommand(id))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("idOnlyOperations")
    void idOnlyOperationsRejectABlankIdAndAnUnknownRow(String label, Function<String, ThrowingCallable> call) {
        assertUseCaseError(call.apply(""), UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(call.apply(null), UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(call.apply("dpl_doesnotexist1"), UseCaseError.NotFound.class, "DispatchPool_NOT_FOUND");
    }

    /// Post-load scope check: a CLIENT principal may act on its own client's
    /// pool but not on a platform-wide one.
    @Test
    void byIdWritesEnforceScopeAfterTheLoad() {
        String ownClient = EntityType.CLIENT.generate();
        var clientCtx = new AuthContext(EntityType.PRINCIPAL.generate(), Scope.CLIENT, "c@x.io", List.of(ownClient),
                List.of(), List.of(), true, List.of("platform:messaging:dispatch-pool:update"));
        var clientEc = ExecutionContext.of(clientCtx.principalId());
        var platformWide = created(code("dpidscope-platform"), "Platform");
        var own = runAsAnchor(CreateDispatchPool.of(repo), new CreateCommand(code("dpidscope-own"), "Own", null, null, null, ownClient));

        assertUseCaseError(() -> Auth.runAs(clientCtx, () -> SuspendDispatchPool.of(repo).run(uow, new SuspendCommand(platformWide.poolId()), clientEc)),
                UseCaseError.Authorization.class, "SCOPE_FORBIDDEN");
        assertThat(reload(platformWide.poolId()).status()).isEqualTo(DispatchPoolStatus.ACTIVE);

        Auth.runAs(clientCtx, () -> SuspendDispatchPool.of(repo).run(uow, new SuspendCommand(own.poolId()), clientEc));
        assertThat(reload(own.poolId()).status()).isEqualTo(DispatchPoolStatus.SUSPENDED);
    }

    // ── Sync ───────────────────────────────────────────────────────────────

    @Test
    void syncCreatesThenUpdatesByCodeWithoutTouchingUnlistedPools() {
        String appCode = "dpsyncapp" + RUN;
        String one = code("dpsynup-one");
        String two = code("dpsynup-two");
        String three = code("dpsynup-three");

        // rateLimit 1 pins sync's lower bound: ≥ 1 when set (create's is ≥ 0);
        // an absent concurrency is the default 10, applied by the operation.
        var first = runAsAnchor(SyncDispatchPools.of(repo), sync(appCode, false,
                input(one, "A", null, 5), input(two, "B", 1, 1), input(three, "C", null, null)));
        assertThat(first.created()).isEqualTo(3);
        assertThat(first.updated()).isZero();
        assertThat(first.deleted()).isZero();
        assertThat(first.syncedCodes()).containsExactly(one, two, three);
        assertThat(repo.findByCode(three, null)).get().extracting(DispatchPool::concurrency)
                .as("absent concurrency defaults to 10").isEqualTo(DispatchPool.DEFAULT_CONCURRENCY);
        assertThat(first.eventType()).isEqualTo(DispatchPoolEvents.SYNCED);
        assertThat(first.subject()).isEqualTo(DispatchPoolEvents.syncSubjectFor(appCode));
        assertThat(first.messageGroup()).isEqualTo("platform:dispatchpools");

        var second = runAsAnchor(SyncDispatchPools.of(repo), sync(appCode, false,
                input(one, "A renamed", 60, 7), input(two, "B", 1, null)));
        assertThat(second.created()).isZero();
        assertThat(second.updated()).isEqualTo(2);
        assertThat(second.deleted()).as("no removeUnlisted → nothing archived").isZero();

        var got = repo.findByCode(one, null).orElseThrow();
        assertThat(got.name()).isEqualTo("A renamed");
        assertThat(got.concurrency()).isEqualTo(7);
        assertThat(got.rateLimit()).isEqualTo(60);
        assertThat(got.status()).isEqualTo(DispatchPoolStatus.ACTIVE);
        assertThat(repo.findByCode(two, null)).get().extracting(DispatchPool::concurrency)
                .as("sync replaces, so an absent concurrency resets an existing pool to the default").isEqualTo(DispatchPool.DEFAULT_CONCURRENCY);
        assertThat(repo.findByCode(three, null)).get().extracting(DispatchPool::status)
                .as("unlisted pool untouched without removeUnlisted").isEqualTo(DispatchPoolStatus.ACTIVE);

        // Per-row events + the rollup, each with an audit row naming the sync command.
        assertThat(eventsFor(got.id(), DispatchPoolEvents.CREATED)).hasSize(1);
        assertThat(eventsFor(got.id(), DispatchPoolEvents.UPDATED)).hasSize(1);
        assertThat(auditsFor(got.id(), "SyncDispatchPoolsCommand")).hasSize(2);
        var rollups = DB.fetch("SELECT type, message_group, data::text AS data FROM msg_events WHERE subject = ? AND type = ?",
                DispatchPoolEvents.syncSubjectFor(appCode), DispatchPoolEvents.SYNCED);
        assertThat(rollups).hasSize(2);
        assertThat(rollups.getFirst().get("message_group")).isEqualTo("platform:dispatchpools");
        assertThat(rollups).extracting(r -> json(r.get("data", String.class)).get("syncedCodes").size()).containsExactlyInAnyOrder(3, 2);
        assertThat(auditsFor(appCode, "SyncDispatchPoolsCommand")).hasSize(2);
    }

    /// HAZARD (spec §7): sync matches globally and `removeUnlisted` archives
    /// every non-listed, non-archived pool in the table — never hard-deletes.
    /// The test asserts only on its own rows and a lower bound on the count.
    @Test
    void syncWithRemoveUnlistedArchivesAbsentPoolsButNeverDeletes() {
        String appCode = "dpsyncrm" + RUN;
        String keep = code("dpsyncrm-keep");
        String drop = code("dpsyncrm-drop");
        runAsAnchor(SyncDispatchPools.of(repo), sync(appCode, false, input(keep, "Keep", null, 2), input(drop, "Drop", null, 2)));
        // An already-archived pool is not archived (or counted) again.
        var archivedBefore = created(code("dpsyncrm-already"), "Already");
        runAsAnchor(ArchiveDispatchPool.of(repo), new ArchiveCommand(archivedBefore.poolId()));

        var second = runAsAnchor(SyncDispatchPools.of(repo), sync(appCode, true, input(keep, "Keep renamed", null, 3)));
        assertThat(second.created()).isZero();
        assertThat(second.updated()).isEqualTo(1);
        assertThat(second.deleted()).as("removal is global; other rows may inflate the count").isGreaterThanOrEqualTo(1);

        var kept = repo.findByCode(keep, null).orElseThrow();
        assertThat(kept.name()).isEqualTo("Keep renamed");
        assertThat(kept.status()).isEqualTo(DispatchPoolStatus.ACTIVE);

        var dropped = repo.findByCode(drop, null).orElseThrow(() -> new AssertionError("removeUnlisted archives, never hard-deletes"));
        assertThat(dropped.status()).as("unlisted pool must be ARCHIVED").isEqualTo(DispatchPoolStatus.ARCHIVED);
        assertThat(eventsFor(dropped.id(), DispatchPoolEvents.ARCHIVED)).hasSize(1);
        assertThat(auditsFor(dropped.id(), "SyncDispatchPoolsCommand")).hasSize(2);
        assertThat(eventsFor(archivedBefore.poolId(), DispatchPoolEvents.ARCHIVED)).as("already-archived rows are skipped").hasSize(1);
    }

    static Stream<Arguments> badSyncCommands() {
        return Stream.of(
                Arguments.of("missing application code", new SyncDispatchPoolsCommand(APP_ID, null, List.of(), false), "APPLICATION_CODE_REQUIRED"),
                // Sync does NOT lowercase codes (create does): uppercase fails the pattern outright.
                Arguments.of("uppercase code", sync("dpsyncval", false, input("DPSync-Mixed", "X", null, 1)), "INVALID_POOL_CODE"),
                Arguments.of("code starts with digit", sync("dpsyncval", false, input("1bad", "X", null, 1)), "INVALID_POOL_CODE"),
                Arguments.of("blank code", sync("dpsyncval", false, input(" ", "X", null, 1)), "INVALID_POOL_CODE"),
                Arguments.of("missing name", sync("dpsyncval", false, input("dpsyncval-noname", null, null, 1)), "NAME_REQUIRED"),
                // Sync's rateLimit bound is ≥ 1 when set — 0 is an error here but valid at create.
                Arguments.of("zero rate limit", sync("dpsyncval", false, input("dpsyncval-rate", "X", 0, 1)), "INVALID_RATE_LIMIT"),
                Arguments.of("zero concurrency", sync("dpsyncval", false, input("dpsyncval-conc", "X", null, 0)), "INVALID_CONCURRENCY"));
    }

    @ParameterizedTest(name = "{0} → {2}")
    @MethodSource("badSyncCommands")
    void syncRejectsABadCommandBeforeWritingAnything(String label, SyncDispatchPoolsCommand cmd, String expectedCode) {
        assertUseCaseError(() -> runAsAnchor(SyncDispatchPools.of(repo), cmd), UseCaseError.Validation.class, expectedCode);
    }

    @Test
    void syncNamesTheOffendingCode() {
        assertThatThrownBy(() -> runAsAnchor(SyncDispatchPools.of(repo), sync("dpsyncval", false, input("Bad-Code", "X", null, 1))))
                .hasMessageContaining("Pool code 'Bad-Code' is invalid.");
    }

    /// Sync authorizes against the application: a principal without access
    /// to it is refused; an explicit application grant passes.
    @Test
    void syncRequiresAccessToTheApplication() {
        String appCode = "dpsyncauth" + RUN;
        var noApps = new AuthContext(EntityType.PRINCIPAL.generate(), Scope.ANCHOR, "a@x.io", List.of("*"),
                List.of(), List.of(), false, List.of("*"));
        var ec = ExecutionContext.of(noApps.principalId());
        assertUseCaseError(() -> Auth.runAs(noApps, () -> SyncDispatchPools.of(repo).run(uow,
                        sync(appCode, false, input(code("dpsyncauth-one"), "A", null, 1)), ec)),
                UseCaseError.Authorization.class, "FORBIDDEN");
        assertThat(repo.findByCode(code("dpsyncauth-one"), null)).isEmpty();

        assertUseCaseError(() -> SyncDispatchPools.of(repo).run(uow,
                        sync(appCode, false, input(code("dpsyncauth-one"), "A", null, 1)), ExecutionContext.of(null)),
                UseCaseError.Authorization.class, "UNAUTHENTICATED");

        var explicitApp = new AuthContext(EntityType.PRINCIPAL.generate(), Scope.ANCHOR, "a@x.io", List.of("*"),
                List.of(), List.of(APP_ID), false, List.of("*"));
        var ev = Auth.runAs(explicitApp, () -> SyncDispatchPools.of(repo).run(uow,
                sync(appCode, false, input(code("dpsyncauth-one"), "A", null, 1)), ExecutionContext.of(explicitApp.principalId())));
        assertThat(ev.created()).isEqualTo(1);
    }

    // ── Repository reads ───────────────────────────────────────────────────

    @Test
    void listFiltersCombineAndResultsAreOrderedByCode() {
        String client = EntityType.CLIENT.generate();
        var b = created(code("dplist-b"), "B");
        var a = created(code("dplist-a"), "A");
        var c = runAsAnchor(CreateDispatchPool.of(repo), new CreateCommand(code("dplist-c"), "C", null, null, null, client));
        runAsAnchor(SuspendDispatchPool.of(repo), new SuspendCommand(b.poolId()));

        var all = repo.findWithFilters(new ListFilter(null, null));
        assertThat(all).extracting(DispatchPool::id).as("ordered by code").containsSubsequence(a.poolId(), b.poolId(), c.poolId());
        assertThat(repo.findAll()).extracting(DispatchPool::id).contains(a.poolId(), b.poolId(), c.poolId());

        assertThat(repo.findWithFilters(new ListFilter("SUSPENDED", null))).extracting(DispatchPool::id).contains(b.poolId()).doesNotContain(a.poolId());
        assertThat(repo.findWithFilters(new ListFilter(null, client))).extracting(DispatchPool::id).containsExactly(c.poolId());
        assertThat(repo.findWithFilters(new ListFilter("ACTIVE", client))).extracting(DispatchPool::id).containsExactly(c.poolId());
        assertThat(repo.findWithFilters(new ListFilter("SUSPENDED", client))).isEmpty();
    }
}
