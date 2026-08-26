package io.flowcatalyst.platform.audit.api;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.audit.AuditLogFixture;
import io.flowcatalyst.platform.audit.AuditLogFixture.OtherCommand;
import io.flowcatalyst.platform.audit.AuditLogFixture.SeedCommand;
import io.flowcatalyst.platform.audit.AuditLogRepository;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.platform.shared.auth.ClaimsResolver;
import io.flowcatalyst.platform.shared.auth.JwtVerifier;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static io.flowcatalyst.platform.audit.AuditLogFixture.aggregate;
import static io.flowcatalyst.platform.audit.AuditLogFixture.entityId;
import static io.flowcatalyst.platform.audit.AuditLogFixture.entityType;
import static io.flowcatalyst.platform.audit.AuditLogFixture.seed;
import static org.assertj.core.api.Assertions.assertThat;

/// The nine `/api/audit-logs` routes end to end through Javalin (spec §2–6):
/// the view gate, the lockfile envelopes, the cursor walk, the facets and
/// the error envelope.
@SuppressWarnings("deprecation")
class AuditLogApiTest {

    private static final String[] ANCHOR = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "ANCHOR"};
    private static final String[] VIEWER = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, "cli_audapi_" + AuditLogFixture.RUN,
            Authenticator.TEST_PERMISSIONS, "platform:admin:audit-log:view"};
    private static final String[] NO_PERMISSION = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, "cli_audapi_" + AuditLogFixture.RUN,
            Authenticator.TEST_PERMISSIONS, "platform:admin:audit-log:export"};

    private static final Instant BASE = Instant.parse("2026-02-01T08:00:00.500000Z");
    private static final String AGG = aggregate("api");
    private static final String TYPE = entityType("api");
    private static String principal;
    private static String entityA;
    private static List<String> idsNewestFirst;
    private static TestHttp http;

    @BeforeAll
    static void start() {
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        var state = new AuditLogApi.State(new AuditLogRepository(AuditLogFixture.DS));
        http = new TestHttp(cfg -> {
            HttpError.install(cfg.routes);
            cfg.routes.before("/api/*", auth);
            AuditLogApi.register(cfg.routes, state);
        });

        principal = AuditLogFixture.principal("Grace Hopper " + AuditLogFixture.RUN);
        entityA = entityId();
        var ids = new ArrayList<String>();
        for (int i = 0; i < 5; i++) {
            ids.add(seed(AGG, entityA, principal, BASE.minusSeconds(i), new SeedCommand("n" + i, i)));
        }
        idsNewestFirst = List.copyOf(ids);
        seed(AGG, entityId(), null, BASE.minusSeconds(20), new OtherCommand("someone else"));
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

    // ── List (cursor) ──────────────────────────────────────────────────────

    @Test
    void listEnvelopeAndEntryShapeAreTheLockfiles() {
        var body = ok(http.get("/api/audit-logs?entityType=" + TYPE + "&entityId=" + entityA, ANCHOR));
        assertThat(body.propertyNames()).containsExactly("auditLogs", "hasMore");
        assertThat(body.get("hasMore").asBoolean()).isFalse();
        var logs = body.get("auditLogs");
        assertThat(logs).extracting(n -> n.get("id").asText()).containsExactlyElementsOf(idsNewestFirst);

        var first = logs.get(0);
        assertThat(first.propertyNames()).containsExactly(
                "id", "entityType", "entityId", "operation", "operationJson", "principalId", "principalName", "performedAt");
        assertThat(first.get("entityType").asText()).isEqualTo(TYPE);
        assertThat(first.get("entityId").asText()).isEqualTo(entityA);
        assertThat(first.get("operation").asText()).isEqualTo("SeedCommand");
        assertThat(first.get("operationJson").isTextual()).as("a JSON string, not a nested object").isTrue();
        // jsonb re-orders keys (shorter first); the wire carries the stored document, compacted
        assertThat(first.get("operationJson").asText()).isEqualTo("{\"n\":0,\"note\":\"n0\"}");
        assertThat(first.get("principalId").asText()).isEqualTo(principal);
        assertThat(first.get("principalName").asText()).isEqualTo("Grace Hopper " + AuditLogFixture.RUN);
        assertThat(first.get("performedAt").asText()).isEqualTo("2026-02-01T08:00:00.500000Z");
    }

    @Test
    void cursorWalkVisitsEveryRowOnceNewestFirst() {
        var seen = new ArrayList<String>();
        String path = "/api/audit-logs?pageSize=2&entityType=" + TYPE + "&entityId=" + entityA;
        var page = ok(http.get(path, ANCHOR));
        int pages = 1;
        while (true) {
            page.get("auditLogs").forEach(n -> seen.add(n.get("id").asText()));
            if (!page.get("hasMore").asBoolean()) {
                assertThat(page.has("nextCursor")).isFalse();
                break;
            }
            assertThat(page.get("auditLogs")).hasSize(2);
            assertThat(page.get("nextCursor").asText()).isNotBlank();
            page = ok(http.get(path + "&after=" + page.get("nextCursor").asText(), ANCHOR));
            pages++;
        }
        assertThat(pages).isEqualTo(3);
        assertThat(seen).containsExactlyElementsOf(idsNewestFirst);
    }

    @Test
    void recentIsAnAliasOfTheList() {
        var list = ok(http.get("/api/audit-logs?entityType=" + TYPE, ANCHOR));
        var recent = ok(http.get("/api/audit-logs/recent?entityType=" + TYPE, ANCHOR));
        assertThat(recent).isEqualTo(list);
        assertThat(recent.get("auditLogs")).hasSize(6);
    }

    @Test
    void listFiltersNarrowTheWindow() {
        assertThat(ok(http.get("/api/audit-logs?entityType=" + TYPE + "&operation=OtherCommand", ANCHOR)).get("auditLogs"))
                .singleElement().satisfies(n -> assertThat(n.has("principalId")).isFalse());
        assertThat(ok(http.get("/api/audit-logs?entityType=" + TYPE + "&principalId=" + principal, ANCHOR)).get("auditLogs")).hasSize(5);
        assertThat(ok(http.get("/api/audit-logs?entityType=" + TYPE + "&applicationIds=app_a,%20app_b,", ANCHOR)).get("auditLogs")).isEmpty();
        assertThat(ok(http.get("/api/audit-logs?entityType=" + TYPE + "&clientIds=,%20,", ANCHOR)).get("auditLogs")).as("all-blank CSV is no filter").hasSize(6);
        assertThat(ok(http.get("/api/audit-logs?entityType=" + TYPE + "&pageSize=0", ANCHOR)).get("auditLogs")).hasSize(6);
        assertThat(ok(http.get("/api/audit-logs?entityType=" + TYPE + "&pageSize=201", ANCHOR)).get("auditLogs")).hasSize(6);
    }

    @Test
    void badCursorAndBadPageSizeAre400Envelopes() {
        var cursor = http.get("/api/audit-logs?after=not-a-cursor", ANCHOR);
        assertThat(cursor.statusCode()).isEqualTo(400);
        assertThat(cursor.body()).isEqualTo("{\"error\":\"CURSOR\",\"message\":\"invalid cursor\"}\n");

        var size = http.get("/api/audit-logs?pageSize=ten", ANCHOR);
        assertThat(size.statusCode()).isEqualTo(400);
        var env = json(size);
        assertThat(env.get("error").asText()).isEqualTo("VALIDATION");
        var detail = env.get("details").get("errors").get(0);
        assertThat(detail.get("message").asText()).isEqualTo("invalid integer");
        assertThat(detail.get("location").asText()).isEqualTo("query.pageSize");
        assertThat(detail.get("value").asText()).isEqualTo("ten");
    }

    // ── Facets ─────────────────────────────────────────────────────────────

    @Test
    void facetRoutesAnswerTheirOwnEnvelopes() {
        var types = ok(http.get("/api/audit-logs/entity-types", ANCHOR));
        assertThat(types.propertyNames()).containsExactly("entityTypes");
        assertThat(types.get("entityTypes")).extracting(JsonNode::asText).contains(TYPE);

        var ops = ok(http.get("/api/audit-logs/operations", ANCHOR));
        assertThat(ops.propertyNames()).containsExactly("operations");
        assertThat(ops.get("operations")).extracting(JsonNode::asText).contains("SeedCommand", "OtherCommand");

        var apps = ok(http.get("/api/audit-logs/application-ids", ANCHOR));
        assertThat(apps.propertyNames()).containsExactly("applicationIds");
        assertThat(apps.get("applicationIds").isArray()).isTrue();

        var clients = ok(http.get("/api/audit-logs/client-ids", ANCHOR));
        assertThat(clients.propertyNames()).containsExactly("clientIds");
        assertThat(clients.get("clientIds").isArray()).isTrue();
    }

    // ── Entity / principal lists ───────────────────────────────────────────

    @Test
    void entityAndPrincipalListsAreUnpaginatedListEnvelopes() {
        var byEntity = ok(http.get("/api/audit-logs/entity/" + TYPE + "/" + entityA, ANCHOR));
        assertThat(byEntity.propertyNames()).containsExactly("auditLogs", "hasMore");
        assertThat(byEntity.get("hasMore").asBoolean()).isFalse();
        assertThat(byEntity.get("auditLogs")).extracting(n -> n.get("id").asText()).containsExactlyElementsOf(idsNewestFirst);

        var byPrincipal = ok(http.get("/api/audit-logs/principal/" + principal, ANCHOR));
        assertThat(byPrincipal.get("hasMore").asBoolean()).isFalse();
        assertThat(byPrincipal.get("auditLogs")).extracting(n -> n.get("id").asText()).containsExactlyElementsOf(idsNewestFirst);

        assertThat(ok(http.get("/api/audit-logs/entity/" + TYPE + "/" + entityId(), ANCHOR)).get("auditLogs")).isEmpty();
    }

    // ── Get by id ──────────────────────────────────────────────────────────

    @Test
    void getByIdAnswersTheEntryOrTheNotFoundEnvelope() {
        var get = ok(http.get("/api/audit-logs/" + idsNewestFirst.get(1), ANCHOR));
        assertThat(get.get("id").asText()).isEqualTo(idsNewestFirst.get(1));
        assertThat(get.get("operationJson").asText()).isEqualTo("{\"n\":1,\"note\":\"n1\"}");

        String missing = EntityType.AUDIT_LOG.generate();
        var r = http.get("/api/audit-logs/" + missing, ANCHOR);
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(r.body()).isEqualTo("{\"error\":\"AuditLog_NOT_FOUND\",\"message\":\"AuditLog not found: " + missing + "\"}\n");
    }

    // ── Gate ───────────────────────────────────────────────────────────────

    @Test
    void everyRouteRequiresTheViewPermission() {
        for (String path : List.of("/api/audit-logs", "/api/audit-logs/recent", "/api/audit-logs/entity-types",
                "/api/audit-logs/operations", "/api/audit-logs/application-ids", "/api/audit-logs/client-ids",
                "/api/audit-logs/entity/X/y", "/api/audit-logs/principal/p", "/api/audit-logs/aud_x")) {
            var denied = http.get(path, NO_PERMISSION);
            assertThat(denied.statusCode()).as(path).isEqualTo(403);
            assertThat(json(denied).get("error").asText()).as(path).isEqualTo("PERMISSION_REQUIRED");
            assertThat(json(denied).get("message").asText()).contains("platform:admin:audit-log:view");

            var anon = http.get(path);
            assertThat(anon.statusCode()).as(path).isEqualTo(403);
            assertThat(json(anon).get("error").asText()).as(path).isEqualTo("UNAUTHENTICATED");
        }
        // a client-scoped viewer with the permission sees every row (spec §8, open question 5)
        assertThat(ok(http.get("/api/audit-logs?entityType=" + TYPE, VIEWER)).get("auditLogs")).hasSize(6);
    }
}
