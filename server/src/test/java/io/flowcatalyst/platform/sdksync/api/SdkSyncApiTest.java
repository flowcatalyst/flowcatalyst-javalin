package io.flowcatalyst.platform.sdksync.api;

import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ApplicationType;
import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientIdentifier;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.connection.ConnectionRepository;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import io.flowcatalyst.platform.dispatchpool.DispatchPoolRepository;
import io.flowcatalyst.platform.docs.AppDocRepository;
import io.flowcatalyst.platform.eventtype.EventTypeRepository;
import io.flowcatalyst.platform.openapispecs.OpenApiSpecRepository;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.process.ProcessRepository;
import io.flowcatalyst.platform.role.RoleRepository;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.platform.shared.auth.ClaimsResolver;
import io.flowcatalyst.platform.shared.auth.JwtVerifier;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.platform.subscription.SubscriptionRepository;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import tools.jackson.databind.JsonNode;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;

import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS;
import static org.assertj.core.api.Assertions.assertThat;

/// The ten SDK self-registration routes end to end (`docs/spec/sdksync.md`):
/// the gates, application resolution, the `removeUnlisted` query flag, and
/// the two response shapes.
@SuppressWarnings("deprecation")
class SdkSyncApiTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final String APP = "sdksync" + RUN;
    private static final String OTHER_APP = "sdkother" + RUN;
    /// A separate application, WITH a provisioned service account — every
    /// other route here needs none, but `connections/sync` refuses without
    /// one (`APPLICATION_SERVICE_ACCOUNT_REQUIRED`), so [#APP] itself must
    /// stay bare (other routes' `unknownApplicationIsNotFound`-style
    /// assumptions are unaffected either way).
    private static final String CONN_APP = "sdksyncconn" + RUN;

    private static final UnitOfWork UOW = new UnitOfWork(TestPg.dataSource(), new PlatformSink(Json.MAPPER));
    private static final ApplicationRepository APPS = new ApplicationRepository(TestPg.dataSource());
    private static final ClientRepository CLIENTS = new ClientRepository(TestPg.dataSource());

    /// A real TSID: `client_id` is `varchar(17)`, so a readable string like
    /// `cli_sdksync_<run>` overflows the column.
    private static final String CALLER_CLIENT = EntityType.CLIENT.generate();

    private static String appId;
    private static String otherAppId;
    private static String connAppId;
    private static Client connClient;
    private static TestHttp http;

    /// An anchor: every permission, every application.
    private static String[] anchor() {
        return new String[] {
                Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
                Authenticator.TEST_SCOPE, "ANCHOR",
                Authenticator.TEST_PERMISSIONS, "platform:*:*:*"};
    }

    /// A client-scoped caller holding exactly `permissions`, bound to
    /// `applicationId` — the shape that makes the per-application check
    /// observable.
    private static String[] boundTo(String applicationId, String permissions) {
        return new String[] {
                Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
                Authenticator.TEST_SCOPE, "CLIENT",
                Authenticator.TEST_CLIENTS, CALLER_CLIENT,
                Authenticator.TEST_APPLICATIONS, applicationId,
                Authenticator.TEST_PERMISSIONS, permissions};
    }

    @BeforeAll
    static void start() {
        appId = seedApplication(APP);
        otherAppId = seedApplication(OTHER_APP);
        connAppId = seedApplicationWithServiceAccount(CONN_APP);
        connClient = seedClient("sdksyncconncli");

        var state = new SdkSyncApi.State(APPS,
                new EventTypeRepository(TestPg.dataSource()), new RoleRepository(TestPg.dataSource()),
                new SubscriptionRepository(TestPg.dataSource()), new ConnectionRepository(TestPg.dataSource()),
                CLIENTS,
                new ProcessRepository(TestPg.dataSource()), new DispatchPoolRepository(TestPg.dataSource()),
                new ScheduledJobRepository(TestPg.dataSource()), new OpenApiSpecRepository(TestPg.dataSource()),
                new AppDocRepository(TestPg.dataSource()), new PrincipalRepository(TestPg.dataSource()), UOW,
                new io.flowcatalyst.platform.function.TriggerObjectRepository(TestPg.dataSource()),
                new io.flowcatalyst.platform.application.ClientConfigRepository(TestPg.dataSource()));

        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before("/api/*", auth);
            SdkSyncApi.register(routes, state);
        });
    }

    @AfterAll
    static void stop() {
        http.close();
    }

    private static String seedApplication(String code) {
        var app = Application.create(ApplicationType.INTEGRATION, code, code + " app");
        UOW.inTransaction(tx -> {
            APPS.persist(app, tx.dbTx());
            return null;
        });
        return app.id();
    }

    /// `app_applications.service_account_id` is FK'd to `iam_principals.id`
    /// (a `SERVICE`-typed principal) — `connections/sync` refuses without one.
    private static String seedApplicationWithServiceAccount(String code) {
        var app = Application.create(ApplicationType.INTEGRATION, code, code + " app")
                .attachServiceAccount(servicePrincipal(code));
        UOW.inTransaction(tx -> {
            APPS.persist(app, tx.dbTx());
            return null;
        });
        return app.id();
    }

    private static String servicePrincipal(String tag) {
        String id = EntityType.SERVICE_ACCOUNT.generate();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        DSL.using(TestPg.dataSource(), SQLDialect.POSTGRES).insertInto(IAM_PRINCIPALS)
                .set(IAM_PRINCIPALS.ID, id).set(IAM_PRINCIPALS.TYPE, "SERVICE")
                .set(IAM_PRINCIPALS.NAME, "sa " + tag).set(IAM_PRINCIPALS.ACTIVE, true)
                .set(IAM_PRINCIPALS.SERVICE_ACCOUNT_ID, id)
                .set(IAM_PRINCIPALS.CREATED_AT, now).set(IAM_PRINCIPALS.UPDATED_AT, now).execute();
        return id;
    }

    /// A real client, for the connection sync's `clientId` id-or-identifier-
    /// slug resolution (hand-off "Connection sync (new)").
    private static Client seedClient(String tag) {
        Client c = Client.create(tag, ClientIdentifier.parse(tag + "-" + RUN));
        try (java.sql.Connection conn = TestPg.dataSource().getConnection()) {
            conn.setAutoCommit(false);
            CLIENTS.persist(c, DbTx.wrapForBootstrap(conn));
            conn.commit();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return c;
    }

    private static String connSyncPath() {
        return "/api/applications/" + CONN_APP + "/connections/sync";
    }

    private static JsonNode json(HttpResponse<String> r) {
        try {
            return Json.MAPPER.readTree(r.body());
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + r.body(), e);
        }
    }

    private static HttpResponse<String> post(String path, String body, String... headers) {
        return http.post(path, body, headers);
    }

    private static String syncPath(String leaf) {
        return "/api/applications/" + APP + "/" + leaf + "/sync";
    }

    // ── Principals: the role ceiling (Go F8 e08eee4) ─────────────────────────

    /// The application route applies the role ceiling too: an application
    /// role holding only its application's permissions syncs for a caller
    /// holding none of them; one a super-admin gave a platform permission is
    /// 403 `ROLE_ABOVE_CALLER` and nobody is written.
    @Test
    void principalsSyncIsBoundedByTheRoleCeiling() {
        var roles = new RoleRepository(TestPg.dataSource());
        var own = io.flowcatalyst.platform.role.Role.create(APP, "ceil-viewer", "Viewer")
                .withPermissions(java.util.List.of(APP + ":order:view"));
        var elevated = io.flowcatalyst.platform.role.Role.create(APP, "ceil-elevated", "Elevated")
                .withPermissions(java.util.List.of("platform:iam:role:update"), io.flowcatalyst.platform.role.Role.CrossApplication.ALLOWED);
        UOW.inTransaction(tx -> { roles.persist(own, tx.dbTx()); roles.persist(elevated, tx.dbTx()); return null; });
        String[] sdk = {
                Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
                Authenticator.TEST_SCOPE, "ANCHOR",
                Authenticator.TEST_APPLICATIONS, appId,
                Authenticator.TEST_PERMISSIONS, "platform:iam:user:create"};
        var principals = new PrincipalRepository(TestPg.dataSource());
        String ownEmail = "ceilown" + RUN + "@example.test";
        String upEmail = "ceilup" + RUN + "@example.test";

        var ok = post(syncPath("principals"),
                "{\"principals\":[{\"email\":\"" + ownEmail + "\",\"name\":\"Own\",\"roles\":[\"" + own.name() + "\"],\"active\":true}]}", sdk);
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
        assertThat(principals.findByEmail(ownEmail)).isPresent();

        var refused = post(syncPath("principals"),
                "{\"principals\":[{\"email\":\"" + upEmail + "\",\"name\":\"Up\",\"roles\":[\"" + elevated.name() + "\"],\"active\":true}]}", sdk);
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(json(refused).get("error").asText()).isEqualTo("ROLE_ABOVE_CALLER");
        assertThat(principals.findByEmail(upEmail)).as("nothing written").isEmpty();
    }

    // ── Principals: names that are not roles (Go F15, 3aeb08c) ───────────────

    /// Go `TestSyncPrincipals_AppRouteSkipsUnknownRoles`. On the application's
    /// route a name that is not a role, whatever its prefix, is not assigned,
    /// creates nothing and refuses nothing: the entry syncs with its other
    /// roles and the response lists the name in `skippedRoles` (lower-cased,
    /// each once across the request, in payload order; omitted when none). A
    /// known role of another application still refuses the whole sync.
    @Test
    void principalsSyncSkipsRoleNamesThatAreNotRoles() {
        var roles = new RoleRepository(TestPg.dataSource());
        var clerk = io.flowcatalyst.platform.role.Role.create(APP, "f15-clerk", "Clerk")
                .withPermissions(java.util.List.of(APP + ":order:view"));
        var foreign = io.flowcatalyst.platform.role.Role.create(OTHER_APP, "f15-clerk", "Clerk")
                .withPermissions(java.util.List.of(OTHER_APP + ":order:view"));
        UOW.inTransaction(tx -> { roles.persist(clerk, tx.dbTx()); roles.persist(foreign, tx.dbTx()); return null; });
        String[] caller = {
                Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
                Authenticator.TEST_SCOPE, "ANCHOR",
                Authenticator.TEST_APPLICATIONS, appId,
                Authenticator.TEST_PERMISSIONS, "platform:iam:user:update"};
        var principals = new PrincipalRepository(TestPg.dataSource());
        String a = "f15a" + RUN + "@example.test";
        String b = "f15b" + RUN + "@example.test";
        String c = "f15c" + RUN + "@example.test";
        String pending = APP + ":f15-not-yet-synced";

        // Own-prefixed, unprefixed and another application's prefix alike are
        // skipped; the known role is assigned; the sync is not refused.
        var r = post(syncPath("principals"), "{\"principals\":["
                + "{\"email\":\"" + a + "\",\"name\":\"A\",\"roles\":[\"" + clerk.name() + "\",\"" + pending
                + "\",\"Unprefixed\",\"ghostapp:admin\"]},"
                + "{\"email\":\"" + b + "\",\"name\":\"B\",\"roles\":[\"" + pending.toUpperCase(Locale.ROOT)
                + "\",\"unprefixed\"]}]}", caller);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        var body = json(r);
        assertThat(body.get("created").asInt()).isEqualTo(2);
        assertThat(body.get("syncedCodes").toString()).isEqualTo("[\"" + a + "\",\"" + b + "\"]");
        assertThat(body.get("skippedRoles").toString())
                .isEqualTo("[\"" + pending + "\",\"unprefixed\",\"ghostapp:admin\"]");
        assertThat(principals.findByEmail(a).orElseThrow().roleNames()).as("no assignment for a name that is not a role")
                .containsExactly(clerk.name());
        assertThat(principals.findByEmail(b).orElseThrow().roleNames()).as("every name skipped: still synced, no role")
                .isEmpty();

        // Once the role exists, the same sync assigns it and reports nothing.
        var synced = post(syncPath("roles"), "{\"roles\":[{\"name\":\"f15-not-yet-synced\"}]}", anchor());
        assertThat(synced.statusCode()).as(synced.body()).isEqualTo(200);
        r = post(syncPath("principals"), "{\"principals\":[{\"email\":\"" + a + "\",\"name\":\"A\",\"roles\":[\""
                + clerk.name() + "\",\"" + pending + "\"]}]}", caller);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.body()).as("omitted when nothing was skipped").doesNotContain("skippedRoles");
        assertThat(principals.findByEmail(a).orElseThrow().roleNames()).containsExactlyInAnyOrder(clerk.name(), pending);

        // An existing user whose every name is skipped keeps none of this
        // application's SDK_SYNC roles: the merge replaces the set with the empty one.
        r = post(syncPath("principals"), "{\"principals\":[{\"email\":\"" + a + "\",\"name\":\"A\",\"roles\":[\"Nope:Gone\"]}]}", caller);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(json(r).get("updated").asInt()).isEqualTo(1);
        assertThat(json(r).get("skippedRoles").toString()).isEqualTo("[\"nope:gone\"]");
        assertThat(principals.findByEmail(a).orElseThrow().roleNames()).isEmpty();

        // A known role of another application still refuses the whole sync,
        // even beside an unknown name.
        r = post(syncPath("principals"), "{\"principals\":[{\"email\":\"" + c + "\",\"name\":\"C\",\"roles\":[\"ghostapp:admin\",\""
                + foreign.name() + "\"]}]}", caller);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(403);
        assertThat(json(r).get("error").asText()).isEqualTo("ROLE_APP_FORBIDDEN");
        assertThat(json(r).get("message").asText())
                .isEqualTo("role '" + foreign.name() + "' does not belong to application '" + APP + "'");
        assertThat(principals.findByEmail(c)).as("a refused sync writes nothing").isEmpty();
    }

    // ── Roles: the permission ceiling (Go F15, 2ba9763) ──────────────────────

    /// The wire shape of the role sync's ceiling: an application role a
    /// super-admin gave a platform permission cannot lose it through the SDK
    /// sync of a caller who lacks it, 403 `PERMISSION_ABOVE_CALLER` with the
    /// role-edit routes' message, nothing written; a re-sync that keeps it passes.
    @Test
    void rolesSyncIsBoundedByThePermissionCeiling() {
        var roles = new RoleRepository(TestPg.dataSource());
        var role = io.flowcatalyst.platform.role.Role.create(APP, "ceil-http", "Ceiling")
                .withApplicationId(appId)
                .withSource(io.flowcatalyst.platform.role.RoleSource.SDK)
                .withPermissions(java.util.List.of(APP + ":order:view", "platform:iam:user:view"),
                        io.flowcatalyst.platform.role.Role.CrossApplication.ALLOWED);
        UOW.inTransaction(tx -> { roles.persist(role, tx.dbTx()); return null; });
        String[] sdk = {
                Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
                Authenticator.TEST_SCOPE, "ANCHOR",
                Authenticator.TEST_APPLICATIONS, appId,
                Authenticator.TEST_PERMISSIONS, "platform:application-service:role:update"};

        var refused = post(syncPath("roles"),
                "{\"roles\":[{\"name\":\"ceil-http\",\"permissions\":[\"" + APP + ":order:view\"]}]}", sdk);
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(json(refused).get("error").asText()).isEqualTo("PERMISSION_ABOVE_CALLER");
        assertThat(json(refused).get("message").asText())
                .isEqualTo("You may only add or remove permissions you hold yourself: platform:iam:user:view");
        assertThat(roles.findByName(role.name()).orElseThrow().permissions()).as("nothing written")
                .containsExactlyInAnyOrder(APP + ":order:view", "platform:iam:user:view");

        var kept = post(syncPath("roles"), "{\"roles\":[{\"name\":\"ceil-http\"}]}", sdk);
        assertThat(kept.statusCode()).as(kept.body()).isEqualTo(200);
    }

    // ── Gates ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("anonymous is 403 UNAUTHENTICATED before anything else happens")
    void anonymousIsRejected() {
        var r = post(syncPath("event-types"), "{\"eventTypes\":[]}");
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(json(r).get("error").asText()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    @DisplayName("an unrelated permission is 403 PERMISSION_REQUIRED, not a 404 on the application")
    void wrongPermissionIsRejectedBeforeResolution() {
        // Order matters (spec §3): the gate runs before the application is
        // resolved, so an under-privileged caller must not be able to probe
        // which application codes exist.
        var r = post("/api/applications/does-not-exist-" + RUN + "/event-types/sync", "{\"eventTypes\":[]}",
                boundTo(appId, "platform:messaging:process:view"));
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(json(r).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");
    }

    @Test
    @DisplayName("an unknown application is 404 Application_NOT_FOUND")
    void unknownApplicationIsNotFound() {
        var r = post("/api/applications/nosuch" + RUN + "/event-types/sync", "{\"eventTypes\":[]}", anchor());
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(json(r).get("error").asText()).isEqualTo("Application_NOT_FOUND");
    }

    @Test
    @DisplayName("a caller bound to another application is 403 FORBIDDEN — on a handler-checked route")
    void handlerCheckedRouteRefusesAnotherApplication() {
        // Load-bearing: with removeUnlisted a sync PRUNES, so a route that
        // reached an application the caller is not bound to would delete
        // another tenant's rows. Event-types is checked in the handler,
        // because its command is keyed by code and carries no applicationId.
        var r = post(syncPath("event-types"), "{\"eventTypes\":[]}",
                boundTo(otherAppId, "platform:messaging:event-type:sync"));
        assertThat(r.statusCode()).as("body was: %s", r.body()).isEqualTo(403);
        assertThat(json(r).get("error").asText()).isEqualTo("FORBIDDEN");
        assertThat(json(r).get("message").asText()).contains(APP);
    }

    @Test
    @DisplayName("a caller bound to another application is 403 FORBIDDEN — on an operation-checked route")
    void operationCheckedRouteRefusesAnotherApplication() {
        // The same rule applied in the other place: roles' command carries an
        // applicationId, so the operation's authorize phase raises it. Both
        // paths are pinned because only one of them is exercised per route.
        //
        // The payload must be VALID to reach that phase — `validate` runs
        // before `authorize` (spec §3's failure order), so an empty list
        // answers 400 ROLES_REQUIRED and never tests authorisation at all.
        var r = post(syncPath("roles"), "{\"roles\":[{\"name\":\"viewer\",\"displayName\":\"Viewer\"}]}",
                boundTo(otherAppId, "platform:iam:role:manage"));
        assertThat(r.statusCode()).as("body was: %s", r.body()).isEqualTo(403);
        assertThat(json(r).get("error").asText()).isEqualTo("FORBIDDEN");
    }

    /// The SDK definitions sync publishes the OpenAPI document as the application's own
    /// service account (mirrors Go's TestCanSyncApplicationOpenAPIAdmitsApplicationService).
    @Test
    @DisplayName("openapi sync admits the application-service permission, confined to the bound application")
    void openapiSyncAdmitsApplicationServiceAndConfinesIt() {
        String spec = "{\"spec\":{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"T\",\"version\":\"1.0.0\"},\"paths\":{}}}";
        var own = post(syncPath("openapi"), spec,
                boundTo(appId, "platform:application-service:application-openapi:sync"));
        assertThat(own.statusCode()).as("body was: %s", own.body()).isEqualTo(200);
        var developer = post(syncPath("openapi"), spec, boundTo(appId, "platform:developer:application-openapi:sync"));
        assertThat(developer.statusCode()).as("developer sync still admitted: %s", developer.body()).isEqualTo(200);
        var other = post(syncPath("openapi"), spec,
                boundTo(otherAppId, "platform:application-service:application-openapi:sync"));
        assertThat(other.statusCode()).as("body was: %s", other.body()).isEqualTo(403);
        var viewOnly = post(syncPath("openapi"), spec,
                boundTo(appId, "platform:application-service:application:view"));
        assertThat(viewOnly.statusCode()).as("the application view permission must not admit an openapi sync")
                .isEqualTo(403);
        assertThat(json(viewOnly).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");
    }

    /// An application code longer than 17 characters: the sync rollup's audit row
    /// carries the code as its entity id (subject `platform.eventtypes.{code}`), and
    /// `aud_logs.entity_id` was varchar(17) — every such sync answered 500
    /// AUDIT_WRITE (V18 widens it). Mutant: drop V18.
    @Test
    void aSyncForAnApplicationWithALongCodeIsNotAnAuditWriteFailure() {
        String longCode = "sdksynclongcodeapp" + RUN; // well past 17 characters
        seedApplication(longCode);
        var r = post("/api/applications/" + longCode + "/event-types/sync",
                "{\"eventTypes\":[{\"code\":\"" + longCode + ":orders:order:created\",\"name\":\"Order created\"}]}",
                anchor());
        assertThat(r.statusCode()).as("body was: %s", r.body()).isEqualTo(200);
    }

    // ── Connections (code-first-connections.md §3, tests C9 + C10) ──────────

    private static String connBody(String code) {
        return "{\"connections\":[{\"code\":\"" + code + "\",\"name\":\"C\"}]}";
    }

    /// C10: each of the five permissions in the route's any-of gate admits
    /// ALONE (mutant: remove one from `Checks.requireAny(...)` — the ONE
    /// test using exactly that permission then 403s).
    @Test
    @DisplayName("connections sync: each of the five gate permissions alone admits")
    void connectionsSyncEachOfTheFivePermissionsAloneAdmits() {
        String[] perms = {
                "platform:messaging:connection:sync",
                "platform:messaging:connection:manage",
                "platform:application-service:connection:create",
                "platform:application-service:connection:update",
                "platform:application-service:connection:delete"};
        for (String perm : perms) {
            String code = "c10-" + perm.replace(':', '-') + "-" + RUN;
            var r = post(connSyncPath(), connBody(code), boundTo(connAppId, perm));
            assertThat(r.statusCode()).as("permission '%s' alone, body: %s", perm, r.body()).isEqualTo(200);
            assertThat(json(r).get("created").asInt()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("connections sync: none of the five is 403 PERMISSION_REQUIRED")
    void connectionsSyncWithNoneOfTheFivePermissionsIs403() {
        var r = post(connSyncPath(), connBody("c10-none-" + RUN), boundTo(connAppId, "platform:messaging:connection:view"));
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(json(r).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");
    }

    /// C9's HTTP-level half: `clientId` resolves by id OR identifier slug
    /// before authorization, and an unknown reference is 404.
    @Test
    @DisplayName("connections sync: clientId resolves by id or identifier slug")
    void connectionsSyncResolvesClientByIdOrIdentifierSlug() {
        String byId = "c9-byid-" + RUN;
        var r1 = post(connSyncPath(),
                "{\"clientId\":\"" + connClient.id() + "\",\"connections\":[{\"code\":\"" + byId + "\",\"name\":\"ById\"}]}", anchor());
        assertThat(r1.statusCode()).as("body: %s", r1.body()).isEqualTo(200);

        String bySlug = "c9-byslug-" + RUN;
        // Upper-cased on purpose — identifiers are normalised lower-case at create time.
        var r2 = post(connSyncPath(),
                "{\"clientId\":\"" + connClient.identifier().toUpperCase(Locale.ROOT)
                        + "\",\"connections\":[{\"code\":\"" + bySlug + "\",\"name\":\"BySlug\"}]}", anchor());
        assertThat(r2.statusCode()).as("body: %s", r2.body()).isEqualTo(200);
    }

    @Test
    @DisplayName("connections sync: an unknown clientId is 404 Client_NOT_FOUND")
    void connectionsSyncUnknownClientIsNotFound() {
        var r = post(connSyncPath(),
                "{\"clientId\":\"does-not-exist-" + RUN + "\",\"connections\":[]}", anchor());
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(json(r).get("error").asText()).isEqualTo("Client_NOT_FOUND");
    }

    // ── Subscriptions (code-first-connections.md §3) — the same HTTP-level
    // clientId resolution as connections, now on the subscriptions route too.

    /// The subscriptions route resolves `clientId` the same way the
    /// connections route does — by id OR identifier slug, before authorization.
    @Test
    @DisplayName("subscriptions sync: clientId resolves by id or identifier slug")
    void subscriptionsSyncResolvesClientByIdOrIdentifierSlug() {
        String byId = "subc9-byid-" + RUN;
        var r1 = post(syncPath("subscriptions"),
                "{\"clientId\":\"" + connClient.id() + "\",\"subscriptions\":[{\"code\":\"" + byId + "\",\"name\":\"ById\",\"target\":\"https://example.test/"
                        + byId + "\",\"eventTypes\":[{\"eventTypeCode\":\"test:a:b:c\"}]}]}", anchor());
        assertThat(r1.statusCode()).as("body: %s", r1.body()).isEqualTo(200);
        assertThat(json(r1).get("created").asInt()).isEqualTo(1);

        String bySlug = "subc9-byslug-" + RUN;
        // Upper-cased on purpose — identifiers are normalised lower-case at create time.
        var r2 = post(syncPath("subscriptions"),
                "{\"clientId\":\"" + connClient.identifier().toUpperCase(Locale.ROOT)
                        + "\",\"subscriptions\":[{\"code\":\"" + bySlug + "\",\"name\":\"BySlug\",\"target\":\"https://example.test/"
                        + bySlug + "\",\"eventTypes\":[{\"eventTypeCode\":\"test:a:b:c\"}]}]}", anchor());
        assertThat(r2.statusCode()).as("body: %s", r2.body()).isEqualTo(200);
        assertThat(json(r2).get("created").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("subscriptions sync: an unknown clientId is 404 Client_NOT_FOUND")
    void subscriptionsSyncUnknownClientIsNotFound() {
        var r = post(syncPath("subscriptions"),
                "{\"clientId\":\"does-not-exist-" + RUN + "\",\"subscriptions\":[]}", anchor());
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(json(r).get("error").asText()).isEqualTo("Client_NOT_FOUND");
    }

    // ── Happy paths ────────────────────────────────────────────────────────

    @Test
    @DisplayName("event-types sync answers the shared SyncResultResponse shape")
    void eventTypesSync() {
        var r = post(syncPath("event-types"),
                "{\"eventTypes\":[{\"code\":\"" + APP + ":orders:order:placed\",\"name\":\"Order Placed\"}]}", anchor());
        assertThat(r.statusCode()).as("body was: %s", r.body()).isEqualTo(200);
        var body = json(r);
        assertThat(body.get("applicationCode").asText()).isEqualTo(APP);
        assertThat(body.get("created").asInt()).isEqualTo(1);
        assertThat(body.get("syncedCodes").isArray()).isTrue();
        assertThat(body.get("syncedCodes").get(0).asText()).isEqualTo(APP + ":orders:order:placed");
    }

    @Test
    @DisplayName("a body with no list field syncs nothing rather than 400")
    void absentListIsAnEmptySync() {
        // Deviation (1) in the spec: huma rejected the missing required array
        // with 422; the command records coerce null to an empty list, and an
        // empty sync with removeUnlisted is a legitimate "prune all".
        var r = post(syncPath("dispatch-pools"), "{}", anchor());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(json(r).get("created").asInt()).isZero();
    }

    @Test
    @DisplayName("removeUnlisted is read from the query string, and only the literal true enables it")
    void removeUnlistedIsAQueryFlag() {
        // It decides whether a sync DELETES, so a typo must read as "no".
        post(syncPath("processes"), "{\"processes\":[{\"code\":\"" + APP + ":ops:p-one\",\"name\":\"One\"}]}", anchor());

        var bodyFlagIgnored = post(syncPath("processes"),
                "{\"processes\":[],\"removeUnlisted\":true}", anchor());
        assertThat(bodyFlagIgnored.statusCode()).isEqualTo(200);
        assertThat(json(bodyFlagIgnored).get("deleted").asInt())
                .as("the flag is not read from the body").isZero();

        var truthy = post(syncPath("processes") + "?removeUnlisted=1", "{\"processes\":[]}", anchor());
        assertThat(json(truthy).get("deleted").asInt())
                .as("only the literal \"true\" enables a prune; 1 must not").isZero();

        var real = post(syncPath("processes") + "?removeUnlisted=true", "{\"processes\":[]}", anchor());
        assertThat(json(real).get("deleted").asInt())
                .as("the unlisted process is pruned").isEqualTo(1);
    }

    @Test
    @DisplayName("the body-scoped process alias resolves the application from the body")
    void processesByBodyAlias() {
        var r = post("/api/processes/sync",
                "{\"applicationCode\":\"" + APP + "\",\"processes\":[{\"code\":\"" + APP + ":ops:alias-one\",\"name\":\"Alias\"}]}", anchor());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(json(r).get("applicationCode").asText()).isEqualTo(APP);

        // Absent applicationCode is a 404 from the lookup, not a validation
        // error — the lookup is the first thing that sees it (spec §3).
        var blank = post("/api/processes/sync", "{\"processes\":[]}", anchor());
        assertThat(blank.statusCode()).isEqualTo(404);
        assertThat(json(blank).get("error").asText()).isEqualTo("Application_NOT_FOUND");
    }

    @Test
    @DisplayName("docs sync replaces declaratively and reports the slugs")
    void docsSync() {
        var r = post(syncPath("docs"),
                "{\"docs\":[{\"slug\":\"intro\",\"title\":\"Intro\",\"content\":\"# Hello\"}]}", anchor());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(json(r).get("syncedCodes").get(0).asText()).isEqualTo("intro");

        // The payload IS the set: a second sync without the page removes it,
        // with no removeUnlisted flag involved.
        var replaced = post(syncPath("docs"), "{\"docs\":[]}", anchor());
        assertThat(json(replaced).get("deleted").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("scheduled-jobs sync has its own shape: job ids, and archiveUnlisted in the body")
    void scheduledJobsSync() {
        // A unique clientId, deliberately NOT the platform (null) scope.
        // archiveUnlisted sweeps the WHOLE clientId scope — it does not narrow
        // by application, even though the route is mounted under one and the
        // command carries an applicationId. On the platform scope this test
        // archived 20 of other tests' jobs and passed only in isolation. Go
        // carries the same rule and the same warning in its own sync tests.
        String scope = EntityType.CLIENT.generate();
        var r = post(syncPath("scheduled-jobs"),
                "{\"clientId\":\"" + scope + "\",\"jobs\":[{\"code\":\"nightly-" + RUN + "\",\"name\":\"Nightly\","
                        + "\"crons\":[\"0 0 0 * * *\"]}]}", anchor());
        assertThat(r.statusCode()).as("body was: %s", r.body()).isEqualTo(200);
        var body = json(r);
        assertThat(body.get("applicationCode").asText()).isEqualTo(APP);
        assertThat(body.has("syncedCodes")).as("not the shared shape").isFalse();
        assertThat(body.get("created").isArray()).as("ids, not a count").isTrue();
        assertThat(body.get("created").size()).isEqualTo(1);
        assertThat(body.get("updated").isArray()).isTrue();
        assertThat(body.get("archived").isArray())
                .as("always present, so 'archived nothing' and 'was not asked to' differ").isTrue();

        // archiveUnlisted is in the BODY on this route, unlike every other.
        var archived = post(syncPath("scheduled-jobs"),
                "{\"clientId\":\"" + scope + "\",\"jobs\":[],\"archiveUnlisted\":true}", anchor());
        assertThat(json(archived).get("archived").size())
                .as("only this scope's job; the sweep is bounded by clientId").isEqualTo(1);
    }

    @Test
    @DisplayName("openapi sync reports CURRENT, then UNCHANGED for the same spec")
    void openapiSync() {
        String spec = "{\"spec\":{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"T\",\"version\":\"1.0.0\"},\"paths\":{}}}";
        var first = post(syncPath("openapi"), spec, anchor());
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(json(first).get("status").asText()).isEqualTo("CURRENT");
        assertThat(json(first).get("unchanged").asBoolean()).isFalse();
        assertThat(json(first).has("archivedPriorVersion")).as("nothing to archive on a first upload").isFalse();

        var again = post(syncPath("openapi"), spec, anchor());
        assertThat(json(again).get("status").asText())
                .as("status is derived from unchanged, not carried").isEqualTo("UNCHANGED");
        assertThat(json(again).get("unchanged").asBoolean()).isTrue();
    }
}
