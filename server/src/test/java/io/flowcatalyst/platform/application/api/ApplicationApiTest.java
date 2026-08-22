package io.flowcatalyst.platform.application.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ClientConfigRepository;
import io.flowcatalyst.platform.role.RoleRepository;
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
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;

import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS;
import static io.flowcatalyst.db.generated.Tables.IAM_ROLES;
import static io.flowcatalyst.db.generated.Tables.TNT_CLIENTS;
import static org.assertj.core.api.Assertions.assertThat;

/// The fourteen mounted `/api/applications*` routes end to end through
/// Javalin: the authenticator's test headers, the coarse permission / anchor
/// gates, route precedence between the literal and `{id}` paths, the lockfile
/// status codes and body shapes, and the error envelope.
class ApplicationApiTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);

    private static final String ANCHOR_PRINCIPAL = EntityType.PRINCIPAL.generate();
    private static final String[] ANCHOR = {
            Authenticator.TEST_PRINCIPAL, ANCHOR_PRINCIPAL,
            Authenticator.TEST_SCOPE, "ANCHOR"};
    private static final String[] VIEWER = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, "cli_appapi_" + RUN,
            Authenticator.TEST_PERMISSIONS, "platform:admin:application:view"};
    private static final String[] WRITER = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, "cli_appapi_" + RUN,
            Authenticator.TEST_PERMISSIONS, "platform:admin:application:view,platform:admin:application:update"};

    private static final DSLContext DB = DSL.using(TestPg.dataSource(), SQLDialect.POSTGRES);
    private static final ApplicationApi.State state = new ApplicationApi.State(new ApplicationRepository(TestPg.dataSource()),
            new ClientConfigRepository(TestPg.dataSource()), new RoleRepository(TestPg.dataSource()),
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
            ApplicationApi.register(cfg.routes, state);
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
        return "appapi-" + tag + "-" + RUN;
    }

    private static String create(String code, String name, String extraJson) {
        var r = http.post("/api/applications", "{\"code\":\"" + code + "\",\"name\":\"" + name + "\"" + extraJson + "}", ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        var id = json(r).get("id").asText();
        assertThat(id).startsWith("app_");
        return id;
    }

    private static String client(String name) {
        String id = EntityType.CLIENT.generate();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        DB.insertInto(TNT_CLIENTS).set(TNT_CLIENTS.ID, id).set(TNT_CLIENTS.NAME, name).set(TNT_CLIENTS.IDENTIFIER, name + "-" + RUN)
                .set(TNT_CLIENTS.STATUS, "ACTIVE").set(TNT_CLIENTS.CREATED_AT, now).set(TNT_CLIENTS.UPDATED_AT, now).execute();
        return id;
    }

    // ── Happy paths ────────────────────────────────────────────────────────

    @Test
    void createThenReadByIdByCodeAndInList() {
        String code = code("crud");
        String id = create(code, "Orders", ",\"description\":\"desc\",\"website\":\"https://o\",\"type\":\"INTEGRATION\"");

        // POST body is exactly the CreatedResponse envelope.
        var created = http.post("/api/applications", "{\"code\":\"" + code("crud2") + "\",\"name\":\"Two\"}", ANCHOR);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).matches("\\{\"id\":\"app_[0-9A-Z]{13}\"}\n");

        // GET by id: the ApplicationResponse shape.
        var get = http.get("/api/applications/" + id, ANCHOR);
        assertThat(get.statusCode()).isEqualTo(200);
        var app = json(get);
        assertThat(app.get("id").asText()).isEqualTo(id);
        assertThat(app.get("type").asText()).isEqualTo("INTEGRATION");
        assertThat(app.get("code").asText()).isEqualTo(code);
        assertThat(app.get("name").asText()).isEqualTo("Orders");
        assertThat(app.get("description").asText()).isEqualTo("desc");
        assertThat(app.get("website").asText()).isEqualTo("https://o");
        assertThat(app.get("active").asBoolean()).isTrue();
        assertThat(app.get("hasLoginClient").asBoolean()).as("never computed (spec §3)").isFalse();
        assertThat(app.has("iconUrl")).as("null optionals omitted").isFalse();
        assertThat(app.has("serviceAccountId")).isFalse();
        assertThat(app.get("createdAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z");
        assertThat(app.fieldNames()).toIterable().containsExactly("id", "type", "code", "name", "description", "website",
                "active", "hasLoginClient", "createdAt", "updatedAt");

        // GET by code — the literal path wins over `{id}`.
        var byCode = http.get("/api/applications/by-code/" + code, ANCHOR);
        assertThat(byCode.statusCode()).isEqualTo(200);
        assertThat(json(byCode).get("id").asText()).isEqualTo(id);

        // List: {"applications": [...], "total": n}, ordered by code; type filter.
        var list = http.get("/api/applications?type=INTEGRATION", ANCHOR);
        assertThat(list.statusCode()).isEqualTo(200);
        var body = json(list);
        assertThat(body.fieldNames()).toIterable().containsExactly("applications", "total");
        assertThat(body.get("applications")).extracting(n -> n.get("id").asText()).contains(id);
        assertThat(body.get("applications")).extracting(n -> n.get("type").asText()).containsOnly("INTEGRATION");
        assertThat(body.get("total").asInt()).isEqualTo(body.get("applications").size());

        // A CLIENT-scoped viewer can read applications (platform-level, view permission only).
        var viewerList = http.get("/api/applications", VIEWER);
        assertThat(viewerList.statusCode()).isEqualTo(200);
        assertThat(json(viewerList).get("applications")).extracting(n -> n.get("id").asText()).contains(id);
    }

    @Test
    void updateReturns204AndPatchesOnlyTheSuppliedFields() {
        String id = create(code("upd"), "Before", ",\"description\":\"keep\"");
        var put = http.put("/api/applications/" + id, "{\"name\":\"After\",\"iconUrl\":\"https://i\"}", ANCHOR);
        assertThat(put.statusCode()).isEqualTo(204);
        assertThat(put.body()).isEmpty();

        var app = json(http.get("/api/applications/" + id, ANCHOR));
        assertThat(app.get("name").asText()).isEqualTo("After");
        assertThat(app.get("description").asText()).isEqualTo("keep");
        assertThat(app.get("iconUrl").asText()).isEqualTo("https://i");

        var bad = http.put("/api/applications/" + id, "{\"name\":\" \"}", ANCHOR);
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(json(bad).get("error").asText()).isEqualTo("NAME_REQUIRED");
    }

    @Test
    void activateAndDeactivateReturnTheRefreshedApplication() {
        String id = create(code("flag"), "Flag", "");
        var off = http.post("/api/applications/" + id + "/deactivate", null, ANCHOR);
        assertThat(off.statusCode()).as(off.body()).isEqualTo(200);
        assertThat(json(off).get("id").asText()).isEqualTo(id);
        assertThat(json(off).get("active").asBoolean()).isFalse();

        var inactive = http.get("/api/applications?active=false", ANCHOR);
        assertThat(json(inactive).get("applications")).extracting(n -> n.get("id").asText()).contains(id);
        var active = http.get("/api/applications?active=true", ANCHOR);
        assertThat(json(active).get("applications")).extracting(n -> n.get("id").asText()).doesNotContain(id);

        var on = http.post("/api/applications/" + id + "/activate", null, ANCHOR);
        assertThat(on.statusCode()).isEqualTo(200);
        assertThat(json(on).get("active").asBoolean()).isTrue();

        var missing = http.post("/api/applications/app_doesnotexist1/activate", null, ANCHOR);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(json(missing).get("error").asText()).isEqualTo("Application_NOT_FOUND");
    }

    @Test
    void deleteReturns204ThenGetIs404Envelope() {
        String code = code("del");
        String id = create(code, "Doomed", "");
        var del = http.delete("/api/applications/" + id, ANCHOR);
        assertThat(del.statusCode()).isEqualTo(204);

        var get = http.get("/api/applications/" + id, ANCHOR);
        assertThat(get.statusCode()).isEqualTo(404);
        assertThat(get.body()).isEqualTo("{\"error\":\"Application_NOT_FOUND\",\"message\":\"Application not found: " + id + "\"}\n");

        var byCode = http.get("/api/applications/by-code/" + code, ANCHOR);
        assertThat(byCode.statusCode()).isEqualTo(404);
        assertThat(json(byCode).get("message").asText()).isEqualTo("Application not found: " + code);

        var again = http.delete("/api/applications/" + id, ANCHOR);
        assertThat(again.statusCode()).isEqualTo(404);
    }

    @Test
    void clientConfigsAreEnabledDisabledListedAndFetched() {
        String id = create(code("cfg"), "Configurable", "");
        String clientId = client("cfg-" + RUN);

        var none = http.get("/api/applications/" + id + "/clients", ANCHOR);
        assertThat(none.statusCode()).isEqualTo(200);
        assertThat(none.body()).isEqualTo("{\"items\":[]}\n");
        var missing = http.get("/api/applications/" + id + "/clients/" + clientId, ANCHOR);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(json(missing).get("error").asText()).isEqualTo("ClientConfig_NOT_FOUND");
        assertThat(json(missing).get("message").asText()).isEqualTo("ClientConfig not found: " + id + ":" + clientId);

        var enable = http.post("/api/applications/" + id + "/clients/" + clientId + "/enable", null, ANCHOR);
        assertThat(enable.statusCode()).as(enable.body()).isEqualTo(204);

        var one = json(http.get("/api/applications/" + id + "/clients/" + clientId, ANCHOR));
        assertThat(one.get("id").asText()).startsWith("apc_");
        assertThat(one.get("applicationId").asText()).isEqualTo(id);
        assertThat(one.get("clientId").asText()).isEqualTo(clientId);
        assertThat(one.get("enabled").asBoolean()).isTrue();
        assertThat(one.fieldNames()).toIterable().containsExactly("id", "applicationId", "clientId", "enabled", "createdAt", "updatedAt");

        var disable = http.post("/api/applications/" + id + "/clients/" + clientId + "/disable", null, ANCHOR);
        assertThat(disable.statusCode()).isEqualTo(204);
        var items = json(http.get("/api/applications/" + id + "/clients", ANCHOR)).get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("enabled").asBoolean()).isFalse();

        var unknownClient = http.post("/api/applications/" + id + "/clients/clt_doesnotexist1/enable", null, ANCHOR);
        assertThat(unknownClient.statusCode()).isEqualTo(404);
        assertThat(json(unknownClient).get("error").asText()).isEqualTo("Client_NOT_FOUND");
    }

    @Test
    void serviceAccountIsAttachedOnce() {
        String id = create(code("sa"), "With SA", "");
        String saId = EntityType.SERVICE_ACCOUNT.generate();
        String principalId = EntityType.SERVICE_ACCOUNT.generate();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        DB.insertInto(IAM_PRINCIPALS).set(IAM_PRINCIPALS.ID, principalId).set(IAM_PRINCIPALS.TYPE, "SERVICE")
                .set(IAM_PRINCIPALS.NAME, "sa").set(IAM_PRINCIPALS.ACTIVE, true).set(IAM_PRINCIPALS.SERVICE_ACCOUNT_ID, saId)
                .set(IAM_PRINCIPALS.CREATED_AT, now).set(IAM_PRINCIPALS.UPDATED_AT, now).execute();

        var body = "{\"serviceAccountId\":\"" + saId + "\",\"serviceAccountCode\":\"app:x\"}";
        var attach = http.post("/api/applications/" + id + "/service-account", body, ANCHOR);
        assertThat(attach.statusCode()).as(attach.body()).isEqualTo(204);
        assertThat(json(http.get("/api/applications/" + id, ANCHOR)).get("serviceAccountId").asText()).isEqualTo(principalId);

        var again = http.post("/api/applications/" + id + "/service-account", body, ANCHOR);
        assertThat(again.statusCode()).as("business rule → 409").isEqualTo(409);
        assertThat(json(again).get("error").asText()).isEqualTo("APPLICATION_HAS_SERVICE_ACCOUNT");
    }

    @Test
    void rolesListingReturnsRoleNamesForTheApplication() {
        String id = create(code("roles"), "With roles", "");
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        for (String name : new String[]{"zeta", "alpha"}) {
            DB.insertInto(IAM_ROLES).set(IAM_ROLES.ID, EntityType.ROLE.generate()).set(IAM_ROLES.APPLICATION_ID, id)
                    .set(IAM_ROLES.APPLICATION_CODE, code("roles")).set(IAM_ROLES.NAME, code("roles") + ":" + name)
                    .set(IAM_ROLES.DISPLAY_NAME, name).set(IAM_ROLES.SOURCE, "SDK").set(IAM_ROLES.CLIENT_MANAGED, false)
                    .set(IAM_ROLES.CREATED_AT, now).set(IAM_ROLES.UPDATED_AT, now).execute();
        }
        var r = http.get("/api/applications/by-id/" + id + "/roles", ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(json(r).get("roles")).extracting(JsonNode::asText)
                .containsExactly(code("roles") + ":alpha", code("roles") + ":zeta");

        var empty = http.get("/api/applications/by-id/app_doesnotexist1/roles", ANCHOR);
        assertThat(empty.statusCode()).isEqualTo(200);
        assertThat(empty.body()).isEqualTo("{\"roles\":[]}\n");
    }

    // ── Negative paths ─────────────────────────────────────────────────────

    @Test
    void missingPermissionOrPrincipalIs403Envelope() {
        var r = http.post("/api/applications", "{\"code\":\"" + code("perm") + "\",\"name\":\"X\"}", VIEWER);
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(json(r).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");
        assertThat(json(r).get("message").asText()).contains("platform:admin:application:create");

        var del = http.delete("/api/applications/app_whatever", WRITER);
        assertThat(del.statusCode()).as("update permission is not delete").isEqualTo(403);
        assertThat(json(del).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");

        var anon = http.get("/api/applications");
        assertThat(anon.statusCode()).isEqualTo(403);
        assertThat(json(anon).get("error").asText()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void anchorOnlyRoutesRefuseAClientScopedWriter() {
        String id = create(code("anchor"), "Anchor only", "");
        for (String path : new String[]{"/service-account", "/clients/clt_x/enable", "/clients/clt_x/disable"}) {
            var r = http.post("/api/applications/" + id + path, "{}", WRITER);
            assertThat(r.statusCode()).as(path).isEqualTo(403);
            assertThat(json(r).get("error").asText()).as(path).isEqualTo("ANCHOR_REQUIRED");
        }
        var put = http.put("/api/applications/" + id, "{\"name\":\"Writer may update\"}", WRITER);
        assertThat(put.statusCode()).as("any write permission passes the write gate").isEqualTo(204);
    }

    @Test
    void validationConflictAndMalformedJsonAreEnvelopes() {
        var bad = http.post("/api/applications", "{\"code\":\"1bad\",\"name\":\"X\"}", ANCHOR);
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(json(bad).get("error").asText()).isEqualTo("INVALID_CODE_FORMAT");
        assertThat(json(bad).get("message").asText())
                .isEqualTo("code must start with a lowercase letter and contain only lowercase alphanumerics, hyphens, and underscores");

        var noName = http.post("/api/applications", "{\"code\":\"" + code("noname") + "\"}", ANCHOR);
        assertThat(noName.statusCode()).isEqualTo(400);
        assertThat(json(noName).get("error").asText()).isEqualTo("NAME_REQUIRED");

        create(code("dup"), "X", "");
        var dup = http.post("/api/applications", "{\"code\":\"" + code("DUP").toUpperCase(Locale.ROOT) + "\",\"name\":\"X\"}", ANCHOR);
        assertThat(dup.statusCode()).as("normalised before the uniqueness check").isEqualTo(409);
        assertThat(json(dup).get("error").asText()).isEqualTo("CODE_EXISTS");

        var malformed = http.post("/api/applications", "{not json", ANCHOR);
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(json(malformed).get("error").asText()).isEqualTo("INVALID_JSON");
    }
}
