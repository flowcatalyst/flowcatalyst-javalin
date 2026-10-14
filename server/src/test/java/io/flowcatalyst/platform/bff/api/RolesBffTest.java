package io.flowcatalyst.platform.bff.api;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.role.PermissionRepository;
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
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.UUID;

import static io.flowcatalyst.db.generated.Tables.IAM_ROLE_PERMISSIONS;
import static org.assertj.core.api.Assertions.assertThat;
import io.flowcatalyst.platform.shared.auth.WriteGates;
import io.flowcatalyst.platform.shared.auth.WriteGates.Write;
import java.util.List;
import static io.flowcatalyst.platform.shared.auth.Permission.ROLE_CREATE;
import static io.flowcatalyst.platform.shared.auth.Permission.ROLE_DELETE;
import static io.flowcatalyst.platform.shared.auth.Permission.ROLE_UPDATE;

/// The `/bff/roles` surface end to end: the permission catalogue's seed ∪
/// table dedup, the anchor gates (stricter than `/api/roles`'s coarse
/// permission gates), and the `{id}` create response.
@SuppressWarnings("deprecation")
class RolesBffTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);

    private static final RolesBff.State state = new RolesBff.State(
            new RoleRepository(TestPg.dataSource()), new PermissionRepository(TestPg.dataSource()),
            new ApplicationRepository(TestPg.dataSource()), new UnitOfWork(TestPg.dataSource(), new PlatformSink(Json.MAPPER)));
    private static TestHttp http;

    private static final String[] ANCHOR = {Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(), Authenticator.TEST_SCOPE, "ANCHOR", Authenticator.TEST_PERMISSIONS, "platform:*:*:*"};
    private static final String[] WRITER = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_PERMISSIONS, "platform:iam:role:view,platform:iam:role:create,platform:iam:role:update,platform:iam:role:delete"};

    @BeforeAll
    static void start() {
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before("/bff/*", auth);
            RolesBff.register(routes, state);
        });
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

    private static String roleName(String tag) {
        return "rolebff" + RUN + "-" + tag;
    }

    // ── Role CRUD ────────────────────────────────────────────────────────

    @Test
    void createReturnsTheIdEnvelopeAndTheRoleIsThenReadable() {
        String name = roleName("create");
        var r = http.post("/bff/roles", "{\"applicationCode\":\"rbffapp" + RUN + "\",\"roleName\":\"" + name
                + "\",\"displayName\":\"Display " + name + "\",\"permissions\":[\"rbffapp" + RUN + ":iam:role:view\"],\"clientManaged\":false}", ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        assertThat(json(r).propertyNames()).containsExactly("id");
        String id = json(r).get("id").asText();
        assertThat(id).startsWith("rol_");

        var get = http.get("/bff/roles/rbffapp" + RUN + ":" + name, ANCHOR);
        assertThat(get.statusCode()).isEqualTo(200);
        var body = json(get);
        assertThat(body.has("description")).as("absent optional field, no description given").isFalse();
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("id", "name", "shortName", "displayName",
                "permissions", "applicationCode", "source", "clientManaged", "createdAt", "updatedAt");
        assertThat(body.get("id").asText()).isEqualTo(id);
        assertThat(body.get("shortName").asText()).isEqualTo(name);
        assertThat(body.get("applicationCode").asText()).isEqualTo("rbffapp" + RUN);
    }

    @Test
    void updateAndDeleteReturn204() {
        String name = roleName("upd");
        http.post("/bff/roles", "{\"applicationCode\":\"rbffapp" + RUN + "\",\"roleName\":\"" + name
                + "\",\"displayName\":\"D\",\"clientManaged\":false}", ANCHOR);
        String fullName = "rbffapp" + RUN + ":" + name;

        var update = http.put("/bff/roles/" + fullName, "{\"displayName\":\"Renamed\",\"description\":\"a desc\"}", ANCHOR);
        assertThat(update.statusCode()).isEqualTo(204);
        var afterUpdate = json(http.get("/bff/roles/" + fullName, ANCHOR));
        assertThat(afterUpdate.get("displayName").asText()).isEqualTo("Renamed");
        assertThat(afterUpdate.get("description").asText()).as("present when set").isEqualTo("a desc");

        assertThat(http.delete("/bff/roles/" + fullName, ANCHOR).statusCode()).isEqualTo(204);
        assertThat(http.get("/bff/roles/" + fullName, ANCHOR).statusCode()).isEqualTo(404);
    }

    @Test
    void listIsFilteredByApplicationAndSource() {
        String app = "rbfflist" + RUN;
        http.post("/bff/roles", "{\"applicationCode\":\"" + app + "\",\"roleName\":\"one\",\"displayName\":\"One\"}", ANCHOR);

        var list = json(http.get("/bff/roles?application=" + app, ANCHOR));
        assertThat(list.propertyNames()).containsExactlyInAnyOrder("items", "total");
        assertThat(list.get("total").asInt()).isEqualTo(1);
        assertThat(list.get("items").get(0).get("applicationCode").asText()).isEqualTo(app);

        var noneForOtherApp = json(http.get("/bff/roles?application=" + app + "-nope", ANCHOR));
        assertThat(noneForOtherApp.get("total").asInt()).isZero();

        var bySource = json(http.get("/bff/roles?source=DATABASE", ANCHOR));
        assertThat(bySource.get("items").findValuesAsString("applicationCode")).contains(app);
    }

    // ── Load-bearing: anchor gate, stricter than /api/roles' coarse permission ──

    @Test
    void writesAreAnchorOnlyEvenWithFullRolePermissions() {
        assertThat(http.post("/bff/roles", "{\"applicationCode\":\"x\",\"roleName\":\"y\",\"displayName\":\"Y\"}", WRITER).statusCode())
                .as("role:create alone is not enough; must be anchor").isEqualTo(403);

        String name = roleName("gate");
        http.post("/bff/roles", "{\"applicationCode\":\"rbffapp" + RUN + "\",\"roleName\":\"" + name + "\",\"displayName\":\"D\"}", ANCHOR);
        String fullName = "rbffapp" + RUN + ":" + name;
        assertThat(http.put("/bff/roles/" + fullName, "{\"displayName\":\"X\"}", WRITER).statusCode()).isEqualTo(403);
        assertThat(http.delete("/bff/roles/" + fullName, WRITER).statusCode()).isEqualTo(403);
        assertThat(http.post("/bff/roles/sync-platform", null, WRITER).statusCode()).isEqualTo(403);
        assertThat(http.post("/bff/roles/permissions",
                "{\"application\":\"x\",\"context\":\"y\",\"aggregate\":\"z\",\"action\":\"w\"}", WRITER).statusCode()).isEqualTo(403);
    }

    /// Security-fixes S1.2: the anchor tier is reach, never authority. An
    /// anchor holding every permission EXCEPT the role-write family (it has
    /// `role:view` and the whole user family) is refused every write with
    /// `PERMISSION_REQUIRED`, and the role it tried to change is unchanged;
    /// the same anchor with the specific codes (not the wildcard) succeeds.
    @Test
    void writesNeedTheRolePermissionEvenForAnAnchor() {
        String[] anchorWithoutRoleWrite = {Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
                Authenticator.TEST_SCOPE, "ANCHOR",
                Authenticator.TEST_PERMISSIONS, "platform:iam:role:view,platform:iam:user:create,platform:iam:user:update,"
                        + "platform:iam:user:delete,platform:iam:user:assign-roles,platform:admin:application:update"};
        String name = roleName("s12");
        String fullName = "rbffapp" + RUN + ":" + name;
        http.post("/bff/roles", "{\"applicationCode\":\"rbffapp" + RUN + "\",\"roleName\":\"" + name + "\",\"displayName\":\"Before\"}", ANCHOR);

        var create = http.post("/bff/roles", "{\"applicationCode\":\"rbffapp" + RUN + "\",\"roleName\":\"" + name
                + "-new\",\"displayName\":\"Y\"}", anchorWithoutRoleWrite);
        assertThat(create.statusCode()).as(create.body()).isEqualTo(403);
        assertThat(json(create).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");
        assertThat(http.get("/bff/roles/" + fullName + "-new", ANCHOR).statusCode()).as("nothing created").isEqualTo(404);

        var update = http.put("/bff/roles/" + fullName, "{\"displayName\":\"After\"}", anchorWithoutRoleWrite);
        assertThat(update.statusCode()).isEqualTo(403);
        assertThat(json(http.get("/bff/roles/" + fullName, ANCHOR)).get("displayName").asText()).isEqualTo("Before");
        assertThat(http.delete("/bff/roles/" + fullName, anchorWithoutRoleWrite).statusCode()).isEqualTo(403);
        assertThat(http.get("/bff/roles/" + fullName, ANCHOR).statusCode()).as("not deleted").isEqualTo(200);
        assertThat(http.post("/bff/roles/sync-platform", null, anchorWithoutRoleWrite).statusCode()).isEqualTo(403);
        assertThat(http.post("/bff/roles/permissions",
                "{\"application\":\"rbffapp" + RUN + "\",\"context\":\"s12\",\"aggregate\":\"z\",\"action\":\"w\"}",
                anchorWithoutRoleWrite).statusCode()).isEqualTo(403);
        assertThat(http.get("/bff/roles/permissions/rbffapp" + RUN + ":s12:z:w", ANCHOR).statusCode())
                .as("no catalogue entry created").isEqualTo(404);

        String[] anchorRoleWriter = {Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
                Authenticator.TEST_SCOPE, "ANCHOR",
                Authenticator.TEST_PERMISSIONS, "platform:iam:role:view,platform:iam:role:create,platform:iam:role:update,platform:iam:role:delete"};
        assertThat(http.put("/bff/roles/" + fullName, "{\"displayName\":\"After\"}", anchorRoleWriter).statusCode()).isEqualTo(204);
        // The permission ceiling bounds what the sync changes (Go F10): settle the catalogue as
        // the wildcard holder first, so the gate is what this asserts.
        assertThat(http.post("/bff/roles/sync-platform", null, ANCHOR).statusCode()).isEqualTo(200);
        assertThat(http.post("/bff/roles/sync-platform", null, anchorRoleWriter).statusCode()).isEqualTo(200);
        assertThat(http.post("/bff/roles/permissions",
                "{\"application\":\"rbffapp" + RUN + "\",\"context\":\"s12\",\"aggregate\":\"z\",\"action\":\"w\"}",
                anchorRoleWriter).statusCode()).isEqualTo(201);
        assertThat(http.delete("/bff/roles/" + fullName, anchorRoleWriter).statusCode()).isEqualTo(204);
    }

    /// Security-fixes S1.5 through the BFF, as ruled 2026-09-25 (backlog item 15): an
    /// application role may not be created or updated with another application's
    /// permission (here the super-admin wildcard) by anyone but a super-admin, 400
    /// `PERMISSION_OUTSIDE_APPLICATION` with nothing stored. A super-admin may.
    @Test
    void onlyASuperAdminPutsAnotherApplicationsPermissionOnARole() {
        String[] roleWriter = {Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
                Authenticator.TEST_SCOPE, "ANCHOR",
                Authenticator.TEST_PERMISSIONS, "platform:iam:role:view,platform:iam:role:create,platform:iam:role:update,"
                        + "platform:iam:role:delete,platform:iam:user:create"};
        String name = roleName("s15");
        String fullName = "rbffapp" + RUN + ":" + name;
        var create = http.post("/bff/roles", "{\"applicationCode\":\"rbffapp" + RUN + "\",\"roleName\":\"" + name
                + "\",\"displayName\":\"D\",\"permissions\":[\"platform:iam:user:create\"]}", roleWriter);
        assertThat(create.statusCode()).as(create.body()).isEqualTo(400);
        assertThat(json(create).get("error").asText()).isEqualTo("PERMISSION_OUTSIDE_APPLICATION");
        assertThat(http.get("/bff/roles/" + fullName, ANCHOR).statusCode()).isEqualTo(404);

        http.post("/bff/roles", "{\"applicationCode\":\"rbffapp" + RUN + "\",\"roleName\":\"" + name
                + "\",\"displayName\":\"D\",\"permissions\":[\"rbffapp" + RUN + ":a:b:c\"]}", ANCHOR);
        var update = http.put("/bff/roles/" + fullName, "{\"permissions\":[\"platform:iam:user:create\"]}", roleWriter);
        assertThat(update.statusCode()).as(update.body()).isEqualTo(400);
        assertThat(json(http.get("/bff/roles/" + fullName, ANCHOR)).get("permissions").valueStream()
                .map(JsonNode::asText).toList()).containsExactly("rbffapp" + RUN + ":a:b:c");

        var bySuperAdmin = http.put("/bff/roles/" + fullName, "{\"permissions\":[\"rbffapp" + RUN + ":a:b:c\","
                + "\"platform:iam:user:create\"]}", ANCHOR);
        assertThat(bySuperAdmin.statusCode()).as(bySuperAdmin.body()).isEqualTo(204);
        assertThat(json(http.get("/bff/roles/" + fullName, ANCHOR)).get("permissions").valueStream()
                .map(JsonNode::asText).toList()).contains("platform:iam:user:create");
    }

    @Test
    void syncPlatformWorksForAnchor() {
        var r = http.post("/bff/roles/sync-platform", null, ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(json(r).propertyNames()).containsExactlyInAnyOrder("created", "updated", "removed", "total");
        assertThat(json(r).get("total").asInt()).isGreaterThan(0);
    }

    /// The permission ceiling on the platform role sync (Go F10, `c56a68c`): a
    /// role writer holding neither the wildcard nor the permission the
    /// catalogue would restore to a role trimmed by hand is refused, 403
    /// `PERMISSION_ABOVE_CALLER` naming it, and nothing is written; the
    /// wildcard holder restores it.
    @Test
    void syncPlatformIsBoundedByTheCallersPermissions() {
        assertThat(http.post("/bff/roles/sync-platform", null, ANCHOR).statusCode()).isEqualTo(200);
        var db = DSL.using(TestPg.dataSource(), SQLDialect.POSTGRES);
        String roleId = state.roles().findByName("platform:messaging-admin").orElseThrow().id();
        String trimmed = "platform:messaging:connection:sync";
        db.deleteFrom(IAM_ROLE_PERMISSIONS)
                .where(IAM_ROLE_PERMISSIONS.ROLE_ID.eq(roleId).and(IAM_ROLE_PERMISSIONS.PERMISSION.eq(trimmed)))
                .execute();
        String[] roleWriter = {Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
                Authenticator.TEST_SCOPE, "ANCHOR",
                Authenticator.TEST_PERMISSIONS, "platform:iam:role:view,platform:iam:role:create,platform:iam:role:update,platform:iam:role:delete"};

        var refused = http.post("/bff/roles/sync-platform", null, roleWriter);
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(json(refused).get("error").asText()).isEqualTo("PERMISSION_ABOVE_CALLER");
        assertThat(json(refused).get("message").asText())
                .isEqualTo("You may only add or remove permissions you hold yourself: " + trimmed);
        assertThat(state.roles().findByName("platform:messaging-admin").orElseThrow().permissions())
                .as("nothing written").doesNotContain(trimmed);

        assertThat(http.post("/bff/roles/sync-platform", null, ANCHOR).statusCode()).isEqualTo(200);
        assertThat(state.roles().findByName("platform:messaging-admin").orElseThrow().permissions()).contains(trimmed);
    }

    // ── Load-bearing: permission catalogue = seed ∪ table, deduplicated by code ──

    @Test
    void catalogueContainsBothSeededAndCustomTableEntriesDeduplicated() {
        // A code already in the seeded catalogue (shared.auth.Permission.ROLE_VIEW).
        String seededCode = "platform:iam:role:view";
        var seeded = json(http.get("/bff/roles/permissions/" + seededCode, ANCHOR));
        assertThat(seeded.get("permission").asText()).isEqualTo(seededCode);

        // Re-declaring the SAME code through the writable catalogue must not duplicate it.
        var upsert = http.post("/bff/roles/permissions",
                "{\"application\":\"platform\",\"context\":\"iam\",\"aggregate\":\"role\",\"action\":\"view\",\"description\":\"custom\"}", ANCHOR);
        assertThat(upsert.statusCode()).as(upsert.body()).isEqualTo(201);

        var all = json(http.get("/bff/roles/permissions", ANCHOR)).get("items");
        long matches = 0;
        for (JsonNode n : all) if (n.get("permission").asText().equals(seededCode)) matches++;
        assertThat(matches).as("seed ∪ table deduplicated by code").isEqualTo(1);

        // A code that exists ONLY in the table (not in the seeded enum) still surfaces.
        String customCode = "customapp" + RUN + ":ctx:agg:act";
        var create = http.post("/bff/roles/permissions",
                "{\"application\":\"customapp" + RUN + "\",\"context\":\"ctx\",\"aggregate\":\"agg\",\"action\":\"act\",\"description\":\"custom perm\"}", ANCHOR);
        assertThat(create.statusCode()).as(create.body()).isEqualTo(201);
        assertThat(json(create).get("permission").asText()).isEqualTo(customCode);

        var scoped = json(http.get("/bff/roles/permissions?application=customapp" + RUN, ANCHOR)).get("items");
        assertThat(scoped).hasSize(1);
        assertThat(scoped.get(0).get("permission").asText()).isEqualTo(customCode);
        assertThat(scoped.get(0).get("description").asText()).isEqualTo("custom perm");

        // The unfiltered catalogue does not narrow to just this application's one entry.
        var unfiltered = json(http.get("/bff/roles/permissions", ANCHOR)).get("items");
        assertThat(unfiltered.size()).isGreaterThan(1);
    }

    @Test
    void getUnknownPermissionIs404() {
        assertThat(http.get("/bff/roles/permissions/does:not:exist:code", ANCHOR).statusCode()).isEqualTo(404);
    }

    // ── Filters/applications ─────────────────────────────────────────────

    /// Go lists every active application here, not the roles' codes (bff.md,
    /// corrected after the parity harness): a role for a code with no
    /// application row does not appear; the seeded platform application does.
    @Test
    void filterApplicationsListsEveryActiveApplication() {
        String app = "rbfffa" + RUN;
        http.post("/bff/roles", "{\"applicationCode\":\"" + app + "\",\"roleName\":\"z\",\"displayName\":\"Z\"}", ANCHOR);
        String active = "rbffact" + RUN;
        String inactive = "rbffina" + RUN;
        state.uow().inTransaction(tx -> {
            state.applications().persist(io.flowcatalyst.platform.application.Application.create(
                    io.flowcatalyst.platform.application.ApplicationType.APPLICATION, active, "Active " + RUN), tx.dbTx());
            state.applications().persist(io.flowcatalyst.platform.application.Application.create(
                    io.flowcatalyst.platform.application.ApplicationType.APPLICATION, inactive, "Inactive " + RUN).deactivate(), tx.dbTx());
            return null;
        });
        var body = json(http.get("/bff/roles/filters/applications", ANCHOR));
        assertThat(body.propertyNames()).containsExactly("options");
        var codes = body.get("options").findValuesAsString("code");
        assertThat(codes).as("a role's code without an application row is not an application").doesNotContain(app);
        assertThat(codes).as("an active application row is listed").contains(active);
        assertThat(codes).as("an inactive one is not").doesNotContain(inactive);
    }

    // ── Per-operation permission ───────────────────────────────────────────

    /// Each write checks the role permission for its operation, never any of
    /// create/update/delete (Go `5b75de5`): create and defining a permission
    /// need create, update needs update, delete needs delete.
    @Test
    void eachWriteNeedsTheOperationsPermission() {
        String r = "/bff/roles/rbffapp" + RUN + ":gate-missing";
        WriteGates.assertEachWriteNeedsItsPermission(http, List.of(
                        Write.of("create", "POST", "/bff/roles", "{\"applicationCode\":\"rbffapp" + RUN + "\",\"roleName\":\""
                                + roleName("op") + "\",\"displayName\":\"D\"}", ROLE_CREATE),
                        Write.of("update", "PUT", r, "{\"displayName\":\"X\"}", ROLE_UPDATE),
                        Write.of("define permission", "POST", "/bff/roles/permissions",
                                "{\"application\":\"rbffapp" + RUN + "\",\"context\":\"op\",\"aggregate\":\"z\",\"action\":\"w\"}", ROLE_CREATE),
                        Write.of("delete", "DELETE", r, null, ROLE_DELETE)),
                ROLE_CREATE, ROLE_UPDATE, ROLE_DELETE);
    }

    /// The platform role sync creates, updates and removes roles, so it needs
    /// all three; any one alone is refused.
    @Test
    void syncPlatformNeedsCreateUpdateAndDelete() {
        for (var held : List.of(ROLE_CREATE, ROLE_UPDATE, ROLE_DELETE)) {
            var r = http.post("/bff/roles/sync-platform", null, WriteGates.holding(held.code()));
            assertThat(WriteGates.refusedForPermission(r)).as(held.code() + ": " + r.body()).isTrue();
        }
    }

    /// The permission is checked before anchor reach (Go's order): a
    /// non-anchor without it hears `PERMISSION_REQUIRED`, one with it
    /// `ANCHOR_REQUIRED`.
    @Test
    void thePermissionIsCheckedBeforeAnchorReach() {
        String[] clientViewer = {Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
                Authenticator.TEST_SCOPE, "CLIENT", Authenticator.TEST_PERMISSIONS, "platform:iam:role:view"};
        String body = "{\"applicationCode\":\"rbffapp" + RUN + "\",\"roleName\":\"" + roleName("order") + "\",\"displayName\":\"D\"}";
        assertThat(json(http.post("/bff/roles", body, clientViewer)).get("error").asString()).isEqualTo("PERMISSION_REQUIRED");
        assertThat(json(http.post("/bff/roles", body, WRITER)).get("error").asString()).isEqualTo("ANCHOR_REQUIRED");
    }
}
