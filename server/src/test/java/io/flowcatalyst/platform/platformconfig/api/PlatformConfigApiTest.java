package io.flowcatalyst.platform.platformconfig.api;

import tools.jackson.databind.JsonNode;
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

/// The four platform-config routes end to end through Javalin
/// (`docs/spec/config-permissions.md` §A): permission gates (`CONFIG_VIEW` /
/// `CONFIG_MANAGE`) replacing anchor-or-grant, secret masking, the lockfile
/// status codes and body shapes, the error envelope, and that the three
/// access-grant routes withdrawn by this unit answer 404.
@SuppressWarnings("deprecation")
class PlatformConfigApiTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final String APP = "pcapi" + RUN;
    private static final String CLIENT = "cli_pcapi_" + RUN;

    private static final String CONFIG_VIEW = "platform:admin:config:view";
    private static final String CONFIG_MANAGE = "platform:admin:config:manage";

    /// Holds every permission (wildcard) — the general-purpose setup/happy-path
    /// principal; scope no longer matters to the gate (permissions always come
    /// from roles — spec `permissions-from-roles.md`).
    private static final String[] ADMIN = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:*:*:*"};
    /// Holds `CONFIG_VIEW` only — reads succeed, writes refused, secrets masked.
    private static final String[] VIEWER = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, CLIENT,
            Authenticator.TEST_PERMISSIONS, CONFIG_VIEW};
    /// Holds both `CONFIG_VIEW` and `CONFIG_MANAGE` — the seeded `platform:admin`
    /// shape (spec §A.4): every route succeeds, secrets unmasked.
    private static final String[] MANAGER = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, CLIENT,
            Authenticator.TEST_PERMISSIONS, CONFIG_VIEW + "," + CONFIG_MANAGE};
    /// Holds no permission at all.
    private static final String[] NOBODY = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, CLIENT};
    /// An ANCHOR-scoped principal holding every permission EXCEPT the config
    /// family (spec §A.2: "Anchor no longer passes by being anchor"). Pins the
    /// withdrawal of the old `ac.isAnchor() ||` bypass.
    private static final String[] ANCHOR_NO_CONFIG_PERMS = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:messaging:event-type:view"};

    private static final PlatformConfigApi.State state = new PlatformConfigApi.State(
            new PlatformConfigRepository(TestPg.dataSource()),
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
        return property(APP, section, property);
    }

    private static String property(String app, String section, String property) {
        return "/api/config/" + app + "/" + section + "/" + property;
    }

    // ── Happy paths ────────────────────────────────────────────────────────

    @Test
    void setThenGetThenList() {
        var set = http.put(property("smtp", "host"), "{\"value\":\"mail.example.com\",\"description\":\"relay\"}", MANAGER);
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

        var get = http.get(property("smtp", "host"), ADMIN);
        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(json(get).get("id").asText()).isEqualTo(c.get("id").asText());

        // A second set on the same coordinate keeps the id.
        var again = http.put(property("smtp", "host"), "{\"value\":\"mail2.example.com\"}", MANAGER);
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(json(again).get("id").asText()).isEqualTo(c.get("id").asText());
        assertThat(json(again).get("value").asText()).isEqualTo("mail2.example.com");
        assertThat(json(again).has("description")).as("absent description clears").isFalse();

        var list = http.get("/api/platform-config/" + APP, ADMIN);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(json(list).get("items")).anySatisfy(item -> assertThat(item.get("id").asText()).isEqualTo(c.get("id").asText()));
    }

    @Test
    void clientIdQueryParameterAddressesTheClientScopedValue() {
        var global = http.put(property("ui", "colour"), "{\"value\":\"blue\"}", MANAGER);
        assertThat(global.statusCode()).isEqualTo(200);
        var client = http.put(property("ui", "colour") + "?clientId=" + CLIENT, "{\"value\":\"red\"}", MANAGER);
        assertThat(client.statusCode()).as(client.body()).isEqualTo(200);
        assertThat(json(client).get("scope").asText()).isEqualTo("CLIENT");
        assertThat(json(client).get("clientId").asText()).isEqualTo(CLIENT);
        assertThat(json(client).get("id").asText()).isNotEqualTo(json(global).get("id").asText());

        assertThat(json(http.get(property("ui", "colour"), ADMIN)).get("value").asText()).isEqualTo("blue");
        assertThat(json(http.get(property("ui", "colour") + "?clientId=" + CLIENT, ADMIN)).get("value").asText()).isEqualTo("red");
        // An empty query value is "absent".
        assertThat(json(http.get(property("ui", "colour") + "?clientId=", ADMIN)).get("value").asText()).isEqualTo("blue");

        // The query parameter wins over the body's clientId; a body clientId is used when the query is absent.
        var bodyClient = http.put(property("ui", "font"), "{\"value\":\"serif\",\"clientId\":\"" + CLIENT + "\"}", MANAGER);
        assertThat(bodyClient.statusCode()).isEqualTo(200);
        assertThat(json(http.get(property("ui", "font") + "?clientId=" + CLIENT, ADMIN)).get("value").asText()).isEqualTo("serif");
        assertThat(http.get(property("ui", "font"), ADMIN).statusCode()).as("no global value was written").isEqualTo(404);
    }

    @Test
    void deleteIsIdempotent() {
        var set = http.put(property("tmp", "gone"), "{\"value\":\"x\"}", MANAGER);
        assertThat(set.statusCode()).isEqualTo(200);

        var del = http.delete(property("tmp", "gone"), MANAGER);
        assertThat(del.statusCode()).isEqualTo(204);
        assertThat(del.body()).isEmpty();
        var get = http.get(property("tmp", "gone"), ADMIN);
        assertThat(get.statusCode()).isEqualTo(404);
        assertThat(json(get).get("error").asText()).isEqualTo("Config_NOT_FOUND");
        assertThat(json(get).get("message").asText()).isEqualTo("Config not found: " + APP + "/tmp/gone");

        assertThat(http.delete(property("tmp", "gone"), MANAGER).statusCode()).as("absent coordinate is still 204").isEqualTo(204);
    }

    // ── Permission gates (spec §A.5) ──────────────────────────────────────

    /// Spec test 1: a non-anchor role with `view` reads any app's property —
    /// no per-application restriction (spec §A.2, unlike the withdrawn grant
    /// table) — without it, 403.
    @Test
    void nonAnchorWithViewReadsAnyApplicationsPropertyWithoutItIsRefused() {
        String otherApp = "pcapi-other" + RUN;
        assertThat(http.put(property(otherApp, "smtp", "host"), "{\"value\":\"x\"}", MANAGER).statusCode()).isEqualTo(200);
        http.put(property("smtp", "reader-check"), "{\"value\":\"y\"}", MANAGER);

        var ownApp = http.get(property("smtp", "reader-check"), VIEWER);
        assertThat(ownApp.statusCode()).as(ownApp.body()).isEqualTo(200);
        var otherAppGet = http.get(property(otherApp, "smtp", "host"), VIEWER);
        assertThat(otherAppGet.statusCode()).as("view holds no per-application scope: " + otherAppGet.body()).isEqualTo(200);
        var otherAppList = http.get("/api/platform-config/" + otherApp, VIEWER);
        assertThat(otherAppList.statusCode()).isEqualTo(200);

        var denied = http.get(property("smtp", "reader-check"), NOBODY);
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(json(denied).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");
        var deniedList = http.get("/api/platform-config/" + APP, NOBODY);
        assertThat(deniedList.statusCode()).isEqualTo(403);
    }

    /// Spec test 2: an anchor principal with no config permission code is
    /// refused on GET and PUT — the old `ac.isAnchor() ||` bypass is gone.
    /// Mutant: restoring that bypass makes every assertion here pass on a
    /// 200/200/200 instead, so this is the test that must fail for it.
    @Test
    void anchorWithoutConfigPermissionIsRefusedOnGetAndPut() {
        http.put(property("smtp", "anchor-check"), "{\"value\":\"z\"}", MANAGER);

        var list = http.get("/api/platform-config/" + APP, ANCHOR_NO_CONFIG_PERMS);
        assertThat(list.statusCode()).as("mutant: ac.isAnchor() bypass restored").isEqualTo(403);
        assertThat(json(list).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");

        var get = http.get(property("smtp", "anchor-check"), ANCHOR_NO_CONFIG_PERMS);
        assertThat(get.statusCode()).as("mutant: ac.isAnchor() bypass restored").isEqualTo(403);
        assertThat(json(get).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");

        var put = http.put(property("smtp", "anchor-check"), "{\"value\":\"zz\"}", ANCHOR_NO_CONFIG_PERMS);
        assertThat(put.statusCode()).as("mutant: ac.isAnchor() bypass restored").isEqualTo(403);
        assertThat(json(put).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");
    }

    /// Spec test 3: `view` without `manage` — PUT and DELETE refused, and a
    /// `SECRET` value is masked on read; a `manage` holder sees it unmasked
    /// (the PUT response itself is always the unmasked re-read — spec §4 open
    /// question 5, already pinned by `setThenGetThenList`).
    @Test
    void viewWithoutManageCannotWriteOrDeleteAndSecretIsMaskedButManageUnmasks() {
        var set = http.put(property("smtp", "password"), "{\"value\":\"hunter2\",\"valueType\":\"SECRET\"}", MANAGER);
        assertThat(set.statusCode()).as(set.body()).isEqualTo(200);
        assertThat(json(set).get("value").asText()).as("the set response is the re-read value, unmasked").isEqualTo("hunter2");

        var putDenied = http.put(property("smtp", "password"), "{\"value\":\"hunter3\"}", VIEWER);
        assertThat(putDenied.statusCode()).isEqualTo(403);
        assertThat(json(putDenied).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");

        var deleteDenied = http.delete(property("smtp", "password"), VIEWER);
        assertThat(deleteDenied.statusCode()).isEqualTo(403);
        assertThat(json(deleteDenied).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");

        assertThat(json(http.get(property("smtp", "password"), MANAGER)).get("value").asText())
                .as("mutant: CONFIG_MANAGE stops unmasking").isEqualTo("hunter2");
        assertThat(json(http.get(property("smtp", "password"), VIEWER)).get("value").asText())
                .as("mutant: view-only stops masking").isEqualTo("***");
        var list = json(http.get("/api/platform-config/" + APP, VIEWER)).get("items");
        assertThat(list).anySatisfy(item -> {
            assertThat(item.get("property").asText()).isEqualTo("password");
            assertThat(item.get("value").asText()).isEqualTo("***");
        });
        // The value survived the failed PUT above (still "hunter2", not "hunter3").
        assertThat(json(http.get(property("smtp", "password"), MANAGER)).get("value").asText()).isEqualTo("hunter2");
    }

    /// docs/spec/audit-redaction.md test 2: a `SECRET` config value never
    /// appears in the audit row (`SetPropertyCommand` implements
    /// `AuditMasked`; `value` is masked unless `valueType` is exactly
    /// `PLAIN`), while a `PLAIN` value is still recorded — the point of the
    /// rule is to hide secrets, not to blind the audit trail generally.
    /// Mutant: `SetPropertyCommand#auditMaskedFields` always returns
    /// `Set.of()` (never masks) -> the SECRET assertion fails; mutant: it
    /// always returns `Set.of("value")` (masks PLAIN too) -> the PLAIN
    /// assertion fails.
    @Test
    void secretConfigValueNeverAppearsInTheAuditRowButPlainValueDoes() throws Exception {
        var set = http.put(property("audit", "apikey"), "{\"value\":\"sk_live_topsecret\",\"valueType\":\"SECRET\"}", MANAGER);
        assertThat(set.statusCode()).as(set.body()).isEqualTo(200);
        var plain = http.put(property("audit", "plainprop"), "{\"value\":\"visible-value\",\"valueType\":\"PLAIN\"}", MANAGER);
        assertThat(plain.statusCode()).as(plain.body()).isEqualTo(200);

        boolean sawSecret = false;
        boolean sawPlain = false;
        try (var c = TestPg.dataSource().getConnection();
             var ps = c.prepareStatement(
                     "SELECT operation_json::text FROM aud_logs WHERE operation = 'SetPropertyCommand' "
                             + "AND operation_json->>'applicationCode' = ?")) {
            ps.setString(1, APP);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    var parsed = Json.MAPPER.readTree(rs.getString(1));
                    String prop = parsed.get("property").asString();
                    if ("apikey".equals(prop)) {
                        sawSecret = true;
                        assertThat(rs.getString(1)).as("the secret value never lands in the audit row").doesNotContain("sk_live_topsecret");
                        assertThat(parsed.get("value").asString()).isEqualTo("***");
                    }
                    if ("plainprop".equals(prop)) {
                        sawPlain = true;
                        assertThat(parsed.get("value").asString()).as("a PLAIN value is still recorded").isEqualTo("visible-value");
                    }
                }
            }
        }
        assertThat(sawSecret).as("a SetPropertyCommand row for the SECRET property was written").isTrue();
        assertThat(sawPlain).as("a SetPropertyCommand row for the PLAIN property was written").isTrue();
    }

    /// Spec test 5: the three access-grant routes are withdrawn; they answer
    /// 404 like any other unknown route — never the old `ANCHOR_REQUIRED`.
    @Test
    void theThreeAccessGrantRoutesAnswer404() {
        var list = http.get("/api/platform-config/" + APP + "/access", ADMIN);
        assertThat(list.statusCode()).as("mutant: the access routes were not removed").isEqualTo(404);

        var grant = http.post("/api/platform-config/" + APP + "/access", "{\"roleCode\":\"x\",\"canWrite\":true}", ADMIN);
        assertThat(grant.statusCode()).isEqualTo(404);

        var revoke = http.delete("/api/platform-config/access/cfa_whatever", ADMIN);
        assertThat(revoke.statusCode()).isEqualTo(404);
    }

    // ── Negative paths ─────────────────────────────────────────────────────

    @Test
    void unauthenticatedIsRefused() {
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
        // PlatformConfigOperationsTest — "missing value").
        var noValue = http.put(property("smtp", "port"), "{\"description\":\"no value\"}", MANAGER);
        assertThat(noValue.statusCode()).isEqualTo(400);
        assertThat(json(noValue).get("error").asText()).isEqualTo("VALIDATION");

        var badType = http.put(property("smtp", "port"), "{\"value\":\"x\",\"valueType\":\"NOT_A_TYPE\"}", MANAGER);
        assertThat(badType.statusCode()).isEqualTo(400);
        assertThat(json(badType).get("error").asText()).isEqualTo("INVALID_VALUE_TYPE");

        var malformed = http.put(property("smtp", "port"), "{not json", MANAGER);
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(json(malformed).get("error").asText()).isEqualTo("INVALID_JSON");
    }
}
