package io.flowcatalyst.platform.bff.api;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ApplicationType;
import io.flowcatalyst.platform.eventtype.EventType;
import io.flowcatalyst.platform.eventtype.EventTypeRepository;
import io.flowcatalyst.platform.openapispecs.OpenApiDocument;
import io.flowcatalyst.platform.openapispecs.OpenApiSpec;
import io.flowcatalyst.platform.openapispecs.OpenApiSpecRepository;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.platform.shared.auth.ClaimsResolver;
import io.flowcatalyst.platform.shared.auth.JwtVerifier;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// The `/bff/developer/*` surface end to end: the anchor gate on every
/// route, the current/versions/spec-by-id reads including the
/// belongs-to-app 404, and the platform-openapi sync write.
@SuppressWarnings("deprecation")
class DeveloperBffTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);

    private static final ApplicationRepository applicationRepo = new ApplicationRepository(TestPg.dataSource());
    private static final OpenApiSpecRepository specRepo = new OpenApiSpecRepository(TestPg.dataSource());
    private static final EventTypeRepository eventTypeRepo = new EventTypeRepository(TestPg.dataSource());
    private static final UnitOfWork uow = new UnitOfWork(TestPg.dataSource(), new PlatformSink(Json.MAPPER));
    private static TestHttp http;

    private static final String[] ANCHOR = {Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(), Authenticator.TEST_SCOPE, "ANCHOR", Authenticator.TEST_PERMISSIONS, "platform:*:*:*"};
    private static final String[] VIEWER = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_PERMISSIONS, "platform:developer:application-openapi:manage"};
    /// An anchor holding every permission EXCEPT the developer-openapi family (spec `reach-only-routes.md` §3).
    private static final String[] ANCHOR_NO_OPENAPI_PERMS = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:messaging:event-type:view"};
    /// An anchor holding only the specific view code (not the wildcard).
    private static final String[] ANCHOR_OPENAPI_VIEW_ONLY = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:developer:application-openapi:view"};

    @BeforeAll
    static void start() {
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before("/bff/*", auth);
            DeveloperBff.register(routes, new DeveloperBff.State(applicationRepo, specRepo, eventTypeRepo, uow,
                    () -> platformSpecJson()));
        });
    }

    @AfterAll
    static void stop() {
        http.close();
    }

    private static JsonNode platformSpecJson() {
        try {
            return Json.MAPPER.readTree("{\"openapi\":\"3.0.0\",\"info\":{\"version\":\"dev-" + RUN + "\"},\"paths\":{}}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode json(HttpResponse<String> r) {
        try {
            return Json.MAPPER.readTree(r.body());
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + r.body(), e);
        }
    }

    private static Application persistApplication(String code) {
        Application a = Application.create(ApplicationType.APPLICATION, code, "App " + code);
        uow.inTransaction(tx -> {
            applicationRepo.persist(a, tx.dbTx());
            return null;
        });
        return a;
    }

    private static OpenApiSpec persistSpec(String applicationId, String version) {
        var doc = OpenApiDocument.parse(Json.MAPPER.readTree("""
                {"openapi":"3.0.0","info":{"version":"%s"},"paths":{"/x":{"get":{}}}}
                """.formatted(version)));
        OpenApiSpec spec = OpenApiSpec.create(applicationId, version, doc, Instant.now(), null);
        uow.inTransaction(tx -> {
            specRepo.persist(spec, tx.dbTx());
            return null;
        });
        return spec;
    }

    // ── Load-bearing: anchor gate on every route ────────────────────────────

    @Test
    void everyRouteIsAnchorOnly() {
        Application app = persistApplication("devbff-gate-" + RUN);
        assertThat(http.get("/bff/developer/applications", VIEWER).statusCode()).isEqualTo(403);
        assertThat(http.get("/bff/developer/applications/" + app.id(), VIEWER).statusCode()).isEqualTo(403);
        assertThat(http.get("/bff/developer/applications/" + app.id() + "/openapi/current", VIEWER).statusCode()).isEqualTo(403);
        assertThat(http.get("/bff/developer/applications/" + app.id() + "/openapi/versions", VIEWER).statusCode()).isEqualTo(403);
        assertThat(http.get("/bff/developer/applications/" + app.id() + "/openapi/versions/oas_x", VIEWER).statusCode()).isEqualTo(403);
        assertThat(http.get("/bff/developer/applications/" + app.id() + "/event-types", VIEWER).statusCode()).isEqualTo(403);
        assertThat(http.post("/bff/developer/sync-platform-openapi", null, VIEWER).statusCode()).isEqualTo(403);

        assertThat(http.get("/bff/developer/applications", ANCHOR).statusCode()).isEqualTo(200);
    }

    /// docs/spec/reach-only-routes.md §1/§3: an anchor without
    /// DEVELOPER_APPLICATION_OPENAPI_VIEW is refused read, an anchor without
    /// DEVELOPER_APPLICATION_OPENAPI_SYNC is refused the sync write, and the
    /// specific view code (not the wildcard) is enough to read.
    @Test
    void anchorWithoutOpenApiPermissionIsRefusedButTheSpecificPermissionSucceeds() {
        var readDenied = http.get("/bff/developer/applications", ANCHOR_NO_OPENAPI_PERMS);
        assertThat(readDenied.statusCode()).isEqualTo(403);
        assertThat(json(readDenied).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");

        var writeDenied = http.post("/bff/developer/sync-platform-openapi", null, ANCHOR_NO_OPENAPI_PERMS);
        assertThat(writeDenied.statusCode()).isEqualTo(403);
        assertThat(json(writeDenied).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");

        var readAllowed = http.get("/bff/developer/applications", ANCHOR_OPENAPI_VIEW_ONLY);
        assertThat(readAllowed.statusCode()).as(readAllowed.body()).isEqualTo(200);
    }

    // ── Applications list / by id ────────────────────────────────────────

    @Test
    void listAndGetApplicationIncludeCurrentSpecSummary() {
        Application app = persistApplication("devbff-app-" + RUN);
        persistSpec(app.id(), "1.0.0");

        var list = json(http.get("/bff/developer/applications", ANCHOR));
        assertThat(list.propertyNames()).containsExactly("items");
        var found = streamOf(list.get("items")).filter(n -> n.get("id").asText().equals(app.id())).findFirst().orElseThrow();
        assertThat(found.propertyNames()).containsExactlyInAnyOrder("id", "code", "name", "currentVersion", "currentSpecId", "currentSyncedAt");
        assertThat(found.get("currentVersion").asText()).isEqualTo("1.0.0");

        var single = json(http.get("/bff/developer/applications/" + app.id(), ANCHOR));
        assertThat(single.get("currentVersion").asText()).isEqualTo("1.0.0");
        assertThat(single.get("currentSpecId").asText()).isNotBlank();
    }

    @Test
    void applicationWithNoSpecOmitsTheOptionalSpecFields() {
        Application app = persistApplication("devbff-nospec-" + RUN);
        var single = json(http.get("/bff/developer/applications/" + app.id(), ANCHOR));
        assertThat(single.has("currentVersion")).isFalse();
        assertThat(single.has("currentSpecId")).isFalse();
        assertThat(single.has("currentSyncedAt")).isFalse();
    }

    @Test
    void unknownApplicationIs404() {
        assertThat(http.get("/bff/developer/applications/app_doesnotexist1", ANCHOR).statusCode()).isEqualTo(404);
    }

    // ── OpenAPI current / versions / version-by-id ─────────────────────────

    @Test
    void currentSpecIs404WhenNoneSyncedYet() {
        Application app = persistApplication("devbff-current404-" + RUN);
        assertThat(http.get("/bff/developer/applications/" + app.id() + "/openapi/current", ANCHOR).statusCode()).isEqualTo(404);
    }

    @Test
    void currentAndVersionsAndVersionByIdShapes() {
        Application app = persistApplication("devbff-versions-" + RUN);
        persistSpec(app.id(), "1.0.0");
        OpenApiSpec archived = specRepo.findCurrentByApplication(app.id()).orElseThrow(); // will be archived by the next sync below

        var current = json(http.get("/bff/developer/applications/" + app.id() + "/openapi/current", ANCHOR));
        assertThat(current.propertyNames()).containsExactlyInAnyOrder("id", "applicationId", "version", "status", "spec", "syncedAt");
        assertThat(current.get("version").asText()).isEqualTo("1.0.0");

        var versions = json(http.get("/bff/developer/applications/" + app.id() + "/openapi/versions", ANCHOR));
        assertThat(versions.propertyNames()).containsExactly("items");
        assertThat(versions.get("items")).hasSize(1);
        var vs = versions.get("items").get(0);
        assertThat(vs.propertyNames()).containsExactlyInAnyOrder("id", "version", "status", "hasBreaking", "syncedAt");

        var byId = json(http.get("/bff/developer/applications/" + app.id() + "/openapi/versions/" + archived.id(), ANCHOR));
        assertThat(byId.get("id").asText()).isEqualTo(archived.id());
    }

    /// Load-bearing: a spec id that belongs to a DIFFERENT application 404s,
    /// even though the row itself exists.
    @Test
    void versionByIdRejectsASpecBelongingToAnotherApplication() {
        Application appA = persistApplication("devbff-crossA-" + RUN);
        Application appB = persistApplication("devbff-crossB-" + RUN);
        OpenApiSpec specOfA = persistSpec(appA.id(), "1.0.0");

        assertThat(http.get("/bff/developer/applications/" + appA.id() + "/openapi/versions/" + specOfA.id(), ANCHOR).statusCode()).isEqualTo(200);
        var crossed = http.get("/bff/developer/applications/" + appB.id() + "/openapi/versions/" + specOfA.id(), ANCHOR);
        assertThat(crossed.statusCode()).as("spec belongs to appA, not appB").isEqualTo(404);
    }

    // ── Event types of an application ───────────────────────────────────────

    @Test
    void listEventTypesOfAnApplicationIncludesSpecVersions() {
        String appCode = "devbffet" + RUN;
        Application app = persistApplication(appCode);
        uow.inTransaction(tx -> {
            eventTypeRepo.persist(EventType.create(appCode + ":sub:agg:one", "One").addSchemaVersion("1.0",
                    Json.MAPPER.readTree("{\"type\":\"object\"}")), tx.dbTx());
            return null;
        });

        var body = json(http.get("/bff/developer/applications/" + app.id() + "/event-types", ANCHOR));
        assertThat(body.propertyNames()).containsExactly("items");
        assertThat(body.get("items")).hasSize(1);
        var et = body.get("items").get(0);
        assertThat(et.propertyNames()).containsExactlyInAnyOrder("id", "code", "name", "status", "application",
                "subdomain", "aggregate", "eventName", "specVersions");
        assertThat(et.get("specVersions")).hasSize(1);
        var sv = et.get("specVersions").get(0);
        assertThat(sv.propertyNames()).containsExactlyInAnyOrder("id", "version", "status", "schema");
        assertThat(sv.get("schema").asText()).contains("object");
    }

    // ── sync-platform-openapi ────────────────────────────────────────────

    @Test
    void syncPlatformOpenApiCreatesThenReportsUnchangedOnReSync() {
        persistApplication("platform"); // the seeded row this route requires

        var first = http.post("/bff/developer/sync-platform-openapi", null, ANCHOR);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        var firstBody = json(first);
        assertThat(firstBody.propertyNames()).containsExactlyInAnyOrder("applicationCode", "specId", "version",
                "status", "hasBreaking", "unchanged");
        assertThat(firstBody.get("applicationCode").asText()).isEqualTo("platform");
        assertThat(firstBody.get("unchanged").asBoolean()).isFalse();
        assertThat(firstBody.get("status").asText()).isEqualTo("CURRENT");

        var second = http.post("/bff/developer/sync-platform-openapi", null, ANCHOR);
        assertThat(second.statusCode()).as(second.body()).isEqualTo(200);
        var secondBody = json(second);
        assertThat(secondBody.get("unchanged").asBoolean()).as("byte-identical re-sync").isTrue();
        assertThat(secondBody.get("status").asText()).isEqualTo("UNCHANGED");
        assertThat(secondBody.has("archivedPriorVersion")).as("no prior archived on an unchanged sync").isFalse();
    }

    private static java.util.stream.Stream<JsonNode> streamOf(JsonNode array) {
        return java.util.stream.StreamSupport.stream(array.spliterator(), false);
    }
}
