package io.flowcatalyst.platform.oauthclient.api;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientIdentifier;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.oauthclient.OAuthClientRepository;
import io.flowcatalyst.platform.portalapp.PortalApp;
import io.flowcatalyst.platform.portalapp.PortalAppCode;
import io.flowcatalyst.platform.portalapp.PortalAppRepository;
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

/// The eleven `/api/oauth-clients*` routes end to end through Javalin (spec
/// `auth-core.md` §6.3; A-22 `docs/improvements.md`): the authenticator's
/// test headers, the anchor-only gate on every single route, the lockfile
/// status codes and body shapes, and the plaintext-secret discipline
/// (present on create of a CONFIDENTIAL client and on rotate, never on read).
@SuppressWarnings("deprecation")
class OAuthClientApiTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);

    private static final String[] ANCHOR = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:*:*:*"};
    /// A CLIENT-scoped principal holding every plausible permission, which must not help — this
    /// surface is anchor-only, not permission-gated (spec §6.3).
    private static final String[] CLIENT_SCOPED = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, "cli_ocapi_" + RUN,
            Authenticator.TEST_PERMISSIONS, "platform:*:*:*"};

    private static final Optional<Encryption> ENCRYPTION = Optional.of(Encryption.withKey(Encryption.generateKey()));
    private static final UnitOfWork UOW = new UnitOfWork(TestPg.dataSource(), new PlatformSink(Json.MAPPER));
    private static final ClientRepository CLIENT_REPO = new ClientRepository(TestPg.dataSource());
    private static final PortalAppRepository PORTAL_APP_REPO = new PortalAppRepository(TestPg.dataSource());
    private static final OAuthClientApi.State state = new OAuthClientApi.State(
            new OAuthClientRepository(TestPg.dataSource(), new ApplicationRepository(TestPg.dataSource())),
            UOW, ENCRYPTION, PORTAL_APP_REPO);
    private static TestHttp http;

    @BeforeAll
    static void start() {
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before("/api/*", auth);
            OAuthClientApi.register(routes, state);
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

    private static String name(String tag) {
        return "oc-api-" + tag + "-" + RUN;
    }

    private static JsonNode create(String tag, String clientType, String extraJson) {
        var r = http.post("/api/oauth-clients",
                "{\"clientName\":\"" + name(tag) + "\",\"clientType\":\"" + clientType + "\"" + extraJson + "}", ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return json(r);
    }

    /// A fresh client + one active portal app on it, for the §4.5 `portalAppId` tests.
    private static PortalApp seedPortalApp(String tag) {
        Client c = Client.create("OAuth Client API Test " + tag, ClientIdentifier.parse("oc-api-" + tag + "-" + RUN));
        PortalApp app = PortalApp.create(c.id(), PortalAppCode.parse(tag + "-" + RUN), "App " + tag, null);
        UOW.inTransaction(tx -> {
            CLIENT_REPO.persist(c, tx.dbTx());
            PORTAL_APP_REPO.persist(app, tx.dbTx());
            return null;
        });
        return app;
    }

    // ── Happy paths ────────────────────────────────────────────────────────

    @Test
    void createConfidentialReturnsThePlaintextSecretOnceThenNeverAgain() {
        var body = create("confidential", "CONFIDENTIAL", "");
        assertThat(body.get("clientSecret").asText()).isNotBlank();
        String id = body.get("client").get("id").asText();

        // Never again on read.
        var get = http.get("/api/oauth-clients/" + id, ANCHOR);
        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(json(get).has("clientSecret")).as("secret never appears on a read").isFalse();

        var list = http.get("/api/oauth-clients", ANCHOR);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(json(list).get("clients")).noneSatisfy(c -> assertThat(c.has("clientSecret")).isTrue());
    }

    @Test
    void createPublicNeverReturnsAClientSecret() {
        var body = create("public", "PUBLIC", "");
        assertThat(body.has("clientSecret")).as("PUBLIC clients have no secret").isFalse();
    }

    @Test
    void createThenGetByIdAndByClientIdReturnTheFullResponseShape() {
        var body = create("shape", "CONFIDENTIAL",
                ",\"redirectUris\":[\"https://a.example/cb\"],\"grantTypes\":[\"authorization_code\"],"
                        + "\"defaultScopes\":[\"read\",\"write\"],\"pkceRequired\":false,\"applicationIds\":[]");
        var client = body.get("client");
        String id = client.get("id").asText();
        String clientId = client.get("clientId").asText();

        var byId = json(http.get("/api/oauth-clients/" + id, ANCHOR));
        // serviceAccountPrincipalId / portalClientId / previousSecret* are all
        // null here and correctly omitted (NON_ABSENT) — see the dedicated
        // overlap-visibility assertions in the rotate/revoke tests below.
        assertThat(byId.propertyNames()).containsExactlyInAnyOrder("id", "clientId", "clientName", "clientType",
                "redirectUris", "postLogoutRedirectUris", "allowedOrigins", "grantTypes", "defaultScopes",
                "pkceRequired", "applicationIds", "applications", "active", "apiAccess", "createdAt", "updatedAt");
        assertThat(byId.get("redirectUris")).extracting(JsonNode::asText).containsExactly("https://a.example/cb");
        assertThat(byId.get("grantTypes")).extracting(JsonNode::asText).containsExactly("authorization_code");
        assertThat(byId.get("defaultScopes")).extracting(JsonNode::asText).containsExactlyInAnyOrder("read", "write");
        assertThat(byId.get("pkceRequired").asBoolean()).isFalse();
        assertThat(byId.get("active").asBoolean()).isTrue();
        assertThat(byId.has("previousSecretExpiresAt")).as("no overlap in flight").isFalse();

        var byClientId = json(http.get("/api/oauth-clients/by-client-id/" + clientId, ANCHOR));
        assertThat(byClientId.get("id").asText()).isEqualTo(id);
    }

    @Test
    void portalClientIdAppearsOnTheWireWhenSet() {
        // portal_client_id is VARCHAR(17), like every other TSID column.
        String portalOwner = EntityType.CLIENT.generate();
        var body = create("portal", "PUBLIC", ",\"portalClientId\":\"" + portalOwner + "\"");
        assertThat(body.get("client").get("portalClientId").asText()).isEqualTo(portalOwner);

        String id = body.get("client").get("id").asText();
        var cleared = http.put("/api/oauth-clients/" + id, "{\"portalClientId\":\"\"}", ANCHOR);
        assertThat(cleared.statusCode()).isEqualTo(204);
        assertThat(json(http.get("/api/oauth-clients/" + id, ANCHOR)).has("portalClientId"))
                .as("blank clears it back to omitted").isFalse();
    }

    /// spec §4.5's controller pre-check: `portalAppId` resolves `portalClientId`
    /// from the app's own owner, rejects an unknown app, and rejects a
    /// request that names a conflicting `portalClientId` explicitly.
    @Test
    void createWithPortalAppIdResolvesPortalClientIdAndRejectsAMismatch() {
        PortalApp app = seedPortalApp("resolve");
        var body = create("resolve", "PUBLIC", ",\"portalAppId\":\"" + app.id() + "\"");
        assertThat(body.get("client").get("portalAppId").asText()).isEqualTo(app.id());
        assertThat(body.get("client").get("portalClientId").asText())
                .as("resolved from the app, not sent by the caller").isEqualTo(app.clientId());

        var notFound = http.post("/api/oauth-clients",
                "{\"clientName\":\"" + name("resolve-404") + "\",\"clientType\":\"PUBLIC\",\"portalAppId\":\"pta_doesnotexist1\"}", ANCHOR);
        assertThat(notFound.statusCode()).isEqualTo(404);
        assertThat(json(notFound).get("error").asText()).isEqualTo("PortalApp_NOT_FOUND");

        var mismatch = http.post("/api/oauth-clients",
                "{\"clientName\":\"" + name("resolve-mismatch") + "\",\"clientType\":\"PUBLIC\",\"portalAppId\":\"" + app.id()
                        + "\",\"portalClientId\":\"" + EntityType.CLIENT.generate() + "\"}", ANCHOR);
        assertThat(mismatch.statusCode()).isEqualTo(400);
        assertThat(json(mismatch).get("error").asText()).isEqualTo("PORTAL_APP_CLIENT_MISMATCH");
    }

    /// spec §4.5: `portalClientId: ""` clears both the portal flag and the
    /// app link, even when `portalAppId` is simply omitted from the request
    /// (not sent as `""` itself) — proving the API-level "clears both" rule,
    /// distinct from the operation-level three-state contract
    /// `OAuthClientOperationsTest` pins directly.
    @Test
    void updateClearingPortalClientIdAlsoClearsTheAppLinkWithoutNamingIt() {
        PortalApp app = seedPortalApp("clearboth");
        var body = create("clearboth", "PUBLIC", ",\"portalAppId\":\"" + app.id() + "\"");
        String id = body.get("client").get("id").asText();
        assertThat(body.get("client").get("portalAppId").asText()).isEqualTo(app.id());

        var cleared = http.put("/api/oauth-clients/" + id, "{\"portalClientId\":\"\"}", ANCHOR);
        assertThat(cleared.statusCode()).as(cleared.body()).isEqualTo(204);

        var got = json(http.get("/api/oauth-clients/" + id, ANCHOR));
        assertThat(got.has("portalClientId")).as("portalClientId cleared").isFalse();
        assertThat(got.has("portalAppId")).as("app link cleared too, though never named in the request").isFalse();
    }

    @Test
    void updateReturns204AndPersists() {
        var body = create("update", "PUBLIC", "");
        String id = body.get("client").get("id").asText();

        var put = http.put("/api/oauth-clients/" + id, "{\"clientName\":\"Renamed\"}", ANCHOR);
        assertThat(put.statusCode()).isEqualTo(204);
        assertThat(put.body()).isEmpty();

        var got = json(http.get("/api/oauth-clients/" + id, ANCHOR));
        assertThat(got.get("clientName").asText()).isEqualTo("Renamed");

        var bad = http.put("/api/oauth-clients/" + id, "{\"clientName\":\"\"}", ANCHOR);
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(json(bad).get("error").asText()).isEqualTo("CLIENT_NAME_REQUIRED");
    }

    @Test
    void activateAndDeactivateReturnTheSuccessEnvelope() {
        var body = create("lifecycle", "PUBLIC", "");
        String id = body.get("client").get("id").asText();

        var deactivate = http.post("/api/oauth-clients/" + id + "/deactivate", null, ANCHOR);
        assertThat(deactivate.statusCode()).isEqualTo(200);
        assertThat(json(deactivate).get("success").asBoolean()).isTrue();
        assertThat(json(deactivate).get("message").asText()).isEqualTo("OAuth client deactivated");
        assertThat(json(http.get("/api/oauth-clients/" + id, ANCHOR)).get("active").asBoolean()).isFalse();

        var activate = http.post("/api/oauth-clients/" + id + "/activate", null, ANCHOR);
        assertThat(activate.statusCode()).isEqualTo(200);
        assertThat(json(activate).get("message").asText()).isEqualTo("OAuth client activated");
        assertThat(json(http.get("/api/oauth-clients/" + id, ANCHOR)).get("active").asBoolean()).isTrue();
    }

    @Test
    void rotateSecretAndItsSdkAliasBothReturnTheNewSecretAndTheGraceExpiry() {
        var body = create("rotate", "CONFIDENTIAL", "");
        var client = body.get("client");
        String id = client.get("id").asText();
        String clientId = client.get("clientId").asText();
        String firstSecret = body.get("clientSecret").asText();

        var rotate = http.post("/api/oauth-clients/" + id + "/rotate-secret", null, ANCHOR);
        assertThat(rotate.statusCode()).as(rotate.body()).isEqualTo(200);
        var rotated = json(rotate);
        assertThat(rotated.get("clientId").asText()).isEqualTo(clientId);
        assertThat(rotated.get("clientSecret").asText()).isNotBlank().isNotEqualTo(firstSecret);
        assertThat(rotated.has("previousSecretExpiresAt")).as("default grace, not immediate").isTrue();

        var afterRotate = json(http.get("/api/oauth-clients/" + id, ANCHOR));
        assertThat(afterRotate.has("previousSecretExpiresAt")).as("overlap now live on read").isTrue();

        // SDK alias, immediate cutover this time.
        var alias = http.post("/api/oauth-clients/" + id + "/regenerate-secret", "{\"graceSeconds\":0}", ANCHOR);
        assertThat(alias.statusCode()).isEqualTo(200);
        assertThat(json(alias).has("previousSecretExpiresAt")).as("graceSeconds:0 omits the field").isFalse();

        var afterImmediate = json(http.get("/api/oauth-clients/" + id, ANCHOR));
        assertThat(afterImmediate.has("previousSecretExpiresAt")).as("no overlap after an immediate cutover").isFalse();
    }

    @Test
    void rotateSecretOnAPublicClientIs409NotConfidential() {
        var body = create("rotatepub", "PUBLIC", "");
        String id = body.get("client").get("id").asText();
        var rotate = http.post("/api/oauth-clients/" + id + "/rotate-secret", null, ANCHOR);
        assertThat(rotate.statusCode()).isEqualTo(409);
        assertThat(json(rotate).get("error").asText()).isEqualTo("NOT_CONFIDENTIAL");
    }

    @Test
    void revokePreviousSecretIsIdempotentAndClosesTheOverlap() {
        var body = create("revoke", "CONFIDENTIAL", "");
        String id = body.get("client").get("id").asText();
        http.post("/api/oauth-clients/" + id + "/rotate-secret", null, ANCHOR);
        assertThat(json(http.get("/api/oauth-clients/" + id, ANCHOR)).has("previousSecretExpiresAt")).isTrue();

        var first = http.post("/api/oauth-clients/" + id + "/revoke-previous-secret", null, ANCHOR);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(json(first).get("success").asBoolean()).isTrue();
        assertThat(json(http.get("/api/oauth-clients/" + id, ANCHOR)).has("previousSecretExpiresAt")).isFalse();

        var second = http.post("/api/oauth-clients/" + id + "/revoke-previous-secret", null, ANCHOR);
        assertThat(second.statusCode()).as("idempotent — no error the second time").isEqualTo(200);
    }

    @Test
    void deleteReturns204ThenGetIs404Envelope() {
        var body = create("delete", "PUBLIC", "");
        String id = body.get("client").get("id").asText();

        var del = http.delete("/api/oauth-clients/" + id, ANCHOR);
        assertThat(del.statusCode()).isEqualTo(204);

        var get = http.get("/api/oauth-clients/" + id, ANCHOR);
        assertThat(get.statusCode()).isEqualTo(404);
        assertThat(get.body()).isEqualTo("{\"error\":\"OAuthClient_NOT_FOUND\",\"message\":\"OAuthClient not found: " + id + "\"}\n");

        var again = http.delete("/api/oauth-clients/" + id, ANCHOR);
        assertThat(again.statusCode()).isEqualTo(404);
    }

    // ── Negative paths ─────────────────────────────────────────────────────

    @Test
    void validationConflictAndMalformedJsonAre4xxEnvelopes() {
        // clientName is schema-required too — sent as "" so the request reaches the domain check.
        var missingName = http.post("/api/oauth-clients", "{\"clientName\":\"\",\"clientType\":\"PUBLIC\"}", ANCHOR);
        assertThat(missingName.statusCode()).isEqualTo(400);
        assertThat(json(missingName).get("error").asText()).isEqualTo("CLIENT_NAME_REQUIRED");

        var badType = http.post("/api/oauth-clients", "{\"clientName\":\"" + name("badtype") + "\",\"clientType\":\"BOGUS\"}", ANCHOR);
        assertThat(badType.statusCode()).isEqualTo(400);
        assertThat(json(badType).get("error").asText()).isEqualTo("INVALID_CLIENT_TYPE");

        var conflict = http.post("/api/oauth-clients",
                "{\"clientName\":\"" + name("conflict") + "\",\"clientType\":\"PUBLIC\","
                        + "\"portalClientId\":\"cli_owner\",\"apiAccess\":true}", ANCHOR);
        assertThat(conflict.statusCode()).isEqualTo(400);
        assertThat(json(conflict).get("error").asText()).isEqualTo("PORTAL_API_ACCESS_CONFLICT");

        var malformed = http.post("/api/oauth-clients", "{not json", ANCHOR);
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(json(malformed).get("error").asText()).isEqualTo("INVALID_JSON");
    }

    /// Every route is anchor-only (spec §6.3): a CLIENT-scoped principal
    /// holding every plausible permission still gets 403, and an
    /// unauthenticated caller gets 403 `UNAUTHENTICATED` — never 404, which
    /// would leak whether the id exists.
    @Test
    void everyRouteIsAnchorOnly() {
        var body = create("gate", "CONFIDENTIAL", "");
        String id = body.get("client").get("id").asText();
        String clientId = body.get("client").get("clientId").asText();

        for (var r : List.of(
                http.get("/api/oauth-clients", CLIENT_SCOPED),
                http.post("/api/oauth-clients", "{\"clientName\":\"" + name("gate2") + "\",\"clientType\":\"PUBLIC\"}", CLIENT_SCOPED),
                http.get("/api/oauth-clients/" + id, CLIENT_SCOPED),
                http.get("/api/oauth-clients/by-client-id/" + clientId, CLIENT_SCOPED),
                http.put("/api/oauth-clients/" + id, "{\"clientName\":\"X\"}", CLIENT_SCOPED),
                http.post("/api/oauth-clients/" + id + "/activate", null, CLIENT_SCOPED),
                http.post("/api/oauth-clients/" + id + "/deactivate", null, CLIENT_SCOPED),
                http.post("/api/oauth-clients/" + id + "/rotate-secret", null, CLIENT_SCOPED),
                http.post("/api/oauth-clients/" + id + "/regenerate-secret", null, CLIENT_SCOPED),
                http.post("/api/oauth-clients/" + id + "/revoke-previous-secret", null, CLIENT_SCOPED),
                http.delete("/api/oauth-clients/" + id, CLIENT_SCOPED))) {
            assertThat(r.statusCode()).as(r.body()).isEqualTo(403);
            assertThat(r.body()).isEqualTo("{\"error\":\"ANCHOR_REQUIRED\",\"message\":\"anchor scope required\"}\n");
        }

        var anonymous = http.get("/api/oauth-clients/" + id);
        assertThat(anonymous.statusCode()).isEqualTo(403);
        assertThat(json(anonymous).get("error").asText()).isEqualTo("UNAUTHENTICATED");

        // Nothing the CLIENT_SCOPED principal attempted actually took effect.
        var stillThere = json(http.get("/api/oauth-clients/" + id, ANCHOR));
        assertThat(stillThere.get("clientName").asText()).isEqualTo(name("gate"));
        assertThat(stillThere.get("active").asBoolean()).isTrue();
    }
}
