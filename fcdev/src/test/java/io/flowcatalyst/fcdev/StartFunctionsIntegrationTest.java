package io.flowcatalyst.fcdev;

import io.flowcatalyst.fnhost.FnHost;
import io.flowcatalyst.fnhost.load.FixtureJars;
import io.flowcatalyst.platform.shared.database.Pools;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.subscription.Subscription;
import io.flowcatalyst.platform.subscription.SubscriptionRepository;
import io.flowcatalyst.platform.function.TriggerObject;
import io.flowcatalyst.platform.function.TriggerObjectKind;
import io.flowcatalyst.platform.function.TriggerObjectRepository;
import io.flowcatalyst.server.EnvReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// E1 (`docs/spec/function-developer-surface.md` §1, §4 E1/E3): boots the
/// REAL `fcdev start` wiring (`StartCommand`, exactly as `StartIntegrationTest`
/// does — embedded Postgres, ephemeral ports) with functions ON, then drives
/// the whole local loop end to end with the `fn-cli.json` credentials the
/// real boot wrote. `fcdev-fn-cli` holds only `function-publisher`/
/// `messaging-admin` (`FunctionDevBootstrapTest`) — neither grants
/// `ADMIN_APPLICATION_CREATE` — so the owning application (needed for its
/// service account's signing secret, which a subscription requires) is
/// provisioned by a wildcard ANCHOR test-header fixture first, same as
/// `RouterConfigEndpointTest`'s own `ANCHOR`; the function itself, its
/// event type, its publish and its promote all go through the REAL
/// `fcdev-fn-cli` bearer token. Publishes a
/// [io.flowcatalyst.fnhost.load.FixtureJars] jar, triggers the host's own
/// reconcile loop, polls for `READY`, promotes, invokes the host directly
/// over HTTP.
///
/// This is also where the REAL composition-root wiring is pinned — not a
/// hand-built harness: `StartCommand#devEnv` must have set
/// `FC_FN_SIGNATURES=off` / `FC_FN_POOL_URL`, and `StartCommand#launch` must
/// have actually started `FnHostLauncher` — the orchestrator's own note that
/// "wired a constant/none() in the composition root" is the recurring defect
/// on this branch applies directly here.
@SuppressWarnings("deprecation")
class StartFunctionsIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private Path root;
    private StartCommand.Started started;
    private Pools sidePools;

    @AfterEach
    void shutdown() throws Exception {
        if (sidePools != null) sidePools.close();
        if (started != null) started.close();
        if (root != null) EmbeddedPg.deleteTree(root);
    }

    /// Boots exactly as `StartIntegrationTest` does, functions left ON
    /// (the default) with ephemeral fn ports.
    private StartCommand.Started boot(Map<String, String> extraEnv) throws Exception {
        io.flowcatalyst.server.Logging.init(Map.of("FC_LOG_LEVEL", "warn", "FC_LOG_FORMAT", "text"));
        root = Files.createTempDirectory("fcdev-fn-it");
        Path dataPath = root.resolve("flowcatalyst/embedded-pg");
        Path pidFile = root.resolve("flowcatalyst/fcdev.pid");
        Path cache = root.resolve("cache");
        // Two REAL, ephemerally-allocated ports, probed and released — the
        // same convention `RouterStartupOrderTest`/`FunctionHostListenerIntegrationTest`
        // use. `--fn-port 0` cannot be used here: the platform's own
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
            // A fresh PrometheusRegistry per boot (StartCommand's own doc on its
            // package-private 4-arg constructor): Server#start registers
            // process-global collectors the JVM-wide default registry never
            // deregisters, and this class boots more than once per fork.
            return new StartCommand(env, paths, sub.opts, new io.prometheus.metrics.model.registry.PrometheusRegistry()).launch();
        } catch (Exception | ExceptionInInitializerError e) {
            LoggerFactory.getLogger(StartFunctionsIntegrationTest.class)
                    .warn("embedded PostgreSQL could not start here; skipping", e);
            Assumptions.abort("embedded PostgreSQL cannot start in this environment: " + e);
            throw e;
        }
    }

    // ── E3: --no-functions ──────────────────────────────────────────────

    /// §4 E3: `--no-functions` starts no host, creates no `fcdev-fn-*`
    /// clients, and writes no `fn-cli.json`. Mutant candidates: skip the
    /// `opts.functions()` guard anywhere in `StartCommand#launch`/`#devEnv`.
    @Test
    void noFunctionsStartsNoHostCreatesNoClientsAndWritesNoCredentialsFile() throws Exception {
        started = boot(Map.of("FC_DEV_FUNCTIONS", "false"));

        assertThat(started.fnHost()).as("no launcher result at all under --no-functions").isNull();

        Path fnCliJson = new DevPaths(root, root.resolve("cache")).fnCliCredentialsPath();
        assertThat(fnCliJson).as("fn-cli.json must not be written").doesNotExist();

        // No fcdev-fn-host / fcdev-fn-cli client rows: minting a token for
        // either fixed client id must fail (nothing was ever bootstrapped).
        var hostAttempt = mintTokenRaw(started.apiPort(), "fcdev-fn-host", "whatever-secret");
        assertThat(hostAttempt.statusCode()).as("no fcdev-fn-host client should exist").isEqualTo(401);
        var cliAttempt = mintTokenRaw(started.apiPort(), "fcdev-fn-cli", "whatever-secret");
        assertThat(cliAttempt.statusCode()).as("no fcdev-fn-cli client should exist").isEqualTo(401);

        // A direct row count — never trust "the wrong secret was rejected"
        // alone, since a wrong secret is ALSO rejected when the client DOES
        // exist (a mutant that bootstrapped the clients regardless of
        // --no-functions would still pass the 401 checks above).
        sidePools = Pools.open("postgresql://postgres:postgres@localhost:"
                + started.embeddedPg().orElseThrow().port() + "/flowcatalyst?sslmode=disable", new EnvReader(Map.of()));
        try (var conn = sidePools.api().getConnection();
             var stmt = conn.prepareStatement(
                     "select count(*) from oauth_clients where client_id in ('fcdev-fn-host','fcdev-fn-cli')")) {
            var rs = stmt.executeQuery();
            rs.next();
            assertThat(rs.getInt(1)).as("mutant: clients bootstrapped despite --no-functions").isEqualTo(0);
        }
    }

    // ── E1: the real end-to-end loop ─────────────────────────────────────

    @Test
    void publishReadyPromoteInvokeEndToEndThroughTheRealFnCliCredentials(@TempDir Path work) throws Exception {
        started = boot(Map.of());

        // ── the real wiring: FnHostLauncher actually started (JVM branch on this test run) ──
        assertThat(started.fnHost()).as("FnHostLauncher must have run under the real StartCommand wiring")
                .isInstanceOf(FnHostLauncher.InProcess.class);
        FnHost host = ((FnHostLauncher.InProcess) started.fnHost()).host();
        assertThat(host.port()).as("the function listener must be bound").isGreaterThan(0);

        // ── fn-cli.json, written by the real boot ──
        Path fnCliJson = new DevPaths(root, root.resolve("cache")).fnCliCredentialsPath();
        assertThat(fnCliJson).exists();
        JsonNode creds = Json.MAPPER.readTree(Files.readString(fnCliJson));
        assertThat(creds.path("clientId").asText()).isEqualTo("fcdev-fn-cli");
        assertThat(creds.path("platformUrl").asText()).isEqualTo("http://localhost:" + started.apiPort());
        assertThat(creds.path("hostUrl").asText()).isEqualTo("http://127.0.0.1:" + host.port());

        String cliToken = mintToken(started.apiPort(), creds.path("clientId").asText(), creds.path("clientSecret").asText());

        // ── the application: fcdev-fn-cli holds only function-publisher +
        //    messaging-admin (FunctionDevBootstrapTest), neither of which
        //    grants ADMIN_APPLICATION_CREATE — an application (and its
        //    service account, needed below for the subscription's signing
        //    secret) is infrastructure an operator provisions, same as the
        //    real workflow; a wildcard ANCHOR fixture sets it up exactly as
        //    RouterConfigEndpointTest's own ANCHOR fixture does, never used
        //    for the load-bearing assertions themselves. ──
        String run = Long.toUnsignedString(System.nanoTime(), 36);
        String appCode = "fnhostit" + run;
        String[] anchor = {
                io.flowcatalyst.platform.shared.auth.Authenticator.TEST_PRINCIPAL,
                io.flowcatalyst.platform.shared.tsid.EntityType.PRINCIPAL.generate(),
                io.flowcatalyst.platform.shared.auth.Authenticator.TEST_SCOPE, "ANCHOR",
                io.flowcatalyst.platform.shared.auth.Authenticator.TEST_PERMISSIONS, "platform:*:*:*"};
        var createdApp = adminPost(started.apiPort(), anchor, "/api/applications",
                obj("code", appCode, "name", appCode, "type", "APPLICATION"), 201);
        String appId = createdApp.path("id").asText();
        adminPost(started.apiPort(), anchor, "/api/applications/" + appId + "/provision-service-account",
                null, 201);

        // ── event type + function, created with the fn-cli.json credentials themselves ──
        String eventTypeCode = appCode + ":sample:thing:created";
        cliPost(started.apiPort(), cliToken, "/api/event-types",
                obj("code", eventTypeCode, "name", "sample created " + run), 201);

        String serviceName = "fnhostit" + run;
        var createResp = cliPost(started.apiPort(), cliToken, "/api/functions",
                obj("applicationCode", appCode, "serviceName", serviceName, "name", "sample", "runtime", "jvm"), 201);
        String address = createResp.path("address").asText();

        // ── publish v1: a fixture with a plain (auth: none) endpoint AND a
        //    subscription bound to that same endpoint, so promote's stored
        //    subscription target pins FC_FN_POOL_URL (E1 mutant: wrong pool URL). ──
        Path jar = work.resolve("e1-fn.jar");
        FixtureJars.builder().source("fixture.e1.HelloFn", """
                package fixture.e1;
                import io.flowcatalyst.function.*;
                public final class HelloFn implements Function {
                    public Result handle(Request in, FunctionContext ctx) throws Exception {
                        return Result.json(200, "{\\"ok\\":true}");
                    }
                }
                """).build(jar);
        String digest = digestOf(jar);
        JsonNode manifest = obj("runtime", "jvm", "entrypoint", "fixture.e1.HelloFn", "pool", "default", "warm", false,
                "endpoints", array(obj("path", "/hello", "auth", "none"), obj("path", "/events", "auth", "webhook")),
                "subscriptions", array(obj("eventType", eventTypeCode, "path", "/events", "mode", "IMMEDIATE")));
        cliPost(started.apiPort(), cliToken, "/api/functions/" + address + "/versions",
                obj("artifactRef", jar.toUri().toString(), "digest", digest, "manifest", manifest), 201);

        // ── reconcile: the host's OWN loop, driven by hand (spec §1: fcdev's
        //    class path holds the whole server — this is the JVM branch) ──
        host.triggerReconcile();
        awaitCondition(() -> readVersionState(started.apiPort(), cliToken, address, 1).equals("READY"),
                "v1 must become READY", Duration.ofSeconds(10));

        // ── promote ──
        cliPut(started.apiPort(), cliToken, "/api/functions/" + address + "/aliases/live", obj("version", 1), 200);
        host.triggerReconcile();

        // ── the stored subscription endpoint pins FC_FN_POOL_URL (E1 mutant table) ──
        sidePools = Pools.open("postgresql://postgres:postgres@localhost:"
                + started.embeddedPg().orElseThrow().port() + "/flowcatalyst?sslmode=disable", new EnvReader(Map.of()));
        var triggerObjects = new TriggerObjectRepository(sidePools.api());
        var subscriptions = new SubscriptionRepository(sidePools.api());
        String functionId = findFunctionId(started.apiPort(), cliToken, address);
        TriggerObject subLink = triggerObjects.listByFunction(functionId).stream()
                .filter(t -> t.kind() == TriggerObjectKind.SUBSCRIPTION).findFirst()
                .orElseThrow(() -> new AssertionError("promote must have created the subscription"));
        Subscription subscription = subscriptions.findById(subLink.objectId()).orElseThrow();
        assertThat(subscription.endpoint())
                .as("mutant: wrong FC_FN_POOL_URL — the stored subscription target")
                .isEqualTo("http://127.0.0.1:" + host.port() + "/functions/" + address + "/events");

        // ── invoke the host directly over HTTP ──
        var invoked = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + host.port() + "/functions/" + address + "/hello"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(invoked.statusCode()).as(invoked.body()).isEqualTo(200);

        // ── a second fixture: proves the filtering parent loader —
        //    io.flowcatalyst.server.Platform and io.flowcatalyst.fcdev.FcDev
        //    are both unreachable from inside a published function, IN THIS
        //    SAME fcdev PROCESS (the in-process branch's whole point). ──
        String serviceName2 = "fnhostit" + run + "probe";
        var createResp2 = cliPost(started.apiPort(), cliToken, "/api/functions",
                obj("applicationCode", "platform", "serviceName", serviceName2, "name", "probe", "runtime", "jvm"), 201);
        String address2 = createResp2.path("address").asText();
        Path jar2 = work.resolve("e1-probe-fn.jar");
        FixtureJars.builder().source("fixture.e1.ClassLoaderProbeFn", """
                package fixture.e1;
                import io.flowcatalyst.function.*;
                public final class ClassLoaderProbeFn implements Function {
                    public Result handle(Request in, FunctionContext ctx) throws Exception {
                        String server = probe("io.flowcatalyst.server.Platform");
                        String fcdev = probe("io.flowcatalyst.fcdev.FcDev");
                        String json = "{\\"server\\":\\"" + server + "\\",\\"fcdev\\":\\"" + fcdev + "\\"}";
                        return Result.json(200, json);
                    }
                    private static String probe(String className) {
                        try {
                            Class.forName(className);
                            return "LOADED";
                        } catch (ClassNotFoundException e) {
                            return "ClassNotFoundException";
                        }
                    }
                }
                """).build(jar2);
        String digest2 = digestOf(jar2);
        JsonNode manifest2 = obj("runtime", "jvm", "entrypoint", "fixture.e1.ClassLoaderProbeFn", "pool", "default", "warm", false,
                "endpoints", array(obj("path", "/check", "auth", "none")));
        cliPost(started.apiPort(), cliToken, "/api/functions/" + address2 + "/versions",
                obj("artifactRef", jar2.toUri().toString(), "digest", digest2, "manifest", manifest2), 201);
        host.triggerReconcile();
        awaitCondition(() -> readVersionState(started.apiPort(), cliToken, address2, 1).equals("READY"),
                "probe v1 must become READY", Duration.ofSeconds(10));
        cliPut(started.apiPort(), cliToken, "/api/functions/" + address2 + "/aliases/live", obj("version", 1), 200);
        host.triggerReconcile();
        awaitCondition(() -> {
            try {
                var r = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + host.port()
                                + "/functions/" + address2 + "/check")).GET().build(), HttpResponse.BodyHandlers.ofString());
                return r.statusCode() == 200;
            } catch (Exception e) {
                return false;
            }
        }, "probe must become invokable", Duration.ofSeconds(10));

        var probe = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + host.port() + "/functions/" + address2 + "/check"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(probe.statusCode()).as(probe.body()).isEqualTo(200);
        JsonNode probeBody = Json.MAPPER.readTree(probe.body());
        assertThat(probeBody.path("server").asText())
                .as("mutant: a published function can load io.flowcatalyst.server.Platform")
                .isEqualTo("ClassNotFoundException");
        assertThat(probeBody.path("fcdev").asText())
                .as("mutant: a published function can load io.flowcatalyst.fcdev.FcDev")
                .isEqualTo("ClassNotFoundException");
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static String findFunctionId(int apiPort, String token, String address) throws Exception {
        var r = get(apiPort, "/api/functions/" + address, token);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return Json.MAPPER.readTree(r.body()).path("id").asText();
    }

    private static String readVersionState(int apiPort, String token, String address, int version) {
        try {
            var r = get(apiPort, "/api/functions/" + address + "/status", token);
            if (r.statusCode() != 200) return "";
            JsonNode status = Json.MAPPER.readTree(r.body());
            for (JsonNode v : status.path("versions")) {
                if (v.path("version").asInt() == version) return v.path("state").asString();
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String digestOf(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) md.update(buffer, 0, n);
        }
        return "sha256:" + java.util.HexFormat.of().formatHex(md.digest());
    }

    private static void awaitCondition(java.util.function.BooleanSupplier condition, String description, Duration timeout) {
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

    private static String mintToken(int apiPort, String clientId, String clientSecret) throws Exception {
        var r = mintTokenRaw(apiPort, clientId, clientSecret);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return Json.MAPPER.readTree(r.body()).path("access_token").asText();
    }

    private static HttpResponse<String> mintTokenRaw(int apiPort, String clientId, String clientSecret) throws Exception {
        String form = "grant_type=client_credentials"
                + "&client_id=" + java.net.URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + java.net.URLEncoder.encode(clientSecret, StandardCharsets.UTF_8);
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + apiPort + "/oauth/token"))
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(int apiPort, String path, String token) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + apiPort + path)).GET();
        if (token != null) b.header("Authorization", "Bearer " + token);
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /// Anchor test-header setup helper (`X-FC-Test-Principal` etc.) — used
    /// only to provision the application infrastructure the fn-cli client
    /// itself has no permission to create; never for a load-bearing
    /// assertion (same discipline as `RouterConfigEndpointTest`'s `ANCHOR`).
    private static JsonNode adminPost(int apiPort, String[] headers, String path, ObjectNode body, int expectedStatus) throws Exception {
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

    private static JsonNode cliPost(int apiPort, String token, String path, ObjectNode body, int expectedStatus) throws Exception {
        return cliSend(apiPort, token, "POST", path, body, expectedStatus);
    }

    private static JsonNode cliPut(int apiPort, String token, String path, ObjectNode body, int expectedStatus) throws Exception {
        return cliSend(apiPort, token, "PUT", path, body, expectedStatus);
    }

    private static JsonNode cliSend(int apiPort, String token, String method, String path, ObjectNode body, int expectedStatus)
            throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + apiPort + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .method(method, HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8));
        var r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(method + " " + path + " -> " + r.body()).isEqualTo(expectedStatus);
        return r.body().isBlank() ? Json.MAPPER.createObjectNode() : Json.MAPPER.readTree(r.body());
    }

    private static ObjectNode obj(Object... kv) {
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

    private static ArrayNode array(JsonNode... values) {
        ArrayNode node = Json.MAPPER.createArrayNode();
        for (JsonNode v : values) node.add(v);
        return node;
    }
}
