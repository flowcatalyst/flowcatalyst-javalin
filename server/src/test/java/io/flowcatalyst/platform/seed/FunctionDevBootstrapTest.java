package io.flowcatalyst.platform.seed;

import io.flowcatalyst.platform.shared.database.Migrator;
import io.flowcatalyst.platform.shared.database.Pools;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.server.Env;
import io.flowcatalyst.server.Server;
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

/// [FunctionDevBootstrap] mints the two fcdev clients the developer surface
/// spec (§1) describes: `fcdev-fn-host` (role `platform:function-host`) and
/// `fcdev-fn-cli` (roles `platform:function-publisher` +
/// `platform:messaging-admin`). This drives the REAL workflow — bootstrap,
/// mint a client-credentials token via `/oauth/token`, call a gated route —
/// never a shortcut through the auth internals, same discipline as
/// `RouterConfigEndpointTest`: the assertion that actually pins the role
/// grant is a real 200/403 on a route reached through a real minted token.
@SuppressWarnings("deprecation")
class FunctionDevBootstrapTest {

    /// Its OWN database: the seeder creates the `platform` application, and on the
    /// shared `TestPg` database that row broke `DeveloperBffTest` (which inserts it
    /// itself) whenever this class happened to run first.
    private static DataSource ds;
    private static Server.Running running;
    private static Encryption encryption;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll
    static void start() {
        ds = TestPg.newDatabase("fn_dev_bootstrap");
        Migrator.migrate(ds);
        new Seeder(ds).run();
        String appKey = Encryption.generateKey();
        encryption = Encryption.fromKeys(appKey, "").orElseThrow();
        Env env = Env.load(Map.of(
                "FC_API_PORT", "0",
                "FC_METRICS_PORT", "0",
                "FC_PLATFORM_ENABLED", "true",
                "FC_AUTH_ALLOW_TEST_HEADERS", "true",
                "FLOWCATALYST_APP_KEY", appKey));
        running = new Server(env, new Server.Mode.Platform(Pools.ofSingle(ds)),
                Server.Spa.none(), new PrometheusRegistry()).start();
    }

    @AfterAll
    static void stop() {
        if (running != null) running.stop();
    }

    private static String mintToken(FunctionDevBootstrap.Credentials c) throws Exception {
        String form = "grant_type=client_credentials"
                + "&client_id=" + URLEncoder.encode(c.clientId(), StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(c.secret(), StandardCharsets.UTF_8);
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + running.apiPort() + "/oauth/token"))
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build();
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json(response).get("access_token").asText();
    }

    private static HttpResponse<String> get(String path, String token) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + running.apiPort() + path)).GET();
        if (token != null) b.header("Authorization", "Bearer " + token);
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> r) {
        return Json.MAPPER.readTree(r.body());
    }

    /// The whole point of the unit: a token minted for `fcdev-fn-host`
    /// reaches the host's own control-plane route, and a token minted for
    /// `fcdev-fn-cli` is refused there — pinning that the ROLE (not merely
    /// "some token exists") is what the gate keys on. Mutant: swap the two
    /// clients' roles, or grant the host client `function-publisher`/
    /// `messaging-admin` too — this fails on the host-client assertion.
    @Test
    void theHostClientReachesTheControlPlaneAndTheCliClientDoesNot() throws Exception {
        var creds = FunctionDevBootstrap.bootstrap(ds, encryption);

        String hostToken = mintToken(creds.host());
        String cliToken = mintToken(creds.cli());

        var hostReachesControl = get("/control/functions/desired-state?pool=default", hostToken);
        assertThat(hostReachesControl.statusCode()).as(hostReachesControl.body()).isEqualTo(200);

        var cliRefusedControl = get("/control/functions/desired-state?pool=default", cliToken);
        assertThat(cliRefusedControl.statusCode()).as(cliRefusedControl.body()).isEqualTo(403);
    }

    /// The mirror image: `fcdev-fn-cli` (function-publisher + messaging-admin,
    /// both of which grant `FUNCTION_VIEW`) reaches `GET /api/functions`, and
    /// `fcdev-fn-host` (function-host only, no `FUNCTION_VIEW`) is refused
    /// there. Mutant: grant the host client `function-publisher` too, or drop
    /// `FUNCTION_VIEW` from the CLI's roles — this fails on the CLI-client
    /// assertion.
    @Test
    void theCliClientReachesTheApiAndTheHostClientDoesNot() throws Exception {
        var creds = FunctionDevBootstrap.bootstrap(ds, encryption);

        String hostToken = mintToken(creds.host());
        String cliToken = mintToken(creds.cli());

        var cliReachesApi = get("/api/functions", cliToken);
        assertThat(cliReachesApi.statusCode()).as(cliReachesApi.body()).isEqualTo(200);

        var hostRefusedApi = get("/api/functions", hostToken);
        assertThat(hostRefusedApi.statusCode()).as(hostRefusedApi.body()).isEqualTo(403);
    }

    /// Idempotency, [RouterClientBootstrap]'s own contract mirrored: a second
    /// `bootstrap` call reuses the SAME client id / principal (no duplicate
    /// rows, no error) but mints a FRESH secret each time — the old secret no
    /// longer mints a token afterwards. Mutant: an `insertInto` without
    /// `onConflict`/`onConflictDoNothing` would throw a duplicate-key error on
    /// the second call; a mutant that skipped rotating the secret would leave
    /// the OLD token minting successfully after the second bootstrap.
    @Test
    void aSecondBootstrapReusesTheSameClientButRotatesTheSecret() throws Exception {
        var first = FunctionDevBootstrap.bootstrap(ds, encryption);
        var second = FunctionDevBootstrap.bootstrap(ds, encryption);

        assertThat(second.host().clientId()).isEqualTo(first.host().clientId());
        assertThat(second.cli().clientId()).isEqualTo(first.cli().clientId());
        assertThat(second.host().secret()).isNotEqualTo(first.host().secret());
        assertThat(second.cli().secret()).isNotEqualTo(first.cli().secret());

        // The old secret is now dead — minting with it must fail, not merely
        // "the new one also works" (which a mutant that stored BOTH refs, or
        // never actually replaced the stored ref, would still pass).
        String oldSecretForm = "grant_type=client_credentials"
                + "&client_id=" + URLEncoder.encode(first.host().clientId(), StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(first.host().secret(), StandardCharsets.UTF_8);
        var oldTokenAttempt = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + running.apiPort() + "/oauth/token"))
                        .POST(HttpRequest.BodyPublishers.ofString(oldSecretForm))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(oldTokenAttempt.statusCode()).as(oldTokenAttempt.body()).isNotEqualTo(200);

        // The new secret mints and still reaches the same gated route.
        String newToken = mintToken(second.host());
        var reached = get("/control/functions/desired-state?pool=default", newToken);
        assertThat(reached.statusCode()).as(reached.body()).isEqualTo(200);
    }

    /// No credential at all: `/control/functions/*` is 401, matching
    /// `FunctionControlApi`'s own `gate` (a missing bearer is 401, not the
    /// platform's usual 403 `UNAUTHENTICATED`).
    @Test
    void noTokenIs401OnTheControlPlane() throws Exception {
        var response = get("/control/functions/desired-state?pool=default", null);
        assertThat(response.statusCode()).isEqualTo(401);
    }
}
