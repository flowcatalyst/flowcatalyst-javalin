package io.flowcatalyst.fcdev;

import static io.flowcatalyst.db.generated.Tables.MSG_CONNECTIONS;
import static io.flowcatalyst.db.generated.Tables.MSG_SUBSCRIPTIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.operations.ApplicationEvents.ApplicationCreated;
import io.flowcatalyst.platform.application.operations.CreateApplication;
import io.flowcatalyst.platform.application.operations.CreateCommand;
import io.flowcatalyst.platform.application.operations.ProvisionServiceAccount;
import io.flowcatalyst.platform.application.operations.ProvisionServiceAccountCommand;
import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientIdentifier;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.oauthclient.OAuthClientRepository;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountRepository;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Scope;
import io.flowcatalyst.platform.shared.database.Database;
import io.flowcatalyst.platform.shared.database.GatedDataSource;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.FlowCatalystClient;
import io.flowcatalyst.sdk.annotations.AsConnection;
import io.flowcatalyst.sdk.annotations.AsSubscription;
import io.flowcatalyst.sdk.annotations.DefinitionScanner;
import io.flowcatalyst.sdk.error.FlowCatalystException;
import io.flowcatalyst.sdk.sync.Definitions.Connection;
import io.flowcatalyst.sdk.sync.Definitions.DefinitionSet;
import io.flowcatalyst.sdk.sync.Definitions.Subscription;
import io.flowcatalyst.sdk.sync.Definitions.SubscriptionEventType;
import io.flowcatalyst.sdk.sync.DefinitionSyncException;
import io.flowcatalyst.sdk.sync.SyncOptions;
import io.flowcatalyst.sdk.sync.SyncResult.Category;
import io.flowcatalyst.sdk.usecase.ExecutionContext;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

/// The end-to-end pin for slice K4 (`docs/spec/code-first-connections.md`
/// §4a) — the test that would have caught the original defect ("a Laravel
/// application could not define a subscription in code"): the REAL `sdk/`
/// [io.flowcatalyst.sdk.sync.DefinitionSynchronizer], talking real HTTP with
/// a real OAuth client-credentials token, against a `fcdev start`-booted
/// in-process platform — never a stub server.
///
/// Lives in `fcdev` rather than `server` because `server` has (and must
/// keep) no dependency on the client SDK, and `fcdev` is the module that
/// already boots the full platform in-process for its own integration tests
/// ([StartIntegrationTest], [DevDispatchRouterConfigIntegrationTest]).
/// `sdk` is added here as a TEST-SCOPE dependency (`fcdev/pom.xml`) — the
/// only new inter-module edge this unit adds.
///
/// Setup goes around the SDK (an application, its provisioned service
/// account and OAuth client, a client) using the same production operations
/// [io.flowcatalyst.fcdev.DevBootstrap]/`ApplicationOperationsTest` use —
/// never the SDK itself, since the SDK cannot create applications. From the
/// first sync call onward, everything goes through the SDK over HTTP with a
/// real bearer token.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConnectionSyncEndToEndTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final String APP_CODE = "k4e2e" + RUN;
    private static final String CONN_CODE = "billing-hook-" + RUN;
    private static final String SUB_CODE = "order-placed-" + RUN;
    // Literal (not RUN-suffixed): annotation attributes must be compile-time
    // constants. Safe — each test class boots its OWN fresh embedded
    // database (see #boot), so there is no cross-run collision to avoid.
    private static final String SCANNED_CONN_CODE = "scanned-billing-hook";
    private static final String SCANNED_SUB_CODE = "scanned-order-placed";

    @AsConnection(code = SCANNED_CONN_CODE, name = "Scanned Billing Webhook")
    static final class ScannedBillingConnection {}

    @AsSubscription(code = SCANNED_SUB_CODE, name = "Scanned Order Placed",
            target = "/webhooks/orders-scanned",
            connectionCode = SCANNED_CONN_CODE,
            eventTypes = {"scanned:sales:order:placed"})
    static final class ScannedOrderPlacedHandler {}

    private Path root;
    private StartCommand.Started started;
    private GatedDataSource pool;
    private FlowCatalystClient sdk;
    private Client client;
    private String connectionId;

    @BeforeAll
    void boot() throws Exception {
        io.flowcatalyst.server.Logging.init(Map.of("FC_LOG_LEVEL", "warn", "FC_LOG_FORMAT", "text"));
        root = Files.createTempDirectory("fcdev-k4-e2e");
        var dataPath = root.resolve("flowcatalyst/embedded-pg");
        var pidFile = root.resolve("flowcatalyst/fcdev.pid");
        var cache = root.resolve("cache");
        var env = DevEnv.of(Map.of(
                "FC_EMBEDDED_DB_PATH", dataPath.toString(),
                "FC_DEV_PID_FILE", pidFile.toString(),
                "XDG_CACHE_HOME", cache.toString()));
        var sub = new StartCommand.Sub(env);
        new CommandLine(sub, new EnvFactory(env)).parseArgs(
                "--api-port", "0", "--metrics-port", "0", "--embedded-db-port", "0",
                "--router=false", "--stream=false", "--scheduler=false", "--scheduled-job=false", "--outbox=false");
        var paths = new DevPaths(root, cache);
        try {
            started = new StartCommand(env, paths, sub.opts, new io.prometheus.metrics.model.registry.PrometheusRegistry()).launch();
        } catch (Exception | ExceptionInInitializerError e) {
            LoggerFactory.getLogger(ConnectionSyncEndToEndTest.class)
                    .warn("embedded PostgreSQL could not start here; skipping", e);
            Assumptions.abort("embedded PostgreSQL cannot start in this environment: " + e);
            return;
        }

        // The SAME database the booted platform is using (never TestPg's —
        // that is a different, unrelated database).
        String pgUrl = started.embeddedPg().orElseThrow().url();
        pool = Database.newPool(pgUrl, 4);

        // The SAME app encryption key DevBootstrap.ensureAppKey persisted for
        // the running platform — ProvisionServiceAccount's OAuth client
        // secret must hash under the SAME key the platform's own /oauth/token
        // handler verifies against (both run in this one JVM/process).
        String appKey = Files.readString(root.resolve("flowcatalyst/app-key")).strip();
        Optional<Encryption> encryption = Encryption.fromKeys(appKey, "");
        assertThat(encryption).as("app key was persisted by DevBootstrap.ensureAppKey").isPresent();

        seedApplicationClientAndServiceAccount(encryption);

        sdk = FlowCatalystClient.builder()
                .baseUrl("http://localhost:" + started.apiPort())
                .clientCredentials(oauthClientId, oauthClientSecret)
                .build();
    }

    @AfterAll
    void shutdown() throws Exception {
        // FlowCatalystClient has no close() to call.
        if (pool != null) pool.close();
        if (started != null) started.close();
        if (root != null) EmbeddedPg.deleteTree(root);
    }

    // ── fixture: everything the SDK itself cannot create ───────────────

    private String applicationId;
    private String oauthClientId;
    private String oauthClientSecret;
    private String serviceAccountPrincipalId;

    private void seedApplicationClientAndServiceAccount(Optional<Encryption> encryption) {
        var applications = new ApplicationRepository(pool);
        var serviceAccounts = new ServiceAccountRepository(pool, encryption);
        var principals = new PrincipalRepository(pool);
        var oauthClients = new OAuthClientRepository(pool, applications);
        var clients = new ClientRepository(pool);
        var uow = new UnitOfWork(pool, new PlatformSink(Json.MAPPER));

        String bootstrapPrincipal = EntityType.PRINCIPAL.generate();
        AuthContext anchor = new AuthContext(bootstrapPrincipal, Scope.ANCHOR, "e2e@x.io",
                List.of("*"), List.of(), List.of(), true, List.of());
        ExecutionContext ec = ExecutionContext.of(bootstrapPrincipal);

        ApplicationCreated createdApp = Auth.runAs(anchor, () -> CreateApplication.of(applications)
                .run(uow, new CreateCommand(APP_CODE, "K4 e2e " + RUN, "INTEGRATION",
                        null, null, null, null, null, null), ec));
        applicationId = createdApp.applicationId();

        ProvisionServiceAccount.Result provisioned = Auth.runAs(anchor, () -> ProvisionServiceAccount
                .of(applications, serviceAccounts, principals, oauthClients, encryption)
                .run(uow, new ProvisionServiceAccountCommand(applicationId), ec));
        oauthClientId = provisioned.oauthClientClientId();
        oauthClientSecret = provisioned.oauthClientSecret();
        serviceAccountPrincipalId = provisioned.principalId();

        // A bare tnt_clients row — client CRUD is not this test's business,
        // same shorthand SdkSyncApiTest uses.
        client = Client.create("K4 e2e client " + RUN, ClientIdentifier.parse("k4e2ecli-" + RUN));
        try (java.sql.Connection conn = pool.getConnection()) {
            conn.setAutoCommit(false);
            clients.persist(client, DbTx.wrapForBootstrap(conn));
            conn.commit();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ── the pin ──────────────────────────────────────────────────────

    /// Connections (application-owned, global) then a subscription naming
    /// one by `connectionCode`, scoped to a client — through the real SDK
    /// synchronizer, over real HTTP, with a real bearer token minted for the
    /// application's own provisioned service account
    /// (`platform:application-service` permissions, per the hand-off). Then
    /// a second sync with the connection removed from the set and
    /// `removeUnlisted=true`, while the subscription above still references
    /// it: the platform refuses the whole sync with `409
    /// CONNECTION_REFERENCED`, and the SDK surfaces it as a
    /// [DefinitionSyncException] (a [FlowCatalystException]) naming the
    /// connection, rather than throwing a bare HTTP error or silently
    /// deleting the referenced connection. One test, not two independent
    /// ones, because the second half depends on the rows the first half
    /// created and JUnit does not otherwise guarantee method order.
    @Test
    void connectionThenClientScopedSubscriptionByCodeRoundTripThroughTheRealSdk() {
        DefinitionSet connectionSet = DefinitionSet.define(APP_CODE)
                .withConnections(List.of(Connection.of(CONN_CODE, "Billing Webhook")
                        .withDescription("K4 e2e")));
        sdk.definitions().sync(connectionSet);

        DefinitionSet subscriptionSet = DefinitionSet.define(APP_CODE)
                .forClient(client.identifier())
                .withSubscriptions(List.of(Subscription.of(
                                SUB_CODE, "Order Placed", "https://billing.example.com/hook",
                                List.of(SubscriptionEventType.of(APP_CODE + ":sales:order:placed")))
                        .withConnectionCode(CONN_CODE)));
        sdk.definitions().sync(subscriptionSet);

        var db = DSL.using(pool, SQLDialect.POSTGRES);

        Record connRow = db.select(MSG_CONNECTIONS.ID, MSG_CONNECTIONS.SOURCE, MSG_CONNECTIONS.APPLICATION_CODE,
                        MSG_CONNECTIONS.CLIENT_ID, MSG_CONNECTIONS.SERVICE_ACCOUNT_ID)
                .from(MSG_CONNECTIONS)
                .where(MSG_CONNECTIONS.CODE.eq(CONN_CODE).and(MSG_CONNECTIONS.APPLICATION_CODE.eq(APP_CODE)))
                .fetchOne();
        assertThat(connRow).as("the connection the SDK just synced").isNotNull();
        assertThat(connRow.get(MSG_CONNECTIONS.SOURCE)).isEqualTo("API");
        assertThat(connRow.get(MSG_CONNECTIONS.APPLICATION_CODE)).isEqualTo(APP_CODE);
        assertThat(connRow.get(MSG_CONNECTIONS.CLIENT_ID)).as("global — not client-scoped").isNull();
        assertThat(connRow.get(MSG_CONNECTIONS.SERVICE_ACCOUNT_ID)).as("the application's own provisioned service account")
                .isEqualTo(serviceAccountPrincipalId);
        connectionId = connRow.get(MSG_CONNECTIONS.ID);

        Record subRow = db.select(MSG_SUBSCRIPTIONS.CLIENT_ID, MSG_SUBSCRIPTIONS.CONNECTION_ID)
                .from(MSG_SUBSCRIPTIONS)
                .where(MSG_SUBSCRIPTIONS.CODE.eq(SUB_CODE).and(MSG_SUBSCRIPTIONS.APPLICATION_CODE.eq(APP_CODE)))
                .fetchOne();
        assertThat(subRow).as("the subscription the SDK just synced, naming the connection by code").isNotNull();
        assertThat(subRow.get(MSG_SUBSCRIPTIONS.CLIENT_ID)).as("scoped to the client the sync named").isEqualTo(client.id());
        assertThat(subRow.get(MSG_SUBSCRIPTIONS.CONNECTION_ID)).as("resolved from connectionCode to the connection's real id")
                .isEqualTo(connectionId);

        // ── @AsConnection scanning + per-client subscription target base URL ──
        //
        // Proves the annotation path and target resolution reach the REAL
        // platform, not just a stub: the connection is declared with
        // @AsConnection (scanned, not Definitions.Connection.of(...)), and
        // the subscription's target is a bare PATH resolved at sync time
        // against this client's own base URL (DefinitionSet#forClient's
        // targetBaseUrl overload) rather than sent as-is.

        DefinitionSet scannedConnectionSet =
                DefinitionScanner.scan(APP_CODE, List.of(ScannedBillingConnection.class));
        sdk.definitions().sync(scannedConnectionSet);

        DefinitionSet scannedSubscriptionSet =
                DefinitionScanner.scan(APP_CODE, List.of(ScannedOrderPlacedHandler.class))
                        .forClient(client.identifier(), "https://acme.example.test");
        sdk.definitions().sync(scannedSubscriptionSet);

        Record scannedSubRow = db.select(MSG_SUBSCRIPTIONS.CLIENT_ID, MSG_SUBSCRIPTIONS.TARGET)
                .from(MSG_SUBSCRIPTIONS)
                .where(MSG_SUBSCRIPTIONS.CODE.eq(SCANNED_SUB_CODE).and(MSG_SUBSCRIPTIONS.APPLICATION_CODE.eq(APP_CODE)))
                .fetchOne();
        assertThat(scannedSubRow).as("the scanned subscription the SDK just synced").isNotNull();
        assertThat(scannedSubRow.get(MSG_SUBSCRIPTIONS.CLIENT_ID))
                .as("scoped to the client via the scanned set's forClient()").isEqualTo(client.id());
        assertThat(scannedSubRow.get(MSG_SUBSCRIPTIONS.TARGET))
                .as("the relative target resolved against this client's own base URL, not sent as a bare path")
                .isEqualTo("https://acme.example.test/webhooks/orders-scanned");

        // ── sync again, connection unlisted + removeUnlisted, while referenced ──

        // An empty connections list would SKIP the category entirely
        // (omitting a category already skips it), so the real target must
        // stay reachable but simply unlisted: send a different placeholder
        // connection instead of an empty list.
        DefinitionSet syncWithoutTheConnection = DefinitionSet.define(APP_CODE)
                .withConnections(List.of(Connection.of("placeholder-" + RUN, "Placeholder")));

        DefinitionSyncException ex = catchThrowableOfType(DefinitionSyncException.class, () ->
                sdk.definitions().sync(syncWithoutTheConnection, SyncOptions.removingUnlisted()));
        assertThat(ex).as("the platform's 409 CONNECTION_REFERENCED must surface, not a bare HTTP error "
                + "and not a silent success").isNotNull();

        Category.Failed failed = (Category.Failed) ex.result().connections();
        assertThat(failed.error())
                .as("names the platform's CONNECTION_REFERENCED failure and the connection it names")
                .contains(CONN_CODE);

        // Nothing was removed: the referenced connection is still there,
        // exactly as before — the whole sync refused, not a partial prune.
        Integer stillThere = db.selectCount().from(MSG_CONNECTIONS)
                .where(MSG_CONNECTIONS.CODE.eq(CONN_CODE).and(MSG_CONNECTIONS.APPLICATION_CODE.eq(APP_CODE)))
                .fetchOne(0, Integer.class);
        assertThat(stillThere).as("the referenced connection was NOT removed").isEqualTo(1);
    }
}
