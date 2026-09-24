package io.flowcatalyst.server;

import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Pins [Main#needsDb] / [Main#needsMigrateAndSeed]: which deployments get a
/// database pool at all, and which of those additionally run Flyway +
/// the seeder. The gap this closes — `FC_PLATFORM_ENABLED=false
/// FC_ROUTER_ENABLED=true FC_DEFAULT_BROKER=postgres` never built a pool, so
/// the router's own Postgres broker (`docs/spec/router.md` §8.4) started
/// with no consumer (`QueueFactory.createPostgres` logged "needs postgres
/// but no database is configured" and returned empty).
class MainTest {

    private static Env env(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return Env.load(m);
    }

    // ── cookie-hardening §1: FC_AUTH_ALLOW_TEST_HEADERS refused outside dev mode ──

    @Test
    void testHeadersOnWithoutDevModeMustRefuse() {
        var e = env("FC_AUTH_ALLOW_TEST_HEADERS", "true");
        assertThat(Main.mustRefuseTestHeaders(e))
                .as("an authentication bypass with no dev-mode guard must refuse to start")
                .isTrue();
    }

    @Test
    void testHeadersOnWithDevModeIsAllowed() {
        var e = env("FC_AUTH_ALLOW_TEST_HEADERS", "true", "FLOWCATALYST_DEV_MODE", "true");
        assertThat(Main.mustRefuseTestHeaders(e))
                .as("dev mode is the ONE escape hatch — fcdev sets both")
                .isFalse();
    }

    @Test
    void testHeadersOffNeverRefusesRegardlessOfDevMode() {
        assertThat(Main.mustRefuseTestHeaders(env())).isFalse();
        assertThat(Main.mustRefuseTestHeaders(env("FLOWCATALYST_DEV_MODE", "true"))).isFalse();
    }

    @Test
    void platformAloneNeedsDbAndMigrateAndSeed() {
        var e = env(); // FC_PLATFORM_ENABLED defaults true
        assertThat(Main.needsDb(e)).isTrue();
        assertThat(Main.needsMigrateAndSeed(e)).isTrue();
    }

    @Test
    void workerSubsystemsAloneNeedDbAndMigrateAndSeed() {
        for (String flag : new String[] {
                "FC_SCHEDULER_ENABLED", "FC_STREAM_PROCESSOR_ENABLED",
                "FC_SCHEDULED_JOB_ENABLED", "FC_OUTBOX_ENABLED"}) {
            var e = env("FC_PLATFORM_ENABLED", "false", flag, "true");
            assertThat(Main.needsDb(e)).as(flag).isTrue();
            assertThat(Main.needsMigrateAndSeed(e)).as(flag).isTrue();
        }
    }

    /// **Superseded by R4** (`docs/go-mirror/2026-09-12-dispatch-rulings.md`):
    /// this used to be the gap-closing assertion — router-only + the
    /// built-in Postgres broker needed a pool through `Main`. R4 removed
    /// `Router#usesDefaultPostgresBroker` along with the fixed single-queue
    /// branch it audited: a router-only instance no longer opens a pool
    /// through `Main` just because `FC_DEFAULT_BROKER=postgres` is set — a
    /// config-URL-supplied Postgres queue opens its own pool from its own URI
    /// instead (`QueueFactory#createPostgres`). This test now pins the
    /// opposite of what it originally asserted; kept under its original name
    /// with the ruling noted, not deleted, per CONVENTIONS' preference for
    /// recording a superseded rule rather than silently erasing it.
    @Test
    void routerOnlyWithPostgresDefaultBrokerNeedsDbButNotMigrateOrSeed() {
        var e = env("FC_PLATFORM_ENABLED", "false", "FC_ROUTER_ENABLED", "true",
                "FC_DEFAULT_BROKER", "postgres");
        assertThat(Main.needsDb(e)).as("R4: the router itself never causes Main to open a pool any more").isFalse();
        // load-bearing: queue_messages is created by PostgresQueue.initSchema,
        // not Flyway — a router-only instance must never run platform
        // migrations or the seeder against a database that may host nothing
        // else.
        assertThat(Main.needsMigrateAndSeed(e)).isFalse();
    }

    @Test
    void routerOnlyWithoutPostgresDefaultBrokerNeedsNoDb() {
        var e = env("FC_PLATFORM_ENABLED", "false", "FC_ROUTER_ENABLED", "true");
        assertThat(Main.needsDb(e)).isFalse();
        assertThat(Main.needsMigrateAndSeed(e)).isFalse();
    }

    @Test
    void routerOnlyWithNonPostgresDefaultBrokerNeedsNoDb() {
        var e = env("FC_PLATFORM_ENABLED", "false", "FC_ROUTER_ENABLED", "true",
                "FC_DEFAULT_BROKER", "sqs");
        assertThat(Main.needsDb(e)).isFalse();
    }

    /// **Superseded by R4**: before R4 removed `Router#usesDefaultPostgresBroker`,
    /// this pinned that a config URL wins over the default broker for
    /// `needsDb`'s own precedence. Now the router never factors into
    /// `needsDb` at all — this still holds, but for a simpler reason.
    @Test
    void routerOnlyWithConfigUrlAndPostgresBrokerNeedsNoDb() {
        var e = env("FC_PLATFORM_ENABLED", "false", "FC_ROUTER_ENABLED", "true",
                "FC_DEFAULT_BROKER", "postgres", "FLOWCATALYST_CONFIG_URL", "http://config.local/router");
        assertThat(Main.needsDb(e)).isFalse();
    }

    @Test
    void postgresDefaultBrokerWithoutRouterEnabledNeedsNoDb() {
        var e = env("FC_PLATFORM_ENABLED", "false", "FC_DEFAULT_BROKER", "postgres");
        assertThat(Main.needsDb(e)).isFalse();
    }

    @Test
    void mcpAloneNeedsNoDb() {
        var e = env("FC_PLATFORM_ENABLED", "false", "FC_MCP_ENABLED", "true");
        assertThat(Main.needsDb(e)).isFalse();
        assertThat(Main.needsMigrateAndSeed(e)).isFalse();
    }

    @Test
    void platformPlusRouterPostgresBrokerStillMigratesAndSeeds() {
        var e = env("FC_ROUTER_ENABLED", "true", "FC_DEFAULT_BROKER", "postgres");
        assertThat(Main.needsDb(e)).isTrue();
        assertThat(Main.needsMigrateAndSeed(e)).isTrue();
    }

    /// `FC_EXIT_AFTER_START` (docs/spec/jvm-memory.md §1a, the Dockerfile's
    /// AOT training run): [Main#exitAfterStart] is the whole post-boot
    /// effect `Main#main` runs on that path — this pins that it actually
    /// tears the server down, not merely that the log line fires and the
    /// method returns. A mutant that no-ops `running.stop()` (logs, then
    /// falls straight through) would still pass a test that only checked the
    /// method returned; only a live socket to the API listener — connectable
    /// before the call, refused after — proves the listener actually stopped
    /// accepting connections.
    @Test
    void exitAfterStartActuallyStopsTheServer() throws Exception {
        var e = env("FC_EXIT_AFTER_START", "true", "FC_PLATFORM_ENABLED", "false",
                "FC_ROUTER_ENABLED", "true", "FC_API_PORT", "0", "FC_METRICS_PORT", "0");
        var running = new Server(e, Server.Mode.routerOnly(), Server.Spa.none(), new PrometheusRegistry()).start();
        int apiPort = running.apiPort();
        // Sanity: the listener really is up before exitAfterStart runs, so the
        // refusal below is caused by the stop, not by the port never having
        // been open in the first place.
        new Socket("127.0.0.1", apiPort).close();

        Main.exitAfterStart(e, running, List.of(), null);

        assertThat(ourServerStillAnswersOn(apiPort))
                .as("the API listener must actually stop accepting connections, not just log that it would")
                .isFalse();
    }

    /// "Connection refused" is the expected outcome, but it is not the only
    /// honest one: the port was ephemeral, and the moment our listener lets go
    /// of it the OS may hand it to anything else that binds port 0 — another
    /// process, a listener some earlier test class leaked. A bare
    /// connect-must-fail assertion fails then, for a reason that has nothing to
    /// do with `exitAfterStart` (it did, intermittently, under concurrent
    /// builds). So: refused ⇒ stopped; something answers ⇒ it must not be OUR
    /// `/health`.
    private static boolean ourServerStillAnswersOn(int port) throws InterruptedException {
        var client = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build();
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/health"))
                .timeout(java.time.Duration.ofSeconds(2)).GET().build();
        try {
            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            return response.body().contains("\"status\"");
        } catch (IOException e) {
            return false;
        }
    }
}
