package io.flowcatalyst.server;

import io.flowcatalyst.platform.seed.Seeder;
import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.platform.shared.database.Migrator;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.testpg.TestPg;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// The attack scenarios of `docs/spec/security-fixes-2026-09-24.md` S1, driven
/// through the REAL operator workflow on a real server: create an
/// application, provision its service account, mint a client-credentials
/// token for it, and then act AS that application's SDK credential — which
/// holds only the seeded `platform:application-service` role and, having no
/// client, is anchor-reach by construction (`service-account-reach.md`). The
/// anchor tier is reach, never authority: every refusal below is the
/// permission gate, not the tier.
@SuppressWarnings("deprecation")
class AuthorityGatesEndToEndTest {

    private static Server.Running running;
    private static final HttpClient http = HttpClient.newHttpClient();

    /// Fixture setup only (create the application, provision, read back) —
    /// never the principal under test.
    private static final String[] OPERATOR = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:*:*:*"};

    @BeforeAll
    static void start() {
        // Its own database: the seeder writes the code-unique `platform`
        // application and built-in roles (see RouterConfigEndpointTest).
        DataSource ds = TestPg.newDatabase("authority_gates_e2e_test");
        Migrator.migrate(ds);
        Env env = Env.load(Map.of(
                "FC_API_PORT", "0",
                "FC_METRICS_PORT", "0",
                "FC_PLATFORM_ENABLED", "true",
                "FC_DISPATCH_QUEUE_TYPE", "SQS",
                "FC_DISPATCH_QUEUE_PREFIX", "FC-authgates",
                "FC_DISPATCH_QUEUE_URL", "https://sqs.us-east-1.amazonaws.com/123456789012/fc-dispatch",
                "FC_AUTH_ALLOW_TEST_HEADERS", "true",
                "FLOWCATALYST_APP_KEY", Encryption.generateKey()));
        new Seeder(ds).run();
        running = new Server(env, new Server.Mode.Platform(io.flowcatalyst.platform.shared.database.Pools.ofSingle(ds)),
                Server.Spa.none(), new PrometheusRegistry()).start();
    }

    @AfterAll
    static void stop() {
        running.stop();
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────

    private static HttpResponse<String> send(String method, String path, String body, String... headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + running.apiPort() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> r) {
        try {
            return Json.MAPPER.readTree(r.body());
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + r.body(), e);
        }
    }

    private static String[] bearer(String token) {
        return new String[] {"Authorization", "Bearer " + token};
    }

    /// An application with a provisioned service account: its code, the
    /// account's id, and a minted client-credentials token.
    private record AppCredential(String code, String serviceAccountId, String token) {
    }

    private static AppCredential provision(String code) throws Exception {
        var created = send("POST", "/api/applications", "{\"code\":\"" + code + "\",\"name\":\"" + code + "\"}", OPERATOR);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String appId = json(created).get("id").asText();
        var provisioned = send("POST", "/api/applications/" + appId + "/provision-service-account", null, OPERATOR);
        assertThat(provisioned.statusCode()).as(provisioned.body()).isEqualTo(201);
        var oauth = json(provisioned).get("serviceAccount").get("oauthClient");
        String form = "grant_type=client_credentials"
                + "&client_id=" + URLEncoder.encode(oauth.get("clientId").asText(), StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(oauth.get("clientSecret").asText(), StandardCharsets.UTF_8);
        var tokenResponse = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + running.apiPort() + "/oauth/token"))
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .header("Content-Type", "application/x-www-form-urlencoded").build(), HttpResponse.BodyHandlers.ofString());
        assertThat(tokenResponse.statusCode()).as(tokenResponse.body()).isEqualTo(200);
        var sa = send("GET", "/api/service-accounts/code/app:" + code, null, OPERATOR);
        assertThat(sa.statusCode()).as(sa.body()).isEqualTo(200);
        return new AppCredential(code, json(sa).get("id").asText(), json(tokenResponse).get("access_token").asText());
    }

    private static java.util.List<String> rolesOf(String serviceAccountId) throws Exception {
        var r = send("GET", "/api/service-accounts/" + serviceAccountId + "/roles", null, OPERATOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json(r).get("roles").valueStream().map(n -> n.get("roleName").asText()).toList();
    }

    // ── S1.2 ─────────────────────────────────────────────────────────────

    /// Pinned (S1.2): an application's provisioned service account — only
    /// `platform:application-service` — cannot assign itself
    /// `platform:super-admin`, and cannot mint a token for itself after
    /// trying. Both answer 403 `PERMISSION_REQUIRED` (not the tier's
    /// `ANCHOR_REQUIRED`: the account IS anchor-reach), and the observable
    /// effect is that its role set is still exactly its one seeded role.
    @Test
    void aProvisionedServiceAccountCannotGrantItselfSuperAdminNorMintItsOwnToken() throws Exception {
        var app = provision("s12selfgrant");
        var before = rolesOf(app.serviceAccountId());
        assertThat(before).containsExactly("platform:application-service");

        var grant = send("PUT", "/api/service-accounts/" + app.serviceAccountId() + "/roles",
                "{\"roles\":[\"platform:super-admin\"]}", bearer(app.token()));
        assertThat(grant.statusCode()).as(grant.body()).isEqualTo(403);
        assertThat(json(grant).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");

        var mint = send("POST", "/api/service-accounts/" + app.serviceAccountId() + "/token", null, bearer(app.token()));
        assertThat(mint.statusCode()).as(mint.body()).isEqualTo(403);
        assertThat(json(mint).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");

        assertThat(rolesOf(app.serviceAccountId())).as("no role landed").isEqualTo(before);
    }

    // ── S1.5 ─────────────────────────────────────────────────────────────

    /// Pinned (S1.5): the same SDK credential syncing `myapp:admin` with
    /// `platform:*:*:*` is refused 400 `PERMISSION_OUTSIDE_APPLICATION` and
    /// no such role exists afterwards; a role holding its own application's
    /// codes still syncs (200) — the gate is confinement, not a blanket stop.
    @Test
    void anSdkCredentialCannotSyncARoleCarryingPlatformAuthority() throws Exception {
        var app = provision("s15rolesync");

        var attack = send("POST", "/api/applications/" + app.code() + "/roles/sync",
                "{\"roles\":[{\"name\":\"admin\",\"permissions\":[\"platform:*:*:*\"]}]}", bearer(app.token()));
        assertThat(attack.statusCode()).as(attack.body()).isEqualTo(400);
        assertThat(json(attack).get("error").asText()).isEqualTo("PERMISSION_OUTSIDE_APPLICATION");
        assertThat(send("GET", "/bff/roles/" + app.code() + ":admin", null, OPERATOR).statusCode())
                .as("the role was not created").isEqualTo(404);

        var confined = send("POST", "/api/applications/" + app.code() + "/roles/sync",
                "{\"roles\":[{\"name\":\"admin\",\"permissions\":[\"" + app.code() + ":orders:order:view\"]}]}", bearer(app.token()));
        assertThat(confined.statusCode()).as(confined.body()).isEqualTo(200);
        var stored = send("GET", "/bff/roles/" + app.code() + ":admin", null, OPERATOR);
        assertThat(stored.statusCode()).isEqualTo(200);
        assertThat(json(stored).get("permissions").valueStream().map(JsonNode::asText).toList())
                .containsExactly(app.code() + ":orders:order:view");
    }

    // ── S1.4 ─────────────────────────────────────────────────────────────

    /// Pinned (S1.4): an OAuth client whose `principalId` names a USER (here
    /// an anchor administrator) would be a `client_credentials` credential for
    /// that user — refused 400 `INVALID_SERVICE_PRINCIPAL`, and no client row
    /// carries that principal afterwards.
    @Test
    void anOAuthClientCannotBeBoundToAUserPrincipal() throws Exception {
        var admin = send("POST", "/api/principals",
                "{\"email\":\"s14admin@example.test\",\"scope\":\"ANCHOR\"}", OPERATOR);
        assertThat(admin.statusCode()).as(admin.body()).isEqualTo(201);
        String adminId = json(admin).get("id").asText();

        var client = send("POST", "/api/oauth-clients", "{\"clientName\":\"s14-bound\",\"clientType\":\"CONFIDENTIAL\","
                + "\"grantTypes\":[\"client_credentials\"],\"principalId\":\"" + adminId + "\"}", OPERATOR);
        assertThat(client.statusCode()).as(client.body()).isEqualTo(400);
        assertThat(json(client).get("error").asText()).isEqualTo("INVALID_SERVICE_PRINCIPAL");
        var all = send("GET", "/api/oauth-clients", null, OPERATOR);
        assertThat(all.body()).doesNotContain(adminId);
    }
}
