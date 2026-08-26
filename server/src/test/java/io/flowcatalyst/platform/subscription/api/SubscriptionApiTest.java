package io.flowcatalyst.platform.subscription.api;

import tools.jackson.databind.JsonNode;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// The seven `/api/subscriptions` routes end to end through Javalin: the
/// authenticator's test headers, the coarse permission gates, the lockfile
/// status codes and body shapes, and the error envelope.
@SuppressWarnings("deprecation")
class SubscriptionApiTest {

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
            Authenticator.TEST_PERMISSIONS, "platform:messaging:subscription:view,platform:messaging:subscription:create"};

    private static final String BINDINGS = "\"eventTypes\":[{\"eventTypeCode\":\"subapi:orders:order:created\"}]";

    private static final SubscriptionApi.State state = new SubscriptionApi.State(new SubscriptionRepository(TestPg.dataSource()),
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
            SubscriptionApi.register(cfg.routes, state);
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
        return "subapi-" + tag + "-" + RUN;
    }

    /// A CLIENT-scoped principal with the view permission on `clientId` only.
    private static String[] viewer(String clientId) {
        return new String[] {
                Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
                Authenticator.TEST_SCOPE, "CLIENT",
                Authenticator.TEST_CLIENTS, clientId,
                Authenticator.TEST_PERMISSIONS, "platform:messaging:subscription:view"};
    }

    /// `{"code", "name", "endpoint", eventTypes…, extra}` — the minimum valid body plus `extraJson`.
    private static String body(String code, String name, String extraJson) {
        return "{\"code\":\"" + code + "\",\"name\":\"" + name + "\",\"endpoint\":\"https://hooks.example.test/" + code + "\","
                + BINDINGS + extraJson + "}";
    }

    private static String create(String code, String name, String extraJson) {
        var r = http.post("/api/subscriptions", body(code, name, extraJson), ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        var id = json(r).get("id").asText();
        assertThat(id).startsWith("sub_");
        return id;
    }

    // ── Happy paths ────────────────────────────────────────────────────────

    @Test
    void createThenReadByIdAndInList() {
        String code = code("read");
        String id = create(code, "Read Me", ",\"description\":\"desc\",\"mode\":\"NEXT_ON_ERROR\",\"timeoutSeconds\":45,"
                + "\"customConfig\":[{\"key\":\"X-Env\",\"value\":\"test\"}],\"dataOnly\":false,\"connectionId\":\"con_subapi1\"");

        // POST body is exactly the CreatedResponse envelope.
        var created = http.post("/api/subscriptions", body(code("plain"), "Plain", ""), ANCHOR);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).matches("\\{\"id\":\"sub_[0-9A-Z]{13}\"}\n");
        assertThat(created.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");

        // GET by id: the SubscriptionResponse shape.
        var get = http.get("/api/subscriptions/" + id, ANCHOR);
        assertThat(get.statusCode()).isEqualTo(200);
        var s = json(get);
        assertThat(s.get("id").asText()).isEqualTo(id);
        assertThat(s.get("code").asText()).isEqualTo(code);
        assertThat(s.get("name").asText()).isEqualTo("Read Me");
        assertThat(s.get("description").asText()).isEqualTo("desc");
        assertThat(s.get("clientScoped").asBoolean()).isFalse();
        assertThat(s.get("eventTypes")).hasSize(1);
        assertThat(s.get("eventTypes").get(0).get("eventTypeCode").asText()).isEqualTo("subapi:orders:order:created");
        assertThat(s.get("eventTypes").get(0).propertyNames()).as("null binding fields omitted").containsExactly("eventTypeCode");
        assertThat(s.get("connectionId").asText()).isEqualTo("con_subapi1");
        assertThat(s.get("endpoint").asText()).isEqualTo("https://hooks.example.test/" + code);
        assertThat(s.get("customConfig").get(0).get("key").asText()).isEqualTo("X-Env");
        assertThat(s.get("source").asText()).isEqualTo("UI");
        assertThat(s.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(s.get("maxAgeSeconds").asInt()).isEqualTo(86400);
        assertThat(s.get("delaySeconds").asInt()).isZero();
        assertThat(s.get("sequence").asInt()).isEqualTo(99);
        assertThat(s.get("mode").asText()).isEqualTo("NEXT_ON_ERROR");
        assertThat(s.get("timeoutSeconds").asInt()).isEqualTo(45);
        assertThat(s.get("maxRetries").asInt()).isEqualTo(3);
        assertThat(s.get("dataOnly").asBoolean()).isFalse();
        assertThat(s.get("createdBy").asText()).isEqualTo(ANCHOR_PRINCIPAL);
        assertThat(s.has("clientId")).as("null clientId omitted").isFalse();
        assertThat(s.has("applicationCode")).isFalse();
        assertThat(s.has("queue")).isFalse();
        assertThat(s.has("dispatchPoolId")).isFalse();
        assertThat(s.get("createdAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z");
        assertThat(s.get("updatedAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z");
        assertThat(s.propertyNames()).containsExactly("id", "code", "name", "description", "clientScoped",
                "eventTypes", "connectionId", "endpoint", "customConfig", "source", "status", "maxAgeSeconds",
                "delaySeconds", "sequence", "mode", "timeoutSeconds", "maxRetries", "dataOnly", "createdBy",
                "createdAt", "updatedAt");

        // Defaults on the wire.
        var plain = json(http.get("/api/subscriptions/" + json(created).get("id").asText(), ANCHOR));
        assertThat(plain.get("mode").asText()).isEqualTo("IMMEDIATE");
        assertThat(plain.get("timeoutSeconds").asInt()).isEqualTo(30);
        assertThat(plain.get("dataOnly").asBoolean()).isTrue();
        assertThat(plain.get("customConfig").isArray()).as("empty arrays are present").isTrue();
        assertThat(plain.get("customConfig")).isEmpty();

        // List: {"subscriptions": [...], "total": n}, ordered by code.
        var list = http.get("/api/subscriptions", ANCHOR);
        assertThat(list.statusCode()).isEqualTo(200);
        var body = json(list);
        assertThat(body.propertyNames()).containsExactly("subscriptions", "total");
        assertThat(body.get("total").asInt()).isEqualTo(body.get("subscriptions").size());
        assertThat(body.get("subscriptions")).extracting(n -> n.get("code").asText()).contains(code, code("plain"));
        assertThat(body.get("subscriptions").findValuesAsString("code")).isSorted();

        // A viewer (CLIENT scope, view permission) sees platform-wide subscriptions too.
        var viewerList = http.get("/api/subscriptions", VIEWER);
        assertThat(viewerList.statusCode()).isEqualTo(200);
        assertThat(json(viewerList).get("subscriptions")).extracting(n -> n.get("id").asText()).contains(id);
    }

    /// Uses a client of its own (not the shared `CLIENT`) so the exact-match
    /// assertions on `?clientId=` cannot see rows other tests bind to `CLIENT`.
    @Test
    void listFiltersByStatusAndClientAndHidesOtherClientsSubscriptions() {
        String client = EntityType.CLIENT.generate();
        String[] viewer = viewer(client);
        String mineId = create(code("mine"), "Mine", ",\"clientId\":\"" + client + "\"");
        String otherId = create(code("other"), "Other", ",\"clientId\":\"" + EntityType.CLIENT.generate() + "\"");

        var byClient = http.get("/api/subscriptions?clientId=" + client, ANCHOR);
        assertThat(json(byClient).get("subscriptions")).extracting(n -> n.get("id").asText()).containsExactly(mineId);
        assertThat(json(byClient).get("total").asInt()).isEqualTo(1);
        assertThat(json(byClient).get("subscriptions").get(0).get("clientId").asText()).isEqualTo(client);

        // The viewer cannot see the other client's subscription, in the list or by id.
        var viewerList = http.get("/api/subscriptions", viewer);
        assertThat(json(viewerList).get("subscriptions")).extracting(n -> n.get("id").asText()).contains(mineId).doesNotContain(otherId);
        var forbidden = http.get("/api/subscriptions/" + otherId, viewer);
        assertThat(forbidden.statusCode()).isEqualTo(403);
        assertThat(json(forbidden).get("error").asText()).isEqualTo("FORBIDDEN");
        assertThat(json(forbidden).get("message").asText()).isEqualTo("No access to this subscription");

        // Status filter; an empty value is no filter.
        assertThat(http.post("/api/subscriptions/" + mineId + "/pause", null, ANCHOR).statusCode()).isEqualTo(204);
        assertThat(json(http.get("/api/subscriptions?status=PAUSED&clientId=" + client, ANCHOR)).get("subscriptions"))
                .extracting(n -> n.get("id").asText()).containsExactly(mineId);
        assertThat(json(http.get("/api/subscriptions?status=ACTIVE&clientId=" + client, ANCHOR)).get("subscriptions")).isEmpty();
        assertThat(json(http.get("/api/subscriptions?status=&clientId=" + client, ANCHOR)).get("subscriptions")).hasSize(1);
    }

    @Test
    void updateReturns204AndPersistsOnlyTheGivenFields() {
        String id = create(code("upd"), "Before", ",\"description\":\"kept\",\"maxRetries\":8");
        var put = http.put("/api/subscriptions/" + id,
                "{\"name\":\"After\",\"endpoint\":\"https://after.example.test/hook\",\"eventTypes\":[{\"eventTypeCode\":\"subapi:orders:order:*\",\"specVersion\":\"2.0\"}]}", ANCHOR);
        assertThat(put.statusCode()).isEqualTo(204);
        assertThat(put.body()).isEmpty();

        var s = json(http.get("/api/subscriptions/" + id, ANCHOR));
        assertThat(s.get("name").asText()).isEqualTo("After");
        assertThat(s.get("endpoint").asText()).isEqualTo("https://after.example.test/hook");
        assertThat(s.get("description").asText()).isEqualTo("kept");
        assertThat(s.get("maxRetries").asInt()).isEqualTo(8);
        assertThat(s.get("eventTypes")).hasSize(1);
        assertThat(s.get("eventTypes").get(0).get("eventTypeCode").asText()).isEqualTo("subapi:orders:order:*");
        assertThat(s.get("eventTypes").get(0).get("specVersion").asText()).isEqualTo("2.0");

        var bad = http.put("/api/subscriptions/" + id, "{\"name\":\"\"}", ANCHOR);
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(json(bad).get("error").asText()).isEqualTo("NAME_REQUIRED");

        var badEndpoint = http.put("/api/subscriptions/" + id, "{\"endpoint\":\"ftp://x\"}", ANCHOR);
        assertThat(badEndpoint.statusCode()).isEqualTo(400);
        assertThat(json(badEndpoint).get("error").asText()).isEqualTo("INVALID_ENDPOINT");

        var missing = http.put("/api/subscriptions/sub_doesnotexist1", "{\"name\":\"X\"}", ANCHOR);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(json(missing).get("error").asText()).isEqualTo("Subscription_NOT_FOUND");
    }

    @Test
    void pauseAndResumeReturn204AndFlipTheStatus() {
        String id = create(code("flip"), "Flip", "");

        var pause = http.post("/api/subscriptions/" + id + "/pause", null, ANCHOR);
        assertThat(pause.statusCode()).isEqualTo(204);
        assertThat(pause.body()).isEmpty();
        assertThat(json(http.get("/api/subscriptions/" + id, ANCHOR)).get("status").asText()).isEqualTo("PAUSED");

        assertThat(http.post("/api/subscriptions/" + id + "/resume", null, ANCHOR).statusCode()).isEqualTo(204);
        assertThat(json(http.get("/api/subscriptions/" + id, ANCHOR)).get("status").asText()).isEqualTo("ACTIVE");

        var unknown = http.post("/api/subscriptions/sub_doesnotexist1/pause", null, ANCHOR);
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(json(unknown).get("error").asText()).isEqualTo("Subscription_NOT_FOUND");
    }

    @Test
    void deleteReturns204ThenGetIs404Envelope() {
        String id = create(code("del"), "Doomed", "");
        var del = http.delete("/api/subscriptions/" + id, ANCHOR);
        assertThat(del.statusCode()).isEqualTo(204);

        var get = http.get("/api/subscriptions/" + id, ANCHOR);
        assertThat(get.statusCode()).isEqualTo(404);
        assertThat(get.body()).isEqualTo("{\"error\":\"Subscription_NOT_FOUND\",\"message\":\"Subscription not found: " + id + "\"}\n");

        var again = http.delete("/api/subscriptions/" + id, ANCHOR);
        assertThat(again.statusCode()).isEqualTo(404);
        assertThat(json(again).get("error").asText()).isEqualTo("Subscription_NOT_FOUND");
    }

    /// Absent binding / config fields are stored as empty strings, as the
    /// rows always looked (spec §4, open question 10) — never a 500.
    @Test
    void absentBindingAndConfigFieldsAreStoredEmpty() {
        var r = http.post("/api/subscriptions", "{\"code\":\"" + code("blank") + "\",\"name\":\"X\",\"endpoint\":\"https://x.example.test\","
                + "\"eventTypes\":[{\"specVersion\":\"1.0\"}],\"customConfig\":[{\"key\":\"k\"}]}", ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        var s = json(http.get("/api/subscriptions/" + json(r).get("id").asText(), ANCHOR));
        assertThat(s.get("eventTypes").get(0).get("eventTypeCode").asText()).isEmpty();
        assertThat(s.get("customConfig").get(0).get("value").asText()).isEmpty();
    }

    // ── Negative paths ─────────────────────────────────────────────────────

    @Test
    void missingPermissionOrPrincipalIs403Envelope() {
        var r = http.post("/api/subscriptions", body(code("perm"), "X", ""), VIEWER);
        assertThat(r.statusCode()).isEqualTo(403);
        var env = json(r);
        assertThat(env.get("error").asText()).isEqualTo("PERMISSION_REQUIRED");
        assertThat(env.get("message").asText()).contains("platform:messaging:subscription:create");

        var del = http.delete("/api/subscriptions/sub_whatever", VIEWER);
        assertThat(del.statusCode()).isEqualTo(403);
        assertThat(json(del).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");

        // A create-only writer may not delete: delete is gated on the delete verb.
        var writerDel = http.delete("/api/subscriptions/sub_whatever", CLIENT_WRITER);
        assertThat(writerDel.statusCode()).isEqualTo(403);
        assertThat(json(writerDel).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");

        var pause = http.post("/api/subscriptions/sub_whatever/pause", null, VIEWER);
        assertThat(pause.statusCode()).isEqualTo(403);

        var anon = http.get("/api/subscriptions");
        assertThat(anon.statusCode()).isEqualTo(403);
        assertThat(json(anon).get("error").asText()).isEqualTo("UNAUTHENTICATED");
    }

    /// The coarse gate passes (create permission) but the use case's
    /// resource-level authorization refuses a platform-wide create from a
    /// CLIENT-scoped principal; binding to its own client is allowed.
    @Test
    void clientScopedPrincipalCannotCreatePlatformWideSubscription() {
        var r = http.post("/api/subscriptions", body(code("scope-platform"), "X", ""), CLIENT_WRITER);
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(json(r).get("error").asText()).isEqualTo("SCOPE_FORBIDDEN");

        var own = http.post("/api/subscriptions", body(code("scope-own"), "X", ",\"clientId\":\"" + CLIENT + "\""), CLIENT_WRITER);
        assertThat(own.statusCode()).as(own.body()).isEqualTo(201);
    }

    @Test
    void validationConflictAndMalformedJsonAreEnvelopes() {
        var bad = http.post("/api/subscriptions", body("1bad", "X", ""), ANCHOR);
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(json(bad).get("error").asText()).isEqualTo("INVALID_CODE_FORMAT");
        assertThat(json(bad).get("message").asText())
                .isEqualTo("code must start with a lowercase letter and contain only lowercase alphanumeric and hyphens");

        var noBindings = http.post("/api/subscriptions",
                "{\"code\":\"" + code("noet") + "\",\"name\":\"X\",\"endpoint\":\"https://x.example.test\"}", ANCHOR);
        assertThat(noBindings.statusCode()).isEqualTo(400);
        assertThat(json(noBindings).get("error").asText()).isEqualTo("EVENT_TYPES_REQUIRED");

        var badEndpoint = http.post("/api/subscriptions",
                "{\"code\":\"" + code("ftp") + "\",\"name\":\"X\",\"endpoint\":\"ftp://x\"," + BINDINGS + "}", ANCHOR);
        assertThat(badEndpoint.statusCode()).isEqualTo(400);
        assertThat(json(badEndpoint).get("error").asText()).isEqualTo("INVALID_ENDPOINT");

        // Create lowercases before checking uniqueness, so "DUP" collides with "dup".
        var dup = http.post("/api/subscriptions", body(code("dup"), "X", ""), ANCHOR);
        assertThat(dup.statusCode()).isEqualTo(201);
        var dup2 = http.post("/api/subscriptions", body(code("dup").toUpperCase(Locale.ROOT), "X", ""), ANCHOR);
        assertThat(dup2.statusCode()).isEqualTo(409);
        assertThat(json(dup2).get("error").asText()).isEqualTo("CODE_EXISTS");

        var malformed = http.post("/api/subscriptions", "{not json", ANCHOR);
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(json(malformed).get("error").asText()).isEqualTo("INVALID_JSON");
    }
}
