package io.flowcatalyst.platform.subscription.operations;

import com.fasterxml.jackson.databind.JsonNode;
import io.flowcatalyst.platform.connection.ConnectionRepository;
import io.flowcatalyst.platform.connection.operations.CreateConnection;
import io.flowcatalyst.platform.dispatchpool.DispatchPoolRepository;
import io.flowcatalyst.platform.dispatchpool.operations.CreateDispatchPool;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Scope;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.platform.subscription.ConfigEntry;
import io.flowcatalyst.platform.subscription.DispatchMode;
import io.flowcatalyst.platform.subscription.EventTypeBinding;
import io.flowcatalyst.platform.subscription.Subscription;
import io.flowcatalyst.platform.subscription.SubscriptionRepository;
import io.flowcatalyst.platform.subscription.SubscriptionRepository.ListFilter;
import io.flowcatalyst.platform.subscription.SubscriptionSource;
import io.flowcatalyst.platform.subscription.SubscriptionStatus;
import io.flowcatalyst.platform.subscription.operations.SubscriptionEvents.SubscriptionCreated;
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

import static io.flowcatalyst.db.generated.Tables.MSG_SUBSCRIPTIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

/// The subscription use cases against the embedded Postgres (spec §4–9):
/// validation, the per-resource authorization, persistence of the row and
/// its junction rows, and the envelope's guarantee that an aggregate write
/// lands together with its `msg_events` and `aud_logs` rows. The pure rules
/// are covered by `SubscriptionTest`; here each operation is exercised once
/// through the envelope.
///
/// The fixture never truncates, so every test owns its rows: codes and
/// application codes carry a per-JVM suffix.
class SubscriptionOperationsTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final SubscriptionRepository repo = new SubscriptionRepository(DS);
    private static final ConnectionRepository connections = new ConnectionRepository(DS);
    private static final DispatchPoolRepository pools = new DispatchPoolRepository(DS);
    private static final UnitOfWork uow = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));

    /// Per-JVM namespace so codes never collide with another run on the same database.
    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final String PRINCIPAL = EntityType.PRINCIPAL.generate();
    private static final AuthContext ANCHOR = new AuthContext(PRINCIPAL, Scope.ANCHOR, "anchor@x.io",
            List.of("*"), List.of(), List.of(), true, List.of());
    private static final ExecutionContext EC = ExecutionContext.of(PRINCIPAL);
    private static final String APP_ID = EntityType.APPLICATION.generate();

    private static final String ENDPOINT = "https://hooks.example.test/orders";
    private static final List<EventTypeBinding> BINDINGS = List.of(EventTypeBinding.of("subtest:orders:order:created"));

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

    private static CreateCommand createCommand(String code, String name) {
        return new CreateCommand(code, name, ENDPOINT, null, null, null, null, null, BINDINGS, null,
                null, null, null, null, null, null);
    }

    private static SubscriptionCreated created(String code, String name) {
        return runAsAnchor(CreateSubscription.of(repo), createCommand(code, name));
    }

    private static Subscription reload(String id) {
        return repo.findById(id).orElseThrow(() -> new AssertionError("subscription " + id + " not found"));
    }

    private static UpdateCommand updateOf(String id, String name) {
        return new UpdateCommand(id, name, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static SyncSubscriptionsCommand sync(String appCode, boolean removeUnlisted, SyncSubscriptionInput... rows) {
        return new SyncSubscriptionsCommand(APP_ID, appCode, List.of(rows), removeUnlisted);
    }

    private static SyncSubscriptionInput row(String code, String name, String target, String connectionId,
                                             String dispatchPoolCode, Integer maxRetries, boolean dataOnly, String... patterns) {
        var bindings = Stream.of(patterns).map(p -> new SyncEventTypeBindingInput(p, null)).toList();
        return new SyncSubscriptionInput(code, name, null, target, connectionId, bindings, dispatchPoolCode, null,
                maxRetries, null, dataOnly);
    }

    private static SyncSubscriptionInput row(String code, String name) {
        return row(code, name, "https://" + code + ".example.test/hook", null, null, null, true, "subsync:orders:order:created");
    }

    /// A real connection for sync's `connectionId` check; the service account is not validated by connections.
    private static String seededConnection(String code) {
        return runAsAnchor(CreateConnection.of(connections), new io.flowcatalyst.platform.connection.operations.CreateCommand(
                code, "Sub Sync Conn", null, "sva_subsync1", null, null)).connectionId();
    }

    /// A platform-wide pool for sync's `dispatchPoolCode` resolution.
    private static String seededPool(String code) {
        return runAsAnchor(CreateDispatchPool.of(pools), new io.flowcatalyst.platform.dispatchpool.operations.CreateCommand(
                code, "Sub Sync Pool", null, null, null, null)).poolId();
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
    private static Result<Record> eventsFor(String subscriptionId, String type) {
        return DB.fetch("SELECT type, subject, source, message_group, data::text AS data, deduplication_id FROM msg_events WHERE subject = ? AND type = ?",
                SubscriptionEvents.subjectFor(subscriptionId), type);
    }

    /// `aud_logs` rows for one aggregate and command.
    private static Result<Record> auditsFor(String entityId, String operation) {
        return DB.fetch("SELECT entity_type, entity_id, operation, operation_json::text AS operation_json, principal_id FROM aud_logs WHERE entity_id = ? AND operation = ?",
                entityId, operation);
    }

    // ── Create ─────────────────────────────────────────────────────────────

    @Test
    void createWritesTheRowTheJunctionsTheEventAndTheAuditTogether() {
        String code = code("subcreate");
        var ev = runAsAnchor(CreateSubscription.of(repo), new CreateCommand(
                "  " + code.toUpperCase(Locale.ROOT) + "  ", "  Sub Create  ", ENDPOINT, "delivers order events",
                null, "con_subcreate1", "dpl_subcreate1", "sva_subcreate1",
                List.of(new EventTypeBinding(null, "subcrt:orders:order:created", "1.0", "x == 1"),
                        EventTypeBinding.of("subcrt:orders:order:*")),
                List.of(new ConfigEntry("X-Env", "test")),
                "BLOCK_ON_ERROR", 60, 5, 10, 3600, false));

        assertThat(ev.subscriptionId()).startsWith("sub_");
        assertThat(ev.code()).as("code is trimmed + lowercased").isEqualTo(code);
        assertThat(ev.name()).as("name is trimmed").isEqualTo("Sub Create");
        assertThat(ev.eventType()).isEqualTo(SubscriptionEvents.CREATED);
        assertThat(ev.source()).isEqualTo(SubscriptionEvents.SOURCE);
        assertThat(ev.subject()).isEqualTo(SubscriptionEvents.subjectFor(ev.subscriptionId()));
        assertThat(ev.messageGroup()).isEqualTo("platform:subscription:" + ev.subscriptionId());

        var got = reload(ev.subscriptionId());
        assertThat(got.code()).isEqualTo(code);
        assertThat(got.name()).isEqualTo("Sub Create");
        assertThat(got.endpoint()).isEqualTo(ENDPOINT);
        assertThat(got.description()).isEqualTo("delivers order events");
        assertThat(got.status()).as("new subscriptions start ACTIVE").isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(got.source()).as("admin create is UI-sourced").isEqualTo(SubscriptionSource.UI);
        assertThat(got.connectionId()).isEqualTo("con_subcreate1");
        assertThat(got.dispatchPoolId()).isEqualTo("dpl_subcreate1");
        assertThat(got.dispatchPoolCode()).as("the admin surface never sets the pool code").isNull();
        assertThat(got.serviceAccountId()).isEqualTo("sva_subcreate1");
        assertThat(got.createdBy()).isEqualTo(PRINCIPAL);
        assertThat(got.mode()).isEqualTo(DispatchMode.BLOCK_ON_ERROR);
        assertThat(got.timeoutSeconds()).isEqualTo(60);
        assertThat(got.maxRetries()).isEqualTo(5);
        assertThat(got.delaySeconds()).isEqualTo(10);
        assertThat(got.maxAgeSeconds()).isEqualTo(3600);
        assertThat(got.dataOnly()).isFalse();
        assertThat(got.applicationCode()).isNull();
        assertThat(got.eventTypes()).extracting(EventTypeBinding::eventTypeCode, EventTypeBinding::specVersion, EventTypeBinding::filter)
                .as("bindings are stored in order; the filter has no column and reads back null")
                .containsExactly(
                        tuple("subcrt:orders:order:created", "1.0", null),
                        tuple("subcrt:orders:order:*", null, null));
        assertThat(got.customConfig()).containsExactly(new ConfigEntry("X-Env", "test"));

        var events = eventsFor(ev.subscriptionId(), SubscriptionEvents.CREATED);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().get("source")).isEqualTo(SubscriptionEvents.SOURCE);
        assertThat(events.getFirst().get("message_group")).isEqualTo("platform:subscription:" + ev.subscriptionId());
        assertThat(events.getFirst().get("deduplication_id")).isEqualTo(SubscriptionEvents.CREATED + "-" + ev.eventId());
        var data = json(events.getFirst().get("data", String.class));
        assertThat(data.get("subscriptionId").asText()).isEqualTo(ev.subscriptionId());
        assertThat(data.get("code").asText()).isEqualTo(code);
        assertThat(data.get("name").asText()).isEqualTo("Sub Create");
        assertThat(data.fieldNames()).toIterable().containsExactlyInAnyOrder("subscriptionId", "code", "name");

        var audits = auditsFor(ev.subscriptionId(), "CreateCommand");
        assertThat(audits).hasSize(1);
        assertThat(audits.getFirst().get("entity_type")).isEqualTo("Subscription");
        assertThat(audits.getFirst().get("principal_id")).isEqualTo(PRINCIPAL);
        var opJson = json(audits.getFirst().get("operation_json", String.class));
        assertThat(opJson.get("code").asText()).isEqualTo("  " + code.toUpperCase(Locale.ROOT) + "  ");
        assertThat(opJson.get("eventTypes").get(0).get("eventTypeCode").asText()).isEqualTo("subcrt:orders:order:created");
        assertThat(opJson.get("mode").asText()).isEqualTo("BLOCK_ON_ERROR");
    }

    @Test
    void createAppliesTheAggregateDefaultsForAbsentSettings() {
        var ev = created(code("subdefaults"), "Defaults");
        var got = reload(ev.subscriptionId());
        assertThat(got.mode()).isEqualTo(DispatchMode.IMMEDIATE);
        assertThat(got.timeoutSeconds()).isEqualTo(Subscription.DEFAULT_TIMEOUT_SECONDS);
        assertThat(got.maxRetries()).isEqualTo(Subscription.DEFAULT_MAX_RETRIES);
        assertThat(got.delaySeconds()).isEqualTo(Subscription.DEFAULT_DELAY_SECONDS);
        assertThat(got.maxAgeSeconds()).isEqualTo(Subscription.DEFAULT_MAX_AGE_SECONDS);
        assertThat(got.sequence()).isEqualTo(Subscription.DEFAULT_SEQUENCE);
        assertThat(got.dataOnly()).isTrue();
        assertThat(got.customConfig()).isEmpty();
        assertThat(got.description()).isNull();
        assertThat(got.connectionId()).isNull();
    }

    static Stream<Arguments> malformedCreateCommands() {
        return Stream.of(
                Arguments.of("null code", new CreateCommand(null, "X", ENDPOINT, null, null, null, null, null, BINDINGS, null, null, null, null, null, null, null), "CODE_REQUIRED"),
                Arguments.of("blank code", new CreateCommand("  ", "X", ENDPOINT, null, null, null, null, null, BINDINGS, null, null, null, null, null, null, null), "CODE_REQUIRED"),
                Arguments.of("underscore code", new CreateCommand("sub_bad", "X", ENDPOINT, null, null, null, null, null, BINDINGS, null, null, null, null, null, null, null), "INVALID_CODE_FORMAT"),
                Arguments.of("digit-leading code", new CreateCommand("1sub-bad", "X", ENDPOINT, null, null, null, null, null, BINDINGS, null, null, null, null, null, null, null), "INVALID_CODE_FORMAT"),
                Arguments.of("null name", new CreateCommand("sub-noname", null, ENDPOINT, null, null, null, null, null, BINDINGS, null, null, null, null, null, null, null), "NAME_REQUIRED"),
                Arguments.of("blank name", new CreateCommand("sub-noname", " ", ENDPOINT, null, null, null, null, null, BINDINGS, null, null, null, null, null, null, null), "NAME_REQUIRED"),
                Arguments.of("null endpoint", new CreateCommand("sub-noep", "X", null, null, null, null, null, null, BINDINGS, null, null, null, null, null, null, null), "INVALID_ENDPOINT"),
                Arguments.of("ftp endpoint", new CreateCommand("sub-ftpep", "X", "ftp://files.example.test", null, null, null, null, null, BINDINGS, null, null, null, null, null, null, null), "INVALID_ENDPOINT"),
                Arguments.of("no event types", new CreateCommand("sub-noet", "X", ENDPOINT, null, null, null, null, null, List.of(), null, null, null, null, null, null, null), "EVENT_TYPES_REQUIRED"),
                Arguments.of("null event types", new CreateCommand("sub-noet", "X", ENDPOINT, null, null, null, null, null, null, null, null, null, null, null, null, null), "EVENT_TYPES_REQUIRED"));
    }

    @ParameterizedTest(name = "{0} → {2}")
    @MethodSource("malformedCreateCommands")
    void createRejectsAMalformedCommand(String label, CreateCommand cmd, String expectedCode) {
        assertUseCaseError(() -> runAsAnchor(CreateSubscription.of(repo), cmd), UseCaseError.Validation.class, expectedCode);
    }

    /// Uniqueness is per `(code, clientId)`: a second platform-wide create
    /// conflicts, a client-bound one with the same code does not.
    @Test
    void createRejectsADuplicateCodeInTheSameScopeOnly() {
        String code = code("subdup");
        created(code, "First");
        assertUseCaseError(() -> runAsAnchor(CreateSubscription.of(repo), createCommand(code.toUpperCase(Locale.ROOT), "Second")),
                UseCaseError.Conflict.class, "CODE_EXISTS");

        String client = EntityType.CLIENT.generate();
        var bound = runAsAnchor(CreateSubscription.of(repo), new CreateCommand(code, "Bound", ENDPOINT, null, client,
                null, null, null, BINDINGS, null, null, null, null, null, null, null));
        assertThat(reload(bound.subscriptionId()).clientId()).isEqualTo(client);
        assertThat(repo.findByCodeAndClient(code, client)).isPresent();
        assertThat(repo.findByCodeAndClient(code, null)).as("platform-wide row is a different one").isPresent()
                .get().extracting(Subscription::id).isNotEqualTo(bound.subscriptionId());
    }

    /// The use case's per-resource authorization: the coarse "may create
    /// subscriptions" permission is the handler's job, but the use case
    /// enforces that you can only bind a subscription to a client you can
    /// access (and that platform-wide subscriptions require anchor).
    @Test
    void createEnforcesClientScopeOnTheTargetClient() {
        String ownClient = EntityType.CLIENT.generate();
        String otherClient = EntityType.CLIENT.generate();
        var clientCtx = new AuthContext(EntityType.PRINCIPAL.generate(), Scope.CLIENT, "c@x.io", List.of(ownClient),
                List.of(), List.of(), false, List.of("platform:messaging:subscription:create"));
        var clientEc = ExecutionContext.of(clientCtx.principalId());

        // Platform-wide (null clientId) → anchor required → denied.
        assertUseCaseError(() -> Auth.runAs(clientCtx, () -> CreateSubscription.of(repo).run(uow,
                        createCommand(code("subscope-platform"), "X"), clientEc)),
                UseCaseError.Authorization.class, "SCOPE_FORBIDDEN");

        // Bound to a client the principal cannot access → denied.
        assertUseCaseError(() -> Auth.runAs(clientCtx, () -> CreateSubscription.of(repo).run(uow,
                        new CreateCommand(code("subscope-other"), "X", ENDPOINT, null, otherClient,
                                null, null, null, BINDINGS, null, null, null, null, null, null, null), clientEc)),
                UseCaseError.Authorization.class, "SCOPE_FORBIDDEN");

        // Unauthenticated (no bound principal) → denied before anything is written.
        assertUseCaseError(() -> CreateSubscription.of(repo).run(uow, createCommand(code("subscope-anon"), "X"), ExecutionContext.of(null)),
                UseCaseError.Authorization.class, "UNAUTHENTICATED");
        assertThat(repo.findByCodeAndClient(code("subscope-anon"), null)).isEmpty();

        // Bound to the principal's own client → allowed.
        var ev = Auth.runAs(clientCtx, () -> CreateSubscription.of(repo).run(uow,
                new CreateCommand(code("subscope-own"), "Mine", ENDPOINT, null, ownClient,
                        null, null, null, BINDINGS, null, null, null, null, null, null, null), clientEc));
        assertThat(ev.code()).isEqualTo(code("subscope-own"));
    }

    // ── Update ─────────────────────────────────────────────────────────────

    @Test
    void updateReplacesTheGivenFieldsAndTheListsWholesale() {
        var seeded = created(code("subupd"), "Before");

        var ev = runAsAnchor(UpdateSubscription.of(repo), new UpdateCommand(seeded.subscriptionId(), "  After  ", "after",
                "https://after.example.test/hook", "con_subupd1",
                List.of(EventTypeBinding.of("subupd:orders:order:updated")), List.of(new ConfigEntry("k", "v")),
                "NEXT_ON_ERROR", 90, 7, 5, 7200, "dpl_subupd1", "sva_subupd1", false));
        assertThat(ev.subscriptionId()).isEqualTo(seeded.subscriptionId());
        assertThat(ev.name()).as("name is trimmed").isEqualTo("After");
        assertThat(ev.eventType()).isEqualTo(SubscriptionEvents.UPDATED);

        var got = reload(seeded.subscriptionId());
        assertThat(got.code()).as("code is immutable on update").isEqualTo(code("subupd"));
        assertThat(got.name()).isEqualTo("After");
        assertThat(got.description()).isEqualTo("after");
        assertThat(got.endpoint()).isEqualTo("https://after.example.test/hook");
        assertThat(got.connectionId()).isEqualTo("con_subupd1");
        assertThat(got.eventTypes()).as("bindings are replaced wholesale")
                .extracting(EventTypeBinding::eventTypeCode).containsExactly("subupd:orders:order:updated");
        assertThat(got.customConfig()).containsExactly(new ConfigEntry("k", "v"));
        assertThat(got.mode()).isEqualTo(DispatchMode.NEXT_ON_ERROR);
        assertThat(got.timeoutSeconds()).isEqualTo(90);
        assertThat(got.maxRetries()).isEqualTo(7);
        assertThat(got.delaySeconds()).isEqualTo(5);
        assertThat(got.maxAgeSeconds()).isEqualTo(7200);
        assertThat(got.dispatchPoolId()).isEqualTo("dpl_subupd1");
        assertThat(got.serviceAccountId()).isEqualTo("sva_subupd1");
        assertThat(got.dataOnly()).isFalse();
        assertThat(got.status()).as("update must not touch status").isEqualTo(SubscriptionStatus.ACTIVE);

        // Absent fields are unchanged; an explicit empty list empties.
        runAsAnchor(UpdateSubscription.of(repo), new UpdateCommand(seeded.subscriptionId(), null, null, null, null,
                List.of(), null, null, null, null, null, null, null, null, null));
        var again = reload(seeded.subscriptionId());
        assertThat(again.name()).isEqualTo("After");
        assertThat(again.eventTypes()).as("an explicit [] leaves zero bindings (spec open question 10)").isEmpty();
        assertThat(again.customConfig()).as("absent list is unchanged").containsExactly(new ConfigEntry("k", "v"));

        var events = eventsFor(seeded.subscriptionId(), SubscriptionEvents.UPDATED);
        assertThat(events).hasSize(2);
        assertThat(json(events.getFirst().get("data", String.class)).fieldNames()).toIterable().containsExactlyInAnyOrder("subscriptionId", "name");
        assertThat(auditsFor(seeded.subscriptionId(), "UpdateCommand")).hasSize(2);
    }

    static Stream<Arguments> badUpdateCommands() {
        return Stream.of(
                Arguments.of("missing id", updateOf(null, "X"), UseCaseError.Validation.class, "ID_REQUIRED"),
                Arguments.of("blank name", updateOf("sub_doesnotexist1", " "), UseCaseError.Validation.class, "NAME_REQUIRED"),
                Arguments.of("bad endpoint", new UpdateCommand("sub_doesnotexist1", null, null, "not-a-url", null, null, null, null, null, null, null, null, null, null, null),
                        UseCaseError.Validation.class, "INVALID_ENDPOINT"),
                Arguments.of("unknown id", updateOf("sub_doesnotexist1", "X"), UseCaseError.NotFound.class, "Subscription_NOT_FOUND"));
    }

    @ParameterizedTest(name = "{0} → {3}")
    @MethodSource("badUpdateCommands")
    void updateRejectsABadCommand(String label, UpdateCommand cmd, Class<? extends UseCaseError> kind, String expectedCode) {
        assertUseCaseError(() -> runAsAnchor(UpdateSubscription.of(repo), cmd), kind, expectedCode);
    }

    /// A CLIENT-scoped principal cannot touch another tenant's row by guessing its id.
    @Test
    void byIdWritesEnforceTheLoadedRowsClientScope() {
        var seeded = created(code("subscope-byid"), "Platform Wide");
        var clientCtx = new AuthContext(EntityType.PRINCIPAL.generate(), Scope.CLIENT, "c@x.io", List.of(EntityType.CLIENT.generate()),
                List.of(), List.of(), false, List.of("platform:messaging:subscription:update"));
        assertUseCaseError(() -> Auth.runAs(clientCtx, () -> UpdateSubscription.of(repo).run(uow,
                        updateOf(seeded.subscriptionId(), "Hijack"), ExecutionContext.of(clientCtx.principalId()))),
                UseCaseError.Authorization.class, "SCOPE_FORBIDDEN");
        assertUseCaseError(() -> Auth.runAs(clientCtx, () -> PauseSubscription.of(repo).run(uow,
                        new PauseCommand(seeded.subscriptionId()), ExecutionContext.of(clientCtx.principalId()))),
                UseCaseError.Authorization.class, "SCOPE_FORBIDDEN");
        assertThat(reload(seeded.subscriptionId()).name()).isEqualTo("Platform Wide");
    }

    // ── Pause / Resume ─────────────────────────────────────────────────────

    @Test
    void pauseAndResumeRoundTripAndEmitEvenWhenNothingChanges() {
        var seeded = created(code("subpause"), "Pause Me");

        var paused = runAsAnchor(PauseSubscription.of(repo), new PauseCommand(seeded.subscriptionId()));
        assertThat(paused.subscriptionId()).isEqualTo(seeded.subscriptionId());
        assertThat(paused.eventType()).isEqualTo(SubscriptionEvents.PAUSED);
        assertThat(reload(seeded.subscriptionId()).status()).isEqualTo(SubscriptionStatus.PAUSED);

        runAsAnchor(PauseSubscription.of(repo), new PauseCommand(seeded.subscriptionId()));
        assertThat(eventsFor(seeded.subscriptionId(), SubscriptionEvents.PAUSED)).as("a no-op pause still emits").hasSize(2);
        assertThat(json(eventsFor(seeded.subscriptionId(), SubscriptionEvents.PAUSED).getFirst().get("data", String.class)).fieldNames())
                .toIterable().containsExactly("subscriptionId");
        assertThat(auditsFor(seeded.subscriptionId(), "PauseCommand")).hasSize(2);

        var resumed = runAsAnchor(ResumeSubscription.of(repo), new ResumeCommand(seeded.subscriptionId()));
        assertThat(resumed.eventType()).isEqualTo(SubscriptionEvents.RESUMED);
        assertThat(reload(seeded.subscriptionId()).status()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(eventsFor(seeded.subscriptionId(), SubscriptionEvents.RESUMED)).hasSize(1);
        assertThat(auditsFor(seeded.subscriptionId(), "ResumeCommand")).hasSize(1);
    }

    @Test
    void pauseResumeAndDeleteRejectMissingIdOrRow() {
        assertUseCaseError(() -> runAsAnchor(PauseSubscription.of(repo), new PauseCommand(" ")), UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(PauseSubscription.of(repo), new PauseCommand("sub_doesnotexist1")), UseCaseError.NotFound.class, "Subscription_NOT_FOUND");
        assertUseCaseError(() -> runAsAnchor(ResumeSubscription.of(repo), new ResumeCommand(null)), UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(ResumeSubscription.of(repo), new ResumeCommand("sub_doesnotexist1")), UseCaseError.NotFound.class, "Subscription_NOT_FOUND");
        assertUseCaseError(() -> runAsAnchor(DeleteSubscription.of(repo), new DeleteCommand("")), UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(DeleteSubscription.of(repo), new DeleteCommand("sub_doesnotexist1")), UseCaseError.NotFound.class, "Subscription_NOT_FOUND");
    }

    // ── Delete ─────────────────────────────────────────────────────────────

    @Test
    void deleteRemovesTheRowAndItsJunctionRows() {
        var seeded = runAsAnchor(CreateSubscription.of(repo), new CreateCommand(code("subdel"), "Doomed", ENDPOINT, null, null,
                null, null, null, BINDINGS, List.of(new ConfigEntry("k", "v")), null, null, null, null, null, null));

        var ev = runAsAnchor(DeleteSubscription.of(repo), new DeleteCommand(seeded.subscriptionId()));
        assertThat(ev.subscriptionId()).isEqualTo(seeded.subscriptionId());
        assertThat(ev.code()).isEqualTo(code("subdel"));

        assertThat(repo.findById(seeded.subscriptionId())).as("deleted row must be gone").isEmpty();
        assertThat(DB.fetch("SELECT id FROM msg_subscription_event_types WHERE subscription_id = ?", seeded.subscriptionId())).isEmpty();
        assertThat(DB.fetch("SELECT id FROM msg_subscription_custom_configs WHERE subscription_id = ?", seeded.subscriptionId())).isEmpty();
        assertThat(eventsFor(seeded.subscriptionId(), SubscriptionEvents.DELETED)).hasSize(1);
        assertThat(auditsFor(seeded.subscriptionId(), "DeleteCommand")).hasSize(1);
    }

    // ── Sync ───────────────────────────────────────────────────────────────

    @Test
    void syncCreatesUpdatesAndRemovesApiRowsResolvingPoolsAndSparingUiRows() {
        String appCode = "subsyncapp" + RUN;
        String connId = seededConnection(code("subsync-conn"));
        String poolId = seededPool(code("subsync-pool"));

        // A UI-authored row inside the application scope: sync must never touch it.
        var uiRow = created(code("subsync-ui-kept"), "UI Kept");
        DB.update(MSG_SUBSCRIPTIONS).set(MSG_SUBSCRIPTIONS.APPLICATION_CODE, appCode)
                .where(MSG_SUBSCRIPTIONS.ID.eq(uiRow.subscriptionId())).execute();

        var first = runAsAnchor(SyncSubscriptions.of(repo, connections, pools), sync(appCode, false,
                row(code("subsync-a"), "A", "https://a.example.test/hook", connId, code("subsync-pool"), 9, true, "subsync:orders:order:created"),
                row(code("subsync-b"), "B", "https://b.example.test/hook", null, code("subsync-nosuchpool"), null, false, "subsync:orders:order:updated", "subsync:orders:*:*"),
                row(code("subsync-ui-kept"), "UI renamed?")));
        assertThat(first.created()).isEqualTo(2);
        assertThat(first.updated()).as("the UI row is skipped, not updated").isZero();
        assertThat(first.deleted()).isZero();
        assertThat(first.applicationCode()).isEqualTo(appCode);
        assertThat(first.syncedCodes()).containsExactly(code("subsync-a"), code("subsync-b"), code("subsync-ui-kept"));
        assertThat(first.eventType()).isEqualTo(SubscriptionEvents.SYNCED);
        assertThat(first.subject()).isEqualTo(SubscriptionEvents.syncSubjectFor(appCode));
        assertThat(first.messageGroup()).isEqualTo("platform:subscriptions");

        var a = repo.findByCodeAndClient(code("subsync-a"), null).orElseThrow();
        assertThat(a.source()).as("synced rows are API-sourced").isEqualTo(SubscriptionSource.API);
        assertThat(a.applicationCode()).isEqualTo(appCode);
        assertThat(a.connectionId()).isEqualTo(connId);
        assertThat(a.dispatchPoolId()).as("a resolvable dispatchPoolCode links the pool").isEqualTo(poolId);
        assertThat(a.dispatchPoolCode()).isEqualTo(code("subsync-pool"));
        assertThat(a.maxRetries()).isEqualTo(9);
        assertThat(a.timeoutSeconds()).as("absent timeout keeps the default").isEqualTo(Subscription.DEFAULT_TIMEOUT_SECONDS);
        assertThat(a.mode()).as("sync never sets the mode").isEqualTo(DispatchMode.IMMEDIATE);
        assertThat(a.createdBy()).isEqualTo(PRINCIPAL);
        assertThat(a.dataOnly()).isTrue();

        var b = repo.findByCodeAndClient(code("subsync-b"), null).orElseThrow();
        assertThat(b.dispatchPoolId()).as("an unresolvable pool code is silently ignored").isNull();
        assertThat(b.dispatchPoolCode()).isNull();
        assertThat(b.dataOnly()).isFalse();
        assertThat(b.eventTypes()).extracting(EventTypeBinding::eventTypeCode)
                .containsExactly("subsync:orders:order:updated", "subsync:orders:*:*");

        assertThat(reload(uiRow.subscriptionId()).name()).as("UI rows are never updated by sync").isEqualTo("UI Kept");

        var second = runAsAnchor(SyncSubscriptions.of(repo, connections, pools), sync(appCode, true,
                row(code("subsync-a"), "A renamed", "https://a.example.test/hook", null, null, null, true, "subsync:orders:order:created")));
        assertThat(second.created()).isZero();
        assertThat(second.updated()).isEqualTo(1);
        assertThat(second.deleted()).as("only the unlisted API row is removed; the UI row is spared").isEqualTo(1);

        var kept = repo.findByCodeAndClient(code("subsync-a"), null).orElseThrow();
        assertThat(kept.name()).isEqualTo("A renamed");
        assertThat(kept.connectionId()).as("an absent connectionId clears the link (spec open question 5)").isNull();
        assertThat(kept.dispatchPoolCode()).as("an absent dispatchPoolCode leaves the existing pool link").isEqualTo(code("subsync-pool"));
        assertThat(kept.maxRetries()).as("an absent maxRetries leaves the existing value").isEqualTo(9);
        assertThat(repo.findByCodeAndClient(code("subsync-b"), null)).as("removeUnlisted hard-deletes unlisted API rows").isEmpty();
        assertThat(repo.findById(uiRow.subscriptionId())).as("removeUnlisted never touches UI rows").isPresent();

        // Per-row events + the rollup, each with an audit row naming the sync command.
        assertThat(eventsFor(kept.id(), SubscriptionEvents.CREATED)).hasSize(1);
        assertThat(eventsFor(kept.id(), SubscriptionEvents.UPDATED)).hasSize(1);
        assertThat(eventsFor(b.id(), SubscriptionEvents.DELETED)).hasSize(1);
        assertThat(auditsFor(kept.id(), "SyncSubscriptionsCommand")).hasSize(2);
        assertThat(auditsFor(appCode, "SyncSubscriptionsCommand")).extracting(r -> r.get("entity_type")).containsExactly("Subscriptions", "Subscriptions");
        var rollups = DB.fetch("SELECT type, message_group, data::text AS data FROM msg_events WHERE subject = ? AND type = ?",
                SubscriptionEvents.syncSubjectFor(appCode), SubscriptionEvents.SYNCED);
        assertThat(rollups).hasSize(2);
        assertThat(rollups.getFirst().get("message_group")).isEqualTo("platform:subscriptions");
        assertThat(rollups).extracting(r -> json(r.get("data", String.class)).get("syncedCodes").size()).containsExactlyInAnyOrder(3, 1);
    }

    static Stream<Arguments> badSyncCommands() {
        return Stream.of(
                Arguments.of("missing application code", new SyncSubscriptionsCommand(APP_ID, null, List.of(), false), "APPLICATION_CODE_REQUIRED"),
                Arguments.of("entry missing code", sync("subsyncbad", false, row(" ", "X")), "CODE_REQUIRED"),
                Arguments.of("entry missing name", sync("subsyncbad", false, row("subsync-noname", null)), "NAME_REQUIRED"),
                Arguments.of("entry missing target", sync("subsyncbad", false,
                        row("subsync-notarget", "X", " ", null, null, null, true, "subsync:a:b:c")), "TARGET_REQUIRED"),
                Arguments.of("entry missing event types", sync("subsyncbad", false,
                        row("subsync-noet", "X", "https://x.example.test", null, null, null, true)), "EVENT_TYPES_REQUIRED"));
    }

    @ParameterizedTest(name = "{0} → {2}")
    @MethodSource("badSyncCommands")
    void syncRejectsABadCommandBeforeWritingAnything(String label, SyncSubscriptionsCommand cmd, String expectedCode) {
        assertUseCaseError(() -> runAsAnchor(SyncSubscriptions.of(repo, connections, pools), cmd), UseCaseError.Validation.class, expectedCode);
        assertThat(repo.findByApplicationCode("subsyncbad")).isEmpty();
    }

    /// Sync stores the code and target verbatim — no normalisation, no URL check (spec open question 3).
    @Test
    void syncStoresCodeAndTargetVerbatim() {
        String appCode = "subsyncraw" + RUN;
        runAsAnchor(SyncSubscriptions.of(repo, connections, pools), sync(appCode, false,
                row("Raw-" + RUN, "Raw", "not-a-url", null, null, null, true, "subsync:a:b:c")));
        var got = repo.findByCodeAndClient("Raw-" + RUN, null).orElseThrow();
        assertThat(got.endpoint()).isEqualTo("not-a-url");
        assertThat(got.applicationCode()).isEqualTo(appCode);
    }

    /// Connection resolution uses the exact code CONNECTION_NOT_FOUND and aborts before any write.
    @Test
    void syncRejectsAnUnknownConnectionBeforeWritingAnything() {
        String appCode = "subsyncconn404" + RUN;
        assertUseCaseError(() -> runAsAnchor(SyncSubscriptions.of(repo, connections, pools), sync(appCode, false,
                        row(code("subsync-goodconn"), "Good"),
                        row(code("subsync-badconn"), "Bad", "https://x.example.test", "con_doesnotexist1", null, null, true, "subsync:a:b:c"))),
                UseCaseError.NotFound.class, "CONNECTION_NOT_FOUND");
        assertThatThrownBy(() -> runAsAnchor(SyncSubscriptions.of(repo, connections, pools), sync(appCode, false,
                row(code("subsync-badconn"), "Bad", "https://x.example.test", "con_doesnotexist1", null, null, true, "subsync:a:b:c"))))
                .hasMessageContaining("Connection 'con_doesnotexist1' not found");
        assertThat(repo.findByApplicationCode(appCode)).as("a bad row aborts the whole sync").isEmpty();
    }

    /// Sync authorizes against the application: a principal without access
    /// to it is denied before any write; one with explicit access proceeds.
    @Test
    void syncRequiresAccessToTheApplication() {
        String appCode = "subsyncauth" + RUN;
        var noApps = new AuthContext(EntityType.PRINCIPAL.generate(), Scope.CLIENT, "c@x.io", List.of(),
                List.of(), List.of(EntityType.APPLICATION.generate()), false, List.of("platform:messaging:subscription:sync"));
        assertUseCaseError(() -> Auth.runAs(noApps, () -> SyncSubscriptions.of(repo, connections, pools).run(uow,
                        sync(appCode, false, row(code("subsyncauth-one"), "A")), ExecutionContext.of(noApps.principalId()))),
                UseCaseError.Authorization.class, "FORBIDDEN");
        assertThatThrownBy(() -> Auth.runAs(noApps, () -> SyncSubscriptions.of(repo, connections, pools).run(uow,
                sync(appCode, false, row(code("subsyncauth-one"), "A")), ExecutionContext.of(noApps.principalId()))))
                .hasMessageContaining("Not authorised for application '" + appCode + "'");
        assertThat(repo.findByApplicationCode(appCode)).isEmpty();

        assertUseCaseError(() -> SyncSubscriptions.of(repo, connections, pools).run(uow,
                        sync(appCode, false, row(code("subsyncauth-one"), "A")), ExecutionContext.of(null)),
                UseCaseError.Authorization.class, "UNAUTHENTICATED");

        var explicitApp = new AuthContext(EntityType.PRINCIPAL.generate(), Scope.CLIENT, "c@x.io", List.of(),
                List.of(), List.of(APP_ID), false, List.of("platform:messaging:subscription:sync"));
        var ev = Auth.runAs(explicitApp, () -> SyncSubscriptions.of(repo, connections, pools).run(uow,
                sync(appCode, false, row(code("subsyncauth-one"), "A")), ExecutionContext.of(explicitApp.principalId())));
        assertThat(ev.created()).isEqualTo(1);
    }

    // ── Repository reads ───────────────────────────────────────────────────

    @Test
    void listFiltersCombineResultsAreOrderedByCodeAndHydrated() {
        String client = EntityType.CLIENT.generate();
        var a = created(code("sublist-b"), "B");
        var b = runAsAnchor(CreateSubscription.of(repo), new CreateCommand(code("sublist-a"), "A", ENDPOINT, null, client,
                null, null, null, BINDINGS, List.of(new ConfigEntry("k", "v")), null, null, null, null, null, null));
        runAsAnchor(PauseSubscription.of(repo), new PauseCommand(a.subscriptionId()));

        var byClient = repo.findWithFilters(new ListFilter(null, client));
        assertThat(byClient).extracting(Subscription::id).containsExactly(b.subscriptionId());
        assertThat(byClient.getFirst().eventTypes()).as("list hydrates bindings").hasSize(1);
        assertThat(byClient.getFirst().customConfig()).as("list hydrates config").hasSize(1);

        assertThat(repo.findWithFilters(new ListFilter("PAUSED", null))).extracting(Subscription::id).contains(a.subscriptionId())
                .doesNotContain(b.subscriptionId());
        assertThat(repo.findWithFilters(new ListFilter("ACTIVE", client))).extracting(Subscription::id).containsExactly(b.subscriptionId());

        var codes = repo.findWithFilters(new ListFilter(null, null)).stream().map(Subscription::code)
                .filter(c -> c.startsWith("sublist-")).toList();
        assertThat(codes).as("ordered by code").containsExactly(code("sublist-a"), code("sublist-b"));
    }
}
