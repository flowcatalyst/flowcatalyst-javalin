package io.flowcatalyst.server;

import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.platform.shared.database.Migrator;
import io.flowcatalyst.platform.shared.database.Pools;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.testpg.TestPg;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// The COMPOSITION ROOT, not the operation: `ClaimFunctionDomain` auto-verifies
/// a `.localhost` hostname only in dev mode (spec `function-public-routes.md`
/// §1), and its own tests pass the flag in by hand — so a `Platform` that
/// handed it a constant `true` passed every one of them, and every deployment
/// would have verified `anything.localhost` without DNS. Here the real
/// [Server] is booted both ways.
class FunctionDomainDevModeWiringTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static String claimState(boolean devMode) throws Exception {
        DataSource ds = TestPg.newDatabase("fn_domain_wiring_" + (devMode ? "dev" : "prod"));
        Migrator.migrate(ds);
        Map<String, String> vars = new HashMap<>(Map.of(
                "FC_API_PORT", "0",
                "FC_METRICS_PORT", "0",
                "FC_PLATFORM_ENABLED", "true",
                "FC_AUTH_ALLOW_TEST_HEADERS", "true"));
        if (devMode) {
            vars.put("FLOWCATALYST_DEV_MODE", "true");
        }
        Server.Running running = new Server(Env.load(vars), new Server.Mode.Platform(Pools.ofSingle(ds)),
                Server.Spa.none(), new PrometheusRegistry()).start();
        try {
            String host = "w" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + ".localhost";
            HttpResponse<String> response = HTTP.send(HttpRequest
                    .newBuilder(URI.create("http://localhost:" + running.apiPort() + "/api/function-domains"))
                    .header("Content-Type", "application/json")
                    .header(Authenticator.TEST_PRINCIPAL, "usr_wiring")
                    .header(Authenticator.TEST_SCOPE, "ANCHOR")
                    .header(Authenticator.TEST_PERMISSIONS, "platform:*:*:*")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"hostname\":\"" + host + "\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
            JsonNode body = Json.MAPPER.readTree(response.body());
            return body.path("verification").path("state").asString();
        } finally {
            running.stop();
        }
    }

    @Test
    void aLocalhostDomainIsNotAutoVerifiedUnlessTheServerRunsInDevMode() throws Exception {
        assertThat(claimState(false)).as("mutant: Platform passes a constant true for dev mode").isEqualTo("PENDING");
    }

    @Test
    void aLocalhostDomainIsAutoVerifiedWhenTheServerRunsInDevMode() throws Exception {
        assertThat(claimState(true)).as("mutant: Platform passes a constant false for dev mode").isEqualTo("VERIFIED");
    }
}
