package io.flowcatalyst.fcdev;

import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.router.api.Wire;
import io.flowcatalyst.router.config.RouterConfig;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// The end-to-end dev path (`docs/spec/deployed-dispatch.md` §3, unit D part
/// 5): `fcdev` with the router ON serves its own router-config document and
/// the router consumes it — the same code path prod runs, differing only in
/// the queue TYPE the document names (Postgres here, SQS there). Before this
/// unit, `fcdev` never set `FLOWCATALYST_CONFIG_URL` at all, so this whole
/// path was untested by construction.
///
/// A fixed (pre-reserved) `--metrics-port` is required: R3 put the served
/// document on the platform's INTERNAL listener, so
/// `StartCommand#devEnv`'s default is built from `FC_METRICS_PORT`, which
/// must be known before `Server#start` runs (`--metrics-port 0`'s ephemeral
/// port is not knowable that early — see `StartOptionsTest`).
///
/// **The router's OWN first config poll, during `Server#start`, cannot
/// possibly succeed here**: `Router#start` runs (and, with standby disabled,
/// synchronously applies its first fetch) strictly BEFORE `Server#start`
/// binds the metrics listener the config URL points at, so every one of
/// `HttpConfigSource`'s 12 retries hits a refused connection. This test does
/// not rely on that first attempt, or on the 300s periodic poll that would
/// eventually retry it — it forces a second attempt through the router's own
/// `POST /config/reload` admin route once boot has completed and both
/// listeners are definitely up, exactly as an operator or a dashboard reload
/// button would.
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
class DevDispatchRouterConfigIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private Path root;
    private int metricsPort;
    private StartCommand.Started started;

    @BeforeAll
    void boot() throws Exception {
        io.flowcatalyst.server.Logging.init(Map.of("FC_LOG_LEVEL", "warn", "FC_LOG_FORMAT", "text"));
        root = Files.createTempDirectory("fcdev-dispatch-it");
        var dataPath = root.resolve("flowcatalyst/embedded-pg");
        var pidFile = root.resolve("flowcatalyst/fcdev.pid");
        var cache = root.resolve("cache");
        metricsPort = freePort();
        var env = DevEnv.of(Map.of(
                "FC_EMBEDDED_DB_PATH", dataPath.toString(),
                "FC_DEV_PID_FILE", pidFile.toString(),
                "XDG_CACHE_HOME", cache.toString()));
        var sub = new StartCommand.Sub(env);
        new CommandLine(sub, new EnvFactory(env)).parseArgs(
                "--api-port", "0", "--metrics-port", String.valueOf(metricsPort), "--embedded-db-port", "0",
                "--router=true", "--scheduler=false", "--stream=false", "--scheduled-job=false", "--outbox=false");
        var paths = new DevPaths(root, cache);
        try {
            // A fresh registry, not PrometheusRegistry.defaultRegistry:
            // StartIntegrationTest's own StartCommand-booted Server shares
            // this Surefire fork and never deregisters its process-global
            // collectors (Server.Running#stop doesn't), so two Servers on
            // the JVM-wide default would collide on registration.
            started = new StartCommand(env, paths, sub.opts, new PrometheusRegistry()).launch();
        } catch (Exception | ExceptionInInitializerError e) {
            LoggerFactory.getLogger(DevDispatchRouterConfigIntegrationTest.class)
                    .warn("embedded PostgreSQL could not start here; skipping", e);
            Assumptions.abort("embedded PostgreSQL cannot start in this environment: " + e);
        }
    }

    @AfterAll
    void shutdown() throws Exception {
        if (started != null) started.close();
        if (root != null) EmbeddedPg.deleteTree(root);
    }

    /// **Part 1 of the two facts this test pins.** The document
    /// `fcdev`'s own platform serves on its internal listener already lists
    /// the always-present `platform-DEFAULT` queue (every tenant set
    /// unconditionally includes the platform tenant,
    /// `RouterConfigDocumentBuilder`), addressed as a Postgres row-queue on
    /// the SAME database URL the embedded Postgres was started with — never
    /// an SQS URL, `.fifo` name, or anything shaped like the prod document.
    /// Mutant this pins: `devEnv` failing to default `FLOWCATALYST_CONFIG_URL`
    /// at all (the router-config document would never even be reachable
    /// through this test's own assertions below) or defaulting it to the API
    /// port instead of the metrics one (R3) — either breaks this fetch or
    /// points it at a route that does not exist there.
    @Test
    void thePlatformServesItsOwnRouterConfigDocumentNamingAPostgresQueue() throws Exception {
        var response = get("http://localhost:" + metricsPort + "/api/dispatch/router-config");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);

        RouterConfig config = Json.read(response.body(), RouterConfig.class);
        var platformDefault = config.queues().stream()
                .filter(q -> "platform-DEFAULT".equals(q.queueName()))
                .findFirst();
        assertThat(platformDefault).as("the always-present platform tenant's DEFAULT queue").isPresent();
        assertThat(platformDefault.get().queueUri())
                .as("Postgres-backed in dev — never SQS-shaped")
                .startsWith("postgres://")
                .doesNotContain(".fifo");
    }

    /// **Part 2: the router actually picks it up.** `/config/reload` re-runs
    /// `RouterServer#applyConfiguration` synchronously and reports what
    /// changed — this is the FIRST attempt that can possibly succeed (see the
    /// class doc), so `consumersStarted` must count at least the
    /// `platform-DEFAULT` queue's consumer starting for the very first time.
    /// Mutant this pins: the router silently never adopting a config-URL
    /// source at all (`reloaded` would stay `false`, `pools`/`consumersStarted`
    /// would stay `0` — `AdminRoutes#configReload`'s own "not running/no
    /// source" degrade shape) — a counter that must change, not an absence
    /// that would hold either way.
    @Test
    void theRouterAdoptsTheServedConfigOnAForcedReload() throws Exception {
        var reload = post("http://localhost:" + started.apiPort() + "/router/config/reload");
        assertThat(reload.statusCode()).as(reload.body()).isEqualTo(200);

        Wire.ConfigReloadResponse result = Json.read(reload.body(), Wire.ConfigReloadResponse.class);
        assertThat(result.reloaded()).as("the router adopted a config-URL source, not the not-running degrade shape").isTrue();
        assertThat(result.consumersStarted())
                .as("at least the platform-DEFAULT queue's consumer started for the first time")
                .isGreaterThanOrEqualTo(1);
        assertThat(result.failedQueues()).as("the Postgres-backed queue this document names must build cleanly").isEmpty();
    }

    private static HttpResponse<String> get(String url) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String url) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /// Same idiom as `StartIntegrationTest`'s free-port picks: released
    /// before returning, good enough for a port that gets bound milliseconds
    /// later.
    private static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
