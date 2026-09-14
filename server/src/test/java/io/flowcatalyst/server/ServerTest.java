package io.flowcatalyst.server;

import io.flowcatalyst.testpg.TestPg;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Boots the real [Server] (platform enabled, ephemeral ports) against the
/// migrated embedded Postgres and checks the listener surface Go exposes:
/// `/health` on both ports, `/ready`, `/metrics`, the spec routes, the SPA
/// fallback, and the error envelope for an unknown API route.
class ServerTest {

    private static Server.Running running;
    private static PrometheusRegistry registry;
    private static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void start() {
        Env env = Env.load(Map.of(
                "FC_API_PORT", "0",
                "FC_METRICS_PORT", "0",
                "FC_PLATFORM_ENABLED", "true",
                "FC_AUTH_ALLOW_TEST_HEADERS", "true"));
        registry = new PrometheusRegistry();
        running = new Server(env, new Server.Mode.Platform(io.flowcatalyst.platform.shared.database.Pools.ofSingle(TestPg.dataSource())), Frontend.embeddedOrNone(), registry).start();
    }

    @AfterAll
    static void stop() {
        running.stop();
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthOnBothListeners() throws Exception {
        // The API listener carries the readiness checks (ruling C-Q23): the
        // login-attempt partitions the backoff store needs are present here
        // because the migrations and the purger create them.
        var api = get(running.apiPort(), "/health");
        assertThat(api.statusCode()).as(api.body()).isEqualTo(200);
        assertThat(api.body()).isEqualTo("{\"status\":\"UP\",\"version\":\"dev\",\"checks\":{\"loginAttemptPartitions\":\"ok\"}}\n");
        // The metrics listener has no checks to run.
        var metrics = get(running.metricsPort(), "/health");
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body()).isEqualTo("{\"status\":\"UP\",\"version\":\"dev\"}\n");
    }

    @Test
    void readyListsEverySubsystemToggleInGoOrder() throws Exception {
        var r = get(running.metricsPort(), "/ready");
        assertThat(r.body()).isEqualTo(
                "{\"mcp\":false,\"outbox\":false,\"platform\":true,\"router\":false,\"scheduled_job\":false,\"scheduler\":false,\"status\":\"ready\",\"stream\":false}\n");
    }

    @Test
    void metricsIsARealPrometheusEndpoint() throws Exception {
        var r = get(running.metricsPort(), "/metrics");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).contains("text/plain");
    }

    /// `docs/spec/jvm-memory.md`: the container fences the heap, so the
    /// alarm inputs — used-after-GC per pool against the ceiling, and time
    /// spent collecting — must be scrapeable, not just an application
    /// series. Pins the exact names an operator (or an alert rule) depends
    /// on, not merely that "some JVM metric" showed up. Mutant: delete the
    /// call to `JvmMetricsRegistration.register` in `Server.start` and this
    /// fails — none of these series exist without it.
    @Test
    void metricsIncludesTheJvmMemoryGcAndThreadSeries() throws Exception {
        var body = get(running.metricsPort(), "/metrics").body();
        assertThat(body).contains("jvm_memory_used_bytes{area=\"heap\"");
        assertThat(body).contains("jvm_memory_max_bytes{area=\"heap\"");
        assertThat(body).contains("jvm_memory_pool_collection_used_bytes");
        assertThat(body).contains("jvm_gc_collection_seconds");
        assertThat(body).contains("jvm_threads_current");
        assertThat(body).contains("jvm_buffer_pool_used_bytes{pool=\"direct\"");
    }

    /// `Server.start` registers the JVM collectors on whatever registry it
    /// is handed — in production that is the JVM-wide
    /// `PrometheusRegistry.defaultRegistry` (`Main.java`), and `StartCommand`
    /// documents (see its registry test-seam constructor) that a second boot
    /// sharing a registry with one still running is a real shape, not just a
    /// test artifact. [JvmMetricsRegistration#register] must be safe to call
    /// twice on the same registry — swallowing the resulting duplicate-name
    /// `IllegalArgumentException` — rather than let it escape and fail a
    /// second boot outright. Pinned directly against the helper (not by
    /// booting a second full `Server`): the process-global, un-deregistered
    /// collectors `Server.start` also registers unconditionally (`AuthAlarms`,
    /// a fresh `MailSender`'s) already collide on a shared registry today,
    /// independent of this feature — a second full boot on a live registry
    /// is out of scope here.
    ///
    /// Mutant: remove the try/catch in `JvmMetricsRegistration.registerOne`
    /// and the second `register` call throws instead of returning quietly,
    /// and the JVM series still resolves off the registry afterward.
    @Test
    void jvmMetricsRegistrationToleratesBeingCalledTwiceOnTheSameRegistry() throws Exception {
        var isolated = new PrometheusRegistry();
        JvmMetricsRegistration.register(isolated);

        assertThatCode(() -> JvmMetricsRegistration.register(isolated)).doesNotThrowAnyException();

        var formats = io.prometheus.metrics.expositionformats.ExpositionFormats.init();
        var out = new java.io.ByteArrayOutputStream();
        formats.getPrometheusTextFormatWriter().write(out, isolated.scrape());
        String body = out.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("jvm_threads_current");
    }

    @Test
    void specAndSpaAreServedFromTheApiListener() throws Exception {
        assertThat(get(running.apiPort(), "/api/openapi.json").statusCode()).isEqualTo(200);
        assertThat(get(running.apiPort(), "/q/openapi").statusCode()).isEqualTo(200);
        var spa = get(running.apiPort(), "/event-types");
        assertThat(spa.statusCode()).isEqualTo(200);
        assertThat(spa.body()).contains("<div id=\"app\">");
    }

    @Test
    void unknownApiRouteGetsTheEnvelopeNotTheSpa() throws Exception {
        // Only GET falls through to the SPA; a POST to an unknown path is an API 404 envelope.
        var r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + running.apiPort() + "/api/nope"))
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(r.body()).startsWith("{\"error\":\"NOT_FOUND\"");
    }

    /// Dispatch-seam spec §7 audit item 1: `Platform.register()` used to
    /// start a `DispatchJobReaper` and discard the handle, so nothing ever
    /// stopped it — every test that built a `Platform`/`Server` leaked a
    /// background thread that swept the shared test database. This boots and
    /// stops its own [Server] (independent of the shared `running` instance,
    /// since `stop()` only runs once for that one in `@AfterAll`) and asserts
    /// the reaper's executor is actually shut down afterward — a state that
    /// must change, not merely the absence of a symptom.
    /// `docs/spec/deployed-dispatch.md` §3 "Wiring": `schedulerPublisher`
    /// runs in worker mode too, and `DISPATCH_SCHEDULER_ENABLED=true` in the
    /// real deployment is set on the WORKER, not the platform. Before this
    /// unit, [DispatchQueueSettings#resolve] was only ever called in platform
    /// mode (for the served document), so a worker with a misconfigured SQS
    /// dispatch setup would silently fall through to the NOOP publisher
    /// instead of refusing to start — this pins that it now fails at boot,
    /// on the SAME mode the real deployment actually uses.
    @Test
    void aWorkerWithAMisconfiguredSqsDispatchSetupRefusesToStart() {
        Env env = Env.load(Map.of(
                "FC_API_PORT", "0",
                "FC_METRICS_PORT", "0",
                "FC_PLATFORM_ENABLED", "false",
                "FC_SCHEDULER_ENABLED", "true",
                "FC_DISPATCH_QUEUE_TYPE", "SQS"
                // FC_DISPATCH_QUEUE_PREFIX deliberately unset: DispatchQueueSettings.resolve
                // refuses to start rather than compose a queue literally named "FC-{env}-...".
        ));
        var server = new Server(env, new Server.Mode.Worker(io.flowcatalyst.platform.shared.database.Pools.ofSingle(TestPg.dataSource())), Server.Spa.none(), new PrometheusRegistry());

        assertThatThrownBy(server::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FC_DISPATCH_QUEUE_PREFIX");
    }

    @Test
    void stopClosesTheDispatchJobReaper() {
        Env env = Env.load(Map.of(
                "FC_API_PORT", "0",
                "FC_METRICS_PORT", "0",
                "FC_PLATFORM_ENABLED", "true",
                "FC_AUTH_ALLOW_TEST_HEADERS", "true"));
        var server = new Server(env, new Server.Mode.Platform(io.flowcatalyst.platform.shared.database.Pools.ofSingle(TestPg.dataSource())), Frontend.embeddedOrNone(), new PrometheusRegistry());
        var oneOff = server.start();
        try {
            assertThat(oneOff.dispatchJobReaperClosed()).isFalse();
        } finally {
            oneOff.stop();
        }
        assertThat(oneOff.dispatchJobReaperClosed()).isTrue();
    }
}
