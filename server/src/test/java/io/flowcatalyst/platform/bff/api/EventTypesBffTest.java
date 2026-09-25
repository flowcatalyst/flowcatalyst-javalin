package io.flowcatalyst.platform.bff.api;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.eventtype.EventTypeRepository;
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
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// The `/bff/event-types` surface end to end: status codes, the
/// `{items,total}` shape, `clientScoped`, and the anchor-only sync gate.
@SuppressWarnings("deprecation")
class EventTypesBffTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);

    private static final EventTypesBff.State state = new EventTypesBff.State(
            new EventTypeRepository(TestPg.dataSource()), new UnitOfWork(TestPg.dataSource(), new PlatformSink(Json.MAPPER)));
    private static TestHttp http;

    private static final String ANCHOR_PRINCIPAL = EntityType.PRINCIPAL.generate();
    private static final String[] ANCHOR = {Authenticator.TEST_PRINCIPAL, ANCHOR_PRINCIPAL, Authenticator.TEST_SCOPE, "ANCHOR", Authenticator.TEST_PERMISSIONS, "platform:*:*:*"};
    private static final String CLIENT = "cli_" + RUN + "_et";
    private static final String[] VIEWER = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, CLIENT,
            Authenticator.TEST_PERMISSIONS, "platform:messaging:event-type:view"};
    private static final String[] WRITER = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, CLIENT,
            Authenticator.TEST_PERMISSIONS, "platform:messaging:event-type:view,platform:messaging:event-type:create,platform:messaging:event-type:update,platform:messaging:event-type:delete"};

    @BeforeAll
    static void start() {
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before("/bff/*", auth);
            EventTypesBff.register(routes, state);
        });
    }

    @AfterAll
    static void stop() {
        http.close();
    }

    private static JsonNode json(HttpResponse<String> r) {
        try {
            return Json.MAPPER.readTree(r.body());
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + r.body(), e);
        }
    }

    private static String code(String tag) {
        return "etbff" + RUN + ":sub:agg:" + tag;
    }

    private static String create(String code, String extraJson, String... as) {
        var r = http.post("/bff/event-types", "{\"code\":\"" + code + "\",\"name\":\"Name " + code + "\"" + extraJson + "}", as);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return json(r).get("id").asText();
    }

    /// Owner ruling 2026-09-06 #7 through the SPA's own route: the create
    /// drawer posts `clientScoped` to `/bff/event-types` and the detail page
    /// reads it back from `/bff/event-types/{id}`.
    @Test
    void clientScopedIsHonouredOnTheBffCreateAndUpdate() {
        String c = code("bff-scoped");
        var r = http.post("/bff/event-types", "{\"code\":\"" + c + "\",\"name\":\"N\",\"clientScoped\":true}", ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        String id = json(r).get("id").asText();
        assertThat(json(r).get("clientScoped").asBoolean()).isTrue();
        assertThat(json(http.get("/bff/event-types/" + id, ANCHOR)).get("clientScoped").asBoolean()).isTrue();

        var u = http.put("/bff/event-types/" + id, "{\"name\":\"N2\",\"clientScoped\":false}", ANCHOR);
        assertThat(u.statusCode()).as(u.body()).isIn(200, 204);
        assertThat(json(http.get("/bff/event-types/" + id, ANCHOR)).get("clientScoped").asBoolean()).isFalse();
    }

    @Test
    void createReturnsTheFullResponseWithExpectedKeySet() {
        String c = code("create");
        // clientId is required for a non-anchor create: CreateEventType authorises
        // create() against cmd.clientId(), and a platform-wide (null) create is anchor-only.
        var r = http.post("/bff/event-types", "{\"code\":\"" + c + "\",\"name\":\"N\",\"description\":\"d\",\"clientId\":\"" + CLIENT + "\"}", WRITER);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        var body = json(r);
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("id", "code", "application", "subdomain",
                "aggregate", "event", "name", "description", "status", "clientScoped", "specVersions", "createdAt", "updatedAt");
        assertThat(body.get("code").asText()).isEqualTo(c);
        assertThat(body.get("application").asText()).isEqualTo("etbff" + RUN);
        assertThat(body.get("clientScoped").isBoolean()).as("clientScoped is a bare boolean field").isTrue();
        assertThat(body.get("status").asText()).isEqualTo("CURRENT");
    }

    @Test
    void getListUpdatePatchDeleteArchiveLifecycle() {
        String c = code("life");
        String id = create(c, "", ANCHOR);

        var get = http.get("/bff/event-types/" + id, ANCHOR);
        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(json(get).get("id").asText()).isEqualTo(id);

        var list = http.get("/bff/event-types?application=etbff" + RUN, ANCHOR);
        assertThat(list.statusCode()).isEqualTo(200);
        var listBody = json(list);
        assertThat(listBody.propertyNames()).containsExactlyInAnyOrder("items", "total");
        assertThat(listBody.get("total").asInt()).isEqualTo(listBody.get("items").size());
        assertThat(listBody.get("items").findValuesAsString("id")).contains(id);

        // PUT (lockfile-documented) works.
        var put = http.put("/bff/event-types/" + id, "{\"name\":\"Renamed\"}", ANCHOR);
        assertThat(put.statusCode()).isEqualTo(204);
        assertThat(json(http.get("/bff/event-types/" + id, ANCHOR)).get("name").asText()).isEqualTo("Renamed");

        // PATCH (the SPA's own dialect) works identically on the same route.
        var patch = http.send("PATCH", "/bff/event-types/" + id, "{\"name\":\"RenamedAgain\"}", ANCHOR);
        assertThat(patch.statusCode()).as(patch.body()).isEqualTo(204);
        assertThat(json(http.get("/bff/event-types/" + id, ANCHOR)).get("name").asText()).isEqualTo("RenamedAgain");

        var archive = http.post("/bff/event-types/" + id + "/archive", null, ANCHOR);
        assertThat(archive.statusCode()).isEqualTo(200);
        assertThat(json(archive).get("status").asText()).isEqualTo("ARCHIVED");

        assertThat(http.delete("/bff/event-types/" + id, ANCHOR).statusCode()).isEqualTo(204);
        assertThat(http.get("/bff/event-types/" + id, ANCHOR).statusCode()).isEqualTo(404);
    }

    @Test
    void schemaAddFinaliseDeprecate() {
        String id = create(code("schema"), "", ANCHOR);
        var add = http.post("/bff/event-types/" + id + "/schemas",
                "{\"version\":\"1.0\",\"schema\":{\"type\":\"object\"}}", ANCHOR);
        assertThat(add.statusCode()).as(add.body()).isEqualTo(200);
        var afterAdd = json(add);
        assertThat(afterAdd.get("specVersions")).hasSize(1);
        var sv = afterAdd.get("specVersions").get(0);
        assertThat(sv.propertyNames()).containsExactlyInAnyOrder("id", "version", "status", "schemaType", "mimeType", "schema", "createdAt", "updatedAt");
        assertThat(sv.get("status").asText()).isEqualTo("FINALISING");
        assertThat(sv.get("schema").asText()).contains("object");

        var finalise = http.post("/bff/event-types/" + id + "/schemas/1.0/finalise", null, ANCHOR);
        assertThat(finalise.statusCode()).as(finalise.body()).isEqualTo(200);
        assertThat(json(finalise).get("specVersions").get(0).get("status").asText()).isEqualTo("CURRENT");

        var deprecate = http.post("/bff/event-types/" + id + "/schemas/1.0/deprecate", null, ANCHOR);
        assertThat(deprecate.statusCode()).as(deprecate.body()).isEqualTo(200);
        assertThat(json(deprecate).get("specVersions").get(0).get("status").asText()).isEqualTo("DEPRECATED");
    }

    @Test
    void filterSubdomainsAndAggregatesAreScopedAndSorted() {
        String app = "etbfffilt" + RUN;
        create(app + ":zsub:agg:one", "", ANCHOR);
        create(app + ":asub:agg:two", "", ANCHOR);

        var subs = json(http.get("/bff/event-types/filters/subdomains?application=" + app, ANCHOR)).get("options");
        var subList = new java.util.ArrayList<String>();
        subs.forEach(n -> subList.add(n.asText()));
        assertThat(subList).containsExactly("asub", "zsub"); // sorted
    }

    // ── Load-bearing: sync-platform is anchor-only ──────────────────────────

    @Test
    void syncPlatformIsAnchorOnly() {
        assertThat(http.post("/bff/event-types/sync-platform", null, WRITER).statusCode())
                .as("a coarse event-type write permission is not enough").isEqualTo(403);
        assertThat(http.post("/bff/event-types/sync-platform", null, VIEWER).statusCode()).isEqualTo(403);

        var r = http.post("/bff/event-types/sync-platform", null, ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        var body = json(r);
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("created", "updated", "deleted", "total", "schemas");
        assertThat(body.get("schemas").propertyNames()).containsExactlyInAnyOrder("created", "updated", "unchanged");
        assertThat(body.get("total").asInt()).isGreaterThan(0);
    }

    /// Owner ruling 2026-09-25 (backlog "Overnight review" item 16): the body may not
    /// aim the platform catalogue (with removeUnlisted) at another application. That
    /// would replace the application's event types, so its own survive the attempt.
    @Test
    void syncPlatformTargetsThePlatformOnly() {
        String own = create(code("keepme"), "", ANCHOR);
        var aimed = http.post("/bff/event-types/sync-platform", "{\"applicationCode\":\"etbff" + RUN + "\"}", ANCHOR);
        assertThat(aimed.statusCode()).as(aimed.body()).isEqualTo(400);
        assertThat(json(aimed).get("error").asText()).isEqualTo("PLATFORM_SYNC_ONLY");
        assertThat(http.get("/bff/event-types/" + own, ANCHOR).statusCode()).as("the application's type survives").isEqualTo(200);

        assertThat(http.post("/bff/event-types/sync-platform", "{\"applicationCode\":\"platform\"}", ANCHOR).statusCode())
                .as("naming the platform itself is fine").isEqualTo(200);
    }

    /// Security-fixes S1.2: sync-platform needs `EVENT_TYPE_SYNC` at the
    /// anchor tier too — an anchor holding every other event-type code is
    /// refused `PERMISSION_REQUIRED`; the sync code alone (not the wildcard)
    /// admits it.
    @Test
    void syncPlatformAlsoNeedsTheSyncPermission() {
        String[] anchorWithoutSync = {Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(), Authenticator.TEST_SCOPE, "ANCHOR",
                Authenticator.TEST_PERMISSIONS, "platform:messaging:event-type:view,platform:messaging:event-type:create,"
                        + "platform:messaging:event-type:update,platform:messaging:event-type:delete"};
        var refused = http.post("/bff/event-types/sync-platform", null, anchorWithoutSync);
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(json(refused).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");

        String[] anchorSyncer = {Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(), Authenticator.TEST_SCOPE, "ANCHOR",
                Authenticator.TEST_PERMISSIONS, "platform:messaging:event-type:sync"};
        assertThat(http.post("/bff/event-types/sync-platform", null, anchorSyncer).statusCode()).isEqualTo(200);
    }
}
