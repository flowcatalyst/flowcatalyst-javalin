package io.flowcatalyst.platform.process.api;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.process.ProcessRepository;
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

/// The seven `/api/processes` routes end to end through Javalin: the
/// authenticator's test headers, the coarse permission gates, the lockfile
/// status codes and body shapes, and the error envelope.
@SuppressWarnings("deprecation")
class ProcessApiTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final String APP = "prapi" + RUN;

    private static final String[] ANCHOR = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:*:*:*"};
    private static final String[] VIEWER = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, "cli_prapi_" + RUN,
            Authenticator.TEST_PERMISSIONS, "platform:messaging:process:view"};
    private static final String[] UPDATER = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, "cli_prapi_" + RUN,
            Authenticator.TEST_PERMISSIONS, "platform:messaging:process:view,platform:messaging:process:update"};

    private static final ProcessApi.State state = new ProcessApi.State(new ProcessRepository(TestPg.dataSource()),
            new UnitOfWork(TestPg.dataSource(), new PlatformSink(Json.MAPPER)));
    private static TestHttp http;

    @BeforeAll
    static void start() {
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before("/api/*", auth);
            ProcessApi.register(routes, state);
        });
    }

    @AfterAll
    static void stop() {
        http.close();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static JsonNode json(HttpResponse<String> r) {
        try {
            return Json.MAPPER.readTree(r.body());
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + r.body(), e);
        }
    }

    private static String create(String code, String name, String extraJson) {
        var r = http.post("/api/processes", "{\"code\":\"" + code + "\",\"name\":\"" + name + "\"" + extraJson + "}", ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        var id = json(r).get("id").asText();
        assertThat(id).startsWith("prc_");
        return id;
    }

    // ── Happy paths ────────────────────────────────────────────────────────

    @Test
    void createThenReadByIdByCodeAndInList() {
        String code = APP + ":orders:fulfilment";
        String id = create(code, "Order Fulfilment",
                ",\"description\":\"desc\",\"body\":\"graph TD; A-->B\",\"tags\":[\"orders\",\"core\"]");

        // POST body is exactly the CreatedResponse envelope.
        var created = http.post("/api/processes", "{\"code\":\"" + APP + ":orders:returns\",\"name\":\"Returns\"}", ANCHOR);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).matches("\\{\"id\":\"prc_[0-9A-Z]{13}\"}\n");
        assertThat(created.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");

        // GET by id: the ProcessResponse shape.
        var get = http.get("/api/processes/" + id, ANCHOR);
        assertThat(get.statusCode()).isEqualTo(200);
        var p = json(get);
        assertThat(p.get("id").asText()).isEqualTo(id);
        assertThat(p.get("code").asText()).isEqualTo(code);
        assertThat(p.get("name").asText()).isEqualTo("Order Fulfilment");
        assertThat(p.get("description").asText()).isEqualTo("desc");
        assertThat(p.get("status").asText()).isEqualTo("CURRENT");
        assertThat(p.get("source").asText()).isEqualTo("UI");
        assertThat(p.get("application").asText()).isEqualTo(APP);
        assertThat(p.get("subdomain").asText()).isEqualTo("orders");
        assertThat(p.get("processName").asText()).isEqualTo("fulfilment");
        assertThat(p.get("body").asText()).isEqualTo("graph TD; A-->B");
        assertThat(p.get("diagramType").asText()).isEqualTo("mermaid");
        assertThat(p.get("tags")).extracting(JsonNode::asText).containsExactly("orders", "core");
        assertThat(p.has("createdBy")).as("never persisted, so never on the wire (spec §1)").isFalse();
        assertThat(p.get("createdAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z");
        assertThat(p.get("updatedAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z");
        assertThat(p.propertyNames()).containsExactly("id", "code", "name", "description", "status", "source",
                "application", "subdomain", "processName", "body", "diagramType", "tags", "createdAt", "updatedAt");

        // A bare process still carries body ("") and tags ([]) — both required on the wire.
        var bare = json(http.get("/api/processes/by-code/" + APP + ":orders:returns", ANCHOR));
        assertThat(bare.get("body").asText()).isEmpty();
        assertThat(bare.get("tags").isArray()).isTrue();
        assertThat(bare.get("tags")).isEmpty();
        assertThat(bare.has("description")).isFalse();

        // GET by code.
        var byCode = http.get("/api/processes/by-code/" + code, ANCHOR);
        assertThat(byCode.statusCode()).isEqualTo(200);
        assertThat(json(byCode).get("id").asText()).isEqualTo(id);

        // List, filtered by application: {"items": [...]}, ordered by code.
        var list = http.get("/api/processes?application=" + APP, ANCHOR);
        assertThat(list.statusCode()).isEqualTo(200);
        var items = json(list).get("items");
        assertThat(items.isArray()).isTrue();
        assertThat(items).extracting(n -> n.get("code").asText()).contains(code, APP + ":orders:returns");
        assertThat(items).extracting(n -> n.get("code").asText()).as("ordered by code").isSorted();
        assertThat(json(list).propertyNames()).containsExactly("items");

        // A CLIENT-scoped viewer sees them too: processes are global.
        var viewerList = http.get("/api/processes?application=" + APP + "&subdomain=orders", VIEWER);
        assertThat(viewerList.statusCode()).isEqualTo(200);
        assertThat(json(viewerList).get("items")).extracting(n -> n.get("id").asText()).contains(id);
    }

    @Test
    void archiveReturns204AndUnfilteredListStillShowsIt() {
        String id = create(APP + ":arc:flow", "To be archived", "");

        var archive = http.post("/api/processes/" + id + "/archive", null, ANCHOR);
        assertThat(archive.statusCode()).as(archive.body()).isEqualTo(204);
        assertThat(archive.body()).isEmpty();
        assertThat(json(http.get("/api/processes/" + id, ANCHOR)).get("status").asText()).isEqualTo("ARCHIVED");

        // No implied status filter (spec §3): the archived row is listed unless filtered out.
        var unfiltered = http.get("/api/processes?application=" + APP, ANCHOR);
        assertThat(json(unfiltered).get("items")).extracting(n -> n.get("id").asText()).contains(id);
        var current = http.get("/api/processes?application=" + APP + "&status=CURRENT", ANCHOR);
        assertThat(json(current).get("items")).extracting(n -> n.get("id").asText()).doesNotContain(id);
        var archived = http.get("/api/processes?status=ARCHIVED&application=" + APP, ANCHOR);
        assertThat(json(archived).get("items")).extracting(n -> n.get("id").asText()).containsExactly(id);

        var missing = http.post("/api/processes/prc_doesnotexist1/archive", null, ANCHOR);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(json(missing).get("error").asText()).isEqualTo("Process_NOT_FOUND");
    }

    @Test
    void updateReturns204AndPersistsOnlyTheSuppliedFields() {
        String id = create(APP + ":upd:flow", "Before", ",\"body\":\"old\",\"tags\":[\"keep\"]");
        var put = http.put("/api/processes/" + id, "{\"name\":\"After\",\"description\":\"now described\",\"diagramType\":\"plantuml\"}", UPDATER);
        assertThat(put.statusCode()).as(put.body()).isEqualTo(204);
        assertThat(put.body()).isEmpty();

        var p = json(http.get("/api/processes/" + id, ANCHOR));
        assertThat(p.get("name").asText()).isEqualTo("After");
        assertThat(p.get("description").asText()).isEqualTo("now described");
        assertThat(p.get("diagramType").asText()).isEqualTo("plantuml");
        assertThat(p.get("body").asText()).as("absent body untouched").isEqualTo("old");
        assertThat(p.get("tags")).extracting(JsonNode::asText).as("absent tags untouched").containsExactly("keep");

        var bad = http.put("/api/processes/" + id, "{\"name\":\"\"}", ANCHOR);
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(json(bad).get("error").asText()).isEqualTo("NAME_REQUIRED");

        var missing = http.put("/api/processes/prc_doesnotexist1", "{\"name\":\"X\"}", ANCHOR);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(json(missing).get("error").asText()).isEqualTo("Process_NOT_FOUND");
    }

    @Test
    void deleteReturns204ThenGetIs404Envelope() {
        String id = create(APP + ":del:flow", "Doomed", "");
        var del = http.delete("/api/processes/" + id, ANCHOR);
        assertThat(del.statusCode()).isEqualTo(204);

        var get = http.get("/api/processes/" + id, ANCHOR);
        assertThat(get.statusCode()).isEqualTo(404);
        assertThat(get.body()).isEqualTo("{\"error\":\"Process_NOT_FOUND\",\"message\":\"Process not found: " + id + "\"}\n");

        var again = http.delete("/api/processes/" + id, ANCHOR);
        assertThat(again.statusCode()).isEqualTo(404);
        assertThat(json(again).get("error").asText()).isEqualTo("Process_NOT_FOUND");

        var byCode = http.get("/api/processes/by-code/" + APP + ":del:flow", ANCHOR);
        assertThat(byCode.statusCode()).isEqualTo(404);
        assertThat(json(byCode).get("message").asText()).isEqualTo("Process not found: " + APP + ":del:flow");
    }

    // ── Negative paths ─────────────────────────────────────────────────────

    @Test
    void missingPermissionOrPrincipalIs403Envelope() {
        var r = http.post("/api/processes", "{\"code\":\"" + APP + ":perm:denied\",\"name\":\"X\"}", VIEWER);
        assertThat(r.statusCode()).isEqualTo(403);
        var env = json(r);
        assertThat(env.get("error").asText()).isEqualTo("PERMISSION_REQUIRED");
        assertThat(env.get("message").asText()).contains("platform:messaging:process:create");

        // The write trio gates update and archive; delete needs process:delete specifically.
        var archive = http.post("/api/processes/prc_whatever/archive", null, VIEWER);
        assertThat(archive.statusCode()).isEqualTo(403);
        var del = http.delete("/api/processes/prc_whatever", UPDATER);
        assertThat(del.statusCode()).isEqualTo(403);
        assertThat(json(del).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");
        assertThat(json(del).get("message").asText()).contains("platform:messaging:process:delete");

        var anon = http.get("/api/processes");
        assertThat(anon.statusCode()).isEqualTo(403);
        assertThat(json(anon).get("error").asText()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void validationConflictAndMalformedJsonAreEnvelopes() {
        var bad = http.post("/api/processes", "{\"code\":\"only:two\",\"name\":\"X\"}", ANCHOR);
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(json(bad).get("error").asText()).isEqualTo("INVALID_CODE_FORMAT");
        assertThat(json(bad).get("message").asText()).isEqualTo("Process code must follow format: application:subdomain:process-name");

        // name is schema-required too — sent as "" so the request reaches the domain check.
        var noName = http.post("/api/processes", "{\"code\":\"" + APP + ":val:noname\",\"name\":\"\"}", ANCHOR);
        assertThat(noName.statusCode()).isEqualTo(400);
        assertThat(json(noName).get("error").asText()).isEqualTo("NAME_REQUIRED");

        var dup = http.post("/api/processes", "{\"code\":\"" + APP + ":dup:twice\",\"name\":\"X\"}", ANCHOR);
        assertThat(dup.statusCode()).isEqualTo(201);
        var dup2 = http.post("/api/processes", "{\"code\":\"" + APP + ":dup:twice\",\"name\":\"X\"}", ANCHOR);
        assertThat(dup2.statusCode()).isEqualTo(409);
        assertThat(json(dup2).get("error").asText()).isEqualTo("CODE_EXISTS");

        var malformed = http.post("/api/processes", "{not json", ANCHOR);
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(json(malformed).get("error").asText()).isEqualTo("INVALID_JSON");
    }
}
