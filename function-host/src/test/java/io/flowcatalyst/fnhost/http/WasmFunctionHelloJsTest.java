package io.flowcatalyst.fnhost.http;

import com.sun.net.httpserver.HttpServer;
import io.flowcatalyst.fnhost.reconcile.ControlPlane;
import io.flowcatalyst.fnhost.reconcile.DesiredDocument;
import io.flowcatalyst.fnhost.wasm.WasmJsFixtures;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.platform.function.Manifest;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.router.wire.WebhookSigner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/// The committed `function-hello-js` module (`docs/spec/function-js-guest.md`
/// §4) through the REAL reconciler and listener — the JS twin of
/// [io.flowcatalyst.fnhost.wasm.WasmFunctionListenerTest]. Every endpoint
/// answers as the Java `examples/function-hello` does for the same inputs,
/// and an `http.request` to a host outside `manifest.httpAllow` is a clean,
/// guest-visible denial rather than a trap.
class WasmFunctionHelloJsTest {

    private static final FunctionAddress ADDR = FnHttpTestSupport.ADDR_A;
    private static final String WEBHOOK_SECRET = "js-hello-webhook-secret";
    private static final String GREET_PERMISSION = "hello:greeting:greet";
    private static final String WEBHOOK_SIGNATURE_HEADER = "X-FlowCatalyst-Signature";
    private static final String WEBHOOK_TIMESTAMP_HEADER = "X-FlowCatalyst-Timestamp";

    /// The manifest `examples/function-hello-js/manifest.json` ships,
    /// reproduced here (rather than read from the file — the same convention
    /// [io.flowcatalyst.fnhost.wasm.WasmFixtures#manifest] uses for the Rust
    /// fixture) so this test controls `httpAllow` for the denial case below.
    private static Manifest manifest() {
        String json = """
                {
                  "runtime": "wasm",
                  "entrypoint": "handle",
                  "pool": "default",
                  "warm": false,
                  "limits": { "maxDurationMs": 10000, "maxConcurrency": 4, "wasmMemoryMb": 96 },
                  "endpoints": [
                    { "path": "/events/greeting-requested", "auth": "webhook" },
                    { "path": "/api/hello/{name}", "auth": "platform", "methods": ["GET"] },
                    { "path": "/healthz", "auth": "none", "methods": ["GET"] },
                    { "path": "/api/proxy", "auth": "none", "methods": ["GET"] }
                  ],
                  "config": ["GREETING"],
                  "secrets": ["API_KEY"],
                  "httpAllow": ["127.0.0.1"]
                }
                """;
        return Manifest.readStored(Json.MAPPER.readTree(json));
    }

    private static DesiredDocument.Entry entry(Path wasm) {
        return new DesiredDocument.Entry(ADDR, "fnc_hello_js", "v1", 1, DesiredDocument.Role.LIVE,
                DesiredDocument.Mode.WARM, FnHttpTestSupport.digestOf(wasm), FnHttpTestSupport.fileRef(wasm), null,
                null, manifest(), WEBHOOK_SECRET, null, null,
                Map.of("GREETING", "Hi"), Map.of("API_KEY", "s3cret-key-value"), List.of(), List.of());
    }

    // ── GET /healthz (auth: none) ──────────────────────────────────────────

    @Test
    void healthzAnswersOkJustLikeTheJavaHelloFunction(@TempDir Path dir) {
        Path wasm = WasmJsFixtures.guest(dir);
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry(wasm)))) {
            var resp = h.get("/functions/" + ADDR.render() + "/healthz");
            assertThat(resp.statusCode()).as(text(resp)).isEqualTo(200);
            assertThat(FnHttpTestSupport.json(resp.body()).path("status").asString()).isEqualTo("ok");
        }
    }

    // ── GET /api/hello/{name} (auth: platform) ─────────────────────────────

    @Test
    void helloChecksThePermissionBeforeGreetingTheCaller(@TempDir Path dir) throws Exception {
        Path wasm = WasmJsFixtures.guest(dir);
        try (TestJwks jwks = new TestJwks()) {
            var options = new FnHttpServer.Options("127.0.0.1", 0, 512, jwks.issuer, Clock.systemUTC());
            try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry(wasm)), 50, options)) {
                String allowed = jwks.mint("prn_js_1", "SERVICE", "CLIENT", GREET_PERMISSION, List.of("clt_1"),
                        List.of(), false, Instant.now().plusSeconds(300));
                var ok = h.get("/functions/" + ADDR.render() + "/api/hello/Ada", "Authorization", "Bearer " + allowed);
                assertThat(ok.statusCode()).as(text(ok)).isEqualTo(200);
                JsonNode okBody = FnHttpTestSupport.json(ok.body());
                assertThat(okBody.path("message").asString()).isEqualTo("hello, Ada!");
                assertThat(okBody.path("principalId").asString()).isEqualTo("prn_js_1");

                // mutant: skip the hasPermission check — a caller without the
                // application's own permission must never reach the greeting.
                String noPermission = jwks.mint("prn_js_2", "SERVICE", "CLIENT", "hello:greeting:other",
                        List.of("clt_1"), List.of(), false, Instant.now().plusSeconds(300));
                var denied = h.get("/functions/" + ADDR.render() + "/api/hello/Ada", "Authorization",
                        "Bearer " + noPermission);
                assertThat(denied.statusCode()).as(text(denied)).isEqualTo(403);
                assertThat(FnHttpTestSupport.json(denied.body()).path("error").asString())
                        .isEqualTo("PERMISSION_REQUIRED");
            }
        }
    }

    // ── POST /events/greeting-requested (auth: webhook) ────────────────────

    @Test
    void greetingRequestedReadsConfigAndSecretPresenceThenEmitsAndAcks(@TempDir Path dir) {
        Path wasm = WasmJsFixtures.guest(dir);
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry(wasm)))) {
            byte[] body = """
                    {"id":"dlv-1","type":"hello:greeting:greeting:requested","attemptNumber":1,
                     "subject":"subj-1","correlationId":"corr-1","messageGroup":"group-1",
                     "data":{"name":"World"}}
                    """.getBytes(StandardCharsets.UTF_8);
            String timestamp = WebhookSigner.timestamp(Instant.now());
            String signature = WebhookSigner.sign(WEBHOOK_SECRET, timestamp, body);

            var resp = h.post("/functions/" + ADDR.render() + "/events/greeting-requested", body,
                    WEBHOOK_SIGNATURE_HEADER, signature, WEBHOOK_TIMESTAMP_HEADER, timestamp);

            assertThat(resp.statusCode()).as(text(resp)).isEqualTo(200);
            assertThat(resp.body()).as("Result.ok() is an empty body — the ack row").isEmpty();

            assertThat(h.controlPlane.emits()).as("mutant: never call fc_emit_event").hasSize(1);
            ControlPlane.EmitRequest sent = h.controlPlane.emits().getFirst();
            assertThat(sent.address()).isEqualTo(ADDR);
            ControlPlane.EmitItem item = sent.events().getFirst();
            assertThat(item.type()).isEqualTo("hello:greeting:greeting:sent");
            assertThat(item.subject()).isEqualTo("subj-1");
            assertThat(item.messageGroup()).isEqualTo("group-1");
            JsonNode data = FnHttpTestSupport.json(item.data().getBytes(StandardCharsets.UTF_8));
            assertThat(data.path("name").asString()).isEqualTo("World");
            assertThat(data.path("greeting").asString())
                    .as("mutant: fall back to the default greeting instead of reading GREETING from config")
                    .isEqualTo("Hi, World!");
        }
    }

    // mutant: a wrong/missing signature must never reach the guest as a verified webhook call.
    @Test
    void greetingRequestedRefusesAnUnsignedDelivery(@TempDir Path dir) {
        Path wasm = WasmJsFixtures.guest(dir);
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry(wasm)))) {
            var resp = h.post("/functions/" + ADDR.render() + "/events/greeting-requested",
                    "{}".getBytes(StandardCharsets.UTF_8));
            assertThat(resp.statusCode()).isEqualTo(401);
            assertThat(h.controlPlane.emits()).as("the guest was never reached").isEmpty();
        }
    }

    // ── GET /api/proxy — beyond the Java example: ctx.http.request + allowlist ─

    @Test
    void proxyReachesAnAllowlistedLoopbackServerAndDeniesAHostOutsideHttpAllow(@TempDir Path dir) throws Exception {
        AtomicInteger served = new AtomicInteger();
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/ok", exchange -> {
            served.incrementAndGet();
            byte[] resp = "proxied-ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        upstream.start();
        try {
            int port = upstream.getAddress().getPort();
            Path wasm = WasmJsFixtures.guest(dir);
            try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry(wasm)))) {
                var allowed = h.get("/functions/" + ADDR.render() + "/api/proxy?url="
                        + enc("http://127.0.0.1:" + port + "/ok"));
                assertThat(allowed.statusCode()).as(text(allowed)).isEqualTo(200);
                JsonNode allowedBody = FnHttpTestSupport.json(allowed.body());
                assertThat(allowedBody.path("denied").asBoolean()).isFalse();
                assertThat(allowedBody.path("status").asInt()).isEqualTo(200);
                assertThat(allowedBody.path("body").asString()).isEqualTo("proxied-ok");
                assertThat(served.get()).isEqualTo(1);

                // localhost is loopback (http permitted) but NOT on this manifest's httpAllow
                // (only "127.0.0.1" is) — the exact allowlist-denial mutant
                // docs/spec/function-js-guest.md §4 names.
                var denied = h.get("/functions/" + ADDR.render() + "/api/proxy?url="
                        + enc("http://localhost:" + port + "/ok"));
                assertThat(denied.statusCode()).as(text(denied)).isEqualTo(200);
                JsonNode deniedBody = FnHttpTestSupport.json(denied.body());
                assertThat(deniedBody.path("denied").asBoolean())
                        .as("mutant: bypass the allowlist — the call would succeed: " + deniedBody).isTrue();
                assertThat(deniedBody.path("reason").asString()).contains("httpAllow");
                assertThat(served.get()).as("the denied call never left the host").isEqualTo(1);
            }
        } finally {
            upstream.stop(0);
        }
    }

    // ── every existing function-host test keeps passing beside this one ────
    // (proven by the full module suite at the end of the change, not a
    // single test here — see the report.)

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String text(HttpResponse<byte[]> resp) {
        return new String(resp.body(), StandardCharsets.UTF_8);
    }
}
