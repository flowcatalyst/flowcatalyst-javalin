package io.flowcatalyst.platform.role.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.flowcatalyst.platform.role.Permission;
import io.flowcatalyst.platform.role.PermissionRepository;
import io.flowcatalyst.platform.role.RoleRepository;
import io.flowcatalyst.platform.role.RoleSource;
import io.flowcatalyst.platform.role.operations.SyncRoleInput;
import io.flowcatalyst.platform.role.operations.SyncRoles;
import io.flowcatalyst.platform.role.operations.SyncRolesCommand;
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
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static io.flowcatalyst.db.generated.Tables.IAM_ROLES;
import static org.assertj.core.api.Assertions.assertThat;

/// The sixteen `/api/roles*` routes end to end through Javalin: the
/// authenticator's test headers, the coarse permission gates, route
/// precedence between the literal and `{id}` paths, the lockfile status
/// codes and body shapes, and the error envelope.
class RoleApiTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final String APP = "roleapi" + RUN;

    private static final String ANCHOR_PRINCIPAL = EntityType.PRINCIPAL.generate();
    private static final String[] ANCHOR = {
            Authenticator.TEST_PRINCIPAL, ANCHOR_PRINCIPAL,
            Authenticator.TEST_SCOPE, "ANCHOR"};
    private static final String[] VIEWER = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, "cli_roleapi_" + RUN,
            Authenticator.TEST_PERMISSIONS, "platform:iam:role:view"};
    private static final String[] WRITER = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, "cli_roleapi_" + RUN,
            Authenticator.TEST_PERMISSIONS, "platform:iam:role:view,platform:iam:role:update"};

    private static final DSLContext DB = DSL.using(TestPg.dataSource(), SQLDialect.POSTGRES);
    private static final RoleApi.State state = new RoleApi.State(new RoleRepository(TestPg.dataSource()),
            new PermissionRepository(TestPg.dataSource()), new UnitOfWork(TestPg.dataSource(), new PlatformSink(Json.MAPPER)));
    private static TestHttp http;

    @BeforeAll
    static void start() {
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        http = new TestHttp(cfg -> {
            HttpError.install(cfg.routes);
            cfg.routes.before("/api/*", auth);
            RoleApi.register(cfg.routes, state);
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

    private static String create(String roleName, String displayName, String extraJson) {
        var r = http.post("/api/roles",
                "{\"applicationCode\":\"" + APP + "\",\"roleName\":\"" + roleName + "\",\"displayName\":\"" + displayName + "\"" + extraJson + "}", ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        var id = json(r).get("id").asText();
        assertThat(id).startsWith("rol_");
        return id;
    }

    /// A `CODE` row straight into `iam_roles` — the only way to reach the immutability guard.
    private static String seedCodeRole(String roleName) {
        String id = EntityType.ROLE.generate();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        DB.insertInto(IAM_ROLES)
                .set(IAM_ROLES.ID, id)
                .set(IAM_ROLES.NAME, APP + ":" + roleName)
                .set(IAM_ROLES.DISPLAY_NAME, "Code " + roleName)
                .set(IAM_ROLES.APPLICATION_CODE, APP)
                .set(IAM_ROLES.SOURCE, RoleSource.CODE.name())
                .set(IAM_ROLES.CLIENT_MANAGED, false)
                .set(IAM_ROLES.CREATED_AT, now)
                .set(IAM_ROLES.UPDATED_AT, now)
                .execute();
        return id;
    }

    // ── Happy paths ────────────────────────────────────────────────────────

    @Test
    void createThenReadByIdByNameByCodeAndInLists() {
        String id = create("editor", "Editor", ",\"description\":\"desc\",\"permissions\":[\"" + APP + ":doc:b:*\",\"" + APP + ":doc:a:*\"],\"clientManaged\":true");

        // POST body is exactly the CreatedResponse envelope.
        var created = http.post("/api/roles", "{\"applicationCode\":\"" + APP + "\",\"roleName\":\"viewer\",\"displayName\":\"Viewer\"}", ANCHOR);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).matches("\\{\"id\":\"rol_[0-9A-Z]{13}\"}\n");
        assertThat(created.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");

        // GET by id: the RoleResponse shape, fields in lockfile order.
        var get = http.get("/api/roles/" + id, ANCHOR);
        assertThat(get.statusCode()).isEqualTo(200);
        var role = json(get);
        assertThat(role.get("id").asText()).isEqualTo(id);
        assertThat(role.get("name").asText()).isEqualTo(APP + ":editor");
        assertThat(role.get("displayName").asText()).isEqualTo("Editor");
        assertThat(role.get("description").asText()).isEqualTo("desc");
        assertThat(role.get("applicationCode").asText()).isEqualTo(APP);
        assertThat(role.get("permissions")).extracting(JsonNode::asText).containsExactly(APP + ":doc:a:*", APP + ":doc:b:*");
        assertThat(role.get("source").asText()).isEqualTo("DATABASE");
        assertThat(role.get("clientManaged").asBoolean()).isTrue();
        assertThat(role.has("applicationId")).as("null applicationId omitted").isFalse();
        assertThat(role.get("createdAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z");
        assertThat(role.get("updatedAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z");
        assertThat(role.fieldNames()).toIterable().containsExactly("id", "name", "displayName", "description",
                "applicationCode", "permissions", "source", "clientManaged", "createdAt", "updatedAt");

        // The {id} routes also accept the name (SDK clients), and by-code is name-only.
        assertThat(json(http.get("/api/roles/" + APP + ":editor", ANCHOR)).get("id").asText()).isEqualTo(id);
        assertThat(json(http.get("/api/roles/by-code/" + APP + ":editor", ANCHOR)).get("id").asText()).isEqualTo(id);
        assertThat(http.get("/api/roles/by-code/" + id, ANCHOR).statusCode()).isEqualTo(404);

        // List: {"roles": [...], "total": n}, ordered by name.
        var list = http.get("/api/roles", ANCHOR);
        assertThat(list.statusCode()).isEqualTo(200);
        var body = json(list);
        assertThat(body.fieldNames()).toIterable().containsExactly("roles", "total");
        assertThat(body.get("total").asInt()).isEqualTo(body.get("roles").size());
        var ours = body.get("roles").findValues("name").stream().map(JsonNode::asText).filter(n -> n.startsWith(APP + ":")).toList();
        assertThat(ours).containsSubsequence(APP + ":editor", APP + ":viewer");

        // A viewer (CLIENT scope, view permission) can read roles.
        assertThat(http.get("/api/roles/" + id, VIEWER).statusCode()).isEqualTo(200);

        // by-source is a bare array (lenient: an unknown source lists DATABASE roles); filters list our code.
        var bySource = http.get("/api/roles/by-source/DATABASE", ANCHOR);
        assertThat(bySource.statusCode()).isEqualTo(200);
        assertThat(json(bySource).isArray()).isTrue();
        assertThat(json(bySource).findValues("id")).extracting(JsonNode::asText).contains(id);
        assertThat(json(http.get("/api/roles/by-source/bogus", ANCHOR)).findValues("id")).extracting(JsonNode::asText).contains(id);
        assertThat(json(http.get("/api/roles/by-source/CODE", ANCHOR)).findValues("id")).extracting(JsonNode::asText).doesNotContain(id);
        var filters = http.get("/api/roles/filters/applications", ANCHOR);
        assertThat(filters.statusCode()).isEqualTo(200);
        assertThat(json(filters).get("applicationCodes")).extracting(JsonNode::asText).contains(APP);
    }

    @Test
    void byApplicationListsTheSdkSyncedRolesOfOneApplication() {
        String appId = EntityType.APPLICATION.generate();
        var anchor = new AuthContext(ANCHOR_PRINCIPAL, Scope.ANCHOR, null, List.of("*"), List.of(), List.of(), true, List.of());
        // The SDK sync has no route on this surface (sdksync), so run the operation directly.
        Auth.runAs(anchor, () -> SyncRoles.of(state.roles()).run(state.uow(), new SyncRolesCommand(APP, appId,
                List.of(new SyncRoleInput("Synced", "Synced Role", null, List.of(APP + ":s:y:n"), false)), false),
                ExecutionContext.of(ANCHOR_PRINCIPAL)));

        var r = http.get("/api/roles/by-application/" + appId, ANCHOR);
        assertThat(r.statusCode()).isEqualTo(200);
        var arr = json(r);
        assertThat(arr.isArray()).isTrue();
        assertThat(arr).hasSize(1);
        assertThat(arr.get(0).get("name").asText()).isEqualTo(APP + ":synced");
        assertThat(arr.get(0).get("applicationId").asText()).isEqualTo(appId);
        assertThat(arr.get(0).get("source").asText()).isEqualTo("SDK");
        assertThat(json(http.get("/api/roles/by-application/app_none" + RUN, ANCHOR)).isArray()).isTrue();
        assertThat(http.get("/api/roles/by-application/app_none" + RUN, ANCHOR).body()).isEqualTo("[]\n");
    }

    @Test
    void updateReturns204PersistsAndRefusesCodeRoles() {
        String id = create("upd", "Before", "");
        var put = http.put("/api/roles/" + id, "{\"displayName\":\"After\",\"permissions\":[\"" + APP + ":u:p:d\"]}", ANCHOR);
        assertThat(put.statusCode()).as(put.body()).isEqualTo(204);
        assertThat(put.body()).isEmpty();

        var role = json(http.get("/api/roles/" + id, ANCHOR));
        assertThat(role.get("displayName").asText()).isEqualTo("After");
        assertThat(role.get("permissions")).extracting(JsonNode::asText).containsExactly(APP + ":u:p:d");

        // By name on the same route; a blank displayName is a validation error.
        assertThat(http.put("/api/roles/" + APP + ":upd", "{\"description\":\"by name\"}", ANCHOR).statusCode()).isEqualTo(204);
        assertThat(json(http.get("/api/roles/" + id, ANCHOR)).get("description").asText()).isEqualTo("by name");
        var bad = http.put("/api/roles/" + id, "{\"displayName\":\" \"}", ANCHOR);
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(json(bad).get("error").asText()).isEqualTo("DISPLAY_NAME_REQUIRED");

        String codeId = seedCodeRole("code-upd");
        var immutable = http.put("/api/roles/" + codeId, "{\"displayName\":\"Hacked\"}", ANCHOR);
        assertThat(immutable.statusCode()).isEqualTo(409);
        assertThat(json(immutable).get("error").asText()).isEqualTo("CODE_ROLE_IMMUTABLE");
        var del = http.delete("/api/roles/" + codeId, ANCHOR);
        assertThat(del.statusCode()).isEqualTo(409);
        assertThat(json(del).get("error").asText()).isEqualTo("CODE_ROLE_IMMUTABLE");
    }

    @Test
    void deleteReturns204ThenGetIs404Envelope() {
        String id = create("del", "Doomed", "");
        assertThat(http.delete("/api/roles/" + id, ANCHOR).statusCode()).isEqualTo(204);

        var get = http.get("/api/roles/" + id, ANCHOR);
        assertThat(get.statusCode()).isEqualTo(404);
        assertThat(get.body()).isEqualTo("{\"error\":\"Role_NOT_FOUND\",\"message\":\"Role not found: " + id + "\"}\n");

        var again = http.delete("/api/roles/" + id, ANCHOR);
        assertThat(again.statusCode()).isEqualTo(404);
        assertThat(json(again).get("error").asText()).isEqualTo("Role_NOT_FOUND");
        assertThat(http.get("/api/roles/by-code/" + APP + ":del", ANCHOR).statusCode()).isEqualTo(404);
    }

    @Test
    void grantAndRevokeByPathAndBodyReturnTheUpdatedRole() {
        create("perm", "Perm", ",\"permissions\":[\"" + APP + ":base:read:*\"]");
        String name = APP + ":perm";

        var byPath = http.post("/api/roles/" + name + "/permissions/" + APP + ":job:run:*", null, ANCHOR);
        assertThat(byPath.statusCode()).as(byPath.body()).isEqualTo(200);
        assertThat(json(byPath).get("name").asText()).isEqualTo(name);
        assertThat(json(byPath).get("permissions")).extracting(JsonNode::asText)
                .containsExactly(APP + ":base:read:*", APP + ":job:run:*");

        var byBody = http.post("/api/roles/" + name + "/permissions", "{\"permission\":\"" + APP + ":job:list:*\"}", ANCHOR);
        assertThat(byBody.statusCode()).as(byBody.body()).isEqualTo(200);
        assertThat(json(byBody).get("permissions")).extracting(JsonNode::asText)
                .containsExactly(APP + ":base:read:*", APP + ":job:list:*", APP + ":job:run:*");

        var listed = http.get("/api/roles/" + name + "/permissions", ANCHOR);
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(listed.body()).isEqualTo("{\"permissions\":[\"" + APP + ":base:read:*\",\"" + APP + ":job:list:*\",\"" + APP + ":job:run:*\"]}\n");

        var revoked = http.delete("/api/roles/" + name + "/permissions/" + APP + ":job:run:*", ANCHOR);
        assertThat(revoked.statusCode()).isEqualTo(200);
        assertThat(json(revoked).get("permissions")).extracting(JsonNode::asText)
                .containsExactly(APP + ":base:read:*", APP + ":job:list:*");

        // Name-only lookups: an unknown role is a 404 on every permission route.
        assertThat(http.get("/api/roles/" + APP + ":ghost/permissions", ANCHOR).statusCode()).isEqualTo(404);
        var unknown = http.post("/api/roles/" + APP + ":ghost/permissions/a:b:c:d", null, ANCHOR);
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(json(unknown).get("error").asText()).isEqualTo("Role_NOT_FOUND");
        var missing = http.post("/api/roles/" + name + "/permissions", "{}", ANCHOR);
        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(json(missing).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");
    }

    @Test
    void permissionCatalogueListGetAndDelete() {
        String code = APP + ":catalogue:entry:view";
        state.uow().inTransaction(tx -> {
            state.permissions().upsert(Permission.define(code, "Catalogue test"), tx.dbTx());
            return null;
        });

        var list = http.get("/api/roles/permissions", ANCHOR);
        assertThat(list.statusCode()).as("the literal route wins over /api/roles/{id}").isEqualTo(200);
        var body = json(list);
        assertThat(body.fieldNames()).toIterable().containsExactly("permissions", "total");
        assertThat(body.get("total").asInt()).isEqualTo(body.get("permissions").size());
        var entry = body.get("permissions").findValues("permission").stream().map(JsonNode::asText).filter(code::equals).findFirst();
        assertThat(entry).isPresent();

        var get = http.get("/api/roles/permissions/" + code, ANCHOR);
        assertThat(get.statusCode()).as("the literal route wins over /api/roles/{roleName}/permissions").isEqualTo(200);
        assertThat(get.body()).isEqualTo("{\"permission\":\"" + code + "\",\"name\":\"" + code
                + "\",\"description\":\"Catalogue test\",\"category\":\"" + APP + ":catalogue:entry\"}\n");

        assertThat(http.delete("/api/roles/permissions/" + code, VIEWER).statusCode()).isEqualTo(403);
        assertThat(http.delete("/api/roles/permissions/" + code, ANCHOR).statusCode()).isEqualTo(204);
        var gone = http.get("/api/roles/permissions/" + code, ANCHOR);
        assertThat(gone.statusCode()).isEqualTo(404);
        assertThat(json(gone).get("error").asText()).isEqualTo("Permission_NOT_FOUND");
        assertThat(http.delete("/api/roles/permissions/" + code, ANCHOR).statusCode()).as("idempotent").isEqualTo(204);
    }

    // ── Negative paths ─────────────────────────────────────────────────────

    @Test
    void missingPermissionOrPrincipalIs403Envelope() {
        var r = http.post("/api/roles", "{\"applicationCode\":\"" + APP + "\",\"roleName\":\"denied\",\"displayName\":\"X\"}", VIEWER);
        assertThat(r.statusCode()).isEqualTo(403);
        var env = json(r);
        assertThat(env.get("error").asText()).isEqualTo("PERMISSION_REQUIRED");
        assertThat(env.get("message").asText()).contains("platform:iam:role:create");

        // Any write permission passes the create gate (spec §3, open question 8)…
        var writer = http.post("/api/roles", "{\"applicationCode\":\"" + APP + "\",\"roleName\":\"writer\",\"displayName\":\"X\"}", WRITER);
        assertThat(writer.statusCode()).as(writer.body()).isEqualTo(201);
        // …but delete needs the delete permission specifically.
        var del = http.delete("/api/roles/" + json(writer).get("id").asText(), WRITER);
        assertThat(del.statusCode()).isEqualTo(403);
        assertThat(json(del).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");

        var anon = http.get("/api/roles");
        assertThat(anon.statusCode()).isEqualTo(403);
        assertThat(json(anon).get("error").asText()).isEqualTo("UNAUTHENTICATED");
        var anonCatalogue = http.get("/api/roles/permissions");
        assertThat(anonCatalogue.statusCode()).isEqualTo(403);
    }

    @Test
    void validationConflictAndMalformedJsonAreEnvelopes() {
        var bad = http.post("/api/roles", "{\"roleName\":\"x\",\"displayName\":\"X\"}", ANCHOR);
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(json(bad).get("error").asText()).isEqualTo("APPLICATION_REQUIRED");
        assertThat(json(bad).get("message").asText()).isEqualTo("applicationCode is required");

        create("twice", "X", "");
        var dup = http.post("/api/roles", "{\"applicationCode\":\"" + APP + "\",\"roleName\":\"twice\",\"displayName\":\"X\"}", ANCHOR);
        assertThat(dup.statusCode()).isEqualTo(409);
        assertThat(json(dup).get("error").asText()).isEqualTo("ROLE_EXISTS");

        var malformed = http.post("/api/roles", "{not json", ANCHOR);
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(json(malformed).get("error").asText()).isEqualTo("INVALID_JSON");
    }
}
