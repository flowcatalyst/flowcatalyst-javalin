package io.flowcatalyst.fnhost.http;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.flowcatalyst.fnhost.load.LoadedFunction;
import io.flowcatalyst.fnhost.reconcile.DesiredDocument;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.router.wire.WebhookSigner;
import io.flowcatalyst.server.Logging;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.net.Socket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `FnHttpServer` — `docs/spec/function-host-listener.md` §2-§4, tests
/// H1-H14. Real HTTP (`java.net.http`) against a real [FnHttpServer] on an
/// ephemeral port, fixture functions from [io.flowcatalyst.fnhost.load.FixtureJars],
/// a [io.flowcatalyst.fnhost.reconcile.FakeControlPlane]-fed `Reconciler`.
/// One mutant per condition; absence pinned via each fixture's own
/// invocation counter file (a call that never reaches the function leaves it
/// untouched — observable from outside the function's isolated classloader,
/// which a static field on the loaded class is not).
class FnHttpServerTest {

    private static final FunctionAddress ADDR = FnHttpTestSupport.ADDR_A;
    private static final FunctionAddress ADDR_B = FnHttpTestSupport.ADDR_B;

    // ── fixture sources ──────────────────────────────────────────────────

    private static String echoSource(Path counterFile) {
        return """
                package fixture.http;
                import io.flowcatalyst.function.*;
                import java.nio.file.*;
                import java.nio.charset.StandardCharsets;
                import java.util.*;

                public final class EchoFn implements Function {
                    private static final String INSTANCE_ID = java.util.UUID.randomUUID().toString();
                    public Result handle(Request in, FunctionContext ctx) throws Exception {
                        try {
                            Files.writeString(Path.of("%s"), "x", StandardCharsets.UTF_8,
                                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                        } catch (Exception ignored) { }
                        StringBuilder sb = new StringBuilder();
                        sb.append('{');
                        sb.append("\\"instanceId\\":\\"").append(INSTANCE_ID).append("\\",");
                        sb.append("\\"method\\":\\"").append(esc(in.method())).append("\\",");
                        sb.append("\\"path\\":\\"").append(esc(in.path())).append("\\",");
                        sb.append("\\"originalPath\\":\\"").append(esc(in.originalPath())).append("\\",");
                        sb.append("\\"originalHost\\":").append(in.originalHost() == null ? "null" : "\\"" + esc(in.originalHost()) + "\\"").append(',');
                        sb.append("\\"bodyLength\\":").append(in.body().length).append(',');
                        sb.append("\\"caller\\":\\"").append(in.caller().getClass().getSimpleName()).append("\\",");
                        sb.append("\\"pathParams\\":{");
                        boolean first = true;
                        for (var e : in.pathParams().entrySet()) {
                            if (!first) sb.append(','); first = false;
                            sb.append('"').append(esc(e.getKey())).append("\\":\\"").append(esc(e.getValue())).append('"');
                        }
                        sb.append("},\\"query\\":{");
                        first = true;
                        for (var e : in.query().entrySet()) {
                            if (!first) sb.append(','); first = false;
                            sb.append('"').append(esc(e.getKey())).append("\\":[");
                            boolean f2 = true;
                            for (String v : e.getValue()) { if (!f2) sb.append(','); f2 = false; sb.append('"').append(esc(v)).append('"'); }
                            sb.append(']');
                        }
                        sb.append('}');
                        sb.append(",\\"headerAuthorization\\":").append(hdr(in, "Authorization"));
                        sb.append(",\\"headerSignature\\":").append(hdr(in, "X-FlowCatalyst-Signature"));
                        sb.append(",\\"headerTimestamp\\":").append(hdr(in, "X-FlowCatalyst-Timestamp"));
                        sb.append(",\\"headerCustom\\":").append(hdr(in, "X-Test-Custom"));
                        if (in.caller() instanceof Caller.Principal p) {
                            sb.append(",\\"principalId\\":\\"").append(esc(p.id())).append('"');
                            sb.append(",\\"principalType\\":\\"").append(esc(p.type())).append('"');
                            sb.append(",\\"principalTier\\":").append(p.tier() == null ? "null" : "\\"" + esc(p.tier()) + "\\"");
                            sb.append(",\\"principalClientId\\":").append(p.clientId().map(cid -> "\\"" + esc(cid) + "\\"").orElse("null"));
                            sb.append(",\\"principalClients\\":[");
                            boolean fc = true;
                            for (String c : p.clients()) { if (!fc) sb.append(','); fc = false; sb.append('"').append(esc(c)).append('"'); }
                            sb.append(']');
                            sb.append(",\\"principalRoles\\":[");
                            boolean fr = true;
                            for (String role : p.roles()) { if (!fr) sb.append(','); fr = false; sb.append('"').append(esc(role)).append('"'); }
                            sb.append(']');
                            sb.append(",\\"principalApplications\\":[");
                            boolean fa = true;
                            for (String app : p.applications()) { if (!fa) sb.append(','); fa = false; sb.append('"').append(esc(app)).append('"'); }
                            sb.append(']');
                            sb.append(",\\"principalAllApplications\\":").append(p.allApplications());
                            sb.append(",\\"principalPermissions\\":[");
                            boolean fp = true;
                            for (String perm : p.permissions()) { if (!fp) sb.append(','); fp = false; sb.append('"').append(esc(perm)).append('"'); }
                            sb.append(']');
                        }
                        sb.append('}');
                        int status = 200;
                        if (in.query().containsKey("status")) {
                            try { status = Integer.parseInt(in.query().get("status").get(0)); } catch (Exception ignored) { }
                        }
                        Map<String, List<String>> headers = new LinkedHashMap<>();
                        headers.put("X-Multi", List.of("a", "b"));
                        headers.put("Connection", List.of("keep-alive"));
                        headers.put("Content-Length", List.of("999"));
                        return Result.http(status, headers, sb.toString().getBytes(StandardCharsets.UTF_8));
                    }
                    private static String hdr(Request in, String name) {
                        return in.header(name).map(v -> "\\"" + esc(v) + "\\"").orElse("null");
                    }
                    private static String esc(String s) {
                        if (s == null) return "";
                        return s.replace("\\\\", "\\\\\\\\").replace("\\"", "\\\\\\"");
                    }
                }
                """.formatted(path(counterFile));
    }

    private static String parkingSource(Path started, Path release, String tag) {
        return """
                package fixture.http;
                import io.flowcatalyst.function.*;
                import java.nio.file.*;

                public final class ParkingFn implements Function {
                    public Result handle(Request in, FunctionContext ctx) throws Exception {
                        Files.writeString(Path.of("%s"), "x");
                        // A hard safety bound (never asserted on — no test waits this long): a test
                        // whose own release file is deliberately never created (H14's "never returns"
                        // case) must not leave this worker's virtual thread spinning for the rest of
                        // the JVM's life, competing with every later test's own first request.
                        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(90).toNanos();
                        while (!Files.exists(Path.of("%s")) && System.nanoTime() < deadline) {
                            try { Thread.sleep(15); } catch (InterruptedException ignored) { }
                        }
                        return Result.json(200, "{\\"tag\\":\\"%s\\"}");
                    }
                }
                """.formatted(path(started), path(release), tag);
    }

    private static final String THROWING_SOURCE = """
            package fixture.http;
            import io.flowcatalyst.function.*;
            public final class ThrowingFn implements Function {
                public Result handle(Request in, FunctionContext ctx) throws Exception {
                    throw new IllegalStateException("do-not-leak-this-message-to-the-caller");
                }
            }
            """;

    private static final String NULL_SOURCE = """
            package fixture.http;
            import io.flowcatalyst.function.*;
            public final class NullFn implements Function {
                public Result handle(Request in, FunctionContext ctx) throws Exception { return null; }
            }
            """;

    private static String path(Path p) {
        return p.toString().replace("\\", "\\\\");
    }

    private static long countLines(Path file) {
        try {
            if (!Files.exists(file)) return 0;
            return Files.readString(file).length();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── H1: path/params/query/headers; undeclared path 404 + counter 0; wrong method 405 ──

    @Test
    void h1_requestShapeAndRoutingRules(@TempDir Path dir) throws Exception {
        Path counter = dir.resolve("counter");
        Path jar = FnHttpTestSupport.functionJar(dir, "echo", "fixture.http.EchoFn", echoSource(counter));
        var manifest = FnHttpTestSupport.manifest("p", false, 10, 5000, "fixture.http.EchoFn",
                """
                [{"path":"/echo/{id}","auth":"none"},{"path":"/onlypost","auth":"none","methods":["POST"]}]
                """);
        var entry = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jar, manifest, null, null, null);
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry))) {
            var resp = h.get("/functions/" + ADDR.render() + "/echo/42?y=hello+world&y=again",
                    "X-Test-Custom", "hi");
            assertThat(resp.statusCode()).isEqualTo(200);
            JsonNode body = FnHttpTestSupport.json(resp.body());
            assertThat(body.path("path").asString()).as("mutant: pass the full path, not the prefix-stripped one")
                    .isEqualTo("/echo/42");
            assertThat(body.path("originalPath").asString())
                    .isEqualTo("/functions/" + ADDR.render() + "/echo/42");
            assertThat(body.path("originalHost").asString())
                    .as("mutant: pass the full path, not the prefix-stripped one — originalHost is the Host header")
                    .startsWith("127.0.0.1:");
            assertThat(body.path("pathParams").path("id").asString())
                    .as("mutant: skip the endpoint match; pass the full path").isEqualTo("42");
            assertThat(body.path("query").path("y").valueStream().map(JsonNode::asString).toList())
                    .as("query multi-values in order, '+' decoded as a space")
                    .containsExactly("hello world", "again");
            assertThat(body.path("headerCustom").asString()).isEqualTo("hi");
            assertThat(body.path("caller").asString()).isEqualTo("Anonymous");

            // undeclared path: 404, function never invoked
            var missing = h.get("/functions/" + ADDR.render() + "/does-not-exist");
            assertThat(missing.statusCode()).isEqualTo(404);
            assertThat(FnHttpTestSupport.json(missing.body()).path("error").asString()).isEqualTo("ENDPOINT_NOT_FOUND");

            // wrong method: 405 with Allow
            var wrongMethod = h.get("/functions/" + ADDR.render() + "/onlypost");
            assertThat(wrongMethod.statusCode()).as("mutant: skip the endpoint match; pass the full path").isEqualTo(405);
            assertThat(wrongMethod.headers().firstValue("Allow")).contains("POST");

            // a version of zero (or negative) is 400 VERSION_INVALID, not treated as a legal version
            var zeroVersion = h.get("/functions/" + ADDR.render() + ":0/echo/1");
            assertThat(zeroVersion.statusCode()).as("mutant: accept version <= 0").isEqualTo(400);
            assertThat(FnHttpTestSupport.json(zeroVersion.body()).path("error").asString()).isEqualTo("VERSION_INVALID");

            assertThat(countLines(counter)).as("mutant: the function's invocation counter must stay 0 for both refusals")
                    .isEqualTo(1); // only the first, successful call actually invoked the function
        }
    }

    // ── H2: webhook signature verification ──────────────────────────────

    @Test
    void h2_webhookSignatureVerification(@TempDir Path dir) throws Exception {
        Path counter = dir.resolve("counter");
        Path jar = FnHttpTestSupport.functionJar(dir, "echo", "fixture.http.EchoFn", echoSource(counter));
        var manifest = FnHttpTestSupport.manifest("p", false, 10, 5000, "fixture.http.EchoFn",
                """
                [{"path":"/events/*","auth":"webhook"}]
                """);
        String secret = "wh-secret-1";
        var entry = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jar, manifest, secret, "app_1", "clt_1");
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry))) {
            byte[] body = "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8);
            String ts = WebhookSigner.timestamp(Instant.now());
            String sig = WebhookSigner.sign(secret, ts, body);

            var ok = h.post("/functions/" + ADDR.render() + "/events/x", body,
                    "X-FlowCatalyst-Signature", sig, "X-FlowCatalyst-Timestamp", ts);
            assertThat(ok.statusCode()).isEqualTo(200);
            assertThat(FnHttpTestSupport.json(ok.body()).path("caller").asString())
                    .as("mutant: drop each check in turn").isEqualTo("Platform");
            long afterOk = countLines(counter);
            assertThat(afterOk).isEqualTo(1);

            // wrong secret
            String wrongSig = WebhookSigner.sign("wrong-secret", ts, body);
            var wrong = h.post("/functions/" + ADDR.render() + "/events/x", body,
                    "X-FlowCatalyst-Signature", wrongSig, "X-FlowCatalyst-Timestamp", ts);
            assertThat(wrong.statusCode()).as("mutant: drop each check in turn").isEqualTo(401);

            // altered body (signature computed over different bytes)
            var alteredBody = h.post("/functions/" + ADDR.render() + "/events/x",
                    "{\"hello\":\"tampered\"}".getBytes(StandardCharsets.UTF_8),
                    "X-FlowCatalyst-Signature", sig, "X-FlowCatalyst-Timestamp", ts);
            assertThat(alteredBody.statusCode()).isEqualTo(401);

            // altered timestamp (signature no longer matches the new timestamp header)
            String otherTs = WebhookSigner.timestamp(Instant.now().plusSeconds(5));
            var alteredTs = h.post("/functions/" + ADDR.render() + "/events/x", body,
                    "X-FlowCatalyst-Signature", sig, "X-FlowCatalyst-Timestamp", otherTs);
            assertThat(alteredTs.statusCode()).isEqualTo(401);

            // stale (301s old)
            String staleTs = WebhookSigner.timestamp(Instant.now().minusSeconds(301));
            String staleSig = WebhookSigner.sign(secret, staleTs, body);
            var stale = h.post("/functions/" + ADDR.render() + "/events/x", body,
                    "X-FlowCatalyst-Signature", staleSig, "X-FlowCatalyst-Timestamp", staleTs);
            assertThat(stale.statusCode()).as("mutant: drop the max-age check").isEqualTo(401);

            // future (61s ahead)
            String futureTs = WebhookSigner.timestamp(Instant.now().plusSeconds(61));
            String futureSig = WebhookSigner.sign(secret, futureTs, body);
            var future = h.post("/functions/" + ADDR.render() + "/events/x", body,
                    "X-FlowCatalyst-Signature", futureSig, "X-FlowCatalyst-Timestamp", futureTs);
            assertThat(future.statusCode()).as("mutant: drop the future-grace check").isEqualTo(401);

            // missing header
            var missingSig = h.post("/functions/" + ADDR.render() + "/events/x", body,
                    "X-FlowCatalyst-Timestamp", ts);
            assertThat(missingSig.statusCode()).isEqualTo(401);

            assertThat(countLines(counter))
                    .as("mutant: the function's invocation counter must stay at 1 — none of the six failures ever invoked it")
                    .isEqualTo(afterOk);

            // spec §2 step 4: "webhook ⇒ POST" — a GET is 405 even though the manifest names no methods (all methods would apply to any other auth mode).
            var wrongMethodWebhook = h.get("/functions/" + ADDR.render() + "/events/x");
            assertThat(wrongMethodWebhook.statusCode()).as("mutant: webhook endpoints stay open to every method").isEqualTo(405);
            assertThat(wrongMethodWebhook.headers().firstValue("Allow")).contains("POST");

            // known vector, shared with the SDK's own committed test (WebhookSignatureTest#knownVectorMatchesOtherSdks)
            assertThat(WebhookSigner.sign("secret", "2026-08-07T09:32:12.123Z", "hello".getBytes(StandardCharsets.UTF_8)))
                    .isEqualTo("a7d62aade7a79e88792f84e035b72788a3c78a30f46ad265ec8d6fb12a4e9f91");
        }
    }

    // ── H3: secret rotation window ────────────────────────────────────────

    @Test
    void h3_secretRotationWindow(@TempDir Path dir) throws Exception {
        Path counter = dir.resolve("counter");
        Path jar = FnHttpTestSupport.functionJar(dir, "echo", "fixture.http.EchoFn", echoSource(counter));
        var manifest = FnHttpTestSupport.manifest("p", false, 10, 5000, "fixture.http.EchoFn",
                """
                [{"path":"/events/*","auth":"webhook"}]
                """);
        String secretV1 = "rot-secret-v1";
        String secretV2 = "rot-secret-v2";
        var entryV1 = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jar, manifest, secretV1, "app_1", "clt_1");
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entryV1))) {
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

            // Rotate the secret (a NEW document, same version — reconciler.reconcileOnce called by publish()).
            var entryV1Rotated = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jar, manifest, secretV2, "app_1", "clt_1");
            h.publish(FnHttpTestSupport.oneFunction(entryV1Rotated)); // reconcile #2 (reconcile #1 was the initial load)

            String ts = WebhookSigner.timestamp(Instant.now());
            String oldSig = WebhookSigner.sign(secretV1, ts, body);
            var acceptedAtSecondReconcile = h.post("/functions/" + ADDR.render() + "/events/x", body,
                    "X-FlowCatalyst-Signature", oldSig, "X-FlowCatalyst-Timestamp", ts);
            assertThat(acceptedAtSecondReconcile.statusCode())
                    .as("mutant: never accept the previous secret").isEqualTo(200);

            h.publish(FnHttpTestSupport.oneFunction(entryV1Rotated)); // reconcile #3: same document, no NEW change,
            // but the window is measured from the reconcile the CHANGE happened at (#2), so #3 is still within it.
            String ts2 = WebhookSigner.timestamp(Instant.now());
            String oldSig2 = WebhookSigner.sign(secretV1, ts2, body);
            var acceptedAtThirdReconcile = h.post("/functions/" + ADDR.render() + "/events/x", body,
                    "X-FlowCatalyst-Signature", oldSig2, "X-FlowCatalyst-Timestamp", ts2);
            assertThat(acceptedAtThirdReconcile.statusCode()).isEqualTo(200);

            h.publish(FnHttpTestSupport.oneFunction(entryV1Rotated)); // reconcile #4: window has now elapsed.
            String ts3 = WebhookSigner.timestamp(Instant.now());
            String oldSig3 = WebhookSigner.sign(secretV1, ts3, body);
            var rejectedAtFourthReconcile = h.post("/functions/" + ADDR.render() + "/events/x", body,
                    "X-FlowCatalyst-Signature", oldSig3, "X-FlowCatalyst-Timestamp", ts3);
            assertThat(rejectedAtFourthReconcile.statusCode())
                    .as("mutant: accept the previous secret for ever").isEqualTo(401);

            // the CURRENT secret always works
            String newSig = WebhookSigner.sign(secretV2, ts3, body);
            var withCurrent = h.post("/functions/" + ADDR.render() + "/events/x", body,
                    "X-FlowCatalyst-Signature", newSig, "X-FlowCatalyst-Timestamp", ts3);
            assertThat(withCurrent.statusCode()).isEqualTo(200);
        }

        // a function with NO secret at all: every webhook call 401, and the heartbeat reports NO_SIGNING_SECRET
        Path counter2 = dir.resolve("counter2");
        Path jar2 = FnHttpTestSupport.functionJar(dir, "echo2", "fixture.http.EchoFn", echoSource(counter2));
        var noSecretEntry = FnHttpTestSupport.liveEntry(ADDR_B, "fnc_2", "v1-b", 1, jar2, manifest, null, "app_2", "clt_2");
        try (var h2 = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(noSecretEntry))) {
            String ts = WebhookSigner.timestamp(Instant.now());
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            var resp = h2.post("/functions/" + ADDR_B.render() + "/events/x", body,
                    "X-FlowCatalyst-Signature", WebhookSigner.sign("anything", ts, body),
                    "X-FlowCatalyst-Timestamp", ts);
            assertThat(resp.statusCode()).as("mutant: accept when no secret").isEqualTo(401);
            assertThat(countLines(counter2)).isEqualTo(0);

            h2.reconciler.reconcileOnce(Instant.now()); // force a heartbeat
            var lastHeartbeat = h2.controlPlane.heartbeats().getLast();
            var loadedEntry = lastHeartbeat.loaded().stream()
                    .filter(le -> le.address().equals(ADDR_B)).findFirst().orElseThrow();
            assertThat(loadedEntry.state()).isInstanceOf(io.flowcatalyst.fnhost.reconcile.HeartbeatReport.LoadState.Failed.class);
            assertThat(((io.flowcatalyst.fnhost.reconcile.HeartbeatReport.LoadState.Failed) loadedEntry.state()).error())
                    .isEqualTo("NO_SIGNING_SECRET");
        }
    }

    // ── H4: platform bearer token, JWKS ─────────────────────────────────

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void advance(Duration d) {
            now.updateAndGet(i -> i.plus(d));
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    @Test
    void h4_platformBearerToken(@TempDir Path dir) throws Exception {
        try (TestJwks jwks = new TestJwks()) {
            Path counter = dir.resolve("counter");
            Path jar = FnHttpTestSupport.functionJar(dir, "echo", "fixture.http.EchoFn", echoSource(counter));
            var manifest = FnHttpTestSupport.manifest("p", false, 10, 5000, "fixture.http.EchoFn",
                    """
                    [{"path":"/api/*","auth":"platform"}]
                    """);
            var entry = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jar, manifest, null, null, null);
            MutableClock clock = new MutableClock(Instant.now());
            var options = new FnHttpServer.Options("0.0.0.0", 0, 512, jwks.issuer, clock);
            try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry), 50, options)) {
                String token = jwks.mint("prn_1", "SERVICE", "CLIENT", "platform:function:function:view",
                        List.of("clt_1"), List.of(), false, Instant.now().plusSeconds(300));
                var ok = h.get("/functions/" + ADDR.render() + "/api/x", "Authorization", "Bearer " + token);
                assertThat(ok.statusCode()).as(new String(ok.body(), StandardCharsets.UTF_8)).isEqualTo(200);
                JsonNode body = FnHttpTestSupport.json(ok.body());
                assertThat(body.path("caller").asString()).isEqualTo("Principal");
                assertThat(body.path("principalId").asString()).isEqualTo("prn_1");
                assertThat(body.path("principalType").asString()).isEqualTo("SERVICE");
                assertThat(body.path("principalClientId").asString()).isEqualTo("clt_1");
                assertThat(body.path("principalPermissions").valueStream().map(JsonNode::asString).toList())
                        .contains("platform:function:function:view");
                assertThat(jwks.jwksRequestCount()).as("the first, unknown kid triggers exactly one fetch").isEqualTo(1);

                // expired
                String expired = jwks.mint("prn_1", "SERVICE", "CLIENT", "x", List.of(), List.of(), false,
                        Instant.now().minusSeconds(10));
                var expiredResp = h.get("/functions/" + ADDR.render() + "/api/x", "Authorization", "Bearer " + expired);
                assertThat(expiredResp.statusCode()).as("mutant: skip expiry").isEqualTo(401);
                assertThat(expiredResp.headers().firstValue("WWW-Authenticate")).contains("Bearer");

                // wrong issuer
                String wrongIssuer = jwks.mintWithIssuer("http://not-the-platform",
                        "prn_1", "SERVICE", "CLIENT", "x", List.of(), List.of(), false, Instant.now().plusSeconds(60));
                var wrongIssuerResp = h.get("/functions/" + ADDR.render() + "/api/x", "Authorization", "Bearer " + wrongIssuer);
                assertThat(wrongIssuerResp.statusCode()).as("mutant: skip issuer").isEqualTo(401);

                // wrong key (foreign, unrelated key pair signs a token with the SAME known kid)
                var foreign = TestJwks.foreignKeyPair();
                String wrongKey = TestJwks.mint((java.security.interfaces.RSAPrivateKey) foreign.getPrivate(), "kid-1",
                        jwks.issuer, "prn_1", "SERVICE", "CLIENT", "x", List.of(), List.of(), false,
                        Instant.now().plusSeconds(60));
                var wrongKeyResp = h.get("/functions/" + ADDR.render() + "/api/x", "Authorization", "Bearer " + wrongKey);
                assertThat(wrongKeyResp.statusCode()).isEqualTo(401);

                // garbage
                var garbageResp = h.get("/functions/" + ADDR.render() + "/api/x", "Authorization", "Bearer not-a-jwt");
                assertThat(garbageResp.statusCode()).isEqualTo(401);

                // A second, still-unknown kid inside the 30s floor must NOT trigger another fetch.
                assertThat(jwks.jwksRequestCount()).as("mutant: refetch every time").isEqualTo(1);

                // Past the 30s floor: an unknown kid refetches again, and rotation is picked up.
                clock.advance(Duration.ofSeconds(31));
                jwks.rotate();
                String postRotation = jwks.mint("prn_1", "SERVICE", "CLIENT", "x", List.of(), List.of(), false,
                        Instant.now().plusSeconds(60));
                var afterRotation = h.get("/functions/" + ADDR.render() + "/api/x", "Authorization", "Bearer " + postRotation);
                assertThat(afterRotation.statusCode()).isEqualTo(200);
                assertThat(jwks.jwksRequestCount()).isEqualTo(2);
            }
        }
    }

    /// P2 (`docs/spec/function-caller-claims.md` §5): the host maps EVERY
    /// claim onto [io.flowcatalyst.function.Caller.Principal] — not just the
    /// four `h4_platformBearerToken` already covers. A token with tier
    /// `CLIENT`, two clients, two roles, one application and
    /// `all_applications=false` reaches the function with exactly those.
    /// Mutant: drop `roles` from `principalFrom`'s mapping — `principalRoles`
    /// would then be empty instead of the two minted roles.
    @Test
    void h4_platformBearerTokenMapsEveryClaim(@TempDir Path dir) throws Exception {
        try (TestJwks jwks = new TestJwks()) {
            Path counter = dir.resolve("counter-claims");
            Path jar = FnHttpTestSupport.functionJar(dir, "echo-claims", "fixture.http.EchoFn", echoSource(counter));
            var manifest = FnHttpTestSupport.manifest("p", false, 10, 5000, "fixture.http.EchoFn",
                    """
                    [{"path":"/api/*","auth":"platform"}]
                    """);
            var entry = FnHttpTestSupport.liveEntry(ADDR, "fnc_claims", "v1", 1, jar, manifest, null, null, null);
            var options = new FnHttpServer.Options("0.0.0.0", 0, 512, jwks.issuer, Clock.systemUTC());
            try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry), 50, options)) {
                String token = jwks.mint("prn_claims", "SERVICE", "CLIENT", "platform:function:function:view",
                        List.of("clt_1", "clt_2"), List.of("role-a", "role-b"), List.of("app_1"), false,
                        Instant.now().plusSeconds(300));
                var ok = h.get("/functions/" + ADDR.render() + "/api/x", "Authorization", "Bearer " + token);
                assertThat(ok.statusCode()).as(new String(ok.body(), StandardCharsets.UTF_8)).isEqualTo(200);
                JsonNode body = FnHttpTestSupport.json(ok.body());
                assertThat(body.path("principalId").asString()).isEqualTo("prn_claims");
                assertThat(body.path("principalType").asString()).isEqualTo("SERVICE");
                assertThat(body.path("principalTier").asString()).isEqualTo("CLIENT");
                // Two real clients: neither is unambiguous, so clientId() is empty/null.
                assertThat(body.path("principalClientId").isNull()).isTrue();
                assertThat(body.path("principalClients").valueStream().map(JsonNode::asString).toList())
                        .containsExactly("clt_1", "clt_2");
                assertThat(body.path("principalRoles").valueStream().map(JsonNode::asString).toList())
                        .as("mutant: drop roles from principalFrom's mapping")
                        .containsExactly("role-a", "role-b");
                assertThat(body.path("principalApplications").valueStream().map(JsonNode::asString).toList())
                        .containsExactly("app_1");
                assertThat(body.path("principalAllApplications").asBoolean()).isFalse();
                assertThat(body.path("principalPermissions").valueStream().map(JsonNode::asString).toList())
                        .contains("platform:function:function:view");
            }
        }
    }

    /// Defect fix: the issuer comes from discovery
    /// (`<platformUrl>/.well-known/openid-configuration`'s `issuer`), never
    /// from `platformUrl` itself — [TestJwks]'s own address (`issuer`) and its
    /// discovery document's `issuer` (`discoveryIssuer`) are deliberately
    /// different, mirroring the real gap between the host's internal reach
    /// address and the platform's external base URL.
    @Test
    void h4_issuerTakenFromDiscovery(@TempDir Path dir) throws Exception {
        try (TestJwks jwks = new TestJwks()) {
            Path jar = FnHttpTestSupport.functionJar(dir, "echo-disc", "fixture.http.EchoFn",
                    echoSource(dir.resolve("counter-disc")));
            var manifest = FnHttpTestSupport.manifest("p", false, 10, 5000, "fixture.http.EchoFn",
                    """
                    [{"path":"/api/*","auth":"platform"}]
                    """);
            var entry = FnHttpTestSupport.liveEntry(ADDR, "fnc_disc", "v1", 1, jar, manifest, null, null, null);
            var options = new FnHttpServer.Options("0.0.0.0", 0, 512, jwks.issuer, Clock.systemUTC());
            try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry), 50, options)) {
                // A token whose `iss` is the DISCOVERED issuer is accepted.
                String good = jwks.mint("prn_1", "SERVICE", "CLIENT", "x", List.of(), List.of(), false,
                        Instant.now().plusSeconds(60));
                var okResp = h.get("/functions/" + ADDR.render() + "/api/x", "Authorization", "Bearer " + good);
                assertThat(okResp.statusCode()).as("iss = the discovered issuer must be accepted").isEqualTo(200);

                // A token whose `iss` is platformUrl itself (the address the host reaches the
                // platform BY, not the platform's own external base URL) must be rejected —
                // this is the original defect: comparing against platformUrl directly.
                String usingPlatformUrlAsIssuer = jwks.mintWithIssuer(jwks.issuer, "prn_1", "SERVICE", "CLIENT", "x",
                        List.of(), List.of(), false, Instant.now().plusSeconds(60));
                var wrongResp = h.get("/functions/" + ADDR.render() + "/api/x", "Authorization",
                        "Bearer " + usingPlatformUrlAsIssuer);
                assertThat(wrongResp.statusCode()).as("mutant: use platformUrl as issuer").isEqualTo(401);
            }
        }
    }

    /// Defect fix: `jwks_uri` from the discovery document is followed only
    /// when it names the SAME origin as `platformUrl`; a foreign origin (the
    /// platform's own external address, which the host may not be able to
    /// reach) is ignored and keys keep coming from
    /// `<platformUrl>/.well-known/jwks.json`.
    @Test
    void h4_foreignJwksUriIsNotFollowed(@TempDir Path dir) throws Exception {
        try (TestJwks jwks = new TestJwks()) {
            // A JWKS endpoint on a DIFFERENT origin serving keys that can never verify this
            // test's tokens — if the host mistakenly followed a foreign `jwks_uri`, the call
            // would end up 401 (no matching key) instead of the 200 correctly ignoring it
            // produces (the real, same-origin jwks.json still has the right key).
            var decoy = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            decoy.createContext("/.well-known/jwks.json", ex -> {
                byte[] body = "{\"keys\":[]}".getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
                ex.close();
            });
            decoy.start();
            try {
                jwks.useForeignJwksUri("http://127.0.0.1:" + decoy.getAddress().getPort() + "/.well-known/jwks.json");

                Path jar = FnHttpTestSupport.functionJar(dir, "echo-foreign", "fixture.http.EchoFn",
                        echoSource(dir.resolve("counter-foreign")));
                var manifest = FnHttpTestSupport.manifest("p", false, 10, 5000, "fixture.http.EchoFn",
                        """
                        [{"path":"/api/*","auth":"platform"}]
                        """);
                var entry = FnHttpTestSupport.liveEntry(ADDR, "fnc_foreign", "v1", 1, jar, manifest, null, null, null);
                var options = new FnHttpServer.Options("0.0.0.0", 0, 512, jwks.issuer, Clock.systemUTC());
                try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry), 50, options)) {
                    String token = jwks.mint("prn_1", "SERVICE", "CLIENT", "x", List.of(), List.of(), false,
                            Instant.now().plusSeconds(60));
                    var resp = h.get("/functions/" + ADDR.render() + "/api/x", "Authorization", "Bearer " + token);
                    assertThat(resp.statusCode()).as("mutant: follow a foreign jwks_uri").isEqualTo(200);
                }
            } finally {
                decoy.stop(0);
            }
        }
    }

    /// Defect fix: discovery is lazy and floor-gated exactly like the JWKS
    /// fetch. While the platform's discovery endpoint is down, every
    /// `platform`-auth call is 401 (never the OLD platformUrl-as-issuer
    /// fallback) and a retry inside the 30 s floor does not re-hit the
    /// network; once discovery is back up and the floor has elapsed, the very
    /// next call succeeds.
    @Test
    void h4_discoveryDownThenUp(@TempDir Path dir) throws Exception {
        try (TestJwks jwks = new TestJwks()) {
            jwks.breakDiscovery();
            Path jar = FnHttpTestSupport.functionJar(dir, "echo-down", "fixture.http.EchoFn",
                    echoSource(dir.resolve("counter-down")));
            var manifest = FnHttpTestSupport.manifest("p", false, 10, 5000, "fixture.http.EchoFn",
                    """
                    [{"path":"/api/*","auth":"platform"}]
                    """);
            var entry = FnHttpTestSupport.liveEntry(ADDR, "fnc_down", "v1", 1, jar, manifest, null, null, null);
            MutableClock clock = new MutableClock(Instant.now());
            var options = new FnHttpServer.Options("0.0.0.0", 0, 512, jwks.issuer, clock);
            try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry), 50, options)) {
                String token = jwks.mint("prn_1", "SERVICE", "CLIENT", "x", List.of(), List.of(), false,
                        Instant.now().plusSeconds(60));

                var down = h.get("/functions/" + ADDR.render() + "/api/x", "Authorization", "Bearer " + token);
                assertThat(down.statusCode()).as("discovery down: no issuer yet, every platform call is 401")
                        .isEqualTo(401);
                assertThat(down.headers().firstValue("WWW-Authenticate")).contains("Bearer");

                // Still down, still inside the 30s floor: no retry yet.
                var stillDown = h.get("/functions/" + ADDR.render() + "/api/x", "Authorization", "Bearer " + token);
                assertThat(stillDown.statusCode()).isEqualTo(401);
                assertThat(jwks.discoveryRequestCount()).as("mutant: refetch discovery on every request").isEqualTo(1);

                jwks.fixDiscovery();
                clock.advance(Duration.ofSeconds(31));
                var up = h.get("/functions/" + ADDR.render() + "/api/x", "Authorization", "Bearer " + token);
                assertThat(up.statusCode()).as("discovery back up, past the floor: succeeds").isEqualTo(200);
            }
        }
    }

    // ── H5: header stripping ────────────────────────────────────────────

    @Test
    void h5_headerStrippingByAuthMode(@TempDir Path dir) throws Exception {
        Path counter = dir.resolve("counter");
        Path jar = FnHttpTestSupport.functionJar(dir, "echo", "fixture.http.EchoFn", echoSource(counter));
        var manifest = FnHttpTestSupport.manifest("p", false, 10, 5000, "fixture.http.EchoFn",
                """
                [{"path":"/none/*","auth":"none"},{"path":"/events/*","auth":"webhook"}]
                """);
        String secret = "h5-secret";
        var entry = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jar, manifest, secret, "app_1", "clt_1");
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry))) {
            var none = h.get("/functions/" + ADDR.render() + "/none/x",
                    "Authorization", "Bearer keepme", "X-FlowCatalyst-Signature", "sigkeepme",
                    "X-FlowCatalyst-Timestamp", "tskeepme");
            assertThat(none.statusCode()).isEqualTo(200);
            JsonNode noneBody = FnHttpTestSupport.json(none.body());
            assertThat(noneBody.path("headerAuthorization").asString()).as("mutant: strip always").isEqualTo("Bearer keepme");
            assertThat(noneBody.path("headerSignature").asString()).isEqualTo("sigkeepme");
            assertThat(noneBody.path("headerTimestamp").asString()).isEqualTo("tskeepme");

            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            String ts = WebhookSigner.timestamp(Instant.now());
            String sig = WebhookSigner.sign(secret, ts, body);
            var webhook = h.post("/functions/" + ADDR.render() + "/events/x", body,
                    "Authorization", "Bearer strip-me", "X-FlowCatalyst-Signature", sig, "X-FlowCatalyst-Timestamp", ts);
            assertThat(webhook.statusCode()).isEqualTo(200);
            JsonNode webhookBody = FnHttpTestSupport.json(webhook.body());
            assertThat(webhookBody.path("headerAuthorization").isNull()).as("mutant: never strip").isTrue();
            assertThat(webhookBody.path("headerSignature").isNull()).isTrue();
            assertThat(webhookBody.path("headerTimestamp").isNull()).isTrue();
        }
    }

    // ── H6: permits (per-function and host-global) ──────────────────────

    @Test
    void h6_perFunctionPermits(@TempDir Path dir) throws Exception {
        Path started = dir.resolve("started");
        Path release = dir.resolve("release");
        Path jar = FnHttpTestSupport.functionJar(dir, "park", "fixture.http.ParkingFn", parkingSource(started, release, "v1"));
        var manifest = FnHttpTestSupport.manifest("p", false, 1, 20000, "fixture.http.ParkingFn",
                """
                [{"path":"/*","auth":"none"}]
                """);
        var entry = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jar, manifest, null, null, null);
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry))) {
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            CompletableFuture<HttpResponse<byte[]>> first =
                    CompletableFuture.supplyAsync(() -> h.get("/functions/" + ADDR.render() + "/park"), executor);
            awaitFile(started);

            var second = h.get("/functions/" + ADDR.render() + "/park");
            assertThat(second.statusCode()).as("mutant: queue instead of tryAcquire").isEqualTo(429);
            assertThat(second.headers().firstValue("Retry-After")).contains("1");
            assertThat(h.server.permitsForTest().functionAvailable(ADDR))
                    .as("the function is not entered — its permit is fully exhausted at 0").isEqualTo(0);
            assertThat(h.server.permitsForTest().hostAvailable())
                    .as("mutant: a failed function-permit acquire must still return the host permit it already took")
                    .isEqualTo(511);

            Files.writeString(release, "x");
            assertThat(first.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);

            // Third call after release: succeeds again (permit freed).
            Files.deleteIfExists(release);
            CompletableFuture<HttpResponse<byte[]>> third =
                    CompletableFuture.supplyAsync(() -> h.get("/functions/" + ADDR.render() + "/park"), executor);
            awaitFile(started); // re-created by the third call
            Files.writeString(release, "x");
            assertThat(third.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void h6_hostGlobalPermitSharedAcrossFunctions(@TempDir Path dir) throws Exception {
        Path startedA = dir.resolve("started-a");
        Path releaseA = dir.resolve("release-a");
        Path jarA = FnHttpTestSupport.functionJar(dir, "park-a", "fixture.http.ParkingFn", parkingSource(startedA, releaseA, "a"));
        Path startedB = dir.resolve("started-b");
        Path releaseB = dir.resolve("release-b");
        Path jarB = FnHttpTestSupport.functionJar(dir, "park-b", "fixture.http.ParkingFnB",
                parkingSourceNamed("fixture.http.ParkingFnB", startedB, releaseB, "b"));

        var manifest = FnHttpTestSupport.manifest("p", false, 5, 20000, "fixture.http.ParkingFn",
                "[{\"path\":\"/*\",\"auth\":\"none\"}]");
        var manifestB = FnHttpTestSupport.manifest("p", false, 5, 20000, "fixture.http.ParkingFnB",
                "[{\"path\":\"/*\",\"auth\":\"none\"}]");
        var entryA = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jarA, manifest, null, null, null);
        // A distinct versionId: the reconciler keys prepared artifacts by it, and two entries sharing
        // one id race for the same slot (this test hung one run in three until they differed).
        var entryB = FnHttpTestSupport.liveEntry(ADDR_B, "fnc_2", "v1-b", 1, jarB, manifestB, null, null, null);

        var options = FnHttpServer.Options.of(0, 1, "http://127.0.0.1:1"); // host-global cap = 1
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.document(List.of(entryA, entryB)), 50, options)) {
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            CompletableFuture<HttpResponse<byte[]>> a =
                    CompletableFuture.supplyAsync(() -> h.get("/functions/" + ADDR.render() + "/park"), executor);
            awaitFile(startedA);

            var b = h.get("/functions/" + ADDR_B.render() + "/park");
            assertThat(b.statusCode()).as("the host-global permit is exhausted by A, so B is refused too").isEqualTo(429);
            assertThat(h.server.permitsForTest().hostAvailable()).isEqualTo(0);

            Files.writeString(releaseA, "x");
            assertThat(a.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(h.server.permitsForTest().hostAvailable())
                    .as("mutant: leak the host permit").isEqualTo(1);
        }
    }

    private static String parkingSourceNamed(String className, Path started, Path release, String tag) {
        String simple = className.substring(className.lastIndexOf('.') + 1);
        return """
                package fixture.http;
                import io.flowcatalyst.function.*;
                import java.nio.file.*;

                public final class %s implements Function {
                    public Result handle(Request in, FunctionContext ctx) throws Exception {
                        Files.writeString(Path.of("%s"), "x");
                        // Safety bound — see ParkingFn's own copy of this comment (#parkingSource).
                        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(90).toNanos();
                        while (!Files.exists(Path.of("%s")) && System.nanoTime() < deadline) {
                            try { Thread.sleep(15); } catch (InterruptedException ignored) { }
                        }
                        return Result.json(200, "{\\"tag\\":\\"%s\\"}");
                    }
                }
                """.formatted(simple, path(started), path(release), tag);
    }

    private static void awaitFile(Path marker) throws InterruptedException {
        // 60s, not 10s: under a full-suite/contended run the first request through a fresh
        // Harness also pays for FixtureJars' javax.tools.JavaCompiler compile + JVM class-load
        // warm-up, which can occasionally take much longer alongside the rest of this module's
        // heavier (embedded-Postgres-backed) tests' CPU/GC pressure — this bound is about that
        // startup cost, not the (near-instant) file write itself.
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(marker)) return;
            Thread.sleep(10);
        }
        throw new AssertionError("timed out waiting for " + marker);
    }

    // ── H7: timeout, interrupt, deferred permit release ─────────────────

    @Test
    void h7_timeoutInterruptsAndDefersPermitRelease(@TempDir Path dir) throws Exception {
        Path started = dir.resolve("started");
        Path release = dir.resolve("release");
        Path jar = FnHttpTestSupport.functionJar(dir, "park", "fixture.http.ParkingFn", parkingSource(started, release, "v1"));
        var manifest = FnHttpTestSupport.manifest("p", false, 1, 200, "fixture.http.ParkingFn",
                "[{\"path\":\"/*\",\"auth\":\"none\",\"timeoutMs\":200}]"); // 200ms timeout, maxConcurrency 1
        var entry = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jar, manifest, null, null, null);
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry))) {
            long start = System.nanoTime();
            var resp = h.get("/functions/" + ADDR.render() + "/park");
            long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertThat(resp.statusCode()).as("mutant: never time out").isEqualTo(504);
            assertThat(elapsedMs).as("the deadline, not the eventual release, ends the HTTP response").isLessThan(5000);

            assertThat(h.server.permitsForTest().functionAvailable(ADDR))
                    .as("mutant: release the permit at the deadline — it must still be held while the function runs on")
                    .isEqualTo(0);

            Files.writeString(release, "x"); // let the swallowing worker finally return
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline && h.server.permitsForTest().functionAvailable(ADDR) == 0) {
                Thread.sleep(10);
            }
            assertThat(h.server.permitsForTest().functionAvailable(ADDR))
                    .as("freed once the worker actually returns").isEqualTo(1);
        }
    }

    // ── H8: throw / null ⇒ 500 FUNCTION_ERROR, no leaked message, permits released ──

    @Test
    void h8_throwAndNullBothBecome500WithNoLeakedMessage(@TempDir Path dir) throws Exception {
        Path jarThrow = FnHttpTestSupport.functionJar(dir, "throw", "fixture.http.ThrowingFn", THROWING_SOURCE);
        var manifestThrow = FnHttpTestSupport.manifest("p", false, 5, 5000, "fixture.http.ThrowingFn",
                "[{\"path\":\"/*\",\"auth\":\"none\"}]");
        var entryThrow = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jarThrow, manifestThrow, null, null, null);
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entryThrow))) {
            var resp = h.get("/functions/" + ADDR.render() + "/x");
            assertThat(resp.statusCode()).isEqualTo(500);
            String body = new String(resp.body(), StandardCharsets.UTF_8);
            assertThat(body).as("mutant: echo the message").doesNotContain("do-not-leak-this-message");
            assertThat(FnHttpTestSupport.json(resp.body()).path("error").asString()).isEqualTo("FUNCTION_ERROR");
            assertThat(h.server.permitsForTest().functionAvailable(ADDR)).isEqualTo(5);
        }

        Path jarNull = FnHttpTestSupport.functionJar(dir, "null", "fixture.http.NullFn", NULL_SOURCE);
        var manifestNull = FnHttpTestSupport.manifest("p", false, 5, 5000, "fixture.http.NullFn",
                "[{\"path\":\"/*\",\"auth\":\"none\"}]");
        var entryNull = FnHttpTestSupport.liveEntry(ADDR_B, "fnc_2", "v1-b", 1, jarNull, manifestNull, null, null, null);
        try (var h2 = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entryNull))) {
            var resp = h2.get("/functions/" + ADDR_B.render() + "/x");
            assertThat(resp.statusCode()).as("mutant: treat a null Result as success").isEqualTo(500);
            assertThat(FnHttpTestSupport.json(resp.body()).path("error").asString()).isEqualTo("FUNCTION_ERROR");
            assertThat(h2.server.permitsForTest().functionAvailable(ADDR_B)).isEqualTo(5);
        }
    }

    // ── H9: body cap ─────────────────────────────────────────────────────

    @Test
    void h9_bodyCap(@TempDir Path dir) throws Exception {
        Path counter = dir.resolve("counter");
        Path jar = FnHttpTestSupport.functionJar(dir, "echo", "fixture.http.EchoFn", echoSource(counter));
        var manifest = FnHttpTestSupport.manifest("p", false, 5, 5000, "fixture.http.EchoFn",
                "[{\"path\":\"/*\",\"auth\":\"none\",\"maxBodyBytes\":10}]");
        var entry = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jar, manifest, null, null, null);
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry))) {
            // over cap, declared Content-Length: 413 before reading.
            byte[] over = "0123456789X".getBytes(StandardCharsets.UTF_8); // 11 bytes
            var overResp = h.post("/functions/" + ADDR.render() + "/x", over);
            assertThat(overResp.statusCode()).as("mutant: check after buffering").isEqualTo(413);
            assertThat(countLines(counter)).isEqualTo(0);

            // chunked, over cap mid-stream.
            var chunkedReq = HttpRequest.newBuilder(URI.create(h.url("/functions/" + ADDR.render() + "/x")))
                    .POST(HttpRequest.BodyPublishers.ofInputStream(
                            () -> new java.io.ByteArrayInputStream(over)))
                    .build();
            var chunkedResp = h.send(chunkedReq);
            assertThat(chunkedResp.statusCode()).isEqualTo(413);
            assertThat(countLines(counter)).isEqualTo(0);

            // exactly at the cap: 200.
            byte[] atCap = "0123456789".getBytes(StandardCharsets.UTF_8); // 10 bytes
            var atCapResp = h.post("/functions/" + ADDR.render() + "/x", atCap);
            assertThat(atCapResp.statusCode()).isEqualTo(200);
            assertThat(FnHttpTestSupport.json(atCapResp.body()).path("bodyLength").asInt()).isEqualTo(10);
            assertThat(countLines(counter)).isEqualTo(1);
        }
    }

    // ── H10: response building ───────────────────────────────────────────

    @Test
    void h10_responseHopByHopAndContentLengthDropped(@TempDir Path dir) throws Exception {
        Path counter = dir.resolve("counter");
        Path jar = FnHttpTestSupport.functionJar(dir, "echo", "fixture.http.EchoFn", echoSource(counter));
        var manifest = FnHttpTestSupport.manifest("p", false, 5, 5000, "fixture.http.EchoFn",
                "[{\"path\":\"/*\",\"auth\":\"none\"}]");
        var entry = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jar, manifest, null, null, null);
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry))) {
            var resp = h.get("/functions/" + ADDR.render() + "/x?status=201");
            assertThat(resp.statusCode()).as("mutant: pass everything — status must come from the function").isEqualTo(201);
            assertThat(resp.headers().allValues("X-Multi")).as("multi-valued headers pass verbatim").containsExactly("a", "b");
            assertThat(resp.headers().firstValue("Connection"))
                    .as("mutant: pass everything — hop-by-hop headers must be dropped").isEmpty();
            long actualLength = Long.parseLong(resp.headers().firstValue("Content-Length").orElseThrow());
            assertThat(actualLength).as("mutant: pass everything — Content-Length must be the host's own")
                    .isEqualTo(resp.body().length).isNotEqualTo(999);
        }
    }

    // ── H11: versioned invoke ────────────────────────────────────────────

    @Test
    void h11_versionedInvoke(@TempDir Path dir) throws Exception {
        try (TestJwks jwks = new TestJwks()) {
            Path counter1 = dir.resolve("counter1");
            Path counter2 = dir.resolve("counter2");
            Path jar1 = FnHttpTestSupport.functionJar(dir, "v1", "fixture.http.EchoFn", echoSource(counter1));
            Path jar2 = FnHttpTestSupport.functionJar(dir, "v2", "fixture.http.EchoFn2",
                    echoSourceNamed("fixture.http.EchoFn2", counter2));
            var manifest1 = FnHttpTestSupport.manifest("p", false, 5, 5000, "fixture.http.EchoFn",
                    "[{\"path\":\"/events/*\",\"auth\":\"webhook\"},{\"path\":\"/x\",\"auth\":\"none\"}]");
            var manifest2 = FnHttpTestSupport.manifest("p", false, 5, 5000, "fixture.http.EchoFn2",
                    "[{\"path\":\"/x\",\"auth\":\"none\"}]");

            var liveV1 = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jar1, manifest1, "wh-secret",
                    "app_1", "clt_1");
            var candidateV2 = FnHttpTestSupport.candidateEntry(ADDR, "fnc_1", "v2", 2, jar2, manifest2,
                    "app_1", "clt_1");
            var options = new FnHttpServer.Options("0.0.0.0", 0, 512, jwks.issuer, Clock.systemUTC());
            try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.document(List.of(liveV1, candidateV2)), 50, options)) {
                String tokenNoPerm = jwks.mint("prn_v", "SERVICE", "CLIENT", "platform:function:function:view",
                        List.of("clt_1"), List.of(), false, Instant.now().plusSeconds(60));
                String tokenWithPerm = jwks.mint("prn_v", "SERVICE", "CLIENT",
                        "platform:function:version:invoke", List.of("clt_1"), List.of(), true,
                        Instant.now().plusSeconds(60));
                String tokenAnchor = jwks.mint("prn_anchor", "SERVICE", "ANCHOR",
                        "platform:function:version:invoke", List.of(), List.of(), true,
                        Instant.now().plusSeconds(60));
                // allApplications=true isolates this token's failure to the CLIENT check alone — the
                // application check would otherwise ALSO fail it (both are ANDed), masking a mutant
                // that drops only the client-scope check.
                String tokenOtherClient = jwks.mint("prn_other", "SERVICE", "CLIENT",
                        "platform:function:version:invoke", List.of("clt_OTHER"), List.of(), true,
                        Instant.now().plusSeconds(60));
                String tokenRestrictedApp = jwks.mint("prn_app", "SERVICE", "CLIENT",
                        "platform:function:version:invoke", List.of("clt_1"), List.of("app_OTHER"), false,
                        Instant.now().plusSeconds(60));

                // no token
                var noToken = h.get("/functions/" + ADDR.render() + ":2/x");
                assertThat(noToken.statusCode()).isEqualTo(401);

                // token without the permission
                var noPerm = h.get("/functions/" + ADDR.render() + ":2/x", "Authorization", "Bearer " + tokenNoPerm);
                assertThat(noPerm.statusCode()).as("mutant: drop each of the four checks").isEqualTo(403);

                // without reach: another client's token
                var otherClient = h.get("/functions/" + ADDR.render() + ":2/x", "Authorization", "Bearer " + tokenOtherClient);
                assertThat(otherClient.statusCode()).as("mutant: drop each of the four checks").isEqualTo(404);

                // without reach: application-restricted token missing this function's application
                var restrictedApp = h.get("/functions/" + ADDR.render() + ":2/x", "Authorization", "Bearer " + tokenRestrictedApp);
                assertThat(restrictedApp.statusCode()).as("mutant: drop the application-scope reach check").isEqualTo(404);

                // unknown version
                var unknownVersion = h.get("/functions/" + ADDR.render() + ":99/x", "Authorization", "Bearer " + tokenWithPerm);
                assertThat(unknownVersion.statusCode()).isEqualTo(404);

                // candidate served ONLY under :2, never unversioned (unversioned still gets v1, live)
                var candidateOk = h.get("/functions/" + ADDR.render() + ":2/x", "Authorization", "Bearer " + tokenWithPerm);
                assertThat(candidateOk.statusCode()).as("mutant: let unversioned fall through to pinnedVersions").isEqualTo(200);
                assertThat(FnHttpTestSupport.json(candidateOk.body()).has("instanceId"))
                        .as("the v2 fixture answered").isTrue();

                var unversionedStillLive = h.get("/functions/" + ADDR.render() + "/x");
                assertThat(unversionedStillLive.statusCode()).isEqualTo(200);

                // anchor reaches regardless of client scope
                var anchorOk = h.get("/functions/" + ADDR.render() + ":2/x", "Authorization", "Bearer " + tokenAnchor);
                assertThat(anchorOk.statusCode()).isEqualTo(200);

                // a webhook endpoint is reachable versioned WITHOUT a signature
                var versionedWebhook = h.post("/functions/" + ADDR.render() + ":1/events/x",
                        "{}".getBytes(StandardCharsets.UTF_8), "Authorization", "Bearer " + tokenWithPerm);
                assertThat(versionedWebhook.statusCode()).as("mutant: still require the endpoint's own auth").isEqualTo(200);
                assertThat(FnHttpTestSupport.json(versionedWebhook.body()).path("caller").asString())
                        .as("the caller is the Principal, not Platform, for a versioned call").isEqualTo("Principal");
            }
        }
    }

    private static String echoSourceNamed(String className, Path counterFile) {
        return echoSource(counterFile).replace("EchoFn", className.substring(className.lastIndexOf('.') + 1));
    }

    // ── H11b: versioned auth ordering — token(401) before permission(403) before
    //    version-exists+reach(404), all indistinguishable from a probing caller's view ──

    @Test
    void h11b_versionedAuthFailuresAreIndistinguishableForAnExistingOrUnknownVersion(@TempDir Path dir) throws Exception {
        try (TestJwks jwks = new TestJwks()) {
            Path counter = dir.resolve("counter");
            Path jar = FnHttpTestSupport.functionJar(dir, "v1", "fixture.http.EchoFn", echoSource(counter));
            var manifest = FnHttpTestSupport.manifest("p", false, 5, 5000, "fixture.http.EchoFn",
                    "[{\"path\":\"/x\",\"auth\":\"none\"}]");
            var liveV1 = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jar, manifest, null, "app_1", "clt_1");
            var options = new FnHttpServer.Options("0.0.0.0", 0, 512, jwks.issuer, Clock.systemUTC());
            try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(liveV1), 50, options)) {
                // no token: an EXISTING version and a version that has NEVER existed must answer
                // identically — a probing, unauthenticated caller must not be able to tell them apart.
                var noTokenExisting = h.get("/functions/" + ADDR.render() + ":1/x");
                var noTokenMissing = h.get("/functions/" + ADDR.render() + ":99/x");
                assertThat(noTokenExisting.statusCode()).isEqualTo(401);
                assertThat(noTokenMissing.statusCode())
                        .as("mutant: check version-exists before auth — a missing version would then answer 404, not 401")
                        .isEqualTo(401);
                assertThat(noTokenMissing.body())
                        .as("mutant: check version-exists before auth — the failure must be byte-identical either way")
                        .isEqualTo(noTokenExisting.body());
                assertThat(countLines(counter)).isEqualTo(0);

                // a token that authenticates but lacks the permission: same indistinguishability, at 403.
                String tokenNoPerm = jwks.mint("prn_v", "SERVICE", "CLIENT", "platform:function:function:view",
                        List.of("clt_1"), List.of(), false, Instant.now().plusSeconds(60));
                var noPermExisting = h.get("/functions/" + ADDR.render() + ":1/x", "Authorization", "Bearer " + tokenNoPerm);
                var noPermMissing = h.get("/functions/" + ADDR.render() + ":99/x", "Authorization", "Bearer " + tokenNoPerm);
                assertThat(noPermExisting.statusCode()).isEqualTo(403);
                assertThat(noPermMissing.statusCode())
                        .as("mutant: check version-exists before the permission check").isEqualTo(403);
                assertThat(noPermMissing.body())
                        .as("mutant: check version-exists before the permission check — must be byte-identical either way")
                        .isEqualTo(noPermExisting.body());
                assertThat(countLines(counter)).isEqualTo(0);
            }
        }
    }

    // ── H11c: versioned authentication (JWKS fetch) never runs on the event loop ────

    @Test
    @Timeout(45) // a hard safety net — nothing here is meant to take this long even under a mutant
    void h11c_versionedAuthenticationRunsOffTheEventLoopNotOnIt(@TempDir Path dir) throws Exception {
        try (TestJwks jwks = new TestJwks()) {
            Path counterA = dir.resolve("counterA");
            Path jarA = FnHttpTestSupport.functionJar(dir, "a", "fixture.http.EchoFn", echoSource(counterA));
            var manifestA = FnHttpTestSupport.manifest("p", false, 5, 5000, "fixture.http.EchoFn",
                    "[{\"path\":\"/x\",\"auth\":\"none\"}]");
            var entryA = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jarA, manifestA, null, null, null);

            Path counterB = dir.resolve("counterB");
            Path jarB = FnHttpTestSupport.functionJar(dir, "b", "fixture.http.EchoFnB",
                    echoSourceNamed("fixture.http.EchoFnB", counterB));
            var manifestB = FnHttpTestSupport.manifest("p", false, 5, 5000, "fixture.http.EchoFnB",
                    "[{\"path\":\"/x\",\"auth\":\"none\"}]");
            var entryB = FnHttpTestSupport.liveEntry(ADDR_B, "fnc_2", "v1-b", 1, jarB, manifestB, null, "app_2", "clt_2");

            // Exactly ONE event-loop thread — this test means nothing with more than one (H1).
            var options = new FnHttpServer.Options("0.0.0.0", 0, 512, jwks.issuer, Clock.systemUTC(), 1);
            try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.document(List.of(entryA, entryB)), 50, options);
                 var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                jwks.parkNextJwksFetch();
                String tokenUnknownKid = jwks.mint("prn_v", "SERVICE", "CLIENT", "platform:function:version:invoke",
                        List.of("clt_2"), List.of(), true, Instant.now().plusSeconds(60));

                CompletableFuture<HttpResponse<byte[]>> versionedCall = CompletableFuture.supplyAsync(
                        () -> h.get("/functions/" + ADDR_B.render() + ":1/x", "Authorization", "Bearer " + tokenUnknownKid),
                        executor);

                assertThat(jwks.awaitFetchStarted(Duration.ofSeconds(10)))
                        .as("the versioned call's own auth must actually reach the JWKS fetch for this test to mean anything")
                        .isTrue();

                // While that fetch is parked, an UNVERSIONED call to a DIFFERENT function — which never
                // touches auth at all — must still complete promptly: the single event-loop thread was
                // not itself blocked inside the versioned call's authentication. Fired on its own thread
                // and awaited with a SHORT, explicit bound — never the client's own (much longer) request
                // timeout — so a mutant that blocks the event loop fails this test quickly, not slowly.
                CompletableFuture<HttpResponse<byte[]>> unrelatedCall = CompletableFuture.supplyAsync(
                        () -> h.get("/functions/" + ADDR.render() + "/x"), executor);
                HttpResponse<byte[]> unrelated;
                try {
                    unrelated = unrelatedCall.get(3, TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    throw new AssertionError("mutant: authenticate the versioned call on the event loop — an "
                            + "unrelated call to a different function never got a chance to run", e);
                } finally {
                    jwks.releaseParkedFetch(); // let the parked fetch go regardless, so cleanup never hangs
                }
                assertThat(unrelated.statusCode())
                        .as("mutant: authenticate the versioned call on the event loop")
                        .isEqualTo(200);

                var versionedResp = versionedCall.get(15, TimeUnit.SECONDS);
                assertThat(versionedResp.statusCode()).isEqualTo(200);
            }
        }
    }

    // ── H: PinnedVersions.sweep runs after every reconcile (function-invocation.md §2) ──

    @Test
    void pinnedVersionsAreClosedOnceTheirVersionLeavesDesiredState(@TempDir Path dir) throws Exception {
        try (TestJwks jwks = new TestJwks()) {
            Path jar1 = FnHttpTestSupport.functionJar(dir, "sv1", "fixture.http.EchoFn", echoSource(dir.resolve("counter1")));
            Path jar2 = FnHttpTestSupport.functionJar(dir, "sv2", "fixture.http.EchoFnS2",
                    echoSourceNamed("fixture.http.EchoFnS2", dir.resolve("counter2")));
            var manifest1 = FnHttpTestSupport.manifest("p", false, 5, 5000, "fixture.http.EchoFn",
                    "[{\"path\":\"/x\",\"auth\":\"none\"}]");
            var manifest2 = FnHttpTestSupport.manifest("p", false, 5, 5000, "fixture.http.EchoFnS2",
                    "[{\"path\":\"/x\",\"auth\":\"none\"}]");
            var liveV1 = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "sv1", 1, jar1, manifest1, null, "app_1", "clt_1");
            var candidateV2 = FnHttpTestSupport.candidateEntry(ADDR, "fnc_1", "sv2", 2, jar2, manifest2, "app_1", "clt_1");
            var options = new FnHttpServer.Options("0.0.0.0", 0, 512, jwks.issuer, Clock.systemUTC());
            try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.document(List.of(liveV1, candidateV2)), 50, options)) {
                String token = jwks.mint("prn_sweep", "SERVICE", "CLIENT", "platform:function:version:invoke",
                        List.of("clt_1"), List.of(), true, Instant.now().plusSeconds(60));

                var before = h.get("/functions/" + ADDR.render() + ":2/x", "Authorization", "Bearer " + token);
                assertThat(before.statusCode()).isEqualTo(200);
                LoadedFunction pinned = h.server.pinnedVersions().getOrLoad(candidateV2); // the same cached instance
                assertThat(pinned).isNotNull();
                assertThat(pinned.isClosed()).isFalse();

                // v2 leaves desired state — only v1 remains.
                h.publish(FnHttpTestSupport.oneFunction(liveV1));

                assertThat(pinned.isClosed())
                        .as("mutant: never sweep — the pinned loader must be closed once its version leaves desired state")
                        .isTrue();

                var after = h.get("/functions/" + ADDR.render() + ":2/x", "Authorization", "Bearer " + token);
                assertThat(after.statusCode()).as("mutant: never sweep").isEqualTo(404);
            }
        }
    }

    // ── H9 (raw socket): the body cap is enforced BEFORE any body byte is read ──────

    @Test
    void h9_rawSocketProvesTheBodyCapIsCheckedBeforeReading(@TempDir Path dir) throws Exception {
        Path counter = dir.resolve("counter");
        Path jar = FnHttpTestSupport.functionJar(dir, "echo", "fixture.http.EchoFn", echoSource(counter));
        var manifest = FnHttpTestSupport.manifest("p", false, 5, 5000, "fixture.http.EchoFn",
                "[{\"path\":\"/*\",\"auth\":\"none\",\"maxBodyBytes\":10}]");
        var entry = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jar, manifest, null, null, null);
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry))) {
            try (Socket socket = new Socket("127.0.0.1", h.server.port())) {
                socket.setSoTimeout(5000); // bounded — a server that waits for the (never-sent) body must time this out
                String request = "POST /functions/" + ADDR.render() + "/x HTTP/1.1\r\n"
                        + "Host: 127.0.0.1\r\n"
                        + "Content-Length: 1000\r\n"
                        + "Connection: close\r\n"
                        + "\r\n"; // the 1000-byte body itself is NEVER sent
                socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();

                byte[] buf = new byte[4096];
                int n;
                try {
                    n = socket.getInputStream().read(buf);
                } catch (java.net.SocketTimeoutException e) {
                    throw new AssertionError(
                            "mutant: remove the pre-read body-cap check — the server waited for a body that never arrived",
                            e);
                }
                assertThat(n).isGreaterThan(0);
                String responseHead = new String(buf, 0, n, StandardCharsets.UTF_8);
                assertThat(responseHead).as("mutant: remove the pre-read body-cap check").startsWith("HTTP/1.1 413");
            }
            assertThat(countLines(counter)).isEqualTo(0);
        }
    }

    // ── MDC: function/version/execution_id/correlation_id set for the invocation, cleared after ──

    @Test
    void mdcIsSetOnTheHostsOwnLogLineDuringAnInvocationAndReflectsEachInvocationsOwnValues(@TempDir Path dir) throws Exception {
        Path jarThrow = FnHttpTestSupport.functionJar(dir, "throw-mdc", "fixture.http.ThrowingFn", THROWING_SOURCE);
        var manifestThrow = FnHttpTestSupport.manifest("p", false, 5, 5000, "fixture.http.ThrowingFn",
                "[{\"path\":\"/*\",\"auth\":\"none\"}]");
        var entryThrow = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jarThrow, manifestThrow, null, null, null);

        var log = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(FnHttpServer.class);
        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        log.addAppender(captured);
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entryThrow))) {
            var resp = h.get("/functions/" + ADDR.render() + "/x", "X-Correlation-Id", "corr-mdc-1");
            assertThat(resp.statusCode()).isEqualTo(500);

            ILoggingEvent errorEvent = captured.list.stream()
                    .filter(e -> e.getFormattedMessage().contains("function invocation threw"))
                    .findFirst().orElseThrow(() -> new AssertionError("expected a FUNCTION_ERROR host log line"));
            Map<String, String> mdc = errorEvent.getMDCPropertyMap();
            assertThat(mdc.get(Logging.MdcKeys.FUNCTION)).as("mutant: never set MDC").isEqualTo(ADDR.render());
            assertThat(mdc.get(Logging.MdcKeys.VERSION)).as("mutant: never set MDC").isEqualTo("1");
            assertThat(mdc.get(Logging.MdcKeys.EXECUTION_ID)).as("mutant: never set MDC").isNotBlank();
            assertThat(mdc.get(Logging.MdcKeys.CORRELATION_ID)).as("mutant: never set MDC").isEqualTo("corr-mdc-1");

            // A second, independent invocation's own host log line must carry ITS OWN values, not
            // a stale copy of the first's — pins clearing (the values are per-invocation, not leaked).
            captured.list.clear();
            var resp2 = h.get("/functions/" + ADDR.render() + "/x", "X-Correlation-Id", "corr-mdc-2");
            assertThat(resp2.statusCode()).isEqualTo(500);
            ILoggingEvent secondEvent = captured.list.stream()
                    .filter(e -> e.getFormattedMessage().contains("function invocation threw"))
                    .findFirst().orElseThrow();
            assertThat(secondEvent.getMDCPropertyMap().get(Logging.MdcKeys.CORRELATION_ID))
                    .as("mutant: never clear — a later invocation's own log line must carry ITS OWN correlation id")
                    .isEqualTo("corr-mdc-2");
            assertThat(secondEvent.getMDCPropertyMap().get(Logging.MdcKeys.EXECUTION_ID))
                    .as("mutant: never clear — the second invocation's execution id must differ from the first's")
                    .isNotEqualTo(mdc.get(Logging.MdcKeys.EXECUTION_ID));
        } finally {
            log.detachAppender(captured);
        }
    }

    // ── fixture guard: FnHttpTestSupport.document(...) rejects a duplicate versionId ──

    @Test
    void documentRejectsTwoEntriesSharingOneVersionId(@TempDir Path dir) throws Exception {
        Path jarA = FnHttpTestSupport.functionJar(dir, "dupa", "fixture.http.EchoFn", echoSource(dir.resolve("counterA")));
        Path jarB = FnHttpTestSupport.functionJar(dir, "dupb", "fixture.http.EchoFnDup",
                echoSourceNamed("fixture.http.EchoFnDup", dir.resolve("counterB")));
        var manifestA = FnHttpTestSupport.manifest("p", false, 5, 5000, "fixture.http.EchoFn",
                "[{\"path\":\"/x\",\"auth\":\"none\"}]");
        var manifestB = FnHttpTestSupport.manifest("p", false, 5, 5000, "fixture.http.EchoFnDup",
                "[{\"path\":\"/x\",\"auth\":\"none\"}]");
        var entryA = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "dup-v1", 1, jarA, manifestA, null, null, null);
        var entryB = FnHttpTestSupport.liveEntry(ADDR_B, "fnc_2", "dup-v1", 1, jarB, manifestB, null, null, null);

        assertThatThrownBy(() -> FnHttpTestSupport.document(List.of(entryA, entryB)))
                .as("mutant: never check — two entries sharing one versionId race for Reconciler's one prepared slot")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dup-v1");
    }

    // ── H12: lazy load on first call; concurrent dedup; unloadable ──────

    @Test
    void h12_lazyLoadOnFirstCallAndConcurrentDedup(@TempDir Path dir) throws Exception {
        Path counter = dir.resolve("counter");
        Path jar = FnHttpTestSupport.functionJar(dir, "echo", "fixture.http.EchoFn", echoSource(counter));
        var manifest = FnHttpTestSupport.manifest("p", false, 20, 5000, "fixture.http.EchoFn",
                "[{\"path\":\"/*\",\"auth\":\"none\"}]");
        var entry = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jar, manifest, null, null, null);
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry))) {
            var executor = Executors.newFixedThreadPool(4);
            CountDownLatch go = new CountDownLatch(1);
            var f1 = CompletableFuture.supplyAsync(() -> { await(go); return h.get("/functions/" + ADDR.render() + "/x"); }, executor);
            var f2 = CompletableFuture.supplyAsync(() -> { await(go); return h.get("/functions/" + ADDR.render() + "/x"); }, executor);
            go.countDown();
            var r1 = f1.get(10, TimeUnit.SECONDS);
            var r2 = f2.get(10, TimeUnit.SECONDS);
            assertThat(r1.statusCode()).isEqualTo(200);
            assertThat(r2.statusCode()).isEqualTo(200);
            String id1 = FnHttpTestSupport.json(r1.body()).path("instanceId").asString();
            String id2 = FnHttpTestSupport.json(r2.body()).path("instanceId").asString();
            assertThat(id1).as("mutant: no per-address lock — two concurrent first calls must share one load")
                    .isEqualTo(id2);
        }
    }

    @Test
    void h12_unloadableIs503NotFourOhFour(@TempDir Path dir) throws Exception {
        Path counter = dir.resolve("counter");
        Path jar = FnHttpTestSupport.functionJar(dir, "echo", "fixture.http.EchoFn", echoSource(counter));
        // entrypoint names a class that does not exist in the jar -> ENTRYPOINT_NOT_FOUND -> Refused.
        var manifest = FnHttpTestSupport.manifest("p", false, 5, 5000, "fixture.http.DoesNotExist",
                "[{\"path\":\"/*\",\"auth\":\"none\"}]");
        var entry = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jar, manifest, null, null, null);
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry))) {
            var resp = h.get("/functions/" + ADDR.render() + "/x");
            assertThat(resp.statusCode()).as("mutant: 404").isEqualTo(503);
            assertThat(resp.headers().firstValue("Retry-After")).contains("15");
            assertThat(FnHttpTestSupport.json(resp.body()).path("error").asString()).isEqualTo("FUNCTION_UNAVAILABLE");
        }
    }

    /// `docs/spec/function-host-process.md` §3: an `OutOfMemoryError` naming
    /// Metaspace from a lazy function's OWN `init()` must reach the caller as
    /// `503 FUNCTION_UNAVAILABLE` — never a hung connection (no answer ever
    /// written) and never `404` (H12's own contract: known-but-unloadable is
    /// 503, not 404).
    @Test
    void h12_lazyThatOutOfMetaspacesOnInitIs503NotAHangOrFourOhFour(@TempDir Path dir) throws Exception {
        Path jar = FnHttpTestSupport.functionJar(dir, "oom", "fixture.http.MetaspaceOnInitFn", """
                package fixture.http;
                import io.flowcatalyst.function.*;
                public final class MetaspaceOnInitFn implements Function {
                    public void init(FunctionContext ctx) throws Exception {
                        throw new OutOfMemoryError("Metaspace");
                    }
                    public Result handle(Request in, FunctionContext ctx) { return Result.ack(); }
                }
                """);
        var manifest = FnHttpTestSupport.manifest("p", false, 5, 5000, "fixture.http.MetaspaceOnInitFn",
                "[{\"path\":\"/*\",\"auth\":\"none\"}]");
        var entry = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jar, manifest, null, null, null);
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry))) {
            var resp = h.get("/functions/" + ADDR.render() + "/x");
            assertThat(resp.statusCode())
                    .as("mutant: let the OutOfMemoryError kill the request thread instead of answering 503")
                    .isEqualTo(503);
            assertThat(resp.headers().firstValue("Retry-After")).contains("15");
            assertThat(FnHttpTestSupport.json(resp.body()).path("error").asString()).isEqualTo("FUNCTION_UNAVAILABLE");
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── H13: promote while a call is parked on the old version ─────────

    @Test
    void h13_promoteWhileACallIsParkedOnTheOldVersion(@TempDir Path dir) throws Exception {
        Path started = dir.resolve("started");
        Path release = dir.resolve("release");
        Path jarV1 = FnHttpTestSupport.functionJar(dir, "v1", "fixture.http.ParkingFn", parkingSource(started, release, "v1"));
        Path jarV2 = FnHttpTestSupport.functionJar(dir, "v2", "fixture.http.EchoFn", echoSource(dir.resolve("counter2")));
        var manifestV1 = FnHttpTestSupport.manifest("p", true, 5, 20000, "fixture.http.ParkingFn",
                "[{\"path\":\"/*\",\"auth\":\"none\"}]");
        var manifestV2 = FnHttpTestSupport.manifest("p", true, 5, 5000, "fixture.http.EchoFn",
                "[{\"path\":\"/*\",\"auth\":\"none\"}]");
        var entryV1 = FnHttpTestSupport.warmEntry(ADDR, "fnc_1", "v1", 1, jarV1, manifestV1, null, null, null);
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entryV1))) {
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            CompletableFuture<HttpResponse<byte[]>> parked =
                    CompletableFuture.supplyAsync(() -> h.get("/functions/" + ADDR.render() + "/x"), executor);
            awaitFile(started);

            var entryV2 = FnHttpTestSupport.warmEntry(ADDR, "fnc_1", "v2", 2, jarV2, manifestV2, null, null, null);
            CompletableFuture<Void> promote = CompletableFuture.runAsync(
                    () -> h.publish(FnHttpTestSupport.oneFunction(entryV2)), executor);

            // While the promote's own close() is still blocked draining v1, a NEW call must already see v2 —
            // polled in-process against the registry (never over HTTP: an HTTP GET that happened to land
            // before the swap would itself land ON the still-parked v1 and hang until #release is written).
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            boolean sawV2 = false;
            while (System.nanoTime() < deadline) {
                var loaded = h.registry.peek(ADDR);
                if (loaded != null && loaded.version() == 2) {
                    sawV2 = true;
                    break;
                }
                Thread.sleep(10);
            }
            assertThat(sawV2).as("mutant: close immediately — v2 must be registered before v1 finishes draining").isTrue();

            var newCall = h.get("/functions/" + ADDR.render() + "/x");
            assertThat(newCall.statusCode()).as("mutant: close immediately — new calls must already get v2").isEqualTo(200);
            assertThat(FnHttpTestSupport.json(newCall.body()).has("instanceId"))
                    .as("the v2 EchoFn shape, not v1's {\"tag\":...}").isTrue();

            Files.writeString(release, "x"); // let the parked v1 call return
            var parkedResp = parked.get(10, TimeUnit.SECONDS);
            assertThat(parkedResp.statusCode()).isEqualTo(200);
            assertThat(FnHttpTestSupport.json(parkedResp.body()).path("tag").asString())
                    .as("the v1 call completed ON v1 — not aborted, not answered by v2").isEqualTo("v1");

            promote.get(10, TimeUnit.SECONDS); // v1's loader is only now closed (after the parked call returned)
        }
    }

    // ── H14: drain ───────────────────────────────────────────────────────

    @Test
    void h14_drainRejectsNewRequestsWith503(@TempDir Path dir) throws Exception {
        Path counter = dir.resolve("counter");
        Path jar = FnHttpTestSupport.functionJar(dir, "echo", "fixture.http.EchoFn", echoSource(counter));
        var manifest = FnHttpTestSupport.manifest("p", false, 5, 5000, "fixture.http.EchoFn",
                "[{\"path\":\"/*\",\"auth\":\"none\"}]");
        var entry = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jar, manifest, null, null, null);
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry))) {
            h.server.drain();
            var resp = h.get("/functions/" + ADDR.render() + "/x");
            assertThat(resp.statusCode()).as("mutant: never check draining").isEqualTo(503);
            assertThat(FnHttpTestSupport.json(resp.body()).path("error").asString()).isEqualTo("DRAINING");
            assertThat(resp.headers().firstValue("Retry-After")).contains("5");
            assertThat(countLines(counter)).as("a draining host never invokes the function at all").isEqualTo(0);
        } finally {
            // already drained; close() below (via try-with-resources) is a fast no-op since nothing is in flight.
        }
    }

    @Test
    void h14_closeReturnsWithinTheDrainTimeoutEvenIfAFunctionNeverReturns(@TempDir Path dir) throws Exception {
        Path started = dir.resolve("started");
        Path release = dir.resolve("release"); // deliberately never created
        Path jar = FnHttpTestSupport.functionJar(dir, "park", "fixture.http.ParkingFn", parkingSource(started, release, "v1"));
        var manifest = FnHttpTestSupport.manifest("p", false, 5, 60000, "fixture.http.ParkingFn",
                "[{\"path\":\"/*\",\"auth\":\"none\"}]"); // 60s timeout — long enough to outlast the drain bound
        var entry = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jar, manifest, null, null, null);
        var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry));
        // try-with-resources: ExecutorService#close() (Java 19+) shuts down AND awaits every
        // submitted task's termination — including the doomed request below, once #server.close()
        // severs its connection — so nothing from this test is ever still in flight when the next
        // test starts (a leaked blocked HTTP client task here was observed to starve an unrelated
        // later test's very first request under a full-suite run).
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture.supplyAsync(() -> h.get("/functions/" + ADDR.render() + "/x"), executor);
            awaitFile(started);

            long startNanos = System.nanoTime();
            h.server.close(Duration.ofSeconds(2));
            long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
            assertThat(elapsedMs).as("mutant: wait for ever").isLessThan(8000);
        }
    }

    @Test
    void h14_inFlightRequestCompletesDuringDrain(@TempDir Path dir) throws Exception {
        Path started = dir.resolve("started");
        Path release = dir.resolve("release");
        Path jar = FnHttpTestSupport.functionJar(dir, "park", "fixture.http.ParkingFn", parkingSource(started, release, "v1"));
        var manifest = FnHttpTestSupport.manifest("p", false, 5, 20000, "fixture.http.ParkingFn",
                "[{\"path\":\"/*\",\"auth\":\"none\"}]");
        var entry = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jar, manifest, null, null, null);
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry))) {
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            var inFlight = CompletableFuture.supplyAsync(() -> h.get("/functions/" + ADDR.render() + "/x"), executor);
            awaitFile(started);

            h.server.drain();
            var rejected = h.get("/functions/" + ADDR.render() + "/x");
            assertThat(rejected.statusCode()).isEqualTo(503);

            Files.writeString(release, "x");
            var resp = inFlight.get(10, TimeUnit.SECONDS);
            assertThat(resp.statusCode()).as("in-flight requests finish even after drain() was called").isEqualTo(200);
        }
    }
}
