package io.flowcatalyst.platform.cors.api;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.cors.CorsOriginRepository;
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

/// The five `/api/platform/cors*` routes end to end through Javalin: the
/// authenticator's test headers, the anchor-only gates, the public
/// `/allowed` read, route precedence between `/allowed` and `{id}`, the
/// lockfile status codes and body shapes, and the error envelope.
@SuppressWarnings("deprecation")
class CorsOriginApiTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);

    private static final String ANCHOR_PRINCIPAL = EntityType.PRINCIPAL.generate();
    private static final String[] ANCHOR = {
            Authenticator.TEST_PRINCIPAL, ANCHOR_PRINCIPAL,
            Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:*:*:*"};
    /// A CLIENT-scoped principal holding the super-admin wildcard — which must not help on an anchor-only surface.
    private static final String[] CLIENT_ADMIN = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, "cli_cors_" + RUN,
            Authenticator.TEST_PERMISSIONS, "*"};

    private static final CorsOriginApi.State state = new CorsOriginApi.State(new CorsOriginRepository(TestPg.dataSource()),
            new UnitOfWork(TestPg.dataSource(), new PlatformSink(Json.MAPPER)), () -> { });
    private static TestHttp http;

    private static final String TS = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z";

    @BeforeAll
    static void start() {
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before("/api/*", auth);
            CorsOriginApi.register(routes, state);
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

    private static String origin(String tag) {
        return "https://" + tag + "-" + RUN + ".example.com";
    }

    private static String add(String origin, String extraJson) {
        var r = http.post("/api/platform/cors", "{\"origin\":\"" + origin + "\"" + extraJson + "}", ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        var id = json(r).get("id").asText();
        assertThat(id).startsWith("cor_");
        return id;
    }

    // ── Happy paths ────────────────────────────────────────────────────────

    @Test
    void addThenReadByIdInListAndInThePublicAllowlist() {
        String origin = origin("corsapi");
        String id = add(origin, ",\"description\":\"SPA\"");

        // POST body is exactly the CreatedResponse envelope.
        var created = http.post("/api/platform/cors", "{\"origin\":\"" + origin("corsapi-2") + "\"}", ANCHOR);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).matches("\\{\"id\":\"cor_[0-9A-Z]{13}\"}\n");
        assertThat(created.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");

        // GET by id: the AllowedOriginResponse shape.
        var get = http.get("/api/platform/cors/" + id, ANCHOR);
        assertThat(get.statusCode()).isEqualTo(200);
        var o = json(get);
        assertThat(o.get("id").asText()).isEqualTo(id);
        assertThat(o.get("origin").asText()).isEqualTo(origin);
        assertThat(o.get("description").asText()).isEqualTo("SPA");
        assertThat(o.get("createdBy").asText()).isEqualTo(ANCHOR_PRINCIPAL);
        assertThat(o.get("createdAt").asText()).matches(TS);
        assertThat(o.get("updatedAt").asText()).matches(TS);
        assertThat(o.propertyNames()).containsExactly("id", "origin", "description", "createdBy", "createdAt", "updatedAt");

        // Without a description the field is omitted.
        var bare = json(http.get("/api/platform/cors/" + json(created).get("id").asText(), ANCHOR));
        assertThat(bare.has("description")).as("null description omitted").isFalse();

        // List: {"corsOrigins": [...], "total": n}, ordered by origin.
        var list = http.get("/api/platform/cors", ANCHOR);
        assertThat(list.statusCode()).isEqualTo(200);
        var body = json(list);
        assertThat(body.propertyNames()).containsExactly("corsOrigins", "total");
        assertThat(body.get("corsOrigins").isArray()).isTrue();
        assertThat(body.get("total").asInt()).isEqualTo(body.get("corsOrigins").size());
        assertThat(body.get("corsOrigins")).extracting(n -> n.get("id").asText()).contains(id);

        // Public allowlist: {"origins": [...]} — strings only, no bearer, no test headers.
        var allowed = http.get("/api/platform/cors/allowed");
        assertThat(allowed.statusCode()).isEqualTo(200);
        assertThat(json(allowed).propertyNames()).containsExactly("origins");
        assertThat(json(allowed).get("origins")).extracting(JsonNode::asText).contains(origin, origin("corsapi-2"));
    }

    @Test
    void deleteReturns204ThenGetIs404Envelope() {
        String origin = origin("corsdel");
        String id = add(origin, "");

        var del = http.delete("/api/platform/cors/" + id, ANCHOR);
        assertThat(del.statusCode()).isEqualTo(204);
        assertThat(del.body()).isEmpty();

        var get = http.get("/api/platform/cors/" + id, ANCHOR);
        assertThat(get.statusCode()).isEqualTo(404);
        assertThat(get.body()).isEqualTo("{\"error\":\"CorsOrigin_NOT_FOUND\",\"message\":\"CorsOrigin not found: " + id + "\"}\n");

        var again = http.delete("/api/platform/cors/" + id, ANCHOR);
        assertThat(again.statusCode()).isEqualTo(404);
        assertThat(json(again).get("error").asText()).isEqualTo("CorsOrigin_NOT_FOUND");

        assertThat(json(http.get("/api/platform/cors/allowed")).get("origins")).extracting(JsonNode::asText).doesNotContain(origin);
    }

    // ── Negative paths ─────────────────────────────────────────────────────

    @Test
    void nonAnchorAndAnonymousAre403EnvelopesExceptOnThePublicRead() {
        var list = http.get("/api/platform/cors", CLIENT_ADMIN);
        assertThat(list.statusCode()).isEqualTo(403);
        assertThat(json(list).get("error").asText()).isEqualTo("ANCHOR_REQUIRED");
        assertThat(json(list).get("message").asText()).isEqualTo("anchor scope required");

        var add = http.post("/api/platform/cors", "{\"origin\":\"" + origin("corsdenied") + "\"}", CLIENT_ADMIN);
        assertThat(add.statusCode()).isEqualTo(403);
        assertThat(json(add).get("error").asText()).isEqualTo("ANCHOR_REQUIRED");
        assertThat(json(http.get("/api/platform/cors/allowed")).get("origins")).extracting(JsonNode::asText).doesNotContain(origin("corsdenied"));

        var get = http.get("/api/platform/cors/cor_whatever", CLIENT_ADMIN);
        assertThat(get.statusCode()).isEqualTo(403);
        var del = http.delete("/api/platform/cors/cor_whatever", CLIENT_ADMIN);
        assertThat(del.statusCode()).isEqualTo(403);

        var anon = http.get("/api/platform/cors");
        assertThat(anon.statusCode()).isEqualTo(403);
        assertThat(json(anon).get("error").asText()).isEqualTo("UNAUTHENTICATED");

        var publicRead = http.get("/api/platform/cors/allowed");
        assertThat(publicRead.statusCode()).as("the allowlist read is public").isEqualTo(200);
    }

    @Test
    void validationConflictAndMalformedJsonAreErrorEnvelopes() {
        // origin is schema-required — sent as "" so the request reaches the domain check.
        var missing = http.post("/api/platform/cors", "{\"origin\":\"\",\"description\":\"no origin\"}", ANCHOR);
        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(json(missing).get("error").asText()).isEqualTo("ORIGIN_REQUIRED");

        var bad = http.post("/api/platform/cors", "{\"origin\":\"example.com/path\"}", ANCHOR);
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(json(bad).get("error").asText()).isEqualTo("INVALID_ORIGIN_FORMAT");
        assertThat(json(bad).get("message").asText()).isEqualTo("Origin must be a valid URL (e.g. https://example.com or http://localhost:3000)");

        String origin = origin("corsdup");
        add(origin, "");
        var dup = http.post("/api/platform/cors", "{\"origin\":\"  " + origin + "  \"}", ANCHOR);
        assertThat(dup.statusCode()).isEqualTo(409);
        assertThat(json(dup).get("error").asText()).isEqualTo("ORIGIN_ALREADY_EXISTS");

        var malformed = http.post("/api/platform/cors", "{not json", ANCHOR);
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(json(malformed).get("error").asText()).isEqualTo("INVALID_JSON");
    }
}
