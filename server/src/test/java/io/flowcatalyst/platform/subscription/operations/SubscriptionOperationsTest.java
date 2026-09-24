package io.flowcatalyst.platform.subscription.operations;

import io.flowcatalyst.platform.serviceaccount.SigningAccounts;
import io.flowcatalyst.platform.serviceaccount.SigningReach;
import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.connection.ConnectionCode;
import io.flowcatalyst.platform.connection.ConnectionRepository;
import io.flowcatalyst.platform.connection.ConnectionSource;
import io.flowcatalyst.platform.connection.operations.CreateConnection;
import io.flowcatalyst.platform.dispatchpool.DispatchPoolRepository;
import io.flowcatalyst.platform.dispatchpool.operations.CreateDispatchPool;
import io.flowcatalyst.platform.seed.PlatformEventSchemas;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Scope;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.platform.subscription.ConfigEntry;
import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.platform.subscription.EventTypeBinding;
import io.flowcatalyst.platform.subscription.Subscription;
import io.flowcatalyst.platform.subscription.SubscriptionRepository;
import io.flowcatalyst.platform.subscription.SubscriptionRepository.ListFilter;
import io.flowcatalyst.platform.subscription.SubscriptionSource;
import io.flowcatalyst.platform.subscription.SubscriptionStatus;
import io.flowcatalyst.platform.subscription.operations.SubscriptionEvents.SubscriptionCreated;
import io.flowcatalyst.platform.subscription.operations.SubscriptionEvents.SubscriptionsSynced;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.ExecutionContext;
import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
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
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
@SuppressWarnings("deprecation")
class SubscriptionOperationsTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final SubscriptionRepository repo = new SubscriptionRepository(DS);
    private static final ConnectionRepository connections = new ConnectionRepository(DS);
    private static final io.flowcatalyst.platform.application.ApplicationRepository apps =
            new io.flowcatalyst.platform.application.ApplicationRepository(DS);
    private static final DispatchPoolRepository pools = new DispatchPoolRepository(DS);
    private static final UnitOfWork uow = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));
    private static final SigningReach REACH = SigningAccounts.reach(DS);

    // A named service account must exist and be one the caller may sign with
    // (security-fixes-2026-09-24 S3.1): the ids these tests name are real anchor-tier accounts.
    static {
        for (String id : List.of("sva_subsync1", "sva_subcreate1", "sva_subupd1")) {
            SigningAccounts.seed(DS, id, List.of(), null);
        }
    }

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
                null, null, null, null, null, null, null);
    }

    private static SubscriptionCreated created(String code, String name) {
        return runAsAnchor(CreateSubscription.of(repo, connections, REACH), createCommand(code, name));
    }

    private static Subscription reload(String id) {
        return repo.findById(id).orElseThrow(() -> new AssertionError("subscription " + id + " not found"));
    }

    private static UpdateCommand updateOf(String id, String name) {
        return new UpdateCommand(id, name, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /// A client-less sync — `clientId` omitted, exactly as every sync request
    /// behaved before `code-first-connections.md` §3 (C14: these callers'
    /// assertions are unmodified).
    private static SyncSubscriptionsCommand sync(String appCode, boolean removeUnlisted, SyncSubscriptionInput... rows) {
        return sync(appCode, null, removeUnlisted, rows);
    }

    private static SyncSubscriptionsCommand sync(String appCode, String clientId, boolean removeUnlisted, SyncSubscriptionInput... rows) {
        return new SyncSubscriptionsCommand(APP_ID, appCode, clientId, List.of(rows), removeUnlisted);
    }

    private static SyncSubscriptionInput row(String code, String name, String target, String connectionId,
                                             String dispatchPoolCode, Integer maxRetries, boolean dataOnly, String... patterns) {
        var bindings = Stream.of(patterns).map(p -> new SyncEventTypeBindingInput(p, null)).toList();
        return new SyncSubscriptionInput(code, name, null, target, connectionId, null, false, bindings, dispatchPoolCode, null,
                maxRetries, null, dataOnly);
    }

    private static SyncSubscriptionInput row(String code, String name) {
        return row(code, name, "https://" + code + ".example.test/hook", null, null, null, true, "subsync:orders:order:created");
    }

    /// A row naming its connection by `connectionCode` (`code-first-connections.md`
    /// §3) instead of `connectionId`.
    private static SyncSubscriptionInput rowByConnectionCode(String code, String name, String connectionCode,
                                                              boolean sharedConnection, String... patterns) {
        var bindings = Stream.of(patterns).map(p -> new SyncEventTypeBindingInput(p, null)).toList();
        return new SyncSubscriptionInput(code, name, null, "https://" + code + ".example.test/hook", null,
                connectionCode, sharedConnection, bindings, null, null, null, null, true);
    }

    /// A real connection for sync's `connectionId` check; the service account is not validated by connections.
    private static String seededConnection(String code) {
        return runAsAnchor(CreateConnection.of(connections, apps, REACH), new io.flowcatalyst.platform.connection.operations.CreateCommand(
                code, "Sub Sync Conn", null, "sva_subsync1", null, null, null)).connectionId();
    }

    /// A connection row written directly (bypassing [CreateConnection], which
    /// would require a persisted application) — `code-first-connections.md`
    /// §3's namespace/scope rules (C12, C13). `applicationCode` `null` =
    /// shared; `clientId` `null` = global within its namespace.
    private static io.flowcatalyst.platform.connection.Connection seedConnection(String code, String applicationCode, String clientId) {
        io.flowcatalyst.platform.connection.Connection c = io.flowcatalyst.platform.connection.Connection
                .create(ConnectionCode.parse(code), "Seed " + code, EntityType.SERVICE_ACCOUNT.generate())
                .withApplicationCode(applicationCode)
                .withClientId(clientId)
                .withSource(ConnectionSource.UI);
        try (java.sql.Connection conn = DS.getConnection()) {
            conn.setAutoCommit(false);
            connections.persist(c, DbTx.wrapForBootstrap(conn));
            conn.commit();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return c;
    }

    /// A subscription row written directly (bypassing [SyncSubscriptions]) so
    /// a test can plant a row of any `(applicationCode, clientId, source)`
    /// combination — C11's ownership/scoping tests.
    private static Subscription seedSubscription(String code, String applicationCode, String clientId, SubscriptionSource source) {
        Subscription s = Subscription.create(code, "Seed " + code, "https://example.test/" + code)
                .withApplicationCode(applicationCode)
                .withClientId(clientId)
                .withSource(source);
        try (java.sql.Connection conn = DS.getConnection()) {
            conn.setAutoCommit(false);
            repo.persist(s, DbTx.wrapForBootstrap(conn));
            conn.commit();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return s;
    }

    /// A minimal structural JSON-Schema check (mirrors `SyncConnectionsTest`):
    /// every `required` field is present, and — when the schema declares
    /// `additionalProperties: false` — every field `data` carries is one the
    /// schema declared. Omitting `clientId` from the seeded schema (C15's
    /// mutant) makes a `data` that legitimately carries a `clientId` fail the
    /// second loop.
    private static void assertMatchesSchema(JsonNode schema, JsonNode data) {
        for (JsonNode req : schema.path("required")) {
            assertThat(data.has(req.stringValue())).as("required field '%s' present", req.stringValue()).isTrue();
        }
        if (!schema.path("additionalProperties").asBoolean(true)) {
            Set<String> allowed = new HashSet<>();
            schema.path("properties").propertyNames().forEach(allowed::add);
            data.propertyNames().forEach(name ->
                    assertThat(allowed).as("field '%s' not declared in the schema (additionalProperties:false)", name).contains(name));
        }
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
        String connectionId = seedConnection(code("subcreate-conn"), null, null).id();
        var ev = runAsAnchor(CreateSubscription.of(repo, connections, REACH), new CreateCommand(
                "  " + code.toUpperCase(Locale.ROOT) + "  ", "  Sub Create  ", ENDPOINT, "delivers order events",
                null, connectionId, "dpl_subcreate1", "sva_subcreate1",
                List.of(new EventTypeBinding(null, "subcrt:orders:order:created", "1.0", "x == 1"),
                        EventTypeBinding.of("subcrt:orders:order:*")),
                List.of(new ConfigEntry("X-Env", "test")),
                "BLOCK_ON_ERROR", "high_priority", 60, 5, 10, 3600, false));

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
        assertThat(got.connectionId()).isEqualTo(connectionId);
        assertThat(got.dispatchPoolId()).isEqualTo("dpl_subcreate1");
        assertThat(got.dispatchPoolCode()).as("the admin surface never sets the pool code").isNull();
        assertThat(got.serviceAccountId()).isEqualTo("sva_subcreate1");
        assertThat(got.createdBy()).isEqualTo(PRINCIPAL);
        assertThat(got.mode()).isEqualTo(DispatchMode.BLOCK_ON_ERROR);
        assertThat(got.queue()).as("R1a: lower-case \"high_priority\" is accepted and normalised upper-case")
                .isEqualTo("HIGH_PRIORITY");
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
        assertThat(data.propertyNames()).containsExactlyInAnyOrder("subscriptionId", "code", "name");

        var audits = auditsFor(ev.subscriptionId(), "CreateCommand");
        assertThat(audits).hasSize(1);
        assertThat(audits.getFirst().get("entity_type")).isEqualTo("Subscription");
        assertThat(audits.getFirst().get("principal_id")).isEqualTo(PRINCIPAL);
        var opJson = json(audits.getFirst().get("operation_json", String.class));
        assertThat(opJson.get("code").asText()).isEqualTo("  " + code.toUpperCase(Locale.ROOT) + "  ");
        assertThat(opJson.get("eventTypes").get(0).get("eventTypeCode").asText()).isEqualTo("subcrt:orders:order:created");
        assertThat(opJson.get("mode").asText()).isEqualTo("BLOCK_ON_ERROR");
        assertThat(opJson.get("queue").asText()).as("the audit stores the raw command value, not the normalised one")
                .isEqualTo("high_priority");
    }

    @Test
    void createAppliesTheAggregateDefaultsForAbsentSettings() {
        var ev = created(code("subdefaults"), "Defaults");
        var got = reload(ev.subscriptionId());
        assertThat(got.mode()).as("X-01: absent mode defaults to NEXT_ON_ERROR, not IMMEDIATE")
                .isEqualTo(DispatchMode.NEXT_ON_ERROR);
        assertThat(got.timeoutSeconds()).isEqualTo(Subscription.DEFAULT_TIMEOUT_SECONDS);
        assertThat(got.maxRetries()).isEqualTo(Subscription.DEFAULT_MAX_RETRIES);
        assertThat(got.delaySeconds()).isEqualTo(Subscription.DEFAULT_DELAY_SECONDS);
        assertThat(got.maxAgeSeconds()).isEqualTo(Subscription.DEFAULT_MAX_AGE_SECONDS);
        assertThat(got.sequence()).isEqualTo(Subscription.DEFAULT_SEQUENCE);
        assertThat(got.dataOnly()).isTrue();
        assertThat(got.customConfig()).isEmpty();
        assertThat(got.description()).isNull();
        assertThat(got.connectionId()).isNull();
        assertThat(got.queue()).as("ruling R1: no queue sent leaves it unset").isNull();
    }

    static Stream<Arguments> malformedCreateCommands() {
        return Stream.of(
                Arguments.of("null code", new CreateCommand(null, "X", ENDPOINT, null, null, null, null, null, BINDINGS, null, null, null, null, null, null, null, null), "CODE_REQUIRED"),
                Arguments.of("blank code", new CreateCommand("  ", "X", ENDPOINT, null, null, null, null, null, BINDINGS, null, null, null, null, null, null, null, null), "CODE_REQUIRED"),
                Arguments.of("underscore code", new CreateCommand("sub_bad", "X", ENDPOINT, null, null, null, null, null, BINDINGS, null, null, null, null, null, null, null, null), "INVALID_CODE_FORMAT"),
                Arguments.of("digit-leading code", new CreateCommand("1sub-bad", "X", ENDPOINT, null, null, null, null, null, BINDINGS, null, null, null, null, null, null, null, null), "INVALID_CODE_FORMAT"),
                Arguments.of("null name", new CreateCommand("sub-noname", null, ENDPOINT, null, null, null, null, null, BINDINGS, null, null, null, null, null, null, null, null), "NAME_REQUIRED"),
                Arguments.of("blank name", new CreateCommand("sub-noname", " ", ENDPOINT, null, null, null, null, null, BINDINGS, null, null, null, null, null, null, null, null), "NAME_REQUIRED"),
                Arguments.of("null endpoint", new CreateCommand("sub-noep", "X", null, null, null, null, null, null, BINDINGS, null, null, null, null, null, null, null, null), "INVALID_ENDPOINT"),
                Arguments.of("ftp endpoint", new CreateCommand("sub-ftpep", "X", "ftp://files.example.test", null, null, null, null, null, BINDINGS, null, null, null, null, null, null, null, null), "INVALID_ENDPOINT"),
                Arguments.of("no event types", new CreateCommand("sub-noet", "X", ENDPOINT, null, null, null, null, null, List.of(), null, null, null, null, null, null, null, null), "EVENT_TYPES_REQUIRED"),
                Arguments.of("null event types", new CreateCommand("sub-noet", "X", ENDPOINT, null, null, null, null, null, null, null, null, null, null, null, null, null, null), "EVENT_TYPES_REQUIRED"),
                Arguments.of("unrecognised queue", new CreateCommand("sub-badq", "X", ENDPOINT, null, null, null, null, null, BINDINGS, null, null, "workers-high", null, null, null, null, null), "INVALID_QUEUE"));
    }

    @ParameterizedTest(name = "{0} → {2}")
    @MethodSource("malformedCreateCommands")
    void createRejectsAMalformedCommand(String label, CreateCommand cmd, String expectedCode) {
        assertUseCaseError(() -> runAsAnchor(CreateSubscription.of(repo, connections, REACH), cmd), UseCaseError.Validation.class, expectedCode);
    }

    /// Uniqueness is per `(code, clientId)`: a second platform-wide create
    /// conflicts, a client-bound one with the same code does not.
    @Test
    void createRejectsADuplicateCodeInTheSameScopeOnly() {
        String code = code("subdup");
        created(code, "First");
        assertUseCaseError(() -> runAsAnchor(CreateSubscription.of(repo, connections, REACH), createCommand(code.toUpperCase(Locale.ROOT), "Second")),
                UseCaseError.Conflict.class, "CODE_EXISTS");

        String client = EntityType.CLIENT.generate();
        var bound = runAsAnchor(CreateSubscription.of(repo, connections, REACH), new CreateCommand(code, "Bound", ENDPOINT, null, client,
                null, null, null, BINDINGS, null, null, null, null, null, null, null, null));
        assertThat(reload(bound.subscriptionId()).clientId()).isEqualTo(client);
        assertThat(repo.findByCode(code, null, client)).isPresent();
        assertThat(repo.findByCode(code, null, null)).as("platform-wide row is a different one").isPresent()
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
        assertUseCaseError(() -> Auth.runAs(clientCtx, () -> CreateSubscription.of(repo, connections, REACH).run(uow,
                        createCommand(code("subscope-platform"), "X"), clientEc)),
                UseCaseError.Authorization.class, "SCOPE_FORBIDDEN");

        // Bound to a client the principal cannot access → denied.
        assertUseCaseError(() -> Auth.runAs(clientCtx, () -> CreateSubscription.of(repo, connections, REACH).run(uow,
                        new CreateCommand(code("subscope-other"), "X", ENDPOINT, null, otherClient,
                                null, null, null, BINDINGS, null, null, null, null, null, null, null, null), clientEc)),
                UseCaseError.Authorization.class, "SCOPE_FORBIDDEN");

        // Unauthenticated (no bound principal) → denied before anything is written.
        assertUseCaseError(() -> CreateSubscription.of(repo, connections, REACH).run(uow, createCommand(code("subscope-anon"), "X"), ExecutionContext.of(null)),
                UseCaseError.Authorization.class, "UNAUTHENTICATED");
        assertThat(repo.findByCode(code("subscope-anon"), null, null)).isEmpty();

        // Bound to the principal's own client → allowed.
        var ev = Auth.runAs(clientCtx, () -> CreateSubscription.of(repo, connections, REACH).run(uow,
                new CreateCommand(code("subscope-own"), "Mine", ENDPOINT, null, ownClient,
                        null, null, null, BINDINGS, null, null, null, null, null, null, null, null), clientEc));
        assertThat(ev.code()).isEqualTo(code("subscope-own"));
    }

    // ── Signing accounts (security-fixes-2026-09-24 S3.1) ────────────────────
    //
    // A subscription's endpoint is the caller's choice, so the account that signs
    // its deliveries — named directly or through its connection — must be one the
    // caller could sign with itself. Before, any id was accepted, and a
    // client-scoped caller could have another tenant's (or the operator's)
    // account sign deliveries to its own endpoint.

    private static AuthContext clientCaller(String clientId) {
        return new AuthContext(EntityType.PRINCIPAL.generate(), Scope.CLIENT, "c@x.io", List.of(clientId), List.of(),
                List.of(), true, List.of("platform:messaging:subscription:create", "platform:messaging:subscription:update"));
    }

    private static CreateCommand signedCreate(String code, String clientId, String connectionId, String serviceAccountId) {
        return new CreateCommand(code, "Signed", ENDPOINT, null, clientId, connectionId, null, serviceAccountId, BINDINGS,
                null, null, null, null, null, null, null, null);
    }

    private static UpdateCommand signedUpdate(String id, String endpoint, String connectionId, String serviceAccountId) {
        return new UpdateCommand(id, "Renamed", null, endpoint, connectionId, null, null, null, null, null, null, null, null,
                null, serviceAccountId, null);
    }

    private static <C, E extends DomainEvent> E runAsCaller(AuthContext ac, Operation<C, E> op, C cmd) {
        return Auth.runAs(ac, () -> op.run(uow, cmd, ExecutionContext.of(ac.principalId())));
    }

    @Test
    void createRefusesAnAccountOutsideTheCallersReach() {
        String clientA = EntityType.CLIENT.generate();
        var caller = clientCaller(clientA);
        String operatorAccount = SigningAccounts.seed(DS, List.of(), null);
        String sharedAccount = SigningAccounts.seed(DS, List.of(clientA, EntityType.CLIENT.generate()), null);
        String ownAccount = SigningAccounts.seed(DS, List.of(clientA), null);

        for (String account : List.of(operatorAccount, sharedAccount)) {
            String code = code("subsign-refused-" + account.substring(account.length() - 5).toLowerCase(Locale.ROOT));
            assertUseCaseError(() -> runAsCaller(caller, CreateSubscription.of(repo, connections, REACH),
                            signedCreate(code, clientA, null, account)),
                    UseCaseError.Authorization.class, "SERVICE_ACCOUNT_OUT_OF_REACH");
            assertThat(repo.findByCode(code, null, clientA)).as("nothing written").isEmpty();
        }

        var ev = runAsCaller(caller, CreateSubscription.of(repo, connections, REACH),
                signedCreate(code("subsign-own"), clientA, null, ownAccount));
        assertThat(reload(ev.subscriptionId()).serviceAccountId()).isEqualTo(ownAccount);
    }

    @Test
    void createRefusesAConnectionOutsideTheCallersReach() {
        String clientA = EntityType.CLIENT.generate();
        var caller = clientCaller(clientA);
        String platformWide = SigningAccounts.connection(DS, EntityType.CONNECTION.generate(), null,
                SigningAccounts.seed(DS, List.of(clientA), null));
        String ownButOperatorSigned = SigningAccounts.connection(DS, EntityType.CONNECTION.generate(), clientA,
                SigningAccounts.seed(DS, List.of(), null));
        String own = SigningAccounts.connection(DS, EntityType.CONNECTION.generate(), clientA,
                SigningAccounts.seed(DS, List.of(clientA), null));

        assertUseCaseError(() -> runAsCaller(caller, CreateSubscription.of(repo, connections, REACH),
                        signedCreate(code("subsign-conn-platform"), clientA, platformWide, null)),
                UseCaseError.Authorization.class, "CONNECTION_OUT_OF_REACH");
        assertUseCaseError(() -> runAsCaller(caller, CreateSubscription.of(repo, connections, REACH),
                        signedCreate(code("subsign-conn-opsigned"), clientA, ownButOperatorSigned, null)),
                UseCaseError.Authorization.class, "SERVICE_ACCOUNT_OUT_OF_REACH");
        assertThat(repo.findByCode(code("subsign-conn-platform"), null, clientA)).isEmpty();
        assertThat(repo.findByCode(code("subsign-conn-opsigned"), null, clientA)).isEmpty();

        var ev = runAsCaller(caller, CreateSubscription.of(repo, connections, REACH),
                signedCreate(code("subsign-conn-own"), clientA, own, null));
        assertThat(reload(ev.subscriptionId()).connectionId()).isEqualTo(own);
    }

    @Test
    void createRefusesAnAccountOrConnectionThatDoesNotExist() {
        assertUseCaseError(() -> runAsAnchor(CreateSubscription.of(repo, connections, REACH),
                        signedCreate(code("subsign-nosa"), null, null, EntityType.SERVICE_ACCOUNT.generate())),
                UseCaseError.NotFound.class, "ServiceAccount_NOT_FOUND");
        assertUseCaseError(() -> runAsAnchor(CreateSubscription.of(repo, connections, REACH),
                        signedCreate(code("subsign-noconn"), null, EntityType.CONNECTION.generate(), null)),
                UseCaseError.NotFound.class, "Connection_NOT_FOUND");
    }

    /// The endpoint is where the credentials go: re-pointing it on a
    /// subscription an operator configured with an account the caller cannot
    /// reach is refused, even though the account itself is untouched.
    /// Re-sending the current values (the SPA sends the whole form) is not.
    @Test
    void updateRefusesRepointingTheCredentialsOfAnAccountOutsideTheCallersReach() {
        String clientA = EntityType.CLIENT.generate();
        var caller = clientCaller(clientA);
        String operatorAccount = SigningAccounts.seed(DS, List.of(), null);
        String ownAccount = SigningAccounts.seed(DS, List.of(clientA), null);
        String id = runAsAnchor(CreateSubscription.of(repo, connections, REACH),
                signedCreate(code("subsign-upd"), clientA, null, operatorAccount)).subscriptionId();

        runAsCaller(caller, UpdateSubscription.of(repo, connections, REACH), signedUpdate(id, ENDPOINT, "", operatorAccount));
        assertThat(reload(id).name()).as("the unchanged form is accepted").isEqualTo("Renamed");

        assertUseCaseError(() -> runAsCaller(caller, UpdateSubscription.of(repo, connections, REACH),
                        signedUpdate(id, "https://attacker.example.test/hook", null, null)),
                UseCaseError.Authorization.class, "SERVICE_ACCOUNT_OUT_OF_REACH");
        assertThat(reload(id).endpoint()).isEqualTo(ENDPOINT);

        String otherOperatorAccount = SigningAccounts.seed(DS, List.of(), null);
        assertUseCaseError(() -> runAsCaller(caller, UpdateSubscription.of(repo, connections, REACH),
                        signedUpdate(id, null, null, otherOperatorAccount)),
                UseCaseError.Authorization.class, "SERVICE_ACCOUNT_OUT_OF_REACH");
        assertThat(reload(id).serviceAccountId()).isEqualTo(operatorAccount);

        runAsCaller(caller, UpdateSubscription.of(repo, connections, REACH),
                signedUpdate(id, "https://mine.example.test/hook", null, ownAccount));
        assertThat(reload(id).serviceAccountId()).isEqualTo(ownAccount);
        assertThat(reload(id).endpoint()).isEqualTo("https://mine.example.test/hook");
    }

    /// A subscription an application's sync authored may use that
    /// application's accounts — and no other application's.
    @Test
    void updateMayUseTheOwningApplicationsAccountOnly() {
        String clientA = EntityType.CLIENT.generate();
        var caller = clientCaller(clientA);
        var app = io.flowcatalyst.platform.application.Application.create(
                io.flowcatalyst.platform.application.ApplicationType.APPLICATION, "subsignapp-" + RUN, "Sign App");
        var other = io.flowcatalyst.platform.application.Application.create(
                io.flowcatalyst.platform.application.ApplicationType.APPLICATION, "subsignoth-" + RUN, "Other App");
        for (var a : List.of(app, other)) {
            try (java.sql.Connection conn = DS.getConnection()) {
                conn.setAutoCommit(false);
                apps.persist(a, DbTx.wrapForBootstrap(conn));
                conn.commit();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
        String appAccount = SigningAccounts.seed(DS, List.of(), app.id());
        String otherAppAccount = SigningAccounts.seed(DS, List.of(), other.id());
        var synced = seedSubscription(code("subsign-synced"), app.code(), clientA, SubscriptionSource.API);

        runAsCaller(caller, UpdateSubscription.of(repo, connections, REACH), signedUpdate(synced.id(), null, null, appAccount));
        assertThat(reload(synced.id()).serviceAccountId()).isEqualTo(appAccount);

        assertUseCaseError(() -> runAsCaller(caller, UpdateSubscription.of(repo, connections, REACH),
                        signedUpdate(synced.id(), null, null, otherAppAccount)),
                UseCaseError.Authorization.class, "SERVICE_ACCOUNT_OUT_OF_REACH");
        assertThat(reload(synced.id()).serviceAccountId()).isEqualTo(appAccount);
    }

    // ── Update ─────────────────────────────────────────────────────────────

    @Test
    void updateReplacesTheGivenFieldsAndTheListsWholesale() {
        var seeded = created(code("subupd"), "Before");
        String connectionId = seedConnection(code("subupd-conn"), null, null).id();

        var ev = runAsAnchor(UpdateSubscription.of(repo, connections, REACH), new UpdateCommand(seeded.subscriptionId(), "  After  ", "after",
                "https://after.example.test/hook", connectionId,
                List.of(EventTypeBinding.of("subupd:orders:order:updated")), List.of(new ConfigEntry("k", "v")),
                "NEXT_ON_ERROR", "default", 90, 7, 5, 7200, "dpl_subupd1", "sva_subupd1", false));
        assertThat(ev.subscriptionId()).isEqualTo(seeded.subscriptionId());
        assertThat(ev.name()).as("name is trimmed").isEqualTo("After");
        assertThat(ev.eventType()).isEqualTo(SubscriptionEvents.UPDATED);

        var got = reload(seeded.subscriptionId());
        assertThat(got.code()).as("code is immutable on update").isEqualTo(code("subupd"));
        assertThat(got.name()).isEqualTo("After");
        assertThat(got.description()).isEqualTo("after");
        assertThat(got.endpoint()).isEqualTo("https://after.example.test/hook");
        assertThat(got.connectionId()).isEqualTo(connectionId);
        assertThat(got.eventTypes()).as("bindings are replaced wholesale")
                .extracting(EventTypeBinding::eventTypeCode).containsExactly("subupd:orders:order:updated");
        assertThat(got.customConfig()).containsExactly(new ConfigEntry("k", "v"));
        assertThat(got.mode()).isEqualTo(DispatchMode.NEXT_ON_ERROR);
        assertThat(got.queue()).as("R1a: lower-case \"default\" is accepted and normalised upper-case").isEqualTo("DEFAULT");
        assertThat(got.timeoutSeconds()).isEqualTo(90);
        assertThat(got.maxRetries()).isEqualTo(7);
        assertThat(got.delaySeconds()).isEqualTo(5);
        assertThat(got.maxAgeSeconds()).isEqualTo(7200);
        assertThat(got.dispatchPoolId()).isEqualTo("dpl_subupd1");
        assertThat(got.serviceAccountId()).isEqualTo("sva_subupd1");
        assertThat(got.dataOnly()).isFalse();
        assertThat(got.status()).as("update must not touch status").isEqualTo(SubscriptionStatus.ACTIVE);

        // Absent fields are unchanged; an explicit empty list empties.
        runAsAnchor(UpdateSubscription.of(repo, connections, REACH), new UpdateCommand(seeded.subscriptionId(), null, null, null, null,
                List.of(), null, null, null, null, null, null, null, null, null, null));
        var again = reload(seeded.subscriptionId());
        assertThat(again.name()).isEqualTo("After");
        assertThat(again.eventTypes()).as("an explicit [] leaves zero bindings (spec open question 10)").isEmpty();
        assertThat(again.customConfig()).as("absent list is unchanged").containsExactly(new ConfigEntry("k", "v"));
        assertThat(again.queue()).as("a null queue field on update leaves the stored priority unchanged").isEqualTo("DEFAULT");

        // A present-but-blank queue is not absence: it parses to null (R1) and clears the priority.
        runAsAnchor(UpdateSubscription.of(repo, connections, REACH), new UpdateCommand(seeded.subscriptionId(), null, null, null, null,
                null, null, null, "", null, null, null, null, null, null, null));
        assertThat(reload(seeded.subscriptionId()).queue()).as("an explicit blank queue clears it").isNull();

        var events = eventsFor(seeded.subscriptionId(), SubscriptionEvents.UPDATED);
        assertThat(events).hasSize(3);
        assertThat(json(events.getFirst().get("data", String.class)).propertyNames()).containsExactlyInAnyOrder("subscriptionId", "name");
        assertThat(auditsFor(seeded.subscriptionId(), "UpdateCommand")).hasSize(3);
    }

    static Stream<Arguments> badUpdateCommands() {
        return Stream.of(
                Arguments.of("missing id", updateOf(null, "X"), UseCaseError.Validation.class, "ID_REQUIRED"),
                Arguments.of("blank name", updateOf("sub_doesnotexist1", " "), UseCaseError.Validation.class, "NAME_REQUIRED"),
                Arguments.of("bad endpoint", new UpdateCommand("sub_doesnotexist1", null, null, "not-a-url", null, null, null, null, null, null, null, null, null, null, null, null),
                        UseCaseError.Validation.class, "INVALID_ENDPOINT"),
                Arguments.of("bad queue", new UpdateCommand("sub_doesnotexist1", null, null, null, null, null, null, null, "workers-high", null, null, null, null, null, null, null),
                        UseCaseError.Validation.class, "INVALID_QUEUE"),
                Arguments.of("unknown id", updateOf("sub_doesnotexist1", "X"), UseCaseError.NotFound.class, "Subscription_NOT_FOUND"));
    }

    @ParameterizedTest(name = "{0} → {3}")
    @MethodSource("badUpdateCommands")
    void updateRejectsABadCommand(String label, UpdateCommand cmd, Class<? extends UseCaseError> kind, String expectedCode) {
        assertUseCaseError(() -> runAsAnchor(UpdateSubscription.of(repo, connections, REACH), cmd), kind, expectedCode);
    }

    /// A CLIENT-scoped principal cannot touch another tenant's row by guessing its id.
    @Test
    void byIdWritesEnforceTheLoadedRowsClientScope() {
        var seeded = created(code("subscope-byid"), "Platform Wide");
        var clientCtx = new AuthContext(EntityType.PRINCIPAL.generate(), Scope.CLIENT, "c@x.io", List.of(EntityType.CLIENT.generate()),
                List.of(), List.of(), false, List.of("platform:messaging:subscription:update"));
        assertUseCaseError(() -> Auth.runAs(clientCtx, () -> UpdateSubscription.of(repo, connections, REACH).run(uow,
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
        assertThat(json(eventsFor(seeded.subscriptionId(), SubscriptionEvents.PAUSED).getFirst().get("data", String.class)).propertyNames())
                .containsExactly("subscriptionId");
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
        var seeded = runAsAnchor(CreateSubscription.of(repo, connections, REACH), new CreateCommand(code("subdel"), "Doomed", ENDPOINT, null, null,
                null, null, null, BINDINGS, List.of(new ConfigEntry("k", "v")), null, null, null, null, null, null, null));

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
        // X-08 (ruled 2026-09-01): one FIFO lane per application.
        assertThat(first.messageGroup()).isEqualTo("platform:subscriptions:" + appCode);

        var a = repo.findByCode(code("subsync-a"), appCode, null).orElseThrow();
        assertThat(a.source()).as("synced rows are API-sourced").isEqualTo(SubscriptionSource.API);
        assertThat(a.applicationCode()).isEqualTo(appCode);
        assertThat(a.connectionId()).isEqualTo(connId);
        assertThat(a.dispatchPoolId()).as("a resolvable dispatchPoolCode links the pool").isEqualTo(poolId);
        assertThat(a.dispatchPoolCode()).isEqualTo(code("subsync-pool"));
        assertThat(a.maxRetries()).isEqualTo(9);
        assertThat(a.timeoutSeconds()).as("absent timeout keeps the default").isEqualTo(Subscription.DEFAULT_TIMEOUT_SECONDS);
        assertThat(a.mode()).as("sync never sets the mode; it stays at the create default (X-01: NEXT_ON_ERROR)")
                .isEqualTo(DispatchMode.NEXT_ON_ERROR);
        assertThat(a.createdBy()).isEqualTo(PRINCIPAL);
        assertThat(a.dataOnly()).isTrue();

        var b = repo.findByCode(code("subsync-b"), appCode, null).orElseThrow();
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

        var kept = repo.findByCode(code("subsync-a"), appCode, null).orElseThrow();
        assertThat(kept.name()).isEqualTo("A renamed");
        assertThat(kept.connectionId()).as("an absent connectionId clears the link (spec open question 5)").isNull();
        assertThat(kept.dispatchPoolCode()).as("an absent dispatchPoolCode leaves the existing pool link").isEqualTo(code("subsync-pool"));
        assertThat(kept.maxRetries()).as("an absent maxRetries leaves the existing value").isEqualTo(9);
        assertThat(repo.findByCode(code("subsync-b"), appCode, null)).as("removeUnlisted hard-deletes unlisted API rows").isEmpty();
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
        assertThat(rollups.getFirst().get("message_group")).isEqualTo("platform:subscriptions:" + appCode);
        assertThat(rollups).extracting(r -> json(r.get("data", String.class)).get("syncedCodes").size()).containsExactlyInAnyOrder(3, 1);
    }

    /// V4 (`function-invocation.md` §4.1, §4.2, §10): a FUNCTION-sourced row
    /// is `isSyncManaged() == false`, so a `removeUnlisted` sync neither
    /// updates one that happens to share a declared code, nor deletes one
    /// left unlisted — while an ordinary API-sourced row in the same batch
    /// still gets deleted, so this is not "removeUnlisted stopped deleting
    /// anything".
    @Test
    void removeUnlistedNeverTouchesAFunctionSourcedRow() {
        String appCode = "subsyncfn" + RUN;
        Subscription fnRow = Subscription.create(code("subsyncfn-owned"), "Function Owned", ENDPOINT)
                .withApplicationCode(appCode).withSource(SubscriptionSource.FUNCTION);
        uow.inTransaction(tx -> {
            repo.persist(fnRow, tx.dbTx());
            return null;
        });
        var ordinary = runAsAnchor(SyncSubscriptions.of(repo, connections, pools), sync(appCode, false,
                row(code("subsyncfn-ordinary"), "Ordinary")));
        assertThat(ordinary.created()).isEqualTo(1);

        // First call: ONLY the function row's code is declared (as if a
        // coincidentally-matching SDK entry existed) — it must still not be
        // updated (isolated from the ordinary row so `updated()` pins this
        // alone, not diluted by the ordinary row's own no-op "update").
        var withMatchingCode = runAsAnchor(SyncSubscriptions.of(repo, connections, pools), sync(appCode, false,
                row(code("subsyncfn-owned"), "Renamed By Sync")));
        assertThat(withMatchingCode.updated()).as("the function row is skipped, not updated, even when its code is declared").isZero();
        assertThat(reload(fnRow.id()).name()).isEqualTo("Function Owned");

        // Second call: removeUnlisted, function row absent from the batch —
        // it must survive while the ordinary row is deleted.
        var swept = runAsAnchor(SyncSubscriptions.of(repo, connections, pools), sync(appCode, true));
        assertThat(swept.deleted()).as("the ordinary unlisted row is still removed in the same call").isEqualTo(1);
        assertThat(repo.findById(fnRow.id())).as("removeUnlisted never removes a FUNCTION-sourced row").isPresent();
        assertThat(repo.findByCode(code("subsyncfn-ordinary"), appCode, null)).isEmpty();
    }

    /// X-08: two applications' syncs land in two different FIFO lanes.
    @Test
    void syncOfDifferentApplicationsProduceDifferentMessageGroups() {
        String appOne = "subgrp1" + RUN;
        String appTwo = "subgrp2" + RUN;
        var one = runAsAnchor(SyncSubscriptions.of(repo, connections, pools), sync(appOne, false, row(code("subgrp1-a"), "A")));
        var two = runAsAnchor(SyncSubscriptions.of(repo, connections, pools), sync(appTwo, false, row(code("subgrp2-a"), "A")));
        assertThat(one.messageGroup()).isEqualTo("platform:subscriptions:" + appOne);
        assertThat(two.messageGroup()).isEqualTo("platform:subscriptions:" + appTwo);
        assertThat(one.messageGroup()).as("mutant: revert SubscriptionsSynced#messageGroup to the shared constant")
                .isNotEqualTo(two.messageGroup());
    }

    static Stream<Arguments> badSyncCommands() {
        return Stream.of(
                Arguments.of("missing application code", new SyncSubscriptionsCommand(APP_ID, null, null, List.of(), false), "APPLICATION_CODE_REQUIRED"),
                Arguments.of("entry missing code", sync("subsyncbad", false, row(" ", "X")), "CODE_REQUIRED"),
                Arguments.of("entry missing name", sync("subsyncbad", false, row("subsync-noname", null)), "NAME_REQUIRED"),
                Arguments.of("entry missing target", sync("subsyncbad", false,
                        row("subsync-notarget", "X", " ", null, null, null, true, "subsync:a:b:c")), "TARGET_REQUIRED"),
                Arguments.of("entry missing event types", sync("subsyncbad", false,
                        row("subsync-noet", "X", "https://x.example.test", null, null, null, true)), "EVENT_TYPES_REQUIRED"),
                // C13: sharedConnection:true without a connectionCode — mutant: drop the check.
                Arguments.of("sharedConnection without connectionCode", sync("subsyncbad", false,
                        rowByConnectionCode("subsync-sharednocode", "X", null, true, "subsync:a:b:c")),
                        "SHARED_CONNECTION_REQUIRES_CODE"));
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
        var got = repo.findByCode("Raw-" + RUN, appCode, null).orElseThrow();
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

    // ── C11 — client scoping (`code-first-connections.md` §3) ────────────────

    /// Matching/creation are scoped to `(applicationCode, clientId)`: the SAME
    /// code under three different clients (client A, client B, and the global
    /// scope) is three DIFFERENT rows, never a match across scopes, and each
    /// created row carries the request's client (mutant: load by application
    /// only, dropping the client scope, for the MATCH path and "created rows
    /// not stamped with the client").
    @Test
    void syncMatchesAndCreatesAreScopedToTheRequestsClientNeverCrossingIntoAnotherClientOrGlobal() {
        String appCode = "subsyncclia" + RUN;
        String clientA = EntityType.CLIENT.generate();
        String clientB = EntityType.CLIENT.generate();
        String c = code("subsyncscope-shared-code");

        var forA = runAsAnchor(SyncSubscriptions.of(repo, connections, pools), sync(appCode, clientA, false, row(c, "A v1")));
        assertThat(forA.created()).isEqualTo(1);
        Subscription rowA = repo.findByCode(c, appCode, clientA).orElseThrow();
        assertThat(rowA.clientId()).as("a created row carries the request's client").isEqualTo(clientA);

        var forB = runAsAnchor(SyncSubscriptions.of(repo, connections, pools), sync(appCode, clientB, false, row(c, "B v1")));
        assertThat(forB.created()).as("same code, different client — a DIFFERENT row, not an update").isEqualTo(1);
        assertThat(forB.updated()).isZero();
        Subscription rowB = repo.findByCode(c, appCode, clientB).orElseThrow();
        assertThat(rowB.clientId()).isEqualTo(clientB);
        assertThat(rowB.id()).isNotEqualTo(rowA.id());
        assertThat(reload(rowA.id()).name()).as("client A's row untouched by client B's sync").isEqualTo("A v1");

        var global = runAsAnchor(SyncSubscriptions.of(repo, connections, pools), sync(appCode, false, row(c, "Global v1")));
        assertThat(global.created()).as("same code, no client — a THIRD, global row").isEqualTo(1);
        Subscription rowGlobal = repo.findByCode(c, appCode, null).orElseThrow();
        assertThat(rowGlobal.clientId()).isNull();
        assertThat(reload(rowA.id()).name()).isEqualTo("A v1");
        assertThat(reload(rowB.id()).name()).isEqualTo("B v1");

        // Re-syncing client A now UPDATES only client A's row.
        var forAAgain = runAsAnchor(SyncSubscriptions.of(repo, connections, pools), sync(appCode, clientA, false, row(c, "A v2")));
        assertThat(forAAgain.created()).isZero();
        assertThat(forAAgain.updated()).isEqualTo(1);
        assertThat(reload(rowA.id()).name()).isEqualTo("A v2");
        assertThat(reload(rowB.id()).name()).as("client B's row untouched by client A's sync").isEqualTo("B v1");
        assertThat(reload(rowGlobal.id()).name()).as("the global row untouched by client A's sync").isEqualTo("Global v1");
    }

    /// The removal path's client filter: `removeUnlisted` never sweeps a
    /// sibling client's row or the application's global row, even though both
    /// are owned `API` rows of the SAME application (mutant: drop the client
    /// filter on the removal path).
    @Test
    void syncRemovalNeverTouchesAnotherClientsOrTheGlobalRows() {
        String appCode = "subsyncclir" + RUN;
        String clientX = EntityType.CLIENT.generate();
        String clientY = EntityType.CLIENT.generate();

        Subscription otherClient = reload(seedSubscription(code("subsyncclir-otherclient"), appCode, clientY, SubscriptionSource.API).id());
        Subscription global = reload(seedSubscription(code("subsyncclir-global"), appCode, null, SubscriptionSource.API).id());
        Subscription uiInScope = reload(seedSubscription(code("subsyncclir-ui"), appCode, clientX, SubscriptionSource.UI).id());
        Subscription removable = reload(seedSubscription(code("subsyncclir-removable"), appCode, clientX, SubscriptionSource.API).id());

        var ev = runAsAnchor(SyncSubscriptions.of(repo, connections, pools), sync(appCode, clientX, true));

        assertThat(ev.deleted()).as("exactly the one owned, in-scope, API-sourced row").isEqualTo(1);
        assertThat(repo.findById(removable.id())).isEmpty();
        assertThat(reload(otherClient.id())).as("client filter — a sibling client's row survives").isEqualTo(otherClient);
        assertThat(reload(global.id())).as("the application's global row is a different key entirely — survives").isEqualTo(global);
        assertThat(reload(uiInScope.id())).as("a UI row in scope is never swept").isEqualTo(uiInScope);
    }

    // ── C12 — connection namespace resolution ─────────────────────────────────

    /// A bare `connectionCode` resolves ONLY this application's own connection
    /// namespace — a SHARED connection of the same code is a different key
    /// entirely and is never found (mutant: add a fallback from the
    /// application namespace to the shared one).
    @Test
    void syncByConnectionCodeResolvesOnlyThisApplicationsNamespaceNeverFallingBackToShared() {
        String appCode = "subsyncns1" + RUN;
        String connCode = code("subsyncns1-conn");
        var appOwned = seedConnection(connCode, appCode, null);
        seedConnection(connCode, null, null); // a SHARED connection, same code

        var ev = runAsAnchor(SyncSubscriptions.of(repo, connections, pools),
                sync(appCode, false, rowByConnectionCode(code("subsyncns1-sub"), "S", connCode, false, "subsync:a:b:c")));
        assertThat(ev.created()).isEqualTo(1);
        Subscription s = repo.findByCode(code("subsyncns1-sub"), appCode, null).orElseThrow();
        assertThat(s.connectionId()).as("resolves the application-owned connection, not the shared one").isEqualTo(appOwned.id());
    }

    /// `sharedConnection: true` resolves ONLY the shared namespace — the
    /// reverse of the above (mutant: the reverse fallback, shared → application).
    @Test
    void syncSharedConnectionTrueResolvesOnlyTheSharedNamespace() {
        String appCode = "subsyncns2" + RUN;
        String connCode = code("subsyncns2-conn");
        seedConnection(connCode, appCode, null); // application-owned, same code
        var shared = seedConnection(connCode, null, null);

        var ev = runAsAnchor(SyncSubscriptions.of(repo, connections, pools),
                sync(appCode, false, rowByConnectionCode(code("subsyncns2-sub"), "S", connCode, true, "subsync:a:b:c")));
        assertThat(ev.created()).isEqualTo(1);
        Subscription s = repo.findByCode(code("subsyncns2-sub"), appCode, null).orElseThrow();
        assertThat(s.connectionId()).as("resolves the shared connection, not the application-owned one").isEqualTo(shared.id());
    }

    /// The explicit-namespace rule has NO fallback: a bare code with only a
    /// SHARED connection at that code is 404, not a silent match (this is the
    /// specific case the hand-off calls out — a later application-owned
    /// connection at the same code must never silently switch which
    /// credentials sign a subscription's deliveries).
    @Test
    void syncByConnectionCodeIsNotFoundWhenOnlyASharedConnectionExistsAndSharedConnectionIsNotSet() {
        String appCode = "subsyncns3" + RUN;
        String connCode = code("subsyncns3-conn");
        seedConnection(connCode, null, null); // ONLY a shared connection at this code

        assertUseCaseError(() -> runAsAnchor(SyncSubscriptions.of(repo, connections, pools),
                        sync(appCode, false, rowByConnectionCode(code("subsyncns3-sub"), "S", connCode, false, "subsync:a:b:c"))),
                UseCaseError.NotFound.class, "CONNECTION_NOT_FOUND");
        assertThat(repo.findByApplicationCode(appCode)).as("nothing written").isEmpty();
    }

    /// No fallback in the OTHER direction either: `sharedConnection: true` with
    /// only an APPLICATION-OWNED connection at that code is 404. A definition
    /// that says "the shared one" must never be signed with the application's
    /// own credentials because the shared connection happens to be missing in
    /// this environment.
    @Test
    void syncSharedConnectionIsNotFoundWhenOnlyAnApplicationOwnedConnectionExists() {
        String appCode = "subsyncns3b" + RUN;
        String connCode = code("subsyncns3b-conn");
        seedConnection(connCode, appCode, null); // ONLY this application's own connection at this code

        assertUseCaseError(() -> runAsAnchor(SyncSubscriptions.of(repo, connections, pools),
                        sync(appCode, false, rowByConnectionCode(code("subsyncns3b-sub"), "S", connCode, true, "subsync:a:b:c"))),
                UseCaseError.NotFound.class, "CONNECTION_NOT_FOUND");
        assertThat(repo.findByApplicationCode(appCode)).as("nothing written").isEmpty();
    }

    /// Within one namespace, a client-scoped sync prefers its OWN client's
    /// connection over the global one; a sync for a DIFFERENT client (with no
    /// connection of its own at that code) falls back to the global one
    /// (mutant: skip the client-first lookup step).
    @Test
    void syncClientScopedPrefersItsOwnClientsConnectionThenFallsBackToGlobalWithinTheNamespace() {
        String appCode = "subsyncns4" + RUN;
        String clientA = EntityType.CLIENT.generate();
        String clientB = EntityType.CLIENT.generate();
        String connCode = code("subsyncns4-conn");
        var clientScoped = seedConnection(connCode, appCode, clientA);
        var global = seedConnection(connCode, appCode, null);

        var forA = runAsAnchor(SyncSubscriptions.of(repo, connections, pools),
                sync(appCode, clientA, false, rowByConnectionCode(code("subsyncns4-sub-a"), "A", connCode, false, "subsync:a:b:c")));
        assertThat(forA.created()).isEqualTo(1);
        assertThat(repo.findByCode(code("subsyncns4-sub-a"), appCode, clientA).orElseThrow().connectionId())
                .as("client A has its OWN connection at this code — resolves it, not the global one").isEqualTo(clientScoped.id());

        var forB = runAsAnchor(SyncSubscriptions.of(repo, connections, pools),
                sync(appCode, clientB, false, rowByConnectionCode(code("subsyncns4-sub-b"), "B", connCode, false, "subsync:a:b:c")));
        assertThat(forB.created()).isEqualTo(1);
        assertThat(repo.findByCode(code("subsyncns4-sub-b"), appCode, clientB).orElseThrow().connectionId())
                .as("client B has none — falls back to the global connection in the SAME namespace").isEqualTo(global.id());
    }

    /// A client-less sync never resolves a client-scoped connection, even at
    /// the exact code it asked for and even with no global alternative.
    @Test
    void syncClientLessNeverResolvesAClientScopedConnection() {
        String appCode = "subsyncns5" + RUN;
        String clientA = EntityType.CLIENT.generate();
        String connCode = code("subsyncns5-conn");
        seedConnection(connCode, appCode, clientA); // ONLY a client-scoped connection

        assertUseCaseError(() -> runAsAnchor(SyncSubscriptions.of(repo, connections, pools),
                        sync(appCode, false, rowByConnectionCode(code("subsyncns5-sub"), "S", connCode, false, "subsync:a:b:c"))),
                UseCaseError.NotFound.class, "CONNECTION_NOT_FOUND");
    }

    // ── C13 — connectionId/connectionCode mismatch and scope checks ─────────

    private static SyncSubscriptionInput rowByConnectionIdAndCode(String code, String name, String connectionId,
                                                                   String connectionCode, boolean sharedConnection, String... patterns) {
        var bindings = Stream.of(patterns).map(p -> new SyncEventTypeBindingInput(p, null)).toList();
        return new SyncSubscriptionInput(code, name, null, "https://" + code + ".example.test/hook", connectionId,
                connectionCode, sharedConnection, bindings, null, null, null, null, true);
    }

    private static SyncSubscriptionInput rowByConnectionId(String code, String name, String connectionId, String... patterns) {
        return rowByConnectionIdAndCode(code, name, connectionId, null, false, patterns);
    }

    @Test
    void syncConnectionIdAndConnectionCodeNamingDifferentConnectionsIsConnectionMismatch() {
        String appCode = "subsyncc13a" + RUN;
        var connA = seedConnection(code("subsyncc13a-a"), appCode, null);
        seedConnection(code("subsyncc13a-b"), appCode, null);

        assertUseCaseError(() -> runAsAnchor(SyncSubscriptions.of(repo, connections, pools), sync(appCode, false,
                        rowByConnectionIdAndCode(code("subsyncc13a-sub"), "S", connA.id(), code("subsyncc13a-b"), false, "subsync:a:b:c"))),
                UseCaseError.Validation.class, "CONNECTION_MISMATCH");
    }

    /// Scope check, "other client" clause: a client-scoped sync's
    /// `connectionId` names a DIFFERENT client's connection (mutant: drop the
    /// `!= cmd.clientId()` half of the check).
    @Test
    void syncConnectionIdScopedToAnotherClientIsScopeMismatch() {
        String appCode = "subsyncc13b" + RUN;
        String clientA = EntityType.CLIENT.generate();
        String clientB = EntityType.CLIENT.generate();
        var conn = seedConnection(code("subsyncc13b-conn"), appCode, clientB);

        assertUseCaseError(() -> runAsAnchor(SyncSubscriptions.of(repo, connections, pools), sync(appCode, clientA, false,
                        rowByConnectionId(code("subsyncc13b-sub"), "S", conn.id(), "subsync:a:b:c"))),
                UseCaseError.Validation.class, "CONNECTION_SCOPE_MISMATCH");
    }

    /// Scope check, "global sync with a client-scoped id" clause: a
    /// CLIENT-LESS sync's `connectionId` names a client-scoped connection
    /// (mutant: drop the `cmd.clientId() == null ||` half of the check —
    /// distinct from the above, which needs BOTH sides non-null to observe).
    @Test
    void syncConnectionIdScopedToAClientButTheSyncIsGlobalIsScopeMismatch() {
        String appCode = "subsyncc13c" + RUN;
        String clientA = EntityType.CLIENT.generate();
        var conn = seedConnection(code("subsyncc13c-conn"), appCode, clientA);

        assertUseCaseError(() -> runAsAnchor(SyncSubscriptions.of(repo, connections, pools), sync(appCode, false,
                        rowByConnectionId(code("subsyncc13c-sub"), "S", conn.id(), "subsync:a:b:c"))),
                UseCaseError.Validation.class, "CONNECTION_SCOPE_MISMATCH");
    }

    /// Scope check, "other application" clause: `connectionId` names a
    /// connection owned by a DIFFERENT application (mutant: drop the clause).
    @Test
    void syncConnectionIdFromAnotherApplicationIsScopeMismatch() {
        String appCode = "subsyncc13d" + RUN;
        String otherAppCode = "subsyncc13other" + RUN;
        var conn = seedConnection(code("subsyncc13d-conn"), otherAppCode, null);

        assertUseCaseError(() -> runAsAnchor(SyncSubscriptions.of(repo, connections, pools), sync(appCode, false,
                        rowByConnectionId(code("subsyncc13d-sub"), "S", conn.id(), "subsync:a:b:c"))),
                UseCaseError.Validation.class, "CONNECTION_SCOPE_MISMATCH");
    }

    /// A SHARED connection named by id is allowed for ANY application or
    /// client — the positive case the scope checks must not wrongly refuse
    /// (mutant: refuse a shared connection the same as an owned one).
    @Test
    void syncConnectionIdOfASharedConnectionIsAllowedRegardlessOfApplicationOrClient() {
        String appCode = "subsyncc13e" + RUN;
        var shared = seedConnection(code("subsyncc13e-conn"), null, null);

        var ev = runAsAnchor(SyncSubscriptions.of(repo, connections, pools), sync(appCode, false,
                rowByConnectionId(code("subsyncc13e-sub"), "S", shared.id(), "subsync:a:b:c")));
        assertThat(ev.created()).isEqualTo(1);
        assertThat(repo.findByCode(code("subsyncc13e-sub"), appCode, null).orElseThrow().connectionId()).isEqualTo(shared.id());
    }

    // ── C15 — the seeded schema, and authorization's client-access check ────

    @Test
    void syncRollupEventValidatesAgainstTheSeededSchemaIncludingClientId() {
        String appCode = "subsyncc15" + RUN;
        String client = EntityType.CLIENT.generate();

        SubscriptionsSynced ev = runAsAnchor(SyncSubscriptions.of(repo, connections, pools),
                sync(appCode, client, false, row(code("subsyncc15-sub"), "Evt")));

        var rollupRow = DB.fetch("SELECT data::text AS data FROM msg_events WHERE id = ?", ev.eventId());
        assertThat(rollupRow).hasSize(1);
        JsonNode data = json(rollupRow.getFirst().get("data", String.class));
        assertThat(data.propertyNames()).containsExactlyInAnyOrder(
                "applicationCode", "clientId", "created", "updated", "deleted", "syncedCodes");
        assertThat(data.get("clientId").asText()).isEqualTo(client);

        JsonNode schema = PlatformEventSchemas.all().get(SubscriptionEvents.SYNCED);
        assertThat(schema).as("schema seeded for " + SubscriptionEvents.SYNCED).isNotNull();
        assertMatchesSchema(schema, data);
    }

    /// Authorization requires client access when `clientId` is given, but NOT
    /// for a client-less sync (mutant: drop the client-access check in
    /// [Access#checkSyncAccess] — a caller with application access only would
    /// then reach ANY client's subscriptions).
    @Test
    void syncAuthorizationRequiresClientAccessOnlyWhenClientIdIsGiven() {
        String appCode = "sc15auth" + RUN;
        String ownClient = EntityType.CLIENT.generate();
        String otherClient = EntityType.CLIENT.generate();
        var appAccessOnly = new AuthContext(EntityType.PRINCIPAL.generate(), Scope.CLIENT, "c@x.io",
                List.of(ownClient), List.of(), List.of(APP_ID), false, List.of());

        assertUseCaseError(() -> Auth.runAs(appAccessOnly, () -> SyncSubscriptions.of(repo, connections, pools).run(uow,
                        sync(appCode, otherClient, false, row(code("sc15auth-a"), "A")), ExecutionContext.of(appAccessOnly.principalId()))),
                UseCaseError.Authorization.class, "FORBIDDEN");

        var withOwnClient = Auth.runAs(appAccessOnly, () -> SyncSubscriptions.of(repo, connections, pools).run(uow,
                sync(appCode, ownClient, false, row(code("sc15auth-b"), "B")), ExecutionContext.of(appAccessOnly.principalId())));
        assertThat(withOwnClient.created()).isEqualTo(1);

        // Client-less: application access alone is enough, even for a non-anchor.
        var clientLess = Auth.runAs(appAccessOnly, () -> SyncSubscriptions.of(repo, connections, pools).run(uow,
                sync(appCode, false, row(code("sc15auth-c"), "C")), ExecutionContext.of(appAccessOnly.principalId())));
        assertThat(clientLess.created()).isEqualTo(1);
    }

    // ── Repository reads ───────────────────────────────────────────────────

    @Test
    void listFiltersCombineResultsAreOrderedByCodeAndHydrated() {
        String client = EntityType.CLIENT.generate();
        var a = created(code("sublist-b"), "B");
        var b = runAsAnchor(CreateSubscription.of(repo, connections, REACH), new CreateCommand(code("sublist-a"), "A", ENDPOINT, null, client,
                null, null, null, BINDINGS, List.of(new ConfigEntry("k", "v")), null, null, null, null, null, null, null));
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
