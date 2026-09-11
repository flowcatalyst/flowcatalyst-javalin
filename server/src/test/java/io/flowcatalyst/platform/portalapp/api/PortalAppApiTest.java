package io.flowcatalyst.platform.portalapp.api;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientIdentifier;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.oauthclient.ClientType;
import io.flowcatalyst.platform.oauthclient.OAuthClient;
import io.flowcatalyst.platform.oauthclient.OAuthClientRepository;
import io.flowcatalyst.platform.portalapp.PortalApp;
import io.flowcatalyst.platform.portalapp.PortalAppRepository;
import io.flowcatalyst.platform.portalidentity.PortalAppGrantSource;
import io.flowcatalyst.platform.portalidentity.PortalIdentity;
import io.flowcatalyst.platform.portalidentity.PortalIdentityRepository;
import io.flowcatalyst.platform.portalidentity.PortalIdentitySource;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.platform.shared.auth.ClaimsResolver;
import io.flowcatalyst.platform.shared.auth.JwtVerifier;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.encryption.Encryption;
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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// `/api/portal-apps*` end to end (spec `portal-apps.md` §4.4): the two-
/// aggregate create/delete orchestrations through HTTP, the read gate's
/// anchor-omits-clientId exception, the manage gate on every write, and the
/// batched list shape. `PortalAppOperationsTest` covers the operation layer
/// (rollback, event/audit rows, the exact OAuth-client shape) in detail;
/// this class is about the HTTP surface, authorization, and §9.8's
/// cross-aggregate "user loses B's grant" scenario.
@SuppressWarnings("deprecation")
class PortalAppApiTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);

    private static final ClientRepository clientRepo = new ClientRepository(TestPg.dataSource());
    private static final PortalAppRepository portalAppRepo = new PortalAppRepository(TestPg.dataSource());
    private static final OAuthClientRepository oauthClientRepo =
            new OAuthClientRepository(TestPg.dataSource(), new ApplicationRepository(TestPg.dataSource()));
    private static final PortalIdentityRepository portalIdentityRepo = new PortalIdentityRepository(TestPg.dataSource());
    private static final UnitOfWork uow = new UnitOfWork(TestPg.dataSource(), new PlatformSink(Json.MAPPER));
    private static final Optional<Encryption> ENCRYPTION = Optional.of(Encryption.withKey(Encryption.generateKey()));

    private static final String[] ANCHOR = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(), Authenticator.TEST_SCOPE, "ANCHOR"};

    private static TestHttp http;

    @BeforeAll
    static void start() {
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before("/api/*", auth);
            PortalAppApi.register(routes, new PortalAppApi.State(portalAppRepo, oauthClientRepo, clientRepo, uow, ENCRYPTION));
        });
    }

    @AfterAll
    static void stop() {
        http.close();
    }

    // ── Fixtures ───────────────────────────────────────────────────────────

    private static JsonNode json(HttpResponse<String> r) {
        try {
            return Json.MAPPER.readTree(r.body());
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + r.body(), e);
        }
    }

    private static String testClient(String tag) {
        Client c = Client.create("Portal App Api " + tag, ClientIdentifier.parse("paa-" + RUN + "-" + tag));
        uow.inTransaction(tx -> {
            clientRepo.persist(c, tx.dbTx());
            return null;
        });
        return c.id();
    }

    private static String[] viewOnly(String clientId) {
        return new String[]{
                Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(), Authenticator.TEST_SCOPE, "CLIENT",
                Authenticator.TEST_CLIENTS, clientId, Authenticator.TEST_PERMISSIONS, "platform:iam:portal-user:view"};
    }

    private static String[] manage(String clientId) {
        return new String[]{
                Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(), Authenticator.TEST_SCOPE, "CLIENT",
                Authenticator.TEST_CLIENTS, clientId, Authenticator.TEST_PERMISSIONS, "platform:iam:portal-user:manage"};
    }

    private static JsonNode create(String clientId, String code, String extraJson) {
        var r = http.post("/api/portal-apps",
                "{\"clientId\":\"" + clientId + "\",\"code\":\"" + code + "\",\"name\":\"App " + code + "\"" + extraJson + "}", ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return json(r);
    }

    // ── §9.7 provisioning ─────────────────────────────────────────────────

    @Test
    void createConfidentialProvisionsAPortalFlaggedAppLinkedOAuthClient() {
        String clientId = testClient("prov-conf");
        var body = create(clientId, "prov-conf-" + RUN,
                ",\"redirectUris\":[\"https://a.example/cb\"],\"clientType\":\"CONFIDENTIAL\"");

        assertThat(body.get("clientType").asText()).isEqualTo("CONFIDENTIAL");
        String secret = body.get("clientSecret").asText();
        assertThat(secret).isNotBlank();
        String oauthClientRowId = body.get("oauthClientRowId").asText();
        String oauthClientId = body.get("oauthClientId").asText();

        OAuthClient oc = oauthClientRepo.findById(oauthClientRowId).orElseThrow();
        assertThat(oc.clientId()).isEqualTo(oauthClientId);
        assertThat(oc.clientType()).isEqualTo(ClientType.CONFIDENTIAL);
        assertThat(oc.secretRef()).as("secret returned once is not the stored value").isNotEqualTo(secret).startsWith("hashed:v1:");
        assertThat(oc.redirectUris()).containsExactly("https://a.example/cb");
        assertThat(oc.grantTypes()).containsExactly("authorization_code");
        assertThat(oc.pkceRequired()).isTrue();
        assertThat(oc.apiAccess()).isFalse();
        assertThat(oc.portalClientId()).isEqualTo(clientId);

        var app = body.get("portalApp");
        assertThat(app.get("oauthClients")).hasSize(1);
        assertThat(app.get("oauthClients").get(0).get("id").asText()).isEqualTo(oauthClientRowId);
        assertThat(app.get("userCount").asInt()).isZero();
    }

    @Test
    void createPublicHasNoSecret() {
        String clientId = testClient("prov-pub");
        var body = create(clientId, "prov-pub-" + RUN, ",\"clientType\":\"PUBLIC\"");
        assertThat(body.get("clientType").asText()).isEqualTo("PUBLIC");
        assertThat(body.has("clientSecret")).as("PUBLIC has no secret").isFalse();
        OAuthClient oc = oauthClientRepo.findById(body.get("oauthClientRowId").asText()).orElseThrow();
        assertThat(oc.secretRef()).isNull();
    }

    @Test
    void createRejectsADuplicateCodeAndLeavesNoExtraOAuthClient() {
        String clientId = testClient("prov-dup");
        String code = "prov-dup-" + RUN;
        create(clientId, code, "");

        int before = oauthClientRepo.findAll().stream().filter(c -> clientId.equals(c.portalClientId())).toList().size();
        var conflict = http.post("/api/portal-apps",
                "{\"clientId\":\"" + clientId + "\",\"code\":\"" + code.toUpperCase(Locale.ROOT) + "\",\"name\":\"Dup\"}", ANCHOR);
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(json(conflict).get("error").asText()).isEqualTo("CODE_EXISTS");
        int after = oauthClientRepo.findAll().stream().filter(c -> clientId.equals(c.portalClientId())).toList().size();
        assertThat(after).as("no extra OAuth client from the failed attempt").isEqualTo(before);
    }

    @Test
    void createRejectsAWildcardCallback() {
        String clientId = testClient("prov-wild");
        var r = http.post("/api/portal-apps",
                "{\"clientId\":\"" + clientId + "\",\"code\":\"prov-wild-" + RUN
                        + "\",\"name\":\"Wild\",\"redirectUris\":[\"https://*.example.com/cb\"]}", ANCHOR);
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json(r).get("error").asText()).isEqualTo("REDIRECT_URI_INVALID");
    }

    // ── GET list ───────────────────────────────────────────────────────────

    @Test
    void listBatchesUserCountAndOAuthClientsAcrossTheClientsApps() {
        String clientId = testClient("list");
        var a = create(clientId, "list-a-" + RUN, "");
        var b = create(clientId, "list-b-" + RUN, "");
        String appAId = a.get("portalApp").get("id").asText();

        PortalIdentity granted = PortalIdentity.create(clientId, "list-grantee-" + RUN + "@example.com", null, PortalIdentitySource.INVITE)
                .grant(appAId, PortalAppGrantSource.ADMIN);
        uow.inTransaction(tx -> {
            portalIdentityRepo.persist(granted, tx.dbTx());
            return null;
        });

        var list = json(http.get("/api/portal-apps?clientId=" + clientId, ANCHOR));
        assertThat(list.get("portalApps")).hasSize(2);
        for (JsonNode item : list.get("portalApps")) {
            if (item.get("id").asText().equals(appAId)) {
                assertThat(item.get("userCount").asInt()).isEqualTo(1);
            } else {
                assertThat(item.get("userCount").asInt()).isZero();
            }
            assertThat(item.get("oauthClients")).hasSize(1);
        }
    }

    @Test
    void listWithoutClientIdIsEveryClientsAppsForAnchorsAndRequiredForOthers() {
        String clientId = testClient("list-anchor");
        create(clientId, "list-anchor-" + RUN, "");

        var anchorList = http.get("/api/portal-apps", ANCHOR);
        assertThat(anchorList.statusCode()).as(anchorList.body()).isEqualTo(200);
        assertThat(json(anchorList).get("portalApps").size()).as("at least this run's app is in the every-client list")
                .isGreaterThanOrEqualTo(1);

        var nonAnchorNoClientId = http.get("/api/portal-apps", viewOnly(clientId));
        assertThat(nonAnchorNoClientId.statusCode()).isEqualTo(400);
        assertThat(json(nonAnchorNoClientId).get("error").asText()).isEqualTo("CLIENT_ID_REQUIRED");
    }

    @Test
    void viewOnlyCallerGets200OnListButManageIsRequiredToWrite() {
        String clientId = testClient("view-only");
        var created = create(clientId, "view-only-" + RUN, "");
        String id = created.get("portalApp").get("id").asText();

        var list = http.get("/api/portal-apps?clientId=" + clientId, viewOnly(clientId));
        assertThat(list.statusCode()).isEqualTo(200);

        var forbiddenUpdate = http.put("/api/portal-apps/" + id, "{\"clientId\":\"" + clientId + "\",\"name\":\"X\"}", viewOnly(clientId));
        assertThat(forbiddenUpdate.statusCode()).isEqualTo(403);

        var forbiddenDelete = http.delete("/api/portal-apps/" + id + "?clientId=" + clientId, viewOnly(clientId));
        assertThat(forbiddenDelete.statusCode()).isEqualTo(403);

        var forbiddenCreate = http.post("/api/portal-apps",
                "{\"clientId\":\"" + clientId + "\",\"code\":\"view-only-2-" + RUN + "\",\"name\":\"X\"}", viewOnly(clientId));
        assertThat(forbiddenCreate.statusCode()).isEqualTo(403);
    }

    /// A totally-omitted `clientId` on a POST/PUT body is caught by the
    /// lockfile's own schema validation (`required: [clientId]`) before the
    /// handler runs, at generic 400 `VALIDATION` — so this pins the exact
    /// `CLIENT_ID_REQUIRED` code where it is actually reachable: a
    /// present-but-blank `clientId` (POST/PUT bodies, which schema validation
    /// does not reject — presence, not content, is what `required` checks)
    /// and the query-param form (DELETE), which has no body schema at all.
    @Test
    void everyWriteRequiresClientIdBeforeAuthorization() {
        var createOmitted = http.post("/api/portal-apps", "{\"code\":\"x-" + RUN + "\",\"name\":\"X\"}", ANCHOR);
        assertThat(createOmitted.statusCode()).isEqualTo(400);

        var createBlank = http.post("/api/portal-apps", "{\"clientId\":\"\",\"code\":\"x-" + RUN + "\",\"name\":\"X\"}", ANCHOR);
        assertThat(createBlank.statusCode()).isEqualTo(400);
        assertThat(json(createBlank).get("error").asText()).isEqualTo("CLIENT_ID_REQUIRED");

        String clientId = testClient("clientid-req");
        var created = create(clientId, "clientid-req-" + RUN, "");
        String id = created.get("portalApp").get("id").asText();

        var updateBlank = http.put("/api/portal-apps/" + id, "{\"clientId\":\"\",\"name\":\"X\"}", ANCHOR);
        assertThat(updateBlank.statusCode()).isEqualTo(400);
        assertThat(json(updateBlank).get("error").asText()).isEqualTo("CLIENT_ID_REQUIRED");

        var deleteNoClientId = http.delete("/api/portal-apps/" + id, ANCHOR);
        assertThat(deleteNoClientId.statusCode()).isEqualTo(400);
        assertThat(json(deleteNoClientId).get("error").asText()).isEqualTo("CLIENT_ID_REQUIRED");
    }

    // ── PUT update ─────────────────────────────────────────────────────────

    @Test
    void updateReturnsThePortalAppResponseAndLeavesCodeAlone() {
        String clientId = testClient("update");
        var created = create(clientId, "update-" + RUN, "");
        String id = created.get("portalApp").get("id").asText();
        String originalCode = created.get("portalApp").get("code").asText();

        var updated = json(http.put("/api/portal-apps/" + id,
                "{\"clientId\":\"" + clientId + "\",\"name\":\"Renamed\",\"active\":false}", ANCHOR));
        assertThat(updated.get("name").asText()).isEqualTo("Renamed");
        assertThat(updated.get("active").asBoolean()).isFalse();
        assertThat(updated.get("code").asText()).isEqualTo(originalCode);
    }

    @Test
    void updateUnknownIdIs404() {
        String clientId = testClient("update-404");
        var r = http.put("/api/portal-apps/pta_doesnotexist1", "{\"clientId\":\"" + clientId + "\",\"name\":\"X\"}", ANCHOR);
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(json(r).get("error").asText()).isEqualTo("PortalApp_NOT_FOUND");
    }

    // ── DELETE (§3.6, §9.8) ────────────────────────────────────────────────

    /// §9.8: two apps A and B on one client, one identity (created + granted
    /// directly via `PortalIdentityRepository`, per the brief) granted both.
    /// Deleting B removes B's OAuth client, leaves A's, and the identity
    /// loses exactly B's grant.
    @Test
    void deletingAppBRemovesItsOAuthClientLeavesAAndTheUserLosesOnlyBsGrant() {
        String clientId = testClient("del98");
        var a = create(clientId, "del98-a-" + RUN, "");
        var b = create(clientId, "del98-b-" + RUN, "");
        String appAId = a.get("portalApp").get("id").asText();
        String appBId = b.get("portalApp").get("id").asText();
        String oauthClientRowIdB = b.get("oauthClientRowId").asText();
        String oauthClientRowIdA = a.get("oauthClientRowId").asText();

        PortalIdentity identity = PortalIdentity.create(clientId, "del98-" + RUN + "@example.com", null, PortalIdentitySource.INVITE)
                .grant(appAId, PortalAppGrantSource.ADMIN)
                .grant(appBId, PortalAppGrantSource.ADMIN);
        uow.inTransaction(tx -> {
            portalIdentityRepo.persist(identity, tx.dbTx());
            return null;
        });
        assertThat(portalIdentityRepo.findById(identity.id()).orElseThrow().apps()).hasSize(2);

        var del = http.delete("/api/portal-apps/" + appBId + "?clientId=" + clientId, ANCHOR);
        assertThat(del.statusCode()).as(del.body()).isEqualTo(200);
        assertThat(json(del).get("message").asText()).isEqualTo("Portal app deleted with its OAuth client");

        assertThat(portalAppRepo.findById(appBId)).isEmpty();
        assertThat(oauthClientRepo.findById(oauthClientRowIdB)).as("B's OAuth client is gone").isEmpty();
        assertThat(portalAppRepo.findById(appAId)).as("A survives").isPresent();
        assertThat(oauthClientRepo.findById(oauthClientRowIdA)).as("A's OAuth client survives").isPresent();

        PortalIdentity reloaded = portalIdentityRepo.findById(identity.id()).orElseThrow();
        assertThat(reloaded.hasApp(appAId)).as("A's grant remains").isTrue();
        assertThat(reloaded.hasApp(appBId)).as("B's grant is gone").isFalse();
        assertThat(reloaded.apps()).hasSize(1);
    }

    @Test
    void deleteMessageSuffixDependsOnHowManyOAuthClientsWereRemoved() {
        String clientId = testClient("del-suffix");

        // Zero: the provisioned OAuth client is removed out of band first.
        var zero = create(clientId, "del-suffix-zero-" + RUN, "");
        String zeroAppId = zero.get("portalApp").get("id").asText();
        OAuthClient zeroOc = oauthClientRepo.findById(zero.get("oauthClientRowId").asText()).orElseThrow();
        uow.inTransaction(tx -> {
            oauthClientRepo.delete(zeroOc, tx.dbTx());
            return null;
        });
        var delZero = json(http.delete("/api/portal-apps/" + zeroAppId + "?clientId=" + clientId, ANCHOR));
        assertThat(delZero.get("message").asText()).isEqualTo("Portal app deleted");

        // Two: a second OAuth client is linked to the same app directly via the repository.
        var two = create(clientId, "del-suffix-two-" + RUN, "");
        String twoAppId = two.get("portalApp").get("id").asText();
        OAuthClient second = OAuthClient.create("oc-del-suffix-2-" + RUN, "Second", ClientType.PUBLIC)
                .withPortalAppId(twoAppId).withPortalAndApiAccess(clientId, false);
        uow.inTransaction(tx -> {
            oauthClientRepo.persist(second, tx.dbTx());
            return null;
        });
        var delTwo = json(http.delete("/api/portal-apps/" + twoAppId + "?clientId=" + clientId, ANCHOR));
        assertThat(delTwo.get("message").asText()).isEqualTo("Portal app deleted with its OAuth clients");
    }

    @Test
    void deleteUnknownIdIs404() {
        String clientId = testClient("del-404");
        var r = http.delete("/api/portal-apps/pta_doesnotexist1?clientId=" + clientId, ANCHOR);
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(json(r).get("error").asText()).isEqualTo("PortalApp_NOT_FOUND");
    }
}
