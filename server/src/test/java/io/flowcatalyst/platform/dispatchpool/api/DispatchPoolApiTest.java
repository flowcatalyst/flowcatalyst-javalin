package io.flowcatalyst.platform.dispatchpool.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.flowcatalyst.platform.dispatchpool.DispatchPoolRepository;
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

/// The eight `/api/dispatch-pools` routes end to end through Javalin: the
/// authenticator's test headers, the coarse permission gates, the lockfile
/// status codes and body shapes, and the error envelope.
class DispatchPoolApiTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final String CLIENT = EntityType.CLIENT.generate();

    private static final String ANCHOR_PRINCIPAL = EntityType.PRINCIPAL.generate();
    private static final String[] ANCHOR = {
            Authenticator.TEST_PRINCIPAL, ANCHOR_PRINCIPAL,
            Authenticator.TEST_SCOPE, "ANCHOR"};
    private static final String[] VIEWER = viewer(CLIENT);
    private static final String[] CLIENT_WRITER = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, CLIENT,
            Authenticator.TEST_PERMISSIONS, "platform:messaging:dispatch-pool:view,platform:messaging:dispatch-pool:create"};

    private static final DispatchPoolApi.State state = new DispatchPoolApi.State(new DispatchPoolRepository(TestPg.dataSource()),
            new UnitOfWork(TestPg.dataSource(), new PlatformSink(Json.MAPPER)));
    private static TestHttp http;

    @BeforeAll
    static void start() {
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        http = new TestHttp(cfg -> {
            HttpError.install(cfg.routes);
            cfg.routes.before("/api/*", auth);
            DispatchPoolApi.register(cfg.routes, state);
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
        return "dpapi-" + tag + "-" + RUN;
    }

    /// A CLIENT-scoped principal with the view permission on `clientId` only.
    private static String[] viewer(String clientId) {
        return new String[] {
                Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
                Authenticator.TEST_SCOPE, "CLIENT",
                Authenticator.TEST_CLIENTS, clientId,
                Authenticator.TEST_PERMISSIONS, "platform:messaging:dispatch-pool:view"};
    }

    private static String create(String code, String name, String extraJson) {
        var r = http.post("/api/dispatch-pools", "{\"code\":\"" + code + "\",\"name\":\"" + name + "\"" + extraJson + "}", ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        var id = json(r).get("id").asText();
        assertThat(id).startsWith("dpl_");
        return id;
    }

    // ── Happy paths ────────────────────────────────────────────────────────

    @Test
    void createThenReadByIdAndInList() {
        String code = code("read");
        String id = create(code, "Read Me", ",\"description\":\"desc\",\"rateLimit\":120,\"concurrency\":3");

        // POST body is exactly the CreatedResponse envelope.
        var created = http.post("/api/dispatch-pools", "{\"code\":\"" + code("plain") + "\",\"name\":\"Plain\"}", ANCHOR);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).matches("\\{\"id\":\"dpl_[0-9A-Z]{13}\"}\n");
        assertThat(created.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");

        // GET by id: the DispatchPoolResponse shape, in lockfile field order.
        var get = http.get("/api/dispatch-pools/" + id, ANCHOR);
        assertThat(get.statusCode()).isEqualTo(200);
        var p = json(get);
        assertThat(p.get("id").asText()).isEqualTo(id);
        assertThat(p.get("code").asText()).isEqualTo(code);
        assertThat(p.get("name").asText()).isEqualTo("Read Me");
        assertThat(p.get("description").asText()).isEqualTo("desc");
        assertThat(p.get("rateLimit").asInt()).isEqualTo(120);
        assertThat(p.get("concurrency").asInt()).isEqualTo(3);
        assertThat(p.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(p.has("clientId")).as("null clientId omitted").isFalse();
        assertThat(p.has("clientIdentifier")).as("null clientIdentifier omitted").isFalse();
        assertThat(p.get("createdAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z");
        assertThat(p.get("updatedAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z");
        assertThat(p.fieldNames()).toIterable().containsExactly("id", "code", "name", "description", "rateLimit",
                "concurrency", "status", "createdAt", "updatedAt");

        // Defaults on the wire: no rateLimit key, concurrency 10.
        var plain = json(http.get("/api/dispatch-pools/" + json(created).get("id").asText(), ANCHOR));
        assertThat(plain.has("rateLimit")).isFalse();
        assertThat(plain.get("concurrency").asInt()).isEqualTo(10);

        // List: {"pools": [...], "total": n}, ordered by code.
        var list = http.get("/api/dispatch-pools", ANCHOR);
        assertThat(list.statusCode()).isEqualTo(200);
        var body = json(list);
        assertThat(body.fieldNames()).toIterable().containsExactly("pools", "total");
        assertThat(body.get("pools").isArray()).isTrue();
        assertThat(body.get("total").asInt()).isEqualTo(body.get("pools").size());
        assertThat(body.get("pools")).extracting(n -> n.get("code").asText()).contains(code, code("plain"));
        var codes = body.get("pools").findValuesAsText("code");
        assertThat(codes).isSorted();

        // A viewer (CLIENT scope, view permission) sees platform-wide pools too.
        var viewerList = http.get("/api/dispatch-pools", VIEWER);
        assertThat(viewerList.statusCode()).isEqualTo(200);
        assertThat(json(viewerList).get("pools")).extracting(n -> n.get("id").asText()).contains(id);
    }

    /// Uses a client of its own (not the shared `CLIENT`) so the exact-match
    /// assertions on `?clientId=` cannot see rows other tests bind to `CLIENT`.
    @Test
    void listFiltersByStatusAndClientAndHidesOtherClientsPools() {
        String client = EntityType.CLIENT.generate();
        String[] viewer = viewer(client);
        String mineId = create(code("mine"), "Mine", ",\"clientId\":\"" + client + "\"");
        String otherId = create(code("other"), "Other", ",\"clientId\":\"" + EntityType.CLIENT.generate() + "\"");

        var byClient = http.get("/api/dispatch-pools?clientId=" + client, ANCHOR);
        assertThat(json(byClient).get("pools")).extracting(n -> n.get("id").asText()).containsExactly(mineId);
        assertThat(json(byClient).get("total").asInt()).isEqualTo(1);
        assertThat(json(byClient).get("pools").get(0).get("clientId").asText()).isEqualTo(client);

        // The viewer cannot see the other client's pool, in the list or by id.
        var viewerList = http.get("/api/dispatch-pools", viewer);
        assertThat(json(viewerList).get("pools")).extracting(n -> n.get("id").asText()).contains(mineId).doesNotContain(otherId);
        var forbidden = http.get("/api/dispatch-pools/" + otherId, viewer);
        assertThat(forbidden.statusCode()).isEqualTo(403);
        assertThat(json(forbidden).get("error").asText()).isEqualTo("FORBIDDEN");
        assertThat(json(forbidden).get("message").asText()).isEqualTo("No access to this dispatch pool");

        // Status filter; an empty value is no filter; archived pools are listed by default.
        var suspend = http.post("/api/dispatch-pools/" + mineId + "/suspend", null, ANCHOR);
        assertThat(suspend.statusCode()).isEqualTo(204);
        assertThat(json(http.get("/api/dispatch-pools?status=SUSPENDED&clientId=" + client, ANCHOR)).get("pools"))
                .extracting(n -> n.get("id").asText()).containsExactly(mineId);
        assertThat(json(http.get("/api/dispatch-pools?status=ACTIVE&clientId=" + client, ANCHOR)).get("pools")).isEmpty();
        assertThat(json(http.get("/api/dispatch-pools?status=&clientId=" + client, ANCHOR)).get("pools")).hasSize(1);
    }

    @Test
    void updateReturns204AndPersistsOnlyTheGivenFields() {
        String id = create(code("upd"), "Before", ",\"description\":\"kept\",\"rateLimit\":10");
        var put = http.put("/api/dispatch-pools/" + id, "{\"name\":\"After\",\"concurrency\":5}", ANCHOR);
        assertThat(put.statusCode()).isEqualTo(204);
        assertThat(put.body()).isEmpty();

        var p = json(http.get("/api/dispatch-pools/" + id, ANCHOR));
        assertThat(p.get("name").asText()).isEqualTo("After");
        assertThat(p.get("description").asText()).isEqualTo("kept");
        assertThat(p.get("rateLimit").asInt()).isEqualTo(10);
        assertThat(p.get("concurrency").asInt()).isEqualTo(5);

        var bad = http.put("/api/dispatch-pools/" + id, "{\"name\":\"\"}", ANCHOR);
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(json(bad).get("error").asText()).isEqualTo("NAME_REQUIRED");

        var badConcurrency = http.put("/api/dispatch-pools/" + id, "{\"concurrency\":0}", ANCHOR);
        assertThat(badConcurrency.statusCode()).isEqualTo(400);
        assertThat(json(badConcurrency).get("error").asText()).isEqualTo("INVALID_CONCURRENCY");

        var missing = http.put("/api/dispatch-pools/dpl_doesnotexist1", "{\"name\":\"X\"}", ANCHOR);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(json(missing).get("error").asText()).isEqualTo("DispatchPool_NOT_FOUND");
    }

    @Test
    void archiveSuspendAndActivateReturn204AndFlipTheStatus() {
        String id = create(code("flip"), "Flip", "");

        assertThat(http.post("/api/dispatch-pools/" + id + "/suspend", null, ANCHOR).statusCode()).isEqualTo(204);
        assertThat(json(http.get("/api/dispatch-pools/" + id, ANCHOR)).get("status").asText()).isEqualTo("SUSPENDED");

        assertThat(http.post("/api/dispatch-pools/" + id + "/activate", null, ANCHOR).statusCode()).isEqualTo(204);
        assertThat(json(http.get("/api/dispatch-pools/" + id, ANCHOR)).get("status").asText()).isEqualTo("ACTIVE");

        var archive = http.post("/api/dispatch-pools/" + id + "/archive", null, ANCHOR);
        assertThat(archive.statusCode()).isEqualTo(204);
        assertThat(archive.body()).isEmpty();
        assertThat(json(http.get("/api/dispatch-pools/" + id, ANCHOR)).get("status").asText()).isEqualTo("ARCHIVED");

        var unknown = http.post("/api/dispatch-pools/dpl_doesnotexist1/archive", null, ANCHOR);
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(json(unknown).get("error").asText()).isEqualTo("DispatchPool_NOT_FOUND");
    }

    @Test
    void deleteReturns204ThenGetIs404Envelope() {
        String id = create(code("del"), "Doomed", "");
        var del = http.delete("/api/dispatch-pools/" + id, ANCHOR);
        assertThat(del.statusCode()).isEqualTo(204);

        var get = http.get("/api/dispatch-pools/" + id, ANCHOR);
        assertThat(get.statusCode()).isEqualTo(404);
        assertThat(get.body()).isEqualTo("{\"error\":\"DispatchPool_NOT_FOUND\",\"message\":\"DispatchPool not found: " + id + "\"}\n");

        var again = http.delete("/api/dispatch-pools/" + id, ANCHOR);
        assertThat(again.statusCode()).isEqualTo(404);
        assertThat(json(again).get("error").asText()).isEqualTo("DispatchPool_NOT_FOUND");
    }

    // ── Negative paths ─────────────────────────────────────────────────────

    @Test
    void missingPermissionOrPrincipalIs403Envelope() {
        var r = http.post("/api/dispatch-pools", "{\"code\":\"" + code("perm") + "\",\"name\":\"X\"}", VIEWER);
        assertThat(r.statusCode()).isEqualTo(403);
        var env = json(r);
        assertThat(env.get("error").asText()).isEqualTo("PERMISSION_REQUIRED");
        assertThat(env.get("message").asText()).contains("platform:messaging:dispatch-pool:create");

        var del = http.delete("/api/dispatch-pools/dpl_whatever", VIEWER);
        assertThat(del.statusCode()).isEqualTo(403);
        assertThat(json(del).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");

        // A create-only writer may not delete: delete is gated on the delete verb.
        var writerDel = http.delete("/api/dispatch-pools/dpl_whatever", CLIENT_WRITER);
        assertThat(writerDel.statusCode()).isEqualTo(403);
        assertThat(json(writerDel).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");

        var suspend = http.post("/api/dispatch-pools/dpl_whatever/suspend", null, VIEWER);
        assertThat(suspend.statusCode()).isEqualTo(403);

        var anon = http.get("/api/dispatch-pools");
        assertThat(anon.statusCode()).isEqualTo(403);
        assertThat(json(anon).get("error").asText()).isEqualTo("UNAUTHENTICATED");
    }

    /// The coarse gate passes (create permission) but the use case's
    /// resource-level authorization refuses a platform-wide create from a
    /// CLIENT-scoped principal; binding to its own client is allowed.
    @Test
    void clientScopedPrincipalCannotCreatePlatformWidePool() {
        var r = http.post("/api/dispatch-pools", "{\"code\":\"" + code("scope-platform") + "\",\"name\":\"X\"}", CLIENT_WRITER);
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(json(r).get("error").asText()).isEqualTo("SCOPE_FORBIDDEN");

        var own = http.post("/api/dispatch-pools",
                "{\"code\":\"" + code("scope-own") + "\",\"name\":\"X\",\"clientId\":\"" + CLIENT + "\"}", CLIENT_WRITER);
        assertThat(own.statusCode()).as(own.body()).isEqualTo(201);
    }

    @Test
    void validationConflictAndMalformedJsonAreEnvelopes() {
        var bad = http.post("/api/dispatch-pools", "{\"code\":\"1bad\",\"name\":\"X\"}", ANCHOR);
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(json(bad).get("error").asText()).isEqualTo("INVALID_CODE_FORMAT");
        assertThat(json(bad).get("message").asText())
                .isEqualTo("code must start with a lowercase letter and contain only lowercase alphanumeric, hyphens, underscores");

        var rate = http.post("/api/dispatch-pools", "{\"code\":\"" + code("rate") + "\",\"name\":\"X\",\"rateLimit\":-1}", ANCHOR);
        assertThat(rate.statusCode()).isEqualTo(400);
        assertThat(json(rate).get("error").asText()).isEqualTo("INVALID_RATE_LIMIT");

        // Create lowercases before checking uniqueness, so "DUP" collides with "dup".
        var dup = http.post("/api/dispatch-pools", "{\"code\":\"" + code("dup") + "\",\"name\":\"X\"}", ANCHOR);
        assertThat(dup.statusCode()).isEqualTo(201);
        var dup2 = http.post("/api/dispatch-pools", "{\"code\":\"" + code("dup").toUpperCase(Locale.ROOT) + "\",\"name\":\"X\"}", ANCHOR);
        assertThat(dup2.statusCode()).isEqualTo(409);
        assertThat(json(dup2).get("error").asText()).isEqualTo("CODE_EXISTS");

        var malformed = http.post("/api/dispatch-pools", "{not json", ANCHOR);
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(json(malformed).get("error").asText()).isEqualTo("INVALID_JSON");
    }
}
