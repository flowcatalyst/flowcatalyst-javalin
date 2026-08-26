package io.flowcatalyst.platform.event.api;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.event.EventFixture;
import io.flowcatalyst.platform.event.EventFixture.Payload;
import io.flowcatalyst.platform.event.EventRepository;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.platform.shared.auth.ClaimsResolver;
import io.flowcatalyst.platform.shared.auth.JwtVerifier;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.tsid.Tsid;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static io.flowcatalyst.platform.event.EventFixture.NOW;
import static io.flowcatalyst.platform.event.EventFixture.emit;
import static io.flowcatalyst.platform.event.EventFixture.insert;
import static io.flowcatalyst.platform.event.EventFixture.project;
import static io.flowcatalyst.platform.event.EventFixture.readRow;
import static io.flowcatalyst.platform.event.EventFixture.application;
import static io.flowcatalyst.platform.event.EventFixture.type;
import static org.assertj.core.api.Assertions.assertThat;

/// The five `/api/events` routes end to end through Javalin (spec §2–5, §8):
/// the two gates, the bare-array list and its filters, the tenant scoping,
/// the filter options, the detail shape and the error envelopes.
@SuppressWarnings("deprecation")
class EventApiTest {

    private static final String CLIENT_A = "cli_" + EventFixture.RUN + "0000api";
    private static final String CLIENT_B = "cli_" + EventFixture.RUN + "0000apj";

    private static final String[] ANCHOR = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "ANCHOR"};
    private static final String[] VIEWER = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, CLIENT_A,
            Authenticator.TEST_PERMISSIONS, "platform:messaging:event:view"};
    private static final String[] RAW_VIEWER = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, CLIENT_A,
            Authenticator.TEST_PERMISSIONS, "platform:messaging:event:view-raw"};
    private static final String[] NO_PERMISSION = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, CLIENT_A,
            Authenticator.TEST_PERMISSIONS, "platform:messaging:event-type:view"};

    private static final String APP = application("api");
    private static final String SHIPPED = type(APP, "shipping", "shipment", "shipped");
    private static final String PAID = type(APP, "billing", "invoice", "paid");
    private static final Instant T0 = NOW.minusSeconds(1200);
    private static final String IN_RUN = "applications=" + APP;

    private static String entity;
    private static String sinkRow;     // emitted + projected, platform-scoped, newest
    private static String platformRow; // direct, T0
    private static String rowA;        // direct, client A, T0 - 10s
    private static String rowB;        // direct, client B, T0 - 20s
    private static TestHttp http;

    @BeforeAll
    static void start() {
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        var state = new EventApi.State(new EventRepository(EventFixture.DS));
        http = new TestHttp(cfg -> {
            HttpError.install(cfg.routes);
            cfg.routes.before("/api/*", auth);
            EventApi.register(cfg.routes, state);
        });

        entity = EntityType.EVENT_TYPE.generate();
        sinkRow = emit(SHIPPED, "platform.shipment." + entity, EntityType.PRINCIPAL.generate(), NOW.minusSeconds(3),
                "corr-api-" + EventFixture.RUN, "grp-api", new Payload("shipped", 7));
        project(List.of(sinkRow));
        platformRow = insert(readRow(SHIPPED, null, T0));
        var a = readRow(PAID, CLIENT_A, T0.minusSeconds(10));
        a.setMessageGroup("grp-a");
        a.setCorrelationId("corr-a");
        rowA = insert(a);
        rowB = insert(readRow(PAID, CLIENT_B, T0.minusSeconds(20)));
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

    private static JsonNode ok(HttpResponse<String> r) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        return json(r);
    }

    private static List<String> ids(JsonNode array) {
        assertThat(array.isArray()).as("a bare JSON array").isTrue();
        return array.valueStream().map(n -> n.get("id").asText()).toList();
    }

    // ── List ───────────────────────────────────────────────────────────────

    @Test
    void listIsABareArrayOfEventReadNewestFirst() {
        var body = ok(http.get("/api/events?" + IN_RUN, ANCHOR));
        assertThat(ids(body)).containsExactly(sinkRow, platformRow, rowA, rowB);

        var first = body.get(0);
        assertThat(first.propertyNames()).containsExactly(
                "id", "type", "source", "subject", "time", "application", "subdomain", "aggregate", "messageGroup", "correlationId", "projectedAt");
        assertThat(first.get("type").asText()).isEqualTo(SHIPPED);
        assertThat(first.get("source").asText()).isEqualTo("platform:admin");
        assertThat(first.get("subject").asText()).isEqualTo("platform.shipment." + entity);
        assertThat(first.get("time").asText()).isEqualTo(Json.MAPPER.convertValue(NOW.minusSeconds(3), String.class));
        assertThat(first.get("time").asText()).endsWith("Z").matches(".*\\.\\d{6}Z");
        assertThat(first.get("application").asText()).isEqualTo(APP);
        assertThat(first.get("subdomain").asText()).isEqualTo("shipping");
        assertThat(first.get("aggregate").asText()).isEqualTo("shipment");
        assertThat(first.get("messageGroup").asText()).isEqualTo("grp-api");
        assertThat(first.get("correlationId").asText()).isEqualTo("corr-api-" + EventFixture.RUN);
        assertThat(first.has("clientId")).as("platform-scoped: no clientId").isFalse();

        var a = body.get(2);
        assertThat(a.propertyNames()).containsExactly(
                "id", "type", "source", "subject", "time", "application", "subdomain", "aggregate", "messageGroup", "correlationId", "clientId", "projectedAt");
        assertThat(a.get("clientId").asText()).isEqualTo(CLIENT_A);
    }

    @Test
    void listFiltersSizeAndOffsetNarrowTheWindow() {
        assertThat(ids(ok(http.get("/api/events?" + IN_RUN + "&types=" + PAID + ",%20,", ANCHOR)))).containsExactly(rowA, rowB);
        assertThat(ids(ok(http.get("/api/events?" + IN_RUN + "&type=" + SHIPPED, ANCHOR)))).containsExactly(sinkRow, platformRow);
        assertThat(ids(ok(http.get("/api/events?" + IN_RUN + "&subdomains=billing&aggregates=invoice", ANCHOR)))).containsExactly(rowA, rowB);
        assertThat(ids(ok(http.get("/api/events?" + IN_RUN + "&clientIds=" + CLIENT_A + "," + CLIENT_B, ANCHOR)))).containsExactly(rowA, rowB);
        assertThat(ids(ok(http.get("/api/events?" + IN_RUN + "&clientId=" + CLIENT_B, ANCHOR)))).containsExactly(rowB);
        assertThat(ids(ok(http.get("/api/events?" + IN_RUN + "&correlationId=corr-a", ANCHOR)))).containsExactly(rowA);
        assertThat(ids(ok(http.get("/api/events?" + IN_RUN + "&subject=platform.shipment." + entity, ANCHOR)))).containsExactly(sinkRow);
        assertThat(ids(ok(http.get("/api/events?" + IN_RUN + "&source=test://" + APP, ANCHOR)))).containsExactly(platformRow, rowA, rowB);
        // since / until bound created_at, any offset accepted
        String until = URLEncoder.encode(T0.minusSeconds(10).atOffset(ZoneOffset.ofHours(2)).toString(), StandardCharsets.UTF_8);
        assertThat(ids(ok(http.get("/api/events?" + IN_RUN + "&until=" + until, ANCHOR)))).containsExactly(rowA, rowB);
        assertThat(ids(ok(http.get("/api/events?" + IN_RUN + "&since=" + T0, ANCHOR)))).containsExactly(sinkRow, platformRow);
        assertThat(ids(ok(http.get("/api/events?" + IN_RUN + "&since=yesterday", ANCHOR)))).as("unparseable since is ignored").hasSize(4);
        // principalId is accepted and ignored
        assertThat(ids(ok(http.get("/api/events?" + IN_RUN + "&principalId=prn_nobody", ANCHOR)))).hasSize(4);
        // size wins over limit; offset skips
        assertThat(ids(ok(http.get("/api/events?" + IN_RUN + "&limit=1&size=2", ANCHOR)))).containsExactly(sinkRow, platformRow);
        assertThat(ids(ok(http.get("/api/events?" + IN_RUN + "&limit=2", ANCHOR)))).containsExactly(sinkRow, platformRow);
        assertThat(ids(ok(http.get("/api/events?" + IN_RUN + "&size=2&offset=1", ANCHOR)))).containsExactly(platformRow, rowA);
        // out-of-range sizes fall back to the repository default (spec §3, open question 5)
        assertThat(ids(ok(http.get("/api/events?" + IN_RUN + "&size=0", ANCHOR)))).hasSize(4);
        assertThat(ids(ok(http.get("/api/events?" + IN_RUN + "&size=5000", ANCHOR)))).hasSize(4);
    }

    @Test
    void nonIntegerPagingParametersAreOneValidationEnvelope() {
        var r = http.get("/api/events?limit=ten&offset=2&size=many", ANCHOR);
        assertThat(r.statusCode()).isEqualTo(400);
        var env = json(r);
        assertThat(env.get("error").asText()).isEqualTo("VALIDATION");
        var errors = env.get("details").get("errors");
        assertThat(errors).hasSize(2);
        assertThat(errors.get(0).get("message").asText()).isEqualTo("invalid integer");
        assertThat(errors.get(0).get("location").asText()).isEqualTo("query.limit");
        assertThat(errors.get(0).get("value").asText()).isEqualTo("ten");
        assertThat(errors.get(1).get("location").asText()).isEqualTo("query.size");
        assertThat(errors.get(1).get("value").asText()).isEqualTo("many");
    }

    @Test
    void aClientScopedViewerSeesPlatformRowsAndItsOwnClientOnly() {
        assertThat(ids(ok(http.get("/api/events?" + IN_RUN, VIEWER)))).containsExactly(sinkRow, platformRow, rowA);
        assertThat(ids(ok(http.get("/api/events?" + IN_RUN + "&clientIds=" + CLIENT_B, VIEWER)))).as("cannot reach across tenants").isEmpty();
        assertThat(ids(ok(http.get("/api/events?" + IN_RUN + "&clientId=" + CLIENT_B, VIEWER)))).isEmpty();
        assertThat(ids(ok(http.get("/api/events?" + IN_RUN + "&clientIds=" + CLIENT_A + "," + CLIENT_B, VIEWER)))).containsExactly(rowA);
    }

    @Test
    void rawRoutesAreTheSameListBehindTheRawGate() {
        var list = ok(http.get("/api/events?" + IN_RUN, ANCHOR));
        var listRaw = ok(http.get("/api/events/list-raw?" + IN_RUN, ANCHOR));
        var raw = ok(http.get("/api/events/raw?" + IN_RUN, ANCHOR));
        assertThat(listRaw).isEqualTo(list);
        assertThat(raw).isEqualTo(list);
        // view-raw does not grant the plain list, and vice versa
        assertThat(ids(ok(http.get("/api/events/raw?" + IN_RUN, RAW_VIEWER)))).containsExactly(sinkRow, platformRow, rowA);
        assertThat(http.get("/api/events?" + IN_RUN, RAW_VIEWER).statusCode()).isEqualTo(403);
        assertThat(http.get("/api/events/list-raw?" + IN_RUN, VIEWER).statusCode()).isEqualTo(403);
    }

    // ── Filter options ─────────────────────────────────────────────────────

    @Test
    void filterOptionsAnswerThreeLabelledFacets() {
        var body = ok(http.get("/api/events/filter-options", VIEWER));
        assertThat(body.propertyNames()).containsExactly("applications", "subdomains", "eventTypes");
        assertThat(body.get("applications").valueStream().map(n -> n.get("value").asText())).contains(APP);
        var app = body.get("applications").valueStream().filter(n -> n.get("value").asText().equals(APP)).findFirst().orElseThrow();
        assertThat(app.propertyNames()).containsExactly("value", "label");
        assertThat(app.get("label").asText()).isEqualTo(APP);
        assertThat(body.get("subdomains").valueStream().map(n -> n.get("value").asText())).contains("shipping", "billing");
        assertThat(body.get("eventTypes").valueStream().map(n -> n.get("value").asText())).contains(SHIPPED, PAID);
    }

    // ── Get by id ──────────────────────────────────────────────────────────

    @Test
    void getByIdAnswersTheFullEnvelope() {
        var e = ok(http.get("/api/events/" + sinkRow, ANCHOR));
        assertThat(e.propertyNames()).containsExactly(
                "id", "specVersion", "type", "source", "subject", "time", "data", "deduplicationId",
                "messageGroup", "correlationId", "application", "subdomain", "aggregate", "projectedAt", "createdAt");
        assertThat(e.get("specVersion").asText()).isEqualTo("1.0");
        assertThat(e.get("type").asText()).isEqualTo(SHIPPED);
        assertThat(e.get("subject").asText()).isEqualTo("platform.shipment." + entity);
        assertThat(e.get("data").isObject()).as("data is a nested object, not a string").isTrue();
        assertThat(e.get("data").get("note").asText()).isEqualTo("shipped");
        assertThat(e.get("data").get("n").asInt()).isEqualTo(7);
        assertThat(e.has("contextData")).as("the projection has no context").isFalse();
        assertThat(e.get("deduplicationId").asText()).isEqualTo(SHIPPED + "-" + sinkRow);
        assertThat(e.get("messageGroup").asText()).isEqualTo("grp-api");
        assertThat(e.get("application").asText()).isEqualTo(APP);
        assertThat(e.get("subdomain").asText()).isEqualTo("shipping");
        assertThat(e.get("aggregate").asText()).isEqualTo("shipment");
        assertThat(e.get("projectedAt").asText()).matches(".*\\.\\d{6}Z");
        assertThat(e.get("createdAt").asText()).matches(".*\\.\\d{6}Z");

        var a = ok(http.get("/api/events/" + rowA, ANCHOR));
        assertThat(a.get("clientId").asText()).isEqualTo(CLIENT_A);
        assertThat(a.get("data").get("seeded").asBoolean()).isTrue();
    }

    @Test
    void getByIdNotFoundAndOutOfScopeAreTheirEnvelopes() {
        String missing = Tsid.generate();
        var r = http.get("/api/events/" + missing, ANCHOR);
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(r.body()).isEqualTo("{\"error\":\"Event_NOT_FOUND\",\"message\":\"Event not found: " + missing + "\"}\n");

        // a viewer of A: platform row and A's row read; B's row is forbidden, not hidden
        assertThat(ok(http.get("/api/events/" + platformRow, VIEWER)).get("id").asText()).isEqualTo(platformRow);
        assertThat(ok(http.get("/api/events/" + rowA, VIEWER)).get("id").asText()).isEqualTo(rowA);
        var denied = http.get("/api/events/" + rowB, VIEWER);
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(denied.body()).isEqualTo("{\"error\":\"FORBIDDEN\",\"message\":\"No access to this event\"}\n");
    }

    // ── Gate ───────────────────────────────────────────────────────────────

    @Test
    void everyRouteRequiresItsViewPermission() {
        var gates = List.of(
                List.of("/api/events", "platform:messaging:event:view"),
                List.of("/api/events/filter-options", "platform:messaging:event:view"),
                List.of("/api/events/list-raw", "platform:messaging:event:view-raw"),
                List.of("/api/events/raw", "platform:messaging:event:view-raw"),
                List.of("/api/events/" + rowA, "platform:messaging:event:view"));
        for (var gate : gates) {
            String path = gate.get(0);
            var denied = http.get(path, NO_PERMISSION);
            assertThat(denied.statusCode()).as(path).isEqualTo(403);
            assertThat(json(denied).get("error").asText()).as(path).isEqualTo("PERMISSION_REQUIRED");
            assertThat(json(denied).get("message").asText()).as(path).isEqualTo("permission required: " + gate.get(1));

            var anon = http.get(path);
            assertThat(anon.statusCode()).as(path).isEqualTo(403);
            assertThat(json(anon).get("error").asText()).as(path).isEqualTo("UNAUTHENTICATED");
        }
    }
}
