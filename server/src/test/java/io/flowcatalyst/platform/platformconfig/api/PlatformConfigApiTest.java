package io.flowcatalyst.platform.platformconfig.api;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.platformconfig.ConfigAccessRepository;
import io.flowcatalyst.platform.platformconfig.PlatformConfigRepository;
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

/// The seven platform-config routes end to end through Javalin: the
/// authenticator's test headers, the anchor / grant gates, secret masking,
/// the lockfile status codes and body shapes, and the error envelope.
@SuppressWarnings("deprecation")
class PlatformConfigApiTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final String APP = "pcapi" + RUN;
    private static final String READER_ROLE = "pc-reader-" + RUN;
    private static final String WRITER_ROLE = "pc-writer-" + RUN;
    private static final String CLIENT = "cli_pcapi_" + RUN;

    private static final String[] ANCHOR = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "ANCHOR"};
    private static final String[] READER = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, CLIENT,
            Authenticator.TEST_ROLES, READER_ROLE};
    private static final String[] WRITER = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, CLIENT,
            Authenticator.TEST_ROLES, "something-else," + WRITER_ROLE};
    private static final String[] NOBODY = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, CLIENT};

    private static final PlatformConfigApi.State state = new PlatformConfigApi.State(
            new PlatformConfigRepository(TestPg.dataSource()), new ConfigAccessRepository(TestPg.dataSource()),
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
            PlatformConfigApi.register(routes, state);
        });
        // The grants every test relies on; (app, role) pairs are unique to this run.
        grant(READER_ROLE, false);
        grant(WRITER_ROLE, true);
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

    private static String property(String section, String property) {
        return "/api/config/" + APP + "/" + section + "/" + property;
    }

    private static String grant(String role, boolean canWrite) {
        var r = http.post("/api/platform-config/" + APP + "/access",
                "{\"roleCode\":\"" + role + "\",\"canWrite\":" + canWrite + "}", ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        var id = json(r).get("id").asText();
        assertThat(id).startsWith("cfa_");
        return id;
    }

    // ── Happy paths ────────────────────────────────────────────────────────

    @Test
    void setThenGetThenList() {
        var set = http.put(property("smtp", "host"), "{\"value\":\"mail.example.com\",\"description\":\"relay\"}", ANCHOR);
        assertThat(set.statusCode()).as(set.body()).isEqualTo(200);
        var c = json(set);
        assertThat(c.get("id").asText()).startsWith("pcf_");
        assertThat(c.get("applicationCode").asText()).isEqualTo(APP);
        assertThat(c.get("section").asText()).isEqualTo("smtp");
        assertThat(c.get("property").asText()).isEqualTo("host");
        assertThat(c.get("scope").asText()).isEqualTo("GLOBAL");
        assertThat(c.has("clientId")).as("null clientId omitted").isFalse();
        assertThat(c.get("valueType").asText()).isEqualTo("PLAIN");
        assertThat(c.get("value").asText()).isEqualTo("mail.example.com");
        assertThat(c.get("description").asText()).isEqualTo("relay");
        assertThat(c.get("createdAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z");
        assertThat(c.get("updatedAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z");

        var get = http.get(property("smtp", "host"), ANCHOR);
        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(json(get).get("id").asText()).isEqualTo(c.get("id").asText());

        // A second set on the same coordinate keeps the id.
        var again = http.put(property("smtp", "host"), "{\"value\":\"mail2.example.com\"}", ANCHOR);
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(json(again).get("id").asText()).isEqualTo(c.get("id").asText());
        assertThat(json(again).get("value").asText()).isEqualTo("mail2.example.com");
        assertThat(json(again).has("description")).as("absent description clears").isFalse();

        var list = http.get("/api/platform-config/" + APP, ANCHOR);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(json(list).get("items")).anySatisfy(item -> assertThat(item.get("id").asText()).isEqualTo(c.get("id").asText()));
    }

    @Test
    void clientIdQueryParameterAddressesTheClientScopedValue() {
        var global = http.put(property("ui", "colour"), "{\"value\":\"blue\"}", ANCHOR);
        assertThat(global.statusCode()).isEqualTo(200);
        var client = http.put(property("ui", "colour") + "?clientId=" + CLIENT, "{\"value\":\"red\"}", ANCHOR);
        assertThat(client.statusCode()).as(client.body()).isEqualTo(200);
        assertThat(json(client).get("scope").asText()).isEqualTo("CLIENT");
        assertThat(json(client).get("clientId").asText()).isEqualTo(CLIENT);
        assertThat(json(client).get("id").asText()).isNotEqualTo(json(global).get("id").asText());

        assertThat(json(http.get(property("ui", "colour"), ANCHOR)).get("value").asText()).isEqualTo("blue");
        assertThat(json(http.get(property("ui", "colour") + "?clientId=" + CLIENT, ANCHOR)).get("value").asText()).isEqualTo("red");
        // An empty query value is "absent".
        assertThat(json(http.get(property("ui", "colour") + "?clientId=", ANCHOR)).get("value").asText()).isEqualTo("blue");

        // The query parameter wins over the body's clientId; a body clientId is used when the query is absent.
        var bodyClient = http.put(property("ui", "font"), "{\"value\":\"serif\",\"clientId\":\"" + CLIENT + "\"}", ANCHOR);
        assertThat(bodyClient.statusCode()).isEqualTo(200);
        assertThat(json(http.get(property("ui", "font") + "?clientId=" + CLIENT, ANCHOR)).get("value").asText()).isEqualTo("serif");
        assertThat(http.get(property("ui", "font"), ANCHOR).statusCode()).as("no global value was written").isEqualTo(404);
    }

    @Test
    void secretsAreMaskedForNonAnchorsOnReadButNotOnSet() {
        var set = http.put(property("smtp", "password"), "{\"value\":\"hunter2\",\"valueType\":\"SECRET\"}", WRITER);
        assertThat(set.statusCode()).as(set.body()).isEqualTo(200);
        assertThat(json(set).get("valueType").asText()).isEqualTo("SECRET");
        assertThat(json(set).get("value").asText()).as("the set response is the re-read value, unmasked").isEqualTo("hunter2");

        assertThat(json(http.get(property("smtp", "password"), ANCHOR)).get("value").asText()).isEqualTo("hunter2");
        assertThat(json(http.get(property("smtp", "password"), READER)).get("value").asText()).isEqualTo("***");
        var list = json(http.get("/api/platform-config/" + APP, READER)).get("items");
        assertThat(list).anySatisfy(item -> {
            assertThat(item.get("property").asText()).isEqualTo("password");
            assertThat(item.get("value").asText()).isEqualTo("***");
        });
    }

    @Test
    void deleteIsIdempotentAndNeedsWriteAccess() {
        var set = http.put(property("tmp", "gone"), "{\"value\":\"x\"}", ANCHOR);
        assertThat(set.statusCode()).isEqualTo(200);

        var denied = http.delete(property("tmp", "gone"), READER);
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(json(denied).get("error").asText()).isEqualTo("FORBIDDEN");
        assertThat(json(denied).get("message").asText()).isEqualTo("No write access to platform config for " + APP);

        var del = http.delete(property("tmp", "gone"), WRITER);
        assertThat(del.statusCode()).isEqualTo(204);
        assertThat(del.body()).isEmpty();
        var get = http.get(property("tmp", "gone"), ANCHOR);
        assertThat(get.statusCode()).isEqualTo(404);
        assertThat(json(get).get("error").asText()).isEqualTo("Config_NOT_FOUND");
        assertThat(json(get).get("message").asText()).isEqualTo("Config not found: " + APP + "/tmp/gone");

        assertThat(http.delete(property("tmp", "gone"), WRITER).statusCode()).as("absent coordinate is still 204").isEqualTo(204);
    }

    @Test
    void grantsAreListedGrantedAndRevoked() {
        String role = "pc-temp-" + RUN;
        String id = grant(role, true);

        var list = http.get("/api/platform-config/" + APP + "/access", ANCHOR);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(json(list).get("items")).anySatisfy(item -> {
            assertThat(item.get("id").asText()).isEqualTo(id);
            assertThat(item.get("applicationCode").asText()).isEqualTo(APP);
            assertThat(item.get("roleCode").asText()).isEqualTo(role);
            assertThat(item.get("canRead").asBoolean()).isTrue();
            assertThat(item.get("canWrite").asBoolean()).isTrue();
            assertThat(item.get("createdAt").asText()).endsWith("Z");
        });

        // Re-granting the same role answers 201 with the same id.
        assertThat(grant(role, false)).isEqualTo(id);

        var revoke = http.delete("/api/platform-config/access/" + id, ANCHOR);
        assertThat(revoke.statusCode()).isEqualTo(204);
        var again = http.delete("/api/platform-config/access/" + id, ANCHOR);
        assertThat(again.statusCode()).isEqualTo(404);
        assertThat(json(again).get("error").asText()).isEqualTo("PlatformConfigAccess_NOT_FOUND");
    }

    // ── Negative paths ─────────────────────────────────────────────────────

    @Test
    void grantRoutesAreAnchorOnly() {
        var list = http.get("/api/platform-config/" + APP + "/access", WRITER);
        assertThat(list.statusCode()).isEqualTo(403);
        assertThat(json(list).get("error").asText()).isEqualTo("ANCHOR_REQUIRED");

        var grant = http.post("/api/platform-config/" + APP + "/access", "{\"roleCode\":\"x\",\"canWrite\":true}", WRITER);
        assertThat(grant.statusCode()).isEqualTo(403);
        assertThat(json(grant).get("error").asText()).isEqualTo("ANCHOR_REQUIRED");

        var revoke = http.delete("/api/platform-config/access/cfa_whatever", WRITER);
        assertThat(revoke.statusCode()).isEqualTo(403);
        assertThat(json(revoke).get("error").asText()).isEqualTo("ANCHOR_REQUIRED");
    }

    @Test
    void readAndWriteNeedAGrantOrAnchor() {
        var list = http.get("/api/platform-config/" + APP, NOBODY);
        assertThat(list.statusCode()).isEqualTo(403);
        assertThat(json(list).get("error").asText()).isEqualTo("FORBIDDEN");
        assertThat(json(list).get("message").asText()).isEqualTo("No read access to platform config for " + APP);

        var get = http.get(property("smtp", "host"), NOBODY);
        assertThat(get.statusCode()).isEqualTo(403);
        assertThat(json(get).get("error").asText()).isEqualTo("FORBIDDEN");

        // A read grant is not a write grant: the use case refuses before writing.
        var set = http.put(property("smtp", "readonly"), "{\"value\":\"x\"}", READER);
        assertThat(set.statusCode()).isEqualTo(403);
        assertThat(json(set).get("error").asText()).isEqualTo("FORBIDDEN");
        assertThat(json(set).get("message").asText()).isEqualTo("No write access to platform config for " + APP);
        assertThat(http.get(property("smtp", "readonly"), ANCHOR).statusCode()).isEqualTo(404);

        // Reads on another application need their own grant.
        var other = http.get("/api/platform-config/other" + RUN, READER);
        assertThat(other.statusCode()).isEqualTo(403);

        var anon = http.get("/api/platform-config/" + APP);
        assertThat(anon.statusCode()).isEqualTo(403);
        assertThat(json(anon).get("error").asText()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void validationAndMalformedJsonAre400Envelopes() {
        // value is schema-required AND schema-typed as a non-nullable string, so there is no
        // longer an HTTP-reachable value that is both schema-valid and Java-null: an absent
        // value now 400s VALIDATION before ever reaching SetProperty's own `cmd.value() == null`
        // check (that check is still pinned directly against the operation in
        // PlatformConfigOperationsTest — "missing value"). roleCode is schema-required too, but
        // is a plain non-null-checked blank rule, so "" still reaches its domain code.
        var noValue = http.put(property("smtp", "port"), "{\"description\":\"no value\"}", ANCHOR);
        assertThat(noValue.statusCode()).isEqualTo(400);
        assertThat(json(noValue).get("error").asText()).isEqualTo("VALIDATION");

        var noRole = http.post("/api/platform-config/" + APP + "/access", "{\"roleCode\":\"\",\"canWrite\":true}", ANCHOR);
        assertThat(noRole.statusCode()).isEqualTo(400);
        assertThat(json(noRole).get("error").asText()).isEqualTo("ROLE_REQUIRED");

        var malformed = http.put(property("smtp", "port"), "{not json", ANCHOR);
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(json(malformed).get("error").asText()).isEqualTo("INVALID_JSON");
    }
}
