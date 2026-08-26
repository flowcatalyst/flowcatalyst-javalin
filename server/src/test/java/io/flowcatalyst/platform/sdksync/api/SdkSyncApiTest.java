package io.flowcatalyst.platform.sdksync.api;

import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ApplicationType;
import io.flowcatalyst.platform.connection.ConnectionRepository;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// The ten SDK self-registration routes end to end (`docs/spec/sdksync.md`):
/// the gates, application resolution, the `removeUnlisted` query flag, and
/// the two response shapes.
@SuppressWarnings("deprecation")
class SdkSyncApiTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final String APP = "sdksync" + RUN;
    private static final String OTHER_APP = "sdkother" + RUN;

    private static final UnitOfWork UOW = new UnitOfWork(TestPg.dataSource(), new PlatformSink(Json.MAPPER));
    private static final ApplicationRepository APPS = new ApplicationRepository(TestPg.dataSource());

    /// A real TSID: `client_id` is `varchar(17)`, so a readable string like
    /// `cli_sdksync_<run>` overflows the column.
    private static final String CALLER_CLIENT = EntityType.CLIENT.generate();

    private static String appId;
    private static String otherAppId;
    private static TestHttp http;

    /// An anchor: every permission, every application.
    private static String[] anchor() {
        return new String[] {
                Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
                Authenticator.TEST_SCOPE, "ANCHOR"};
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

        var state = new SdkSyncApi.State(APPS,
                new EventTypeRepository(TestPg.dataSource()), new RoleRepository(TestPg.dataSource()),
                new SubscriptionRepository(TestPg.dataSource()), new ConnectionRepository(TestPg.dataSource()),
                new ProcessRepository(TestPg.dataSource()), new DispatchPoolRepository(TestPg.dataSource()),
                new ScheduledJobRepository(TestPg.dataSource()), new OpenApiSpecRepository(TestPg.dataSource()),
                new AppDocRepository(TestPg.dataSource()), new PrincipalRepository(TestPg.dataSource()), UOW);

        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        http = new TestHttp(cfg -> {
            HttpError.install(cfg.routes);
            cfg.routes.before("/api/*", auth);
            SdkSyncApi.register(cfg.routes, state);
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
