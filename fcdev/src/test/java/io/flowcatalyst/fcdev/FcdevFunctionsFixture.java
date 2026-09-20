package io.flowcatalyst.fcdev;

import io.flowcatalyst.platform.shared.database.Pools;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.server.EnvReader;
import io.flowcatalyst.server.Logging;
import org.junit.jupiter.api.Assumptions;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// The real `fcdev start` dev stack (embedded Postgres, ephemeral ports,
/// functions ON), extracted from `StartFunctionsIntegrationTest` (E1) so
/// other slices' end-to-end tests — `fcdev.fn`'s E2 in particular — boot the
/// SAME real wiring rather than a hand-built harness (`docs/spec/function-developer-surface.md`
/// §2's instruction to reuse E1's harness).
///
/// Usage: `new FcdevFunctionsFixture()`, `boot(extraEnv)`, use {@link #root}
/// (the SAME `DevPaths` root a test's own `fn` CLI invocations must point
/// `XDG_DATA_HOME` at, so they pick up the real `fn-cli.json` this boot
/// wrote), then `close()` — implements [AutoCloseable] so a `try`-with-resources
/// or `@AfterEach` covers it.
@SuppressWarnings("deprecation")
public final class FcdevFunctionsFixture implements AutoCloseable {

    public static final HttpClient HTTP = HttpClient.newHttpClient();

    public Path root;
    public StartCommand.Started started;
    private Pools sidePools;

    @Override
    public void close() {
        if (sidePools != null) sidePools.close();
        if (started != null) started.close();
        if (root != null) {
            try {
                EmbeddedPg.deleteTree(root);
            } catch (Exception ignored) {
                // best effort — a leftover temp dir is not fatal
            }
        }
    }

    /// Boots exactly as `StartIntegrationTest` does, functions left ON (the
    /// default) with ephemeral fn ports. Self-skips (JUnit `Assumptions`)
    /// when the embedded PostgreSQL cannot start here.
    public StartCommand.Started boot(Map<String, String> extraEnv) throws Exception {
        Logging.init(Map.of("FC_LOG_LEVEL", "warn", "FC_LOG_FORMAT", "text"));
        root = Files.createTempDirectory("fcdev-fn-it");
        Path dataPath = root.resolve("flowcatalyst/embedded-pg");
        Path pidFile = root.resolve("flowcatalyst/fcdev.pid");
        Path cache = root.resolve("cache");
        // Two REAL, ephemerally-allocated ports, probed and released (the
        // same convention `RouterStartupOrderTest`/`FunctionHostListenerIntegrationTest`
        // use). `--fn-port 0` cannot be used here: the platform's own
        // FC_FN_POOL_URL default is computed BEFORE Server#start (Env is
        // immutable once built), while the function host's REAL bound port
        // is only known AFTER FnHost#start, which runs after Server#start —
        // an inherent ordering constraint for an ephemeral fn port, not a
        // concern in production (where --fn-port is always a concrete value).
        int fnPort;
        int fnMetricsPort;
        try (var p1 = new java.net.ServerSocket(0); var p2 = new java.net.ServerSocket(0)) {
            fnPort = p1.getLocalPort();
            fnMetricsPort = p2.getLocalPort();
        }
        var vars = new java.util.LinkedHashMap<String, String>(Map.of(
                "FC_EMBEDDED_DB_PATH", dataPath.toString(),
                "FC_DEV_PID_FILE", pidFile.toString(),
                "XDG_CACHE_HOME", cache.toString(),
                "FC_FN_METRICS_PORT", Integer.toString(fnMetricsPort)));
        vars.putAll(extraEnv);
        var env = DevEnv.of(vars);
        var sub = new StartCommand.Sub(env);
        new CommandLine(sub, new EnvFactory(env)).parseArgs("--api-port", "0", "--metrics-port", "0",
                "--embedded-db-port", "0", "--router=false", "--stream=false", "--scheduler=false",
                "--scheduled-job=false", "--fn-port", Integer.toString(fnPort));
        var paths = new DevPaths(root, cache);
        try {
            // A fresh PrometheusRegistry per boot: Server#start registers
            // process-global collectors the JVM-wide default registry never
            // deregisters, and different fixtures may boot more than once per fork.
            started = new StartCommand(env, paths, sub.opts, new io.prometheus.metrics.model.registry.PrometheusRegistry()).launch();
            return started;
        } catch (Exception | ExceptionInInitializerError e) {
            LoggerFactory.getLogger(FcdevFunctionsFixture.class)
                    .warn("embedded PostgreSQL could not start here; skipping", e);
            Assumptions.abort("embedded PostgreSQL cannot start in this environment: " + e);
            throw e;
        }
    }

    /// The in-process function host (`fnHost()` is package-private on
    /// [StartCommand.Started] — this fixture lives in the same package so a
    /// caller like `fcdev.fn`'s own end-to-end tests, one package over,
    /// never needs that visibility itself).
    ///
    /// @throws ClassCastException the JVM branch did not run (native/disabled) — every
    ///                            test using this fixture runs on the JVM, so that is a bug, not
    ///                            a condition to handle gracefully
    public io.flowcatalyst.fnhost.FnHost inProcessHost() {
        return ((FnHostLauncher.InProcess) started.fnHost()).host();
    }

    /// `DevPaths` for {@link #root} — the same instance {@link #boot} used
    /// internally, so `paths().fnCliCredentialsPath()` names the real file.
    public DevPaths paths() {
        return new DevPaths(root, root.resolve("cache"));
    }

    /// A side connection pool against the SAME embedded cluster {@link #boot}
    /// started — for assertions that read rows the platform API doesn't
    /// expose (e.g. a stored subscription's endpoint URL). Opened once, lazily.
    public Pools sidePools() {
        if (sidePools == null) {
            sidePools = Pools.open("postgresql://postgres:postgres@localhost:"
                    + started.embeddedPg().orElseThrow().port() + "/flowcatalyst?sslmode=disable",
                    new EnvReader(Map.of()));
        }
        return sidePools;
    }

    // ── helpers (verbatim from StartFunctionsIntegrationTest E1) ─────────

    public static String digestOf(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) md.update(buffer, 0, n);
        }
        return "sha256:" + java.util.HexFormat.of().formatHex(md.digest());
    }

    public static void awaitCondition(java.util.function.BooleanSupplier condition, String description, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        assertThat(condition.getAsBoolean()).as(description).isTrue();
    }

    public static String mintToken(int apiPort, String clientId, String clientSecret) throws Exception {
        var r = mintTokenRaw(apiPort, clientId, clientSecret);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return Json.MAPPER.readTree(r.body()).path("access_token").asText();
    }

    public static HttpResponse<String> mintTokenRaw(int apiPort, String clientId, String clientSecret) throws Exception {
        String form = "grant_type=client_credentials"
                + "&client_id=" + java.net.URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + java.net.URLEncoder.encode(clientSecret, StandardCharsets.UTF_8);
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + apiPort + "/oauth/token"))
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    public static HttpResponse<String> get(int apiPort, String path, String token) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + apiPort + path)).GET();
        if (token != null) b.header("Authorization", "Bearer " + token);
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /// Anchor test-header setup helper (`X-FC-Test-Principal` etc.) — used
    /// only to provision infrastructure the fn-cli client itself has no
    /// permission to create (an application, its service account); never
    /// for a load-bearing assertion (same discipline as `RouterConfigEndpointTest`'s
    /// `ANCHOR`).
    public static JsonNode adminPost(int apiPort, String[] headers, String path, ObjectNode body, int expectedStatus) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + apiPort + path));
        if (body == null) {
            b.method("POST", HttpRequest.BodyPublishers.noBody());
        } else {
            b.header("Content-Type", "application/json")
                    .method("POST", HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8));
        }
        for (int i = 0; i + 1 < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        var r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(expectedStatus);
        return r.body().isBlank() ? Json.MAPPER.createObjectNode() : Json.MAPPER.readTree(r.body());
    }

    public static JsonNode cliPost(int apiPort, String token, String path, ObjectNode body, int expectedStatus) throws Exception {
        return cliSend(apiPort, token, "POST", path, body, expectedStatus);
    }

    public static JsonNode cliPut(int apiPort, String token, String path, ObjectNode body, int expectedStatus) throws Exception {
        return cliSend(apiPort, token, "PUT", path, body, expectedStatus);
    }

    public static JsonNode cliSend(int apiPort, String token, String method, String path, ObjectNode body, int expectedStatus)
            throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + apiPort + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .method(method, HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8));
        var r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(method + " " + path + " -> " + r.body()).isEqualTo(expectedStatus);
        return r.body().isBlank() ? Json.MAPPER.createObjectNode() : Json.MAPPER.readTree(r.body());
    }

    public static ObjectNode obj(Object... kv) {
        ObjectNode node = Json.MAPPER.createObjectNode();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            String key = (String) kv[i];
            Object value = kv[i + 1];
            switch (value) {
                case String s -> node.put(key, s);
                case Boolean bo -> node.put(key, bo);
                case Integer n -> node.put(key, n);
                case JsonNode n -> node.set(key, n);
                default -> throw new IllegalArgumentException("unsupported value type: " + value);
            }
        }
        return node;
    }

    public static ArrayNode array(JsonNode... values) {
        ArrayNode node = Json.MAPPER.createArrayNode();
        for (JsonNode v : values) node.add(v);
        return node;
    }
}
