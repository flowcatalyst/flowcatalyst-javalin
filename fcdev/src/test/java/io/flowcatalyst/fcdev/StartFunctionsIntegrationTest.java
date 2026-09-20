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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static io.flowcatalyst.fcdev.FcdevFunctionsFixture.HTTP;
import static io.flowcatalyst.fcdev.FcdevFunctionsFixture.adminPost;
import static io.flowcatalyst.fcdev.FcdevFunctionsFixture.array;
import static io.flowcatalyst.fcdev.FcdevFunctionsFixture.awaitCondition;
import static io.flowcatalyst.fcdev.FcdevFunctionsFixture.cliPost;
import static io.flowcatalyst.fcdev.FcdevFunctionsFixture.cliPut;
import static io.flowcatalyst.fcdev.FcdevFunctionsFixture.digestOf;
import static io.flowcatalyst.fcdev.FcdevFunctionsFixture.get;
import static io.flowcatalyst.fcdev.FcdevFunctionsFixture.mintToken;
import static io.flowcatalyst.fcdev.FcdevFunctionsFixture.mintTokenRaw;
import static io.flowcatalyst.fcdev.FcdevFunctionsFixture.obj;
import static org.assertj.core.api.Assertions.assertThat;

/// E1 (`docs/spec/function-developer-surface.md` §1, §4 E1/E3): boots the
/// REAL `fcdev start` wiring ([FcdevFunctionsFixture], the extracted form of
/// this test's own original harness — `StartCommand`, exactly as
/// `StartIntegrationTest` does — embedded Postgres, ephemeral ports) with
/// functions ON, then drives the whole local loop end to end with the
/// `fn-cli.json` credentials the real boot wrote. `fcdev-fn-cli` holds only
/// `function-publisher`/`messaging-admin` (`FunctionDevBootstrapTest`) —
/// neither grants `ADMIN_APPLICATION_CREATE` — so the owning application
/// (needed for its service account's signing secret, which a subscription
/// requires) is provisioned by a wildcard ANCHOR test-header fixture first,
/// same as `RouterConfigEndpointTest`'s own `ANCHOR`; the function itself,
/// its event type, its publish and its promote all go through the REAL
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
///
/// `fcdev.fn`'s own end-to-end tests (`FnCliEndToEndTest`) reuse this SAME
/// [FcdevFunctionsFixture] rather than a second copy of this boot logic.
@SuppressWarnings("deprecation")
class StartFunctionsIntegrationTest {

    private final FcdevFunctionsFixture fixture = new FcdevFunctionsFixture();
    private Pools sidePools;

    @AfterEach
    void shutdown() {
        if (sidePools != null) sidePools.close();
        fixture.close();
    }

    // ── E3: --no-functions ──────────────────────────────────────────────

    /// §4 E3: `--no-functions` starts no host, creates no `fcdev-fn-*`
    /// clients, and writes no `fn-cli.json`. Mutant candidates: skip the
    /// `opts.functions()` guard anywhere in `StartCommand#launch`/`#devEnv`.
    @Test
    void noFunctionsStartsNoHostCreatesNoClientsAndWritesNoCredentialsFile() throws Exception {
        StartCommand.Started started = fixture.boot(Map.of("FC_DEV_FUNCTIONS", "false"));

        assertThat(started.fnHost()).as("no launcher result at all under --no-functions").isNull();

        Path fnCliJson = fixture.paths().fnCliCredentialsPath();
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
        StartCommand.Started started = fixture.boot(Map.of());

        // ── the real wiring: FnHostLauncher actually started (JVM branch on this test run) ──
        assertThat(started.fnHost()).as("FnHostLauncher must have run under the real StartCommand wiring")
                .isInstanceOf(FnHostLauncher.InProcess.class);
        FnHost host = ((FnHostLauncher.InProcess) started.fnHost()).host();
        assertThat(host.port()).as("the function listener must be bound").isGreaterThan(0);

        // ── fn-cli.json, written by the real boot ──
        Path fnCliJson = fixture.paths().fnCliCredentialsPath();
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
}
