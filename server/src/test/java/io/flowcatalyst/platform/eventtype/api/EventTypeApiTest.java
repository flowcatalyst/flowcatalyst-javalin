package io.flowcatalyst.platform.eventtype.api;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.eventtype.EventTypeRepository;
import io.flowcatalyst.platform.eventtype.operations.ArchiveCommand;
import io.flowcatalyst.platform.eventtype.operations.ArchiveEventType;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.platform.shared.auth.ClaimsResolver;
import io.flowcatalyst.platform.shared.auth.JwtVerifier;
import io.flowcatalyst.platform.shared.auth.Scope;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.ExecutionContext;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// The eight `/api/event-types` routes end to end through Javalin: the
/// authenticator's test headers, the coarse permission gates, the lockfile
/// status codes and body shapes, and the error envelope.
@SuppressWarnings("deprecation")
class EventTypeApiTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final String APP = "etapi" + RUN;

    private static final String ANCHOR_PRINCIPAL = EntityType.PRINCIPAL.generate();
    private static final String[] ANCHOR = {
            Authenticator.TEST_PRINCIPAL, ANCHOR_PRINCIPAL,
            Authenticator.TEST_SCOPE, "ANCHOR"};
    private static final String[] VIEWER = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, "cli_etapi_" + RUN,
            Authenticator.TEST_PERMISSIONS, "platform:messaging:event-type:view"};
    private static final String[] CLIENT_WRITER = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, "cli_etapi_" + RUN,
            Authenticator.TEST_PERMISSIONS, "platform:messaging:event-type:view,platform:messaging:event-type:create"};

    private static final EventTypeApi.State state = new EventTypeApi.State(new EventTypeRepository(TestPg.dataSource()),
            new UnitOfWork(TestPg.dataSource(), new PlatformSink(Json.MAPPER)));
    private static TestHttp http;
    private static final HttpClient client = HttpClient.newHttpClient();

    @BeforeAll
    static void start() {
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before("/api/*", auth);
            EventTypeApi.register(routes, state);
        });
    }

    @AfterAll
    static void stop() {
        http.close();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static HttpResponse<String> send(String method, String path, String body, String... headers) {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + http.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) b.header("Content-Type", "application/json");
        if (headers.length > 0) b.headers(headers);
        try {
            return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
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

    private static String create(String code, String name, String extraJson) {
        var r = send("POST", "/api/event-types",
                "{\"code\":\"" + code + "\",\"name\":\"" + name + "\"" + extraJson + "}", ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        var id = json(r).get("id").asText();
        assertThat(id).startsWith("evt_");
        return id;
    }

    // ── Happy paths ────────────────────────────────────────────────────────

    /// Owner ruling 2026-09-06 #7: `clientScoped` is honoured on create and on
    /// update (absent leaves it unchanged), and answered on every read.
    @Test
    void clientScopedIsHonouredOnCreateAndUpdateAndAbsentLeavesItUnchanged() {
        String id = create(APP + ":scoped:thing:happened", "Scoped", ",\"clientScoped\":true");
        assertThat(json(send("GET", "/api/event-types/" + id, null, ANCHOR)).get("clientScoped").asBoolean()).isTrue();

        var renamed = send("PUT", "/api/event-types/" + id, "{\"name\":\"Renamed\"}", ANCHOR);
        assertThat(renamed.statusCode()).as(renamed.body()).isEqualTo(204);
        assertThat(json(send("GET", "/api/event-types/" + id, null, ANCHOR)).get("clientScoped").asBoolean())
                .as("absent on update leaves the stored value").isTrue();

        var unscoped = send("PUT", "/api/event-types/" + id, "{\"name\":\"Renamed\",\"clientScoped\":false}", ANCHOR);
        assertThat(unscoped.statusCode()).as(unscoped.body()).isEqualTo(204);
        assertThat(json(send("GET", "/api/event-types/" + id, null, ANCHOR)).get("clientScoped").asBoolean()).isFalse();

        String plain = create(APP + ":scoped:thing:defaulted", "Defaulted", "");
        assertThat(json(send("GET", "/api/event-types/" + plain, null, ANCHOR)).get("clientScoped").asBoolean())
                .as("absent on create is false").isFalse();
    }

    @Test
    void createThenReadByIdByCodeAndInList() {
        String code = APP + ":orders:order:created";
        String id = create(code, "Order Created", ",\"description\":\"desc\",\"schema\":{\"type\":\"object\"}");

        // POST body is exactly the CreatedResponse envelope.
        var created = send("POST", "/api/event-types", "{\"code\":\"" + APP + ":orders:order:shipped\",\"name\":\"Shipped\"}", ANCHOR);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).matches("\\{\"id\":\"evt_[0-9A-Z]{13}\"}\n");
        assertThat(created.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");

        // GET by id: the EventTypeResponse shape.
        var get = send("GET", "/api/event-types/" + id, null, ANCHOR);
        assertThat(get.statusCode()).isEqualTo(200);
        var et = json(get);
        assertThat(et.get("id").asText()).isEqualTo(id);
        assertThat(et.get("code").asText()).isEqualTo(code);
        assertThat(et.get("name").asText()).isEqualTo("Order Created");
        assertThat(et.get("application").asText()).isEqualTo(APP);
        assertThat(et.get("subdomain").asText()).isEqualTo("orders");
        assertThat(et.get("aggregate").asText()).isEqualTo("order");
        assertThat(et.get("eventName").asText()).isEqualTo("created");
        assertThat(et.get("description").asText()).isEqualTo("desc");
        assertThat(et.get("status").asText()).isEqualTo("CURRENT");
        assertThat(et.get("source").asText()).isEqualTo("UI");
        assertThat(et.get("createdBy").asText()).isEqualTo(ANCHOR_PRINCIPAL);
        assertThat(et.has("clientId")).as("null clientId omitted").isFalse();
        assertThat(et.get("createdAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z");
        assertThat(et.get("updatedAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z");
        assertThat(et.get("specVersions")).hasSize(1);
        var sv = et.get("specVersions").get(0);
        assertThat(sv.get("version").asText()).isEqualTo("1.0");
        assertThat(sv.get("status").asText()).isEqualTo("FINALISING");
        assertThat(sv.get("schema").get("type").asText()).isEqualTo("object");
        assertThat(sv.get("createdAt").asText()).endsWith("Z");
        assertThat(et.propertyNames()).containsExactly("id", "code", "name", "application", "subdomain",
                "aggregate", "eventName", "description", "status", "source", "clientScoped", "createdBy", "createdAt", "updatedAt", "specVersions");

        // GET by code.
        var byCode = send("GET", "/api/event-types/by-code/" + code, null, ANCHOR);
        assertThat(byCode.statusCode()).isEqualTo(200);
        assertThat(json(byCode).get("id").asText()).isEqualTo(id);

        // List, filtered by application: {"items": [...]}, ordered by code.
        var list = send("GET", "/api/event-types?application=" + APP, null, ANCHOR);
        assertThat(list.statusCode()).isEqualTo(200);
        var items = json(list).get("items");
        assertThat(items.isArray()).isTrue();
        assertThat(items).extracting(n -> n.get("code").asText())
                .contains(code, APP + ":orders:order:shipped");
        assertThat(json(list).propertyNames()).containsExactly("items");

        // A viewer (CLIENT scope, view permission) sees platform-level event types too.
        var viewerList = send("GET", "/api/event-types?application=" + APP, null, VIEWER);
        assertThat(viewerList.statusCode()).isEqualTo(200);
        assertThat(json(viewerList).get("items")).extracting(n -> n.get("id").asText()).contains(id);
    }

    @Test
    void listDefaultsToCurrentWhenUnfiltered() {
        String id = create(APP + ":list:thing:archived", "To be archived", "");
        // Archive has no route on this surface (it is a BFF operation), so run the operation directly.
        Auth.runAs(new AuthContext(ANCHOR_PRINCIPAL, Scope.ANCHOR, null, List.of("*"), List.of(), List.of(), true, List.of()),
                () -> ArchiveEventType.of(state.repo()).run(state.uow(), new ArchiveCommand(id), ExecutionContext.of(ANCHOR_PRINCIPAL)));

        var unfiltered = send("GET", "/api/event-types", null, ANCHOR);
        assertThat(unfiltered.statusCode()).isEqualTo(200);
        assertThat(json(unfiltered).get("items")).extracting(n -> n.get("id").asText()).doesNotContain(id);
        var archived = send("GET", "/api/event-types?status=ARCHIVED&application=" + APP, null, ANCHOR);
        assertThat(json(archived).get("items")).extracting(n -> n.get("id").asText()).containsExactly(id);
    }

    @Test
    void updateReturns204AndPersists() {
        String id = create(APP + ":upd:thing:changed", "Before", "");
        var put = send("PUT", "/api/event-types/" + id, "{\"name\":\"After\",\"description\":\"now described\"}", ANCHOR);
        assertThat(put.statusCode()).isEqualTo(204);
        assertThat(put.body()).isEmpty();

        var et = json(send("GET", "/api/event-types/" + id, null, ANCHOR));
        assertThat(et.get("name").asText()).isEqualTo("After");
        assertThat(et.get("description").asText()).isEqualTo("now described");

        var bad = send("PUT", "/api/event-types/" + id, "{\"name\":\"\"}", ANCHOR);
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(json(bad).get("error").asText()).isEqualTo("NAME_REQUIRED");
    }

    @Test
    void addSchemaOnBothPathsReturnsTheUpdatedEventType() {
        String id = create(APP + ":sch:thing:added", "Schemas", "");

        var v1 = send("POST", "/api/event-types/" + id + "/schemas", "{\"version\":\"1.0\",\"schema\":{\"type\":\"object\"}}", ANCHOR);
        assertThat(v1.statusCode()).as(v1.body()).isEqualTo(200);
        assertThat(json(v1).get("id").asText()).isEqualTo(id);
        assertThat(json(v1).get("specVersions")).hasSize(1);

        var v2 = send("POST", "/api/event-types/" + id + "/versions", "{\"version\":\"2.0\",\"schema\":{\"type\":\"object\",\"title\":\"v2\"}}", ANCHOR);
        assertThat(v2.statusCode()).as(v2.body()).isEqualTo(200);
        var versions = json(v2).get("specVersions");
        assertThat(versions).hasSize(2);
        assertThat(versions).extracting(n -> n.get("version").asText()).containsExactly("1.0", "2.0");
        assertThat(versions.get(1).get("schema").get("title").asText()).isEqualTo("v2");
        assertThat(versions.get(1).get("status").asText()).isEqualTo("FINALISING");

        var dup = send("POST", "/api/event-types/" + id + "/versions", "{\"version\":\"2.0\",\"schema\":{}}", ANCHOR);
        assertThat(dup.statusCode()).isEqualTo(409);
        assertThat(json(dup).get("error").asText()).isEqualTo("VERSION_EXISTS");

        // schema is schema-required on AddSchemaRequest, but it carries no `type` of its own
        // (an arbitrary JSON Schema document) — an explicit JSON null clears the presence check
        // (spec §2: null on an untyped/nullable member is not a type error) while still reading
        // as null to AddSchema's own SCHEMA_REQUIRED check, so it is the one value that reaches
        // the domain code instead of 400 VALIDATION.
        var missing = send("POST", "/api/event-types/" + id + "/versions", "{\"version\":\"3.0\",\"schema\":null}", ANCHOR);
        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(json(missing).get("error").asText()).isEqualTo("SCHEMA_REQUIRED");
    }

    @Test
    void deleteReturns204ThenGetIs404Envelope() {
        String id = create(APP + ":del:thing:gone", "Doomed", "");
        var del = send("DELETE", "/api/event-types/" + id, null, ANCHOR);
        assertThat(del.statusCode()).isEqualTo(204);
        // No body, no Content-Type — Go sends none on a 204 and the parity harness
        // (S0) flagged Javalin's default text/plain; Server strips it.
        assertThat(del.headers().firstValue("Content-Type")).isEmpty();

        var get = send("GET", "/api/event-types/" + id, null, ANCHOR);
        assertThat(get.statusCode()).isEqualTo(404);
        assertThat(get.body()).isEqualTo("{\"error\":\"EventType_NOT_FOUND\",\"message\":\"EventType not found: " + id + "\"}\n");

        var again = send("DELETE", "/api/event-types/" + id, null, ANCHOR);
        assertThat(again.statusCode()).isEqualTo(404);
        assertThat(json(again).get("error").asText()).isEqualTo("EventType_NOT_FOUND");

        var byCode = send("GET", "/api/event-types/by-code/" + APP + ":del:thing:gone", null, ANCHOR);
        assertThat(byCode.statusCode()).isEqualTo(404);
    }

    // ── Negative paths ─────────────────────────────────────────────────────

    @Test
    void missingPermissionOrPrincipalIs403Envelope() {
        var r = send("POST", "/api/event-types", "{\"code\":\"" + APP + ":perm:thing:denied\",\"name\":\"X\"}", VIEWER);
        assertThat(r.statusCode()).isEqualTo(403);
        var env = json(r);
        assertThat(env.get("error").asText()).isEqualTo("PERMISSION_REQUIRED");
        assertThat(env.get("message").asText()).contains("platform:messaging:event-type:create");

        var del = send("DELETE", "/api/event-types/evt_whatever", null, VIEWER);
        assertThat(del.statusCode()).isEqualTo(403);
        assertThat(json(del).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");

        var anon = send("GET", "/api/event-types", null);
        assertThat(anon.statusCode()).isEqualTo(403);
        assertThat(json(anon).get("error").asText()).isEqualTo("UNAUTHENTICATED");
    }

    /// The coarse gate passes (create permission) but the use case's
    /// resource-level authorization refuses a platform-wide create from a
    /// CLIENT-scoped principal.
    @Test
    void clientScopedPrincipalCannotCreatePlatformWideEventType() {
        var r = send("POST", "/api/event-types", "{\"code\":\"" + APP + ":scope:thing:platform\",\"name\":\"X\"}", CLIENT_WRITER);
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(json(r).get("error").asText()).isEqualTo("SCOPE_FORBIDDEN");

        var own = send("POST", "/api/event-types",
                "{\"code\":\"" + APP + ":scope:thing:own\",\"name\":\"X\",\"clientId\":\"cli_etapi_" + RUN + "\"}", CLIENT_WRITER);
        assertThat(own.statusCode()).as(own.body()).isEqualTo(201);
    }

    @Test
    void validationAndMalformedJsonAre400Envelopes() {
        var bad = send("POST", "/api/event-types", "{\"code\":\"only:three:parts\",\"name\":\"X\"}", ANCHOR);
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(json(bad).get("error").asText()).isEqualTo("INVALID_CODE_FORMAT");
        assertThat(json(bad).get("message").asText()).isEqualTo("Event type code must follow format: application:subdomain:aggregate:event");

        var dup = send("POST", "/api/event-types", "{\"code\":\"" + APP + ":dup:thing:twice\",\"name\":\"X\"}", ANCHOR);
        assertThat(dup.statusCode()).isEqualTo(201);
        var dup2 = send("POST", "/api/event-types", "{\"code\":\"" + APP + ":dup:thing:twice\",\"name\":\"X\"}", ANCHOR);
        assertThat(dup2.statusCode()).isEqualTo(409);
        assertThat(json(dup2).get("error").asText()).isEqualTo("CODE_EXISTS");

        var malformed = send("POST", "/api/event-types", "{not json", ANCHOR);
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(json(malformed).get("error").asText()).isEqualTo("INVALID_JSON");
    }
}
