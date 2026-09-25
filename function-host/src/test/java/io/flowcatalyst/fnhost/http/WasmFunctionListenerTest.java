package io.flowcatalyst.fnhost.http;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import io.flowcatalyst.fnhost.reconcile.ControlPlane;
import io.flowcatalyst.fnhost.reconcile.DesiredDocument;
import io.flowcatalyst.fnhost.wasm.WasmFixtures;
import io.flowcatalyst.function.EmitResult;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.platform.function.Manifest;
import io.flowcatalyst.server.Logging;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/// Wasm functions through the REAL listener (`docs/spec/function-wasm-runtime.md`
/// §6 tests 1-4, 7, 9): a [io.flowcatalyst.fnhost.reconcile.Reconciler] fed by a
/// fake control plane loads the committed Rust guest ([WasmFixtures#guest]) and
/// real HTTP calls reach it. Each test names the guest export it exercises as
/// the manifest's `entrypoint`. The guest reports `x-instance-calls` — how many
/// calls the instance that answered has served — so "a fresh instance" is an
/// observed fact, not an inference.
class WasmFunctionListenerTest {

    private static final FunctionAddress ADDR = FnHttpTestSupport.ADDR_A;
    private static final FunctionAddress JVM_ADDR = FnHttpTestSupport.ADDR_B;

    // ── test 1: READY, reachable, request fields and caller intact ─────────

    @Test
    void aWasmVersionLoadsAndAnHttpCallReachesTheGuestWithEveryRequestFieldIntact(@TempDir Path dir) {
        Path wasm = WasmFixtures.guest(dir);
        Manifest manifest = WasmFixtures.manifest("echo", 4, 5000, 16,
                "[{\"path\":\"/echo/{id}\",\"auth\":\"none\"}]", List.of(), List.of(), List.of());
        var entry = entry(wasm, manifest, DesiredDocument.Mode.WARM, Map.of(), Map.of());
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry))) {
            assertThat(h.registry.peek(ADDR)).as("the warm wasm version is loaded (READY)").isNotNull();
            assertThat(h.controlPlane.heartbeats().getLast().loaded())
                    .as("reported LOADED, not FAILED:RUNTIME_UNSUPPORTED")
                    .singleElement()
                    .satisfies(e -> assertThat(e.state())
                            .isInstanceOf(io.flowcatalyst.fnhost.reconcile.HeartbeatReport.LoadState.Loaded.class));

            var resp = h.post("/functions/" + ADDR.render() + "/echo/42?y=hello+world&y=again",
                    "héllo body".getBytes(StandardCharsets.UTF_8), "X-Test-Custom", "hi");
            assertThat(resp.statusCode()).as(text(resp)).isEqualTo(200);
            assertThat(resp.headers().allValues("x-guest"))
                    .as("the guest's own multi-valued header reaches the caller").containsExactly("echo", "twice");

            JsonNode echoed = FnHttpTestSupport.json(resp.body());
            assertThat(echoed.path("address").asString()).isEqualTo(ADDR.render());
            assertThat(echoed.path("version").asInt()).isEqualTo(1);
            assertThat(echoed.path("invocationId").asString()).isNotBlank();
            assertThat(echoed.path("method").asString()).isEqualTo("POST");
            assertThat(echoed.path("path").asString()).isEqualTo("/echo/42");
            assertThat(echoed.path("originalPath").asString()).isEqualTo("/functions/" + ADDR.render() + "/echo/42");
            assertThat(echoed.path("originalHost").asString()).startsWith("127.0.0.1:");
            assertThat(echoed.path("pathParams").path("id").asString())
                    .as("mutant: drop pathParams from the input JSON").isEqualTo("42");
            assertThat(echoed.path("query").path("y").valueStream().map(JsonNode::asString).toList())
                    .containsExactly("hello world", "again");
            assertThat(headerValues(echoed.path("headers"), "X-Test-Custom")).containsExactly("hi");
            assertThat(new String(Base64.getDecoder().decode(echoed.path("bodyBase64").asString()),
                    StandardCharsets.UTF_8)).isEqualTo("héllo body");
            assertThat(echoed.path("remoteAddress").asString()).isEqualTo("127.0.0.1");
            assertThat(echoed.path("caller").path("kind").asString()).isEqualTo("anonymous");
        }
    }

    // ── test 2: the deadline stops the guest; the thread is freed ────────

    @Test
    void aSpinningGuestIsStoppedAtItsDeadlineItsThreadFreedAndTheNextCallServedByAFreshInstance(
            @TempDir Path dir) throws Exception {
        Path wasm = WasmFixtures.guest(dir);
        Manifest manifest = WasmFixtures.manifest("spin", 1, 200, 16); // 200 ms timeout, maxConcurrency 1
        var entry = entry(wasm, manifest, DesiredDocument.Mode.LAZY, Map.of(), Map.of());
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry))) {
            long start = System.nanoTime();
            var spun = h.get("/functions/" + ADDR.render() + "/x");
            assertThat(spun.statusCode()).as("the listener's timeout status").isEqualTo(504);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));

            // The permit is released only when the worker has actually returned (H7), so the
            // permit coming back IS the thread coming free.
            long freedAt = System.nanoTime();
            while (h.server.permitsForTest().functionAvailable(ADDR) == 0
                    && System.nanoTime() - freedAt < Duration.ofSeconds(3).toNanos()) {
                Thread.sleep(5);
            }
            assertThat(h.server.permitsForTest().functionAvailable(ADDR))
                    .as("mutant: catch and ignore the interrupt — the guest spins on and holds the permit")
                    .isEqualTo(1);
            assertThat(Duration.ofNanos(System.nanoTime() - start))
                    .as("the guest stops at the deadline, not whenever it likes")
                    .isLessThan(Duration.ofMillis(200 + 1000));

            var next = h.get("/functions/" + ADDR.render() + "/x?spin=false");
            assertThat(next.statusCode()).as(text(next)).isEqualTo(200);
            assertThat(next.headers().firstValue("x-instance-calls"))
                    .as("the interrupted instance was discarded; a fresh one answered").contains("1");
        }
    }

    // ── test 3: the memory cap is a clean 500; the instance is replaced ───

    @Test
    void anAllocationPastTheCapIsA500AndTheNextCallIsServedByAFreshInstance(@TempDir Path dir) {
        Path wasm = WasmFixtures.guest(dir);
        Manifest manifest = WasmFixtures.manifest("alloc", 1, 5000, 16); // 16 MiB cap, one instance
        var entry = entry(wasm, manifest, DesiredDocument.Mode.LAZY, Map.of(), Map.of());
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry))) {
            var small = h.get("/functions/" + ADDR.render() + "/x?mb=1");
            assertThat(small.statusCode()).as(text(small)).isEqualTo(200);
            assertThat(small.headers().firstValue("x-instance-calls")).contains("1");

            var tooBig = h.get("/functions/" + ADDR.render() + "/x?mb=64");
            assertThat(tooBig.statusCode()).as("64 MiB past a 16 MiB cap: " + text(tooBig)).isEqualTo(500);
            assertThat(FnHttpTestSupport.json(tooBig.body()).path("error").asString())
                    .as("a fixed reason, never the guest's own message").isEqualTo("the function failed");

            var after = h.get("/functions/" + ADDR.render() + "/x?mb=1");
            assertThat(after.statusCode()).as(text(after)).isEqualTo(200);
            assertThat(after.headers().firstValue("x-instance-calls"))
                    .as("mutant: return the failed instance to the pool — it would answer its 3rd call")
                    .contains("1");
        }
    }

    /// The Extism kernel's OWN memory (input, output, every block a guest allocates through
    /// `Memory::new`) is capped by the same `wasmMemoryMb` — without it a guest looping kernel
    /// allocations grows the host's heap to the 4 GiB Wasm limit. The kernel refuses by answering
    /// offset 0 (the PDK does not raise), so the guest reports how much it was really granted.
    @Test
    void theKernelsOwnMemoryIsCappedByWasmMemoryMb(@TempDir Path dir) {
        Path wasm = WasmFixtures.guest(dir);
        Manifest manifest = WasmFixtures.manifest("kalloc", 1, 5000, 16); // 16 MiB cap
        var entry = entry(wasm, manifest, DesiredDocument.Mode.LAZY, Map.of(), Map.of());
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry))) {
            var r = h.get("/functions/" + ADDR.render() + "/x?mb=64");
            assertThat(r.statusCode()).as(text(r)).isEqualTo(200);
            int granted = FnHttpTestSupport.json(r.body()).path("grantedMb").asInt();
            assertThat(granted).as("mutant: kernel uncapped — all 64 MiB granted").isBetween(1, 16);
        }
    }

    // ── test 4: maxConcurrency calls run in parallel on separate instances ─

    @Test
    void maxConcurrencyCallsRunInParallelEachOnItsOwnInstance(@TempDir Path dir) throws Exception {
        int n = 4;
        int ms = 400;
        Path wasm = WasmFixtures.guest(dir);
        Manifest manifest = WasmFixtures.manifest("busy", n, 10_000, 16);
        var entry = entry(wasm, manifest, DesiredDocument.Mode.WARM, Map.of(), Map.of());
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry))) {
            assertThat(h.get("/functions/" + ADDR.render() + "/x?ms=1").statusCode()).as("warm-up").isEqualTo(200);

            var executor = Executors.newVirtualThreadPerTaskExecutor();
            List<CompletableFuture<HttpResponse<byte[]>>> calls = new ArrayList<>();
            long start = System.nanoTime();
            for (int i = 0; i < n; i++) {
                calls.add(CompletableFuture.supplyAsync(
                        () -> h.get("/functions/" + ADDR.render() + "/x?ms=" + ms), executor));
            }
            List<JsonNode> bodies = new ArrayList<>();
            for (var call : calls) {
                var resp = call.get(30, TimeUnit.SECONDS);
                assertThat(resp.statusCode()).as(text(resp)).isEqualTo(200);
                bodies.add(FnHttpTestSupport.json(resp.body()));
            }
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            long latestStart = bodies.stream().mapToLong(b -> b.path("startNanos").asLong()).max().orElseThrow();
            long earliestEnd = bodies.stream().mapToLong(b -> b.path("endNanos").asLong()).min().orElseThrow();
            assertThat(latestStart)
                    .as("mutant: one instance behind a lock — every guest run would start after another ended")
                    .isLessThan(earliestEnd);
            assertThat(elapsed)
                    .as("closer to one call (%d ms) than to %d in series (%d ms)", ms, n, n * ms)
                    .isLessThan(Duration.ofMillis((ms + (long) n * ms) / 2));
        }
    }

    // ── guest failures: error code and malformed output ───────────────────

    @Test
    void aGuestErrorCodeAndAMalformedReplyAreEach500AndNeverAHostException(@TempDir Path dir) {
        Path wasm = WasmFixtures.guest(dir);
        var failing = entry(wasm, WasmFixtures.manifest("fail", 1, 5000, 16), DesiredDocument.Mode.LAZY,
                Map.of(), Map.of());
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(failing))) {
            var failed = h.get("/functions/" + ADDR.render() + "/x");
            assertThat(failed.statusCode()).isEqualTo(500);
            assertThat(text(failed)).as("the guest's own message stays in the host's WARN")
                    .doesNotContain("on purpose");
            var after = h.get("/functions/" + ADDR.render() + "/x?fail=false");
            assertThat(after.statusCode()).isEqualTo(200);
            assertThat(after.headers().firstValue("x-instance-calls"))
                    .as("mutant: keep an instance whose call returned an error code").contains("1");
        }

        var malformed = entry(wasm, WasmFixtures.manifest("malformed", 1, 5000, 16), DesiredDocument.Mode.LAZY,
                Map.of(), Map.of());
        try (var h = FnHttpTestSupport.start(dir.resolve("m"), FnHttpTestSupport.oneFunction(malformed))) {
            var resp = h.get("/functions/" + ADDR.render() + "/x");
            assertThat(resp.statusCode()).as("mutant: pass a reply of the wrong shape through").isEqualTo(500);
        }
    }

    // ── test 7: host functions ───────────────────────────────────────────

    @Test
    void configAnswersADeclaredKeyAndNothingForAnUndeclaredOne(@TempDir Path dir) {
        Path wasm = WasmFixtures.guest(dir);
        Manifest manifest = WasmFixtures.manifest("config", 2, 5000, 16,
                "[{\"path\":\"/*\",\"auth\":\"none\"}]", List.of("greeting"), List.of(), List.of());
        // "extra" is in the entry's map but not the manifest's declaration: the guest must not see it.
        var entry = entry(wasm, manifest, DesiredDocument.Mode.LAZY,
                Map.of("greeting", "hello", "extra", "not-declared"), Map.of());
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry))) {
            assertThat(guestBody(h, "/x?key=greeting").path("value").asString()).isEqualTo("hello");
            assertThat(guestBody(h, "/x?key=extra").path("value").isNull())
                    .as("mutant: answer any key in the map, declared or not").isTrue();
            assertThat(guestBody(h, "/x?key=missing").path("value").isNull()).isTrue();
        }
    }

    @Test
    void secretAnswersADeclaredKeyEmptyForAnUndeclaredOneAndNeverAppearsInALogLine(@TempDir Path dir) {
        String secretValue = "s3cr3t-value-7f1c";
        Path wasm = WasmFixtures.guest(dir);
        Manifest manifest = WasmFixtures.manifest("secret", 2, 5000, 16,
                "[{\"path\":\"/*\",\"auth\":\"none\"}]", List.of(), List.of("api_token"), List.of());
        var entry = entry(wasm, manifest, DesiredDocument.Mode.LAZY, Map.of(),
                Map.of("api_token", secretValue, "not_declared", "zzz-undeclared"));

        var root = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        // Every configured logger to TRACE, not just the root: the test config pins
        // `io.flowcatalyst` at INFO, which would hide a debug line from a root-only capture.
        Map<ch.qos.logback.classic.Logger, Level> previous = new java.util.HashMap<>();
        for (var logger : root.getLoggerContext().getLoggerList()) {
            previous.put(logger, logger.getLevel());
            logger.setLevel(Level.TRACE);
        }
        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        root.addAppender(captured);
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry))) {
            assertThat(guestBody(h, "/x?key=api_token").path("value").asString()).isEqualTo(secretValue);
            assertThat(guestBody(h, "/x?key=not_declared").path("value").asString())
                    .as("mutant: answer any key in the map, declared or not").isEmpty();
            assertThat(guestBody(h, "/x?key=missing").path("value").asString()).isEmpty();
        } finally {
            root.detachAppender(captured);
            previous.forEach(ch.qos.logback.classic.Logger::setLevel);
        }
        assertThat(captured.list).as("the capture saw the host at work").isNotEmpty();
        assertThat(captured.list)
                .as("mutant: log the secret lookup — no line, field or cause may carry the value")
                .noneSatisfy(event -> assertThat(render(event)).contains(secretValue));
    }

    @Test
    void httpReachesAnAllowlistedLoopbackServerAndANonAllowlistedHostIsAGuestVisibleDenial(@TempDir Path dir)
            throws Exception {
        AtomicInteger served = new AtomicInteger();
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/ok", exchange -> {
            served.incrementAndGet();
            byte[] body = ("upstream-ok from-guest=" + exchange.getRequestHeaders().getFirst("x-from-guest"))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("x-upstream", "yes");
            exchange.sendResponseHeaders(201, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
        try {
            int port = upstream.getAddress().getPort();
            Path wasm = WasmFixtures.guest(dir);
            Manifest manifest = WasmFixtures.manifest("http", 2, 5000, 16,
                    "[{\"path\":\"/*\",\"auth\":\"none\"}]", List.of(), List.of(), List.of("127.0.0.1"));
            var entry = entry(wasm, manifest, DesiredDocument.Mode.LAZY, Map.of(), Map.of());
            try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry))) {
                JsonNode allowed = guestBody(h, "/x?url=" + enc("http://127.0.0.1:" + port + "/ok"));
                assertThat(allowed.path("status").asInt()).as(allowed.toString()).isEqualTo(201);
                assertThat(allowed.path("body").asString()).isEqualTo("upstream-ok from-guest=yes");
                assertThat(allowed.path("xUpstream").asString())
                        .as("a response header reaches the guest (the vendored SDK's headers fix)").isEqualTo("yes");
                assertThat(served.get()).isEqualTo(1);

                // localhost is loopback (so http is permitted) but NOT on this manifest's httpAllow.
                JsonNode denied = guestBody(h, "/x?url=" + enc("http://localhost:" + port + "/ok"));
                assertThat(denied.path("status").asInt())
                        .as("mutant: bypass the allowlist — the call would succeed: " + denied).isEqualTo(0);
                assertThat(denied.path("body").asString()).contains("httpAllow");
                assertThat(served.get()).as("the denied call never left the host").isEqualTo(1);

                JsonNode notHttps = guestBody(h, "/x?url=" + enc("http://example.com/ok"));
                assertThat(notHttps.path("status").asInt()).isEqualTo(0);
                assertThat(notHttps.path("body").asString()).contains("https only");
            }
        } finally {
            upstream.stop(0);
        }
    }

    @Test
    void emitReachesTheControlPlaneAndARefusalIsAValueTheGuestSees(@TempDir Path dir) {
        Path wasm = WasmFixtures.guest(dir);
        var entry = entry(wasm, WasmFixtures.manifest("emit", 2, 5000, 16), DesiredDocument.Mode.LAZY,
                Map.of(), Map.of());
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry))) {
            JsonNode ok = guestBody(h, "/x?dedupId=d-1");
            assertThat(ok.path("result").path("ok").asBoolean()).as(ok.toString()).isTrue();
            assertThat(ok.path("result").path("eventId").asString())
                    .as("mutant: the platform's event id not handed to the guest").isEqualTo("evt_fake_1");
            assertThat(h.controlPlane.emits()).as("mutant: never call the control plane").hasSize(1);
            ControlPlane.EmitRequest sent = h.controlPlane.emits().getFirst();
            assertThat(sent.address()).isEqualTo(ADDR);
            assertThat(sent.version()).isEqualTo(1);
            ControlPlane.EmitItem item = sent.events().getFirst();
            assertThat(item.type()).isEqualTo("fixture:guest:thing:happened");
            assertThat(item.dedupId()).isEqualTo("d-1");
            assertThat(item.subject()).isEqualTo("thing-1");
            assertThat(item.messageGroup()).isEqualTo("group-1");
            assertThat(FnHttpTestSupport.json(item.data().getBytes(StandardCharsets.UTF_8)).path("from").asString())
                    .isEqualTo("wasm");

            h.controlPlane.emitAnswers(request -> new EmitResult.Refused("EVENT_TYPE_NOT_OWNED", 403, "not owned"));
            JsonNode refused = guestBody(h, "/x?dedupId=d-2");
            assertThat(refused.path("result").path("ok").asBoolean())
                    .as("mutant: report ok regardless of the platform's answer").isFalse();
            assertThat(refused.path("result").path("error").asString()).isEqualTo("EVENT_TYPE_NOT_OWNED");

            int before = h.controlPlane.emitCallCount();
            JsonNode noDedup = guestBody(h, "/x");
            assertThat(noDedup.path("result").path("error").asString()).isEqualTo("DEDUP_ID_REQUIRED");
            assertThat(h.controlPlane.emitCallCount()).as("refused before it reached the platform").isEqualTo(before);
        }
    }

    @Test
    void guestLogLinesAndStdoutStderrLandInTheFunctionsOwnLogger(@TempDir Path dir) {
        Path wasm = WasmFixtures.guest(dir);
        var entry = entry(wasm, WasmFixtures.manifest("log", 2, 5000, 16), DesiredDocument.Mode.LAZY,
                Map.of(), Map.of());
        var fnLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("fn." + ADDR.render());
        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        fnLogger.addAppender(captured);
        fnLogger.setLevel(Level.TRACE);
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry))) {
            var resp = h.get("/functions/" + ADDR.render() + "/x?msg=wave", "X-Correlation-Id", "corr-wasm-1");
            assertThat(resp.statusCode()).as(text(resp)).isEqualTo(200);
        } finally {
            fnLogger.detachAppender(captured);
        }
        assertThat(captured.list).extracting(e -> e.getLevel() + " " + e.getFormattedMessage())
                .as("mutant: drop the guest's log lines / leave WASI stdout and stderr unwired")
                .contains("INFO guest info: wave", "WARN guest warn: wave",
                        "INFO guest stdout: wave", "WARN guest stderr: wave");
        ILoggingEvent info = captured.list.stream()
                .filter(e -> e.getFormattedMessage().equals("guest info: wave")).findFirst().orElseThrow();
        assertThat(info.getMDCPropertyMap().get(Logging.MdcKeys.FUNCTION))
                .as("logged on the invocation's own thread, so it carries the invocation's MDC")
                .isEqualTo(ADDR.render());
        assertThat(info.getMDCPropertyMap().get(Logging.MdcKeys.CORRELATION_ID)).isEqualTo("corr-wasm-1");
    }

    // ── test 9: a JVM function keeps working beside a Wasm one ────────────

    @Test
    void aJvmFunctionInTheSameHostKeepsWorkingBesideAWasmOne(@TempDir Path dir) {
        Path wasm = WasmFixtures.guest(dir);
        var wasmEntry = entry(wasm, WasmFixtures.manifest("echo", 2, 5000, 16), DesiredDocument.Mode.WARM,
                Map.of(), Map.of());
        Path jar = FnHttpTestSupport.functionJar(dir, "jvm", "fixture.http.LoaderFn", """
                package fixture.http;
                import io.flowcatalyst.function.*;
                public final class LoaderFn implements Function {
                    public Result handle(Request in, FunctionContext ctx) {
                        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
                        return Result.json(200, "{\\"tccl\\":\\"" + tccl.getName() + "\\"}");
                    }
                }
                """);
        var jvmEntry = new DesiredDocument.Entry(JVM_ADDR, "fnc_j", "j1", 1, DesiredDocument.Role.LIVE,
                DesiredDocument.Mode.WARM, FnHttpTestSupport.digestOf(jar), FnHttpTestSupport.fileRef(jar), null,
                null, FnHttpTestSupport.manifest("pool", true, 2, 5000, "fixture.http.LoaderFn",
                        "[{\"path\":\"/*\",\"auth\":\"none\"}]"),
                null, null, null, Map.of(), Map.of(), List.of(), List.of());
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.document(List.of(wasmEntry, jvmEntry)))) {
            var jvm = h.get("/functions/" + JVM_ADDR.render() + "/x");
            assertThat(jvm.statusCode()).as(text(jvm)).isEqualTo(200);
            assertThat(FnHttpTestSupport.json(jvm.body()).path("tccl").asString())
                    .as("the JVM function still runs under its own class loader")
                    .isEqualTo("fn:" + JVM_ADDR.render() + "@1");

            var wasmResp = h.get("/functions/" + ADDR.render() + "/x");
            assertThat(wasmResp.statusCode()).as(text(wasmResp)).isEqualTo(200);
            assertThat(FnHttpTestSupport.json(wasmResp.body()).path("address").asString()).isEqualTo(ADDR.render());

            var jvmAgain = h.get("/functions/" + JVM_ADDR.render() + "/x");
            assertThat(jvmAgain.statusCode()).isEqualTo(200);
        }
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    private static DesiredDocument.Entry entry(Path wasm, Manifest manifest, DesiredDocument.Mode mode,
                                               Map<String, String> config, Map<String, String> secrets) {
        return new DesiredDocument.Entry(ADDR, "fnc_w", "w1", 1, DesiredDocument.Role.LIVE, mode,
                FnHttpTestSupport.digestOf(wasm), FnHttpTestSupport.fileRef(wasm), null, null, manifest, null,
                null, null, config, secrets, List.of(), List.of());
    }

    /// Calls `pathAndQuery` on [#ADDR], asserts a 200, and returns the guest's
    /// own JSON body.
    private static JsonNode guestBody(FnHttpTestSupport.Harness h, String pathAndQuery) {
        var resp = h.get("/functions/" + ADDR.render() + pathAndQuery);
        assertThat(resp.statusCode()).as(text(resp)).isEqualTo(200);
        return FnHttpTestSupport.json(resp.body());
    }

    /// A header's values from the echoed `headers` object, name matched
    /// case-insensitively (the listener keeps the spelling it received).
    private static List<String> headerValues(JsonNode headers, String name) {
        for (Map.Entry<String, JsonNode> header : headers.properties()) {
            if (header.getKey().equalsIgnoreCase(name)) {
                return header.getValue().valueStream().map(JsonNode::asString).toList();
            }
        }
        return List.of();
    }

    /// Everything a log event could carry a value in: message, arguments,
    /// key-value pairs, MDC, and the cause chain.
    private static String render(ILoggingEvent event) {
        StringBuilder out = new StringBuilder(String.valueOf(event.getFormattedMessage()));
        if (event.getKeyValuePairs() != null) {
            event.getKeyValuePairs().forEach(kv -> out.append(' ').append(kv.key).append('=').append(kv.value));
        }
        out.append(' ').append(event.getMDCPropertyMap());
        for (var t = event.getThrowableProxy(); t != null; t = t.getCause()) {
            out.append(' ').append(t.getMessage());
        }
        return out.toString();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String text(HttpResponse<byte[]> resp) {
        return new String(resp.body(), StandardCharsets.UTF_8);
    }
}
