package io.flowcatalyst.platform.connection.api;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.connection.ConnectionRepository;
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

/// The seven `/api/connections` routes end to end through Javalin: the
/// authenticator's test headers, the coarse permission gates, the lockfile
/// status codes and body shapes, and the error envelope.
@SuppressWarnings("deprecation")
class ConnectionApiTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final String CLIENT = EntityType.CLIENT.generate();
    private static final String OTHER_CLIENT = EntityType.CLIENT.generate();

    private static final String ANCHOR_PRINCIPAL = EntityType.PRINCIPAL.generate();
    private static final String[] ANCHOR = {
            Authenticator.TEST_PRINCIPAL, ANCHOR_PRINCIPAL,
            Authenticator.TEST_SCOPE, "ANCHOR"};
    private static final String[] VIEWER = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, CLIENT,
            Authenticator.TEST_PERMISSIONS, "platform:messaging:connection:view"};
    private static final String[] CLIENT_WRITER = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, CLIENT,
            Authenticator.TEST_PERMISSIONS, "platform:messaging:connection:view,platform:messaging:connection:create,platform:messaging:connection:update"};

    private static final ConnectionApi.State state = new ConnectionApi.State(new ConnectionRepository(TestPg.dataSource()),
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
            ConnectionApi.register(routes, state);
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

    private static String code(String tag) {
        return tag + "-" + RUN;
    }

    private static String createBody(String code, String name, String extraJson) {
        return "{\"code\":\"" + code + "\",\"name\":\"" + name + "\",\"serviceAccountId\":\"sva_connapi1\"" + extraJson + "}";
    }

    private static JsonNode create(String code, String name, String extraJson) {
        var r = http.post("/api/connections", createBody(code, name, extraJson), ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        var body = json(r);
        assertThat(body.get("id").asText()).startsWith("con_");
        return body;
    }

    // ── Happy paths ────────────────────────────────────────────────────────

    @Test
    void createReturnsTheFullConnectionThenReadByIdAndInList() {
        String code = code("connapi-create");
        var created = create(code, "Orders Webhook", ",\"description\":\"desc\",\"externalId\":\"ext-1\"");
        String id = created.get("id").asText();

        // POST answers with the ConnectionResponse shape (spec §3).
        assertThat(created.get("code").asText()).isEqualTo(code);
        assertThat(created.get("name").asText()).isEqualTo("Orders Webhook");
        assertThat(created.get("description").asText()).isEqualTo("desc");
        assertThat(created.get("externalId").asText()).isEqualTo("ext-1");
        assertThat(created.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(created.get("serviceAccountId").asText()).isEqualTo("sva_connapi1");
        assertThat(created.has("clientId")).as("null clientId omitted").isFalse();
        assertThat(created.has("clientIdentifier")).as("null clientIdentifier omitted").isFalse();
        assertThat(created.get("createdAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z");
        assertThat(created.get("updatedAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z");
        assertThat(created.propertyNames()).containsExactly("id", "code", "name", "description", "externalId",
                "status", "serviceAccountId", "createdAt", "updatedAt");

        // GET by id returns the same shape.
        var get = http.get("/api/connections/" + id, ANCHOR);
        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(json(get)).isEqualTo(created);

        // List: {"connections": [...], "total": n}, ordered by code; status filter works.
        create(code("connapi-aaa"), "First by code", "");
        var list = http.get("/api/connections?status=ACTIVE", ANCHOR);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(json(list).propertyNames()).containsExactly("connections", "total");
        var connections = json(list).get("connections");
        assertThat(connections).extracting(n -> n.get("code").asText())
                .as("ordered by code").containsSubsequence(code("connapi-aaa"), code);
        assertThat(json(list).get("total").asInt()).isEqualTo(connections.size());

        // A CLIENT-scoped viewer sees platform-level connections too.
        var viewerList = http.get("/api/connections", VIEWER);
        assertThat(viewerList.statusCode()).isEqualTo(200);
        assertThat(json(viewerList).get("connections")).extracting(n -> n.get("id").asText()).contains(id);
    }

    @Test
    void listByClientIdHidesOtherClientsFromAClientScopedViewer() {
        var own = create(code("connapi-own"), "Own", ",\"clientId\":\"" + CLIENT + "\"");
        var other = create(code("connapi-other"), "Other", ",\"clientId\":\"" + OTHER_CLIENT + "\"");

        var viewerOwn = http.get("/api/connections?clientId=" + CLIENT, VIEWER);
        assertThat(json(viewerOwn).get("connections")).extracting(n -> n.get("id").asText())
                .contains(own.get("id").asText()).doesNotContain(other.get("id").asText());
        var viewerOther = http.get("/api/connections?clientId=" + OTHER_CLIENT, VIEWER);
        assertThat(json(viewerOther).get("connections")).isEmpty();
        assertThat(json(viewerOther).get("total").asInt()).isEqualTo(0);

        var forbidden = http.get("/api/connections/" + other.get("id").asText(), VIEWER);
        assertThat(forbidden.statusCode()).isEqualTo(403);
        assertThat(json(forbidden).get("error").asText()).isEqualTo("FORBIDDEN");
        assertThat(json(forbidden).get("message").asText()).isEqualTo("No access to this connection");

        var anchorOther = http.get("/api/connections?clientId=" + OTHER_CLIENT, ANCHOR);
        assertThat(json(anchorOther).get("connections")).extracting(n -> n.get("id").asText()).containsExactly(other.get("id").asText());
    }

    @Test
    void updateReturns204AndPersists() {
        String id = create(code("connapi-upd"), "Before", "").get("id").asText();
        var put = http.put("/api/connections/" + id, "{\"name\":\"After\",\"description\":\"now described\",\"status\":\"PAUSED\"}", ANCHOR);
        assertThat(put.statusCode()).as(put.body()).isEqualTo(204);
        assertThat(put.body()).isEmpty();

        var c = json(http.get("/api/connections/" + id, ANCHOR));
        assertThat(c.get("name").asText()).isEqualTo("After");
        assertThat(c.get("description").asText()).isEqualTo("now described");
        assertThat(c.get("status").asText()).isEqualTo("PAUSED");
        assertThat(c.get("code").asText()).as("code is immutable").isEqualTo(code("connapi-upd"));

        var bad = http.put("/api/connections/" + id, "{\"name\":\"\"}", ANCHOR);
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(json(bad).get("error").asText()).isEqualTo("NAME_REQUIRED");

        var missing = http.put("/api/connections/con_doesnotexist1", "{\"name\":\"X\"}", ANCHOR);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(json(missing).get("error").asText()).isEqualTo("Connection_NOT_FOUND");
    }

    @Test
    void pauseAndActivateReturnTheUpdatedConnection() {
        String id = create(code("connapi-flip"), "Flip", "").get("id").asText();

        var paused = http.post("/api/connections/" + id + "/pause", null, ANCHOR);
        assertThat(paused.statusCode()).as(paused.body()).isEqualTo(200);
        assertThat(json(paused).get("id").asText()).isEqualTo(id);
        assertThat(json(paused).get("status").asText()).isEqualTo("PAUSED");

        var activated = http.post("/api/connections/" + id + "/activate", null, ANCHOR);
        assertThat(activated.statusCode()).as(activated.body()).isEqualTo(200);
        assertThat(json(activated).get("status").asText()).isEqualTo("ACTIVE");

        var missing = http.post("/api/connections/con_doesnotexist1/pause", null, ANCHOR);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(json(missing).get("error").asText()).isEqualTo("Connection_NOT_FOUND");
    }

    @Test
    void deleteReturns204ThenGetIs404Envelope() {
        String id = create(code("connapi-del"), "Doomed", "").get("id").asText();
        var del = http.delete("/api/connections/" + id, ANCHOR);
        assertThat(del.statusCode()).isEqualTo(204);

        var get = http.get("/api/connections/" + id, ANCHOR);
        assertThat(get.statusCode()).isEqualTo(404);
        assertThat(get.body()).isEqualTo("{\"error\":\"Connection_NOT_FOUND\",\"message\":\"Connection not found: " + id + "\"}\n");

        var again = http.delete("/api/connections/" + id, ANCHOR);
        assertThat(again.statusCode()).isEqualTo(404);
        assertThat(json(again).get("error").asText()).isEqualTo("Connection_NOT_FOUND");
    }

    // ── Negative paths ─────────────────────────────────────────────────────

    @Test
    void missingPermissionOrPrincipalIs403Envelope() {
        var r = http.post("/api/connections", createBody(code("connapi-perm"), "X", ""), VIEWER);
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(json(r).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");
        assertThat(json(r).get("message").asText()).contains("platform:messaging:connection:create");

        assertThat(http.put("/api/connections/con_whatever", "{\"name\":\"X\"}", VIEWER).statusCode()).isEqualTo(403);
        assertThat(http.delete("/api/connections/con_whatever", VIEWER).statusCode()).isEqualTo(403);
        assertThat(http.post("/api/connections/con_whatever/pause", null, VIEWER).statusCode()).isEqualTo(403);
        assertThat(http.post("/api/connections/con_whatever/activate", null, VIEWER).statusCode()).isEqualTo(403);

        var anon = http.get("/api/connections");
        assertThat(anon.statusCode()).isEqualTo(403);
        assertThat(json(anon).get("error").asText()).isEqualTo("UNAUTHENTICATED");
    }

    /// The coarse gate passes (create permission) but the use case's
    /// resource-level authorization refuses a platform-wide create from a
    /// CLIENT-scoped principal; the same principal may create — and pause —
    /// a connection bound to its own client.
    @Test
    void clientScopedPrincipalIsConfinedToItsOwnClient() {
        var r = http.post("/api/connections", createBody(code("connapi-scope-platform"), "X", ""), CLIENT_WRITER);
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(json(r).get("error").asText()).isEqualTo("SCOPE_FORBIDDEN");

        var own = http.post("/api/connections", createBody(code("connapi-scope-own"), "X", ",\"clientId\":\"" + CLIENT + "\""), CLIENT_WRITER);
        assertThat(own.statusCode()).as(own.body()).isEqualTo(201);
        assertThat(json(own).get("clientId").asText()).isEqualTo(CLIENT);

        var paused = http.post("/api/connections/" + json(own).get("id").asText() + "/pause", null, CLIENT_WRITER);
        assertThat(paused.statusCode()).as(paused.body()).isEqualTo(200);

        String platformId = create(code("connapi-scope-anchor"), "Anchor's", "").get("id").asText();
        var denied = http.post("/api/connections/" + platformId + "/pause", null, CLIENT_WRITER);
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(json(denied).get("error").asText()).isEqualTo("SCOPE_FORBIDDEN");
    }

    @Test
    void validationConflictAndMalformedJsonAreEnvelopes() {
        var bad = http.post("/api/connections", createBody("Not_Valid", "X", ""), ANCHOR);
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(json(bad).get("error").asText()).isEqualTo("INVALID_CODE_FORMAT");
        assertThat(json(bad).get("message").asText()).isEqualTo("Code must start with lowercase letter, contain only lowercase alphanumeric and hyphens");

        // serviceAccountId is schema-required too — sent as "" so the request reaches the domain check.
        var noSa = http.post("/api/connections", "{\"code\":\"" + code("connapi-nosa") + "\",\"name\":\"X\",\"serviceAccountId\":\"\"}", ANCHOR);
        assertThat(noSa.statusCode()).isEqualTo(400);
        assertThat(json(noSa).get("error").asText()).isEqualTo("SERVICE_ACCOUNT_REQUIRED");

        create(code("connapi-dup"), "X", "");
        var dup = http.post("/api/connections", createBody(code("connapi-dup").toUpperCase(Locale.ROOT), "X", ""), ANCHOR);
        assertThat(dup.statusCode()).as("codes are normalised before the uniqueness check").isEqualTo(409);
        assertThat(json(dup).get("error").asText()).isEqualTo("CODE_EXISTS");

        var malformed = http.post("/api/connections", "{not json", ANCHOR);
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(json(malformed).get("error").asText()).isEqualTo("INVALID_JSON");
    }
}
