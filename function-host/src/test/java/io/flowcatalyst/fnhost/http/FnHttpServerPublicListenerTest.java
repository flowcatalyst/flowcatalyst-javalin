package io.flowcatalyst.fnhost.http;

import io.flowcatalyst.fnhost.reconcile.DesiredDocument;
import io.flowcatalyst.fnhost.route.TrustedProxies;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.platform.function.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// The public listener (spec `function-public-routes.md` §3) and CORS (§4),
/// at the HTTP level — F6-F9 of §6's table. `PublicRouteTableTest` already
/// pins the pure prefix-matching logic; this pins the listener that WIRES
/// it (host parsing, `X-Forwarded-Host` ignored, no by-address/versioned
/// access, header stripping, `remoteAddress` trust, CORS). `Host` is set via
/// [RawHttpClient] (a raw socket) because `java.net.http` forbids setting it
/// from this test JVM without a pom edit (`-Djdk.httpclient.allowRestrictedHeaders`,
/// not configured here — see the slice's own handback report).
class FnHttpServerPublicListenerTest {

    private static final FunctionAddress ADDR = FunctionAddress.parse("pub.svc.a");
    private static final String HOST = "api.example.test";

    private static String pathEchoSource() {
        return """
                package fixture.pub;
                import io.flowcatalyst.function.*;
                public final class PathEchoFn implements Function {
                    public Result handle(Request in, FunctionContext ctx) throws Exception {
                        // Request#header() is the case-insensitive lookup (Request's own class doc:
                        // "look a header up case-insensitively... rather than indexing the map
                        // directly") — java.net.http may negotiate h2c for the PRIVATE entry, and
                        // HTTP/2 lower-cases every header name on the wire (RFC 7540 8.1.2), so an
                        // exact-case Map#containsKey check here would silently stop proving anything
                        // for that listener while still working for the public entry's raw-socket
                        // HTTP/1.1 request (case preserved) — decorative on exactly one of the two
                        // entries this fixture serves both of.
                        boolean hasFnHeader = in.header("X-FlowCatalyst-Function").isPresent();
                        return Result.json(200, "{\\"path\\":\\"" + in.path() + "\\",\\"originalPath\\":\\""
                                + in.originalPath() + "\\",\\"remoteAddress\\":\\"" + in.remoteAddress()
                                + "\\",\\"hasFnHeader\\":" + hasFnHeader
                                + ",\\"originalHost\\":\\"" + in.originalHost() + "\\"}");
                    }
                }
                """;
    }

    private FnHttpTestSupport.Harness startWithPublicRoute(Path dir, TrustedProxies trustedProxies,
                                                             RecordingObserver observer) {
        Path jar = FnHttpTestSupport.functionJar(dir, "pub", "fixture.pub.PathEchoFn", pathEchoSource());
        var manifest = FnHttpTestSupport.manifest("p", false, 10, 5000, "fixture.pub.PathEchoFn",
                "[{\"path\":\"/*\",\"auth\":\"none\"}]");
        var entry = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jar, manifest, null, null, null);
        var doc = FnHttpTestSupport.oneFunctionWithPublicRoute(entry, HOST, "/billing");
        var options = FnHttpServer.Options.of(0, 512, "http://127.0.0.1:1", observer, 0, trustedProxies).withHost("127.0.0.1");
        return FnHttpTestSupport.start(dir, doc, 50, options);
    }

    // ── F6: longest whole-segment prefix; exact match -> "/"; same path from both listeners ──

    @Test
    void sameFunctionPathFromPublicAndPrivateEntries(@TempDir Path dir) throws Exception {
        RecordingObserver observer = new RecordingObserver();
        try (var h = startWithPublicRoute(dir, TrustedProxies.DEFAULT, observer)) {
            var pub = RawHttpClient.send(h.server.publicPort(), "GET", "/billing/invoices/7",
                    Map.of("Host", HOST), null);
            assertThat(pub.status()).isEqualTo(200);
            var pubJson = FnHttpTestSupport.json(pub.body());
            assertThat(pubJson.path("path").asString()).isEqualTo("/invoices/7");

            var priv = h.get("/functions/" + ADDR.render() + "/invoices/7");
            assertThat(priv.statusCode()).isEqualTo(200);
            var privJson = FnHttpTestSupport.json(priv.body());
            assertThat(privJson.path("path").asString()).as("mutant: prefix stripping differs between listeners")
                    .isEqualTo(pubJson.path("path").asString());
        }
    }

    @Test
    void exactPrefixMatchYieldsRootFunctionPath(@TempDir Path dir) throws Exception {
        RecordingObserver observer = new RecordingObserver();
        try (var h = startWithPublicRoute(dir, TrustedProxies.DEFAULT, observer)) {
            var resp = RawHttpClient.send(h.server.publicPort(), "GET", "/billing", Map.of("Host", HOST), null);
            assertThat(resp.status()).isEqualTo(200);
            assertThat(FnHttpTestSupport.json(resp.body()).path("path").asString()).isEqualTo("/");
        }
    }

    /// Mutant: `startsWith` — `/billing` must not match `/billingx`.
    @Test
    void wholeSegmentBoundary_billingxIsNotMatched(@TempDir Path dir) throws Exception {
        RecordingObserver observer = new RecordingObserver();
        try (var h = startWithPublicRoute(dir, TrustedProxies.DEFAULT, observer)) {
            var resp = RawHttpClient.send(h.server.publicPort(), "GET", "/billingx", Map.of("Host", HOST), null);
            assertThat(resp.status()).isEqualTo(404);
        }
    }

    // ── F7: unknown host 404; X-Forwarded-Host ignored; no by-address/versioned access ──

    @Test
    void unknownHostIs404(@TempDir Path dir) throws Exception {
        RecordingObserver observer = new RecordingObserver();
        try (var h = startWithPublicRoute(dir, TrustedProxies.DEFAULT, observer)) {
            var resp = RawHttpClient.send(h.server.publicPort(), "GET", "/billing",
                    Map.of("Host", "not-registered.example.test"), null);
            assertThat(resp.status()).isEqualTo(404);
        }
    }

    @Test
    void xForwardedHostIsIgnoredForRouting(@TempDir Path dir) throws Exception {
        RecordingObserver observer = new RecordingObserver();
        try (var h = startWithPublicRoute(dir, TrustedProxies.DEFAULT, observer)) {
            // Real Host is unregistered; X-Forwarded-Host names the registered one —
            // must still 404 (mutant: honour X-Forwarded-Host).
            var resp = RawHttpClient.send(h.server.publicPort(), "GET", "/billing",
                    Map.of("Host", "not-registered.example.test", "X-Forwarded-Host", HOST), null);
            assertThat(resp.status()).as("mutant: honour X-Forwarded-Host").isEqualTo(404);
        }
    }

    /// Mutant: route by address on the public port. `/functions/<address>/…`
    /// is just an ordinary (unmatched) path here — 404, and the function's
    /// invocation counter (entered-count) stays 0.
    @Test
    void byAddressPathIs404AndInvocationCounterStaysZero(@TempDir Path dir) throws Exception {
        RecordingObserver observer = new RecordingObserver();
        try (var h = startWithPublicRoute(dir, TrustedProxies.DEFAULT, observer)) {
            var resp = RawHttpClient.send(h.server.publicPort(), "GET", "/functions/" + ADDR.render() + "/x",
                    Map.of("Host", HOST), null);
            assertThat(resp.status()).isEqualTo(404);
            assertThat(observer.entries()).as("mutant: route by address on the public port").isEmpty();
        }
    }

    @Test
    void versionedByAddressPathIs404AndInvocationCounterStaysZero(@TempDir Path dir) throws Exception {
        RecordingObserver observer = new RecordingObserver();
        try (var h = startWithPublicRoute(dir, TrustedProxies.DEFAULT, observer)) {
            var resp = RawHttpClient.send(h.server.publicPort(), "GET", "/functions/" + ADDR.render() + ":1/x",
                    Map.of("Host", HOST), null);
            assertThat(resp.status()).isEqualTo(404);
            assertThat(observer.entries()).as("mutant: route by address:version on the public port").isEmpty();
        }
    }

    /// Mutant: inbound `X-FlowCatalyst-Function` reaches the function (both listeners).
    @Test
    void inboundFlowCatalystFunctionHeaderNeverReachesTheFunction_publicEntry(@TempDir Path dir) throws Exception {
        RecordingObserver observer = new RecordingObserver();
        try (var h = startWithPublicRoute(dir, TrustedProxies.DEFAULT, observer)) {
            var resp = RawHttpClient.send(h.server.publicPort(), "GET", "/billing/x",
                    Map.of("Host", HOST, "X-FlowCatalyst-Function", "evil.svc.fn"), null);
            assertThat(resp.status()).isEqualTo(200);
            assertThat(FnHttpTestSupport.json(resp.body()).path("hasFnHeader").asBoolean())
                    .as("mutant: never strip X-FlowCatalyst-Function").isFalse();
        }
    }

    @Test
    void inboundFlowCatalystFunctionHeaderNeverReachesTheFunction_privateEntry(@TempDir Path dir) throws Exception {
        RecordingObserver observer = new RecordingObserver();
        try (var h = startWithPublicRoute(dir, TrustedProxies.DEFAULT, observer)) {
            var resp = h.get("/functions/" + ADDR.render() + "/x", "X-FlowCatalyst-Function", "evil.svc.fn");
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(FnHttpTestSupport.json(resp.body()).path("hasFnHeader").asBoolean()).isFalse();
        }
    }

    // ── F8: remoteAddress trust ──

    @Test
    void untrustedPeerIgnoresXForwardedForWhateverItSays(@TempDir Path dir) throws Exception {
        RecordingObserver observer = new RecordingObserver();
        // TrustedProxies.NONE: the loopback TCP peer itself is never trusted.
        try (var h = startWithPublicRoute(dir, TrustedProxies.NONE, observer)) {
            var resp = RawHttpClient.send(h.server.publicPort(), "GET", "/billing/x",
                    Map.of("Host", HOST, "X-Forwarded-For", "203.0.113.9"), null);
            assertThat(resp.status()).isEqualTo(200);
            String remoteAddress = FnHttpTestSupport.json(resp.body()).path("remoteAddress").asString();
            assertThat(remoteAddress).as("mutant: trust everyone").isNotEqualTo("203.0.113.9");
            assertThat(remoteAddress).isIn("127.0.0.1", "0:0:0:0:0:0:0:1", "::1");
        }
    }

    @Test
    void trustedPeerUsesTheRightmostForwardedForEntry(@TempDir Path dir) throws Exception {
        RecordingObserver observer = new RecordingObserver();
        // TrustedProxies.DEFAULT covers loopback — the test's own TCP peer is trusted.
        try (var h = startWithPublicRoute(dir, TrustedProxies.DEFAULT, observer)) {
            var resp = RawHttpClient.send(h.server.publicPort(), "GET", "/billing/x",
                    Map.of("Host", HOST, "X-Forwarded-For", "198.51.100.1, 203.0.113.9"), null);
            assertThat(resp.status()).isEqualTo(200);
            String remoteAddress = FnHttpTestSupport.json(resp.body()).path("remoteAddress").asString();
            assertThat(remoteAddress).as("mutant: left-most").isEqualTo("203.0.113.9");
        }
    }

    @Test
    void trustedPeerWithMalformedForwardedForFallsBackToThePeer(@TempDir Path dir) throws Exception {
        RecordingObserver observer = new RecordingObserver();
        try (var h = startWithPublicRoute(dir, TrustedProxies.DEFAULT, observer)) {
            var resp = RawHttpClient.send(h.server.publicPort(), "GET", "/billing/x",
                    Map.of("Host", HOST, "X-Forwarded-For", "not-an-ip"), null);
            assertThat(resp.status()).isEqualTo(200);
            String remoteAddress = FnHttpTestSupport.json(resp.body()).path("remoteAddress").asString();
            assertThat(remoteAddress).as("mutant: pass a malformed entry through")
                    .isIn("127.0.0.1", "0:0:0:0:0:0:0:1", "::1");
        }
    }

    // ── CORS (F9) ─────────────────────────────────────────────────────────

    private FnHttpTestSupport.Harness startWithCorsEndpoint(Path dir, String corsJson, RecordingObserver observer)
            throws Exception {
        Path jar = FnHttpTestSupport.functionJar(dir, "cors", "fixture.pub.PathEchoFn", pathEchoSource());
        var manifest = FnHttpTestSupport.manifest("p", false, 10, 5000, "fixture.pub.PathEchoFn",
                "[{\"path\":\"/api/*\",\"auth\":\"none\",\"methods\":[\"GET\",\"POST\"],\"cors\":" + corsJson + "}]");
        var entry = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jar, manifest, null, null, null);
        var doc = FnHttpTestSupport.oneFunctionWithPublicRoute(entry, HOST, "/");
        var options = FnHttpServer.Options.of(0, 512, "http://127.0.0.1:1", observer, 0, TrustedProxies.DEFAULT).withHost("127.0.0.1");
        return FnHttpTestSupport.start(dir, doc, 50, options);
    }

    @Test
    void allowedPreflightGetsExactHeaderSetAndNeverInvokesTheFunction(@TempDir Path dir) throws Exception {
        RecordingObserver observer = new RecordingObserver();
        String cors = "{\"origins\":[\"https://app.acme.com\"],\"methods\":[\"GET\",\"POST\"],\"headers\":[\"X-Custom\"]}";
        try (var h = startWithCorsEndpoint(dir, cors, observer)) {
            var resp = RawHttpClient.send(h.server.publicPort(), "OPTIONS", "/api/x", Map.of(
                    "Host", HOST, "Origin", "https://app.acme.com", "Access-Control-Request-Method", "POST",
                    "Access-Control-Request-Headers", "X-Custom"), null);
            assertThat(resp.status()).isEqualTo(204);
            assertThat(resp.header("Access-Control-Allow-Origin")).containsExactly("https://app.acme.com");
            assertThat(resp.header("Access-Control-Allow-Methods").get(0)).contains("POST");
            assertThat(resp.header("Access-Control-Allow-Headers")).containsExactly("X-Custom");
            assertThat(resp.header("Access-Control-Max-Age")).containsExactly("600");
            assertThat(resp.header("Vary")).containsExactly("Origin");
            assertThat(resp.header("Access-Control-Allow-Credentials")).as("not requested").isEmpty();

            assertThat(observer.entries()).as("mutant: invoke the function on preflight").isEmpty();
            assertThat(observer.completions()).as("preflight must never complete as an invocation").isEmpty();
            assertThat(observer.refusals()).extracting(RecordingObserver.Refused::outcome)
                    .as("mutant: count preflight as ok").containsExactly("preflight");
        }
    }

    @Test
    void disallowedOriginPreflightGetsNoCorsHeadersAtAll(@TempDir Path dir) throws Exception {
        RecordingObserver observer = new RecordingObserver();
        String cors = "{\"origins\":[\"https://app.acme.com\"],\"methods\":[\"GET\",\"POST\"]}";
        try (var h = startWithCorsEndpoint(dir, cors, observer)) {
            var resp = RawHttpClient.send(h.server.publicPort(), "OPTIONS", "/api/x", Map.of(
                    "Host", HOST, "Origin", "https://evil.example.com", "Access-Control-Request-Method", "GET"),
                    null);
            assertThat(resp.status()).isEqualTo(204);
            assertThat(resp.headers().keySet()).as("mutant: reflect any origin")
                    .noneMatch(k -> k.toLowerCase(java.util.Locale.ROOT).startsWith("access-control"));
            assertThat(resp.header("Vary")).isEmpty();
        }
    }

    @Test
    void methodNotAllowedPreflightGetsNoCorsHeaders(@TempDir Path dir) throws Exception {
        RecordingObserver observer = new RecordingObserver();
        String cors = "{\"origins\":[\"https://app.acme.com\"],\"methods\":[\"GET\"]}";
        try (var h = startWithCorsEndpoint(dir, cors, observer)) {
            var resp = RawHttpClient.send(h.server.publicPort(), "OPTIONS", "/api/x", Map.of(
                    "Host", HOST, "Origin", "https://app.acme.com", "Access-Control-Request-Method", "DELETE"),
                    null);
            assertThat(resp.status()).isEqualTo(204);
            assertThat(resp.header("Access-Control-Allow-Origin")).isEmpty();
        }
    }

    @Test
    void wildcardOriginGetsStarWithoutVary(@TempDir Path dir) throws Exception {
        RecordingObserver observer = new RecordingObserver();
        String cors = "{\"origins\":[\"*\"],\"methods\":[\"GET\"]}";
        try (var h = startWithCorsEndpoint(dir, cors, observer)) {
            var resp = RawHttpClient.send(h.server.publicPort(), "OPTIONS", "/api/x", Map.of(
                    "Host", HOST, "Origin", "https://anyone.example.com", "Access-Control-Request-Method", "GET"),
                    null);
            assertThat(resp.status()).isEqualTo(204);
            assertThat(resp.header("Access-Control-Allow-Origin")).containsExactly("*");
            assertThat(resp.header("Vary")).as("mutant: Vary with a wildcard allow").isEmpty();
        }
    }

    @Test
    void actualRequestReplacesFunctionSetCorsHeaders(@TempDir Path dir) throws Exception {
        RecordingObserver observer = new RecordingObserver();
        String cors = "{\"origins\":[\"https://app.acme.com\"],\"methods\":[\"GET\",\"POST\"],\"allowCredentials\":true}";
        // The fixture function itself sets a WRONG Access-Control-Allow-Origin — the host must replace it.
        Path jar = FnHttpTestSupport.functionJar(dir, "corsfn", "fixture.pub.CorsSettingFn", """
                package fixture.pub;
                import io.flowcatalyst.function.*;
                import java.util.*;
                public final class CorsSettingFn implements Function {
                    public Result handle(Request in, FunctionContext ctx) throws Exception {
                        Map<String, List<String>> h = new LinkedHashMap<>();
                        // Deliberately different CASING from the host's own header names — a stripping
                        // step that only removes an EXACT-case match would leave this leaking alongside
                        // the host's correct one (two Access-Control-Allow-Origin values on the wire).
                        h.put("access-control-allow-origin", List.of("https://attacker.example.com"));
                        h.put("access-control-allow-credentials", List.of("false"));
                        return Result.http(200, h, "{}".getBytes());
                    }
                }
                """);
        var manifest = FnHttpTestSupport.manifest("p", false, 10, 5000, "fixture.pub.CorsSettingFn",
                "[{\"path\":\"/api/*\",\"auth\":\"none\",\"methods\":[\"GET\",\"POST\"],\"cors\":" + cors + "}]");
        var entry = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jar, manifest, null, null, null);
        var doc = FnHttpTestSupport.oneFunctionWithPublicRoute(entry, HOST, "/");
        var options = FnHttpServer.Options.of(0, 512, "http://127.0.0.1:1", observer, 0, TrustedProxies.DEFAULT).withHost("127.0.0.1");
        try (var h = FnHttpTestSupport.start(dir, doc, 50, options)) {
            var resp = RawHttpClient.send(h.server.publicPort(), "GET", "/api/x",
                    Map.of("Host", HOST, "Origin", "https://app.acme.com"), null);
            assertThat(resp.status()).isEqualTo(200);
            assertThat(resp.header("Access-Control-Allow-Origin")).as("mutant: let the function's header win")
                    .containsExactly("https://app.acme.com");
            assertThat(resp.header("Access-Control-Allow-Credentials")).containsExactly("true");
            assertThat(resp.header("Vary")).containsExactly("Origin");
        }
    }

    @Test
    void actualRequestWithDisallowedOriginRunsTheFunctionAndStripsItsCorsHeaders(@TempDir Path dir) throws Exception {
        RecordingObserver observer = new RecordingObserver();
        String cors = "{\"origins\":[\"https://app.acme.com\"],\"methods\":[\"GET\"]}";
        Path jar = FnHttpTestSupport.functionJar(dir, "corsfn2", "fixture.pub.CorsSettingFn2", """
                package fixture.pub;
                import io.flowcatalyst.function.*;
                import java.util.*;
                public final class CorsSettingFn2 implements Function {
                    public Result handle(Request in, FunctionContext ctx) throws Exception {
                        Map<String, List<String>> h = new LinkedHashMap<>();
                        h.put("Access-Control-Allow-Origin", List.of("https://evil.example.com"));
                        return Result.http(200, h, "{}".getBytes());
                    }
                }
                """);
        var manifest = FnHttpTestSupport.manifest("p", false, 10, 5000, "fixture.pub.CorsSettingFn2",
                "[{\"path\":\"/api/*\",\"auth\":\"none\",\"methods\":[\"GET\"],\"cors\":" + cors + "}]");
        var entry = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jar, manifest, null, null, null);
        var doc = FnHttpTestSupport.oneFunctionWithPublicRoute(entry, HOST, "/");
        var options = FnHttpServer.Options.of(0, 512, "http://127.0.0.1:1", observer, 0, TrustedProxies.DEFAULT).withHost("127.0.0.1");
        try (var h = FnHttpTestSupport.start(dir, doc, 50, options)) {
            var resp = RawHttpClient.send(h.server.publicPort(), "GET", "/api/x",
                    Map.of("Host", HOST, "Origin", "https://evil.example.com"), null);
            assertThat(resp.status()).as("CORS is not access control — the function still runs").isEqualTo(200);
            assertThat(resp.header("Access-Control-Allow-Origin")).as("mutant: keep the function's header")
                    .isEmpty();
            assertThat(observer.entries()).as("disallowed origin does not stop invocation").hasSize(1);
        }
    }

    /// A `platform`-authed endpoint's preflight is answered with NO auth
    /// applied at all — a preflight carries no credentials (spec §4).
    @Test
    void preflightOnAPlatformEndpointNeedsNoAuth(@TempDir Path dir) throws Exception {
        RecordingObserver observer = new RecordingObserver();
        String cors = "{\"origins\":[\"https://app.acme.com\"],\"methods\":[\"GET\"]}";
        Path jar = FnHttpTestSupport.functionJar(dir, "platformcors", "fixture.pub.PathEchoFn", pathEchoSource());
        var manifest = FnHttpTestSupport.manifest("p", false, 10, 5000, "fixture.pub.PathEchoFn",
                "[{\"path\":\"/api/*\",\"auth\":\"platform\",\"methods\":[\"GET\"],\"cors\":" + cors + "}]");
        var entry = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jar, manifest, null, null, null);
        var doc = FnHttpTestSupport.oneFunctionWithPublicRoute(entry, HOST, "/");
        var options = FnHttpServer.Options.of(0, 512, "http://127.0.0.1:1", observer, 0, TrustedProxies.DEFAULT).withHost("127.0.0.1");
        try (var h = FnHttpTestSupport.start(dir, doc, 50, options)) {
            // No Authorization header at all — a real request to this endpoint would be 401.
            var resp = RawHttpClient.send(h.server.publicPort(), "OPTIONS", "/api/x", Map.of(
                    "Host", HOST, "Origin", "https://app.acme.com", "Access-Control-Request-Method", "GET"), null);
            assertThat(resp.status()).as("mutant: apply auth to a preflight").isEqualTo(204);
            assertThat(resp.header("Access-Control-Allow-Origin")).containsExactly("https://app.acme.com");
            assertThat(observer.entries()).isEmpty();
        }
    }

    // ── composition: the public listener shares permits (F6/F7's "never by address" also
    // covers permits sharing implicitly since permits are only acquired inside handleEntry) ──

    // ── fc_fn_invocations_total{entry} — real FnMetrics, both listeners ──

    /// Spec §3 addendum: `fc_fn_invocations_total` grows an `entry` label
    /// (`private`|`public`) — pinned against a REAL [io.flowcatalyst.fnhost.metrics.FnMetrics],
    /// not [RecordingObserver], since a `RecordingObserver`-only pin would
    /// never catch a mutant that wires the wrong label VALUE into the real
    /// Prometheus counter (only that some overload was called).
    @Test
    void invocationsCounterCarriesTheRightEntryLabelForEachListener(@TempDir Path dir) throws Exception {
        Path jar = FnHttpTestSupport.functionJar(dir, "entrylabel", "fixture.pub.PathEchoFn", pathEchoSource());
        var manifest = FnHttpTestSupport.manifest("p", false, 10, 5000, "fixture.pub.PathEchoFn",
                "[{\"path\":\"/*\",\"auth\":\"none\"}]");
        var entry = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jar, manifest, null, null, null);
        var doc = FnHttpTestSupport.oneFunctionWithPublicRoute(entry, HOST, "/");

        var registry = new io.prometheus.metrics.model.registry.PrometheusRegistry();
        var metrics = new io.flowcatalyst.fnhost.metrics.FnMetrics(registry,
                new io.flowcatalyst.fnhost.load.FunctionRegistry(10));
        var options = FnHttpServer.Options.of(0, 512, "http://127.0.0.1:1", metrics, 0, TrustedProxies.DEFAULT).withHost("127.0.0.1");
        try (var h = FnHttpTestSupport.start(dir, doc, 50, options)) {
            var priv = h.get("/functions/" + ADDR.render() + "/x");
            assertThat(priv.statusCode()).isEqualTo(200);
            var pub = RawHttpClient.send(h.server.publicPort(), "GET", "/x", Map.of("Host", HOST), null);
            assertThat(pub.status()).isEqualTo(200);

            var out = new java.io.ByteArrayOutputStream();
            io.prometheus.metrics.expositionformats.ExpositionFormats.init().getPrometheusTextFormatWriter()
                    .write(out, registry.scrape());
            String scrape = out.toString(java.nio.charset.StandardCharsets.UTF_8);

            assertThat(scrape).as("mutant: entry always \"private\" (or always \"public\")")
                    .contains("entry=\"private\"").contains("entry=\"public\"");
        }
    }

    // ── package J3 (function-zones-and-aliases.md §4): alias-prefixed hostnames ──

    private static String bodySource(String className, String body) {
        return """
                package fixture.pub;
                import io.flowcatalyst.function.*;
                public final class %s implements Function {
                    public Result handle(Request in, FunctionContext ctx) throws Exception {
                        return Result.json(200, "{\\"body\\":\\"%s\\",\\"originalHost\\":\\"" + in.originalHost()
                                + "\\"}");
                    }
                }
                """.formatted(className, body);
    }

    /// P3 (spec §8): v1 live, v2 aliased `qa` with a DIFFERENT response body.
    /// `Host: qa-hello.localhost` resolves to v2's body (through the
    /// VERSIONED load path, entryForAlias); `Host: hello.localhost` to v1's
    /// (exact, live); `Host: staging-hello.localhost` is 404 — `staging` was
    /// never opted into this route's `aliasPrefixes` (mutant: resolve every
    /// alias-prefixed hostname by ADDRESS alone, which would answer v1's
    /// body for `qa-hello.localhost` too).
    @Test
    void aliasPrefixedHostnameServesTheAliasedVersionExactServesLiveUnoptedInPrefixIs404(
            @TempDir Path dir) throws Exception {
        String host = "hello.localhost";
        Path jarV1 = FnHttpTestSupport.functionJar(dir, "j3-v1", "fixture.pub.J3V1Fn",
                bodySource("J3V1Fn", "v1"));
        Path jarV2 = FnHttpTestSupport.functionJar(dir, "j3-v2", "fixture.pub.J3V2Fn",
                bodySource("J3V2Fn", "v2"));
        var manifestV1 = FnHttpTestSupport.manifest("p", false, 10, 5000, "fixture.pub.J3V1Fn",
                "[{\"path\":\"/*\",\"auth\":\"none\"}]");
        var manifestV2 = FnHttpTestSupport.manifest("p", false, 10, 5000, "fixture.pub.J3V2Fn",
                "[{\"path\":\"/*\",\"auth\":\"none\"}]");
        var liveEntry = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jarV1, manifestV1, null, null, null);
        var aliasEntry = FnHttpTestSupport.aliasEntry(ADDR, "fnc_1", "v2", 2, jarV2, manifestV2, null, null,
                List.of("qa"));
        var routeRef = new DesiredDocument.PublicRouteRef(host, "/", ADDR, List.of("qa"));
        var doc = new DesiredDocument(List.of(liveEntry, aliasEntry), List.of(), List.of(), List.of(routeRef));
        RecordingObserver observer = new RecordingObserver();
        var options = FnHttpServer.Options.of(0, 512, "http://127.0.0.1:1", observer, 0, TrustedProxies.DEFAULT).withHost("127.0.0.1");
        try (var h = FnHttpTestSupport.start(dir, doc, 50, options)) {
            var qa = RawHttpClient.send(h.server.publicPort(), "GET", "/x", Map.of("Host", "qa-" + host), null);
            assertThat(qa.status()).as("mutant: resolve by address only").isEqualTo(200);
            assertThat(FnHttpTestSupport.json(qa.body()).path("body").asString())
                    .as("mutant: resolve by address only, serving v1's body for the qa alias").isEqualTo("v2");
            assertThat(FnHttpTestSupport.json(qa.body()).path("originalHost").asString())
                    .as("Request.originalHost carries the prefixed hostname as arrived")
                    .isEqualTo("qa-" + host);

            var live = RawHttpClient.send(h.server.publicPort(), "GET", "/x", Map.of("Host", host), null);
            assertThat(live.status()).isEqualTo(200);
            assertThat(FnHttpTestSupport.json(live.body()).path("body").asString()).isEqualTo("v1");

            var notOptedIn = RawHttpClient.send(h.server.publicPort(), "GET", "/x",
                    Map.of("Host", "staging-" + host), null);
            assertThat(notOptedIn.status()).as("staging was never opted into aliasPrefixes").isEqualTo(404);
        }
    }

    @Test
    void publicPortIsDisabledByDefaultOptions(@TempDir Path dir) throws Exception {
        Path jar = FnHttpTestSupport.functionJar(dir, "nopublic", "fixture.pub.PathEchoFn", pathEchoSource());
        var manifest = FnHttpTestSupport.manifest("p", false, 10, 5000, "fixture.pub.PathEchoFn",
                "[{\"path\":\"/*\",\"auth\":\"none\"}]");
        var entry = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jar, manifest, null, null, null);
        // The plain Options.of(...) overload used by every pre-F2 test — no public listener.
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry))) {
            assertThat(h.server.publicPort()).isEqualTo(FnHttpServer.PUBLIC_PORT_DISABLED);
        }
    }
}
