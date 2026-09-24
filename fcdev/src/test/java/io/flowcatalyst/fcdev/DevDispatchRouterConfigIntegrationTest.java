package io.flowcatalyst.fcdev;

import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.router.api.Wire;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/// The end-to-end dev path (`docs/spec/router-config-auth.md`, R3′): `fcdev`
/// with the router ON bootstraps its own `fcdev-router` OAuth client
/// ([DevBootstrap#bootstrapRouterCredentials]), points the router at its own
/// API listener for both the token and the document, and the router
/// consumes what the platform serves — the same code path prod runs,
/// differing only in the queue TYPE the document names (Postgres here, SQS
/// there) and in who provisioned the client.
///
/// Everything happens on `fcdev start` itself: the election starts only
/// after both listeners are bound (spec §5a), so the router's FIRST fetch
/// mints a token, GETs the document and starts the consumers before this
/// class's tests even run. That is what the 3 s window below pins — a first
/// attempt that failed (unbound listener, a bad credential, a token without
/// `platform:router`) costs a 5 s retry and cannot meet it.
///
/// Mutants that must fail this: `bootstrapRouterCredentials` putting a
/// secret on the env other than the one it stored (the token mint is
/// refused); `devEnv` defaulting the config URL to the metrics port again
/// (404); the election started before the API bind (connection refused on
/// the first attempt).
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
class DevDispatchRouterConfigIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private Path root;
    private StartCommand.Started started;
    private Instant launched;

    @BeforeAll
    void boot() throws Exception {
        io.flowcatalyst.server.Logging.init(Map.of("FC_LOG_LEVEL", "warn", "FC_LOG_FORMAT", "text"));
        root = Files.createTempDirectory("fcdev-dispatch-it");
        var dataPath = root.resolve("flowcatalyst/embedded-pg");
        var pidFile = root.resolve("flowcatalyst/fcdev.pid");
        var cache = root.resolve("cache");
        var env = DevEnv.of(Map.of(
                "FC_EMBEDDED_DB_PATH", dataPath.toString(),
                "FC_DEV_PID_FILE", pidFile.toString(),
                "XDG_CACHE_HOME", cache.toString()));
        var sub = new StartCommand.Sub(env);
        // A pre-reserved API port: R3′ builds the config URL and the token
        // endpoint from it, and `--api-port 0`'s ephemeral port is not
        // knowable before `Server#start` binds (fcdev then synthesises no
        // URL and bootstraps no credentials — `StartOptionsTest`,
        // `DevBootstrapRouterCredentialsTest`).
        int apiPort = TestPorts.belowEphemeralRange();
        new CommandLine(sub, new EnvFactory(env)).parseArgs(
                "--api-port", String.valueOf(apiPort), "--metrics-port", "0", "--embedded-db-port", "0",
                "--router=true", "--scheduler=false", "--stream=false", "--scheduled-job=false", "--outbox=false");
        var paths = new DevPaths(root, cache);
        try {
            // A fresh registry, not PrometheusRegistry.defaultRegistry:
            // StartIntegrationTest's own StartCommand-booted Server shares
            // this Surefire fork and never deregisters its process-global
            // collectors (Server.Running#stop doesn't), so two Servers on
            // the JVM-wide default would collide on registration.
            started = new StartCommand(env, paths, sub.opts, new PrometheusRegistry()).launch();
            launched = Instant.now();
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

    /// The router adopted fcdev's own served document on its first attempt:
    /// a consumer for the always-present `platform-DEFAULT` queue
    /// (`RouterConfigDocumentBuilder`, every tenant set includes the platform
    /// tenant) is running within 3 s of `launch()` returning, and it is a
    /// Postgres-backed consumer — the document named a `postgres://` queue on
    /// the embedded database, never anything SQS-shaped.
    @Test
    void theRouterConsumesThePlatformDefaultQueueFromItsOwnServedDocument() {
        var manager = started.running().router().manager();
        await(() -> manager.consumerNames().contains("platform-DEFAULT"),
                Duration.ofSeconds(3).minus(Duration.between(launched, Instant.now())));

        var consumer = manager.activeConsumer("platform-DEFAULT");
        assertThat(consumer).as("an active consumer on the platform's DEFAULT queue").isPresent();
        assertThat(consumer.get().getClass().getPackageName())
                .as("Postgres-backed in dev — never SQS-shaped")
                .endsWith(".postgres");
    }

    /// A forced reload (`POST /config/reload`, what the dashboard button
    /// does) still works against the authenticated document and reports the
    /// truth: the source answered (`reloaded`), nothing failed to build, and
    /// nothing NEW started, because the boot already started everything the
    /// document names. Read together with the test above: `0` here is only
    /// meaningful because the consumer is proven to exist already.
    @Test
    void aForcedReloadReadsTheSameDocumentAndFindsNothingNewToStart() throws Exception {
        var manager = started.running().router().manager();
        await(() -> manager.consumerNames().contains("platform-DEFAULT"), Duration.ofSeconds(3));

        var reload = post("http://localhost:" + started.apiPort() + "/router/config/reload");
        assertThat(reload.statusCode()).as(reload.body()).isEqualTo(200);

        Wire.ConfigReloadResponse result = Json.read(reload.body(), Wire.ConfigReloadResponse.class);
        assertThat(result.reloaded()).as("the source answered and the config applied").isTrue();
        assertThat(result.failedQueues()).as("the Postgres-backed queue this document names must build cleanly").isEmpty();
        assertThat(result.consumersStarted()).as("everything the document names was already running").isZero();
        assertThat(manager.consumerNames()).contains("platform-DEFAULT");
    }

    private static HttpResponse<String> post(String url) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void await(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + Math.max(0, timeout.toNanos());
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        assertThat(condition.getAsBoolean()).as("condition met within " + timeout).isTrue();
    }
}
