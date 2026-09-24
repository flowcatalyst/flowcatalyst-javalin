package io.flowcatalyst.fnhost.http;

import io.flowcatalyst.platform.function.FunctionAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/// `FnHttpServer` calls the right [InvocationObserver] method, with the
/// right arguments, at the right point, for each HTTP scenario
/// (`docs/spec/function-host-process.md` §2, tests P2-P4's WIRING half —
/// `FnMetricsTest` pins that a real `FnMetrics` turns these calls into the
/// right series). One mutant per outcome (P2's own table): a
/// [RecordingObserver] instead of a real registry keeps each assertion to
/// "was exactly this called, with exactly these arguments" — the strongest
/// assertion a collapsed/mislabelled outcome could fail.
class FnHttpServerObserverWiringTest {

    private static final FunctionAddress ADDR = FunctionAddress.parse("w.svc.a");
    private static final FunctionAddress ADDR_B = FunctionAddress.parse("w.svc.b");

    private static String statusFnSource() {
        return """
                package fixture.wiring;
                import io.flowcatalyst.function.*;
                import java.util.*;
                public final class StatusFn implements Function {
                    public Result handle(Request in, FunctionContext ctx) throws Exception {
                        int status = 200;
                        if (in.query().containsKey("status")) {
                            status = Integer.parseInt(in.query().get("status").get(0));
                        }
                        return Result.http(status, Map.of(), new byte[0]);
                    }
                }
                """;
    }

    private static final String THROWING_SOURCE = """
            package fixture.wiring;
            import io.flowcatalyst.function.*;
            public final class ThrowingFn implements Function {
                public Result handle(Request in, FunctionContext ctx) throws Exception {
                    throw new IllegalStateException("boom");
                }
            }
            """;

    private static String parkingSource(Path started, Path release) {
        return """
                package fixture.wiring;
                import io.flowcatalyst.function.*;
                import java.nio.file.*;
                public final class ParkingFn implements Function {
                    public Result handle(Request in, FunctionContext ctx) throws Exception {
                        Files.writeString(Path.of("%s"), "x");
                        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(90).toNanos();
                        while (!Files.exists(Path.of("%s")) && System.nanoTime() < deadline) {
                            try { Thread.sleep(15); } catch (InterruptedException ignored) { }
                        }
                        return Result.json(200, "{}");
                    }
                }
                """.formatted(path(started), path(release));
    }

    private static String path(Path p) {
        return p.toString().replace("\\", "\\\\");
    }

    // ── ok / client_error / retry / error(5xx) — all via Result.http(status) ──

    @Test
    void okClientErrorRetryAndFiveHundredEachCompleteWithExactlyThatOutcome(@TempDir Path dir) throws Exception {
        Path jar = FnHttpTestSupport.functionJar(dir, "status", "fixture.wiring.StatusFn", statusFnSource());
        var manifest = FnHttpTestSupport.manifest("p", false, 10, 5000, "fixture.wiring.StatusFn",
                "[{\"path\":\"/*\",\"auth\":\"none\"}]");
        var entry = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jar, manifest, null, null, null);
        RecordingObserver observer = new RecordingObserver();
        var options = FnHttpServer.Options.of(0, 512, "http://127.0.0.1:1", observer).withHost("127.0.0.1");
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry), 50, options)) {
            assertThat(h.get("/functions/" + ADDR.render() + "/x?status=200").statusCode()).isEqualTo(200);
            assertThat(h.get("/functions/" + ADDR.render() + "/x?status=404").statusCode()).isEqualTo(404);
            assertThat(h.get("/functions/" + ADDR.render() + "/x?status=429").statusCode()).isEqualTo(429);
            assertThat(h.get("/functions/" + ADDR.render() + "/x?status=503").statusCode()).isEqualTo(503);
        }

        assertThat(observer.completions()).as("mutant: collapse two outcomes")
                .extracting(RecordingObserver.Completed::outcome)
                .containsExactly("ok", "client_error", "retry", "error");
        assertThat(observer.completions()).allSatisfy(c -> {
            assertThat(c.address()).isEqualTo(ADDR);
            assertThat(c.version()).isEqualTo(1);
        });
        assertThat(observer.refusals()).as("none of these are host refusals").isEmpty();
        // entered/exited symmetry: every completion has a matching entered+exited pair.
        assertThat(observer.entries()).hasSize(4);
        assertThat(observer.exits()).hasSize(4);
    }

    // ── error via throw ──

    @Test
    void throwCompletesWithErrorOutcome(@TempDir Path dir) throws Exception {
        Path jar = FnHttpTestSupport.functionJar(dir, "throw", "fixture.wiring.ThrowingFn", THROWING_SOURCE);
        var manifest = FnHttpTestSupport.manifest("p", false, 10, 5000, "fixture.wiring.ThrowingFn",
                "[{\"path\":\"/*\",\"auth\":\"none\"}]");
        var entry = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jar, manifest, null, null, null);
        RecordingObserver observer = new RecordingObserver();
        var options = FnHttpServer.Options.of(0, 512, "http://127.0.0.1:1", observer).withHost("127.0.0.1");
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry), 50, options)) {
            assertThat(h.get("/functions/" + ADDR.render() + "/x").statusCode()).isEqualTo(500);
        }
        assertThat(observer.completions()).extracting(RecordingObserver.Completed::outcome)
                .as("mutant: collapse two outcomes").containsExactly("error");
        assertThat(observer.entries()).hasSize(1);
        assertThat(observer.exits()).as("mutant: decrement only on success — throw releases too").hasSize(1);
    }

    // ── busy: a permit refusal never enters the function, never completes ──

    @Test
    void busyRefusesWithoutEnteringOrCompleting(@TempDir Path dir) throws Exception {
        Path started = dir.resolve("started");
        Path release = dir.resolve("release");
        Path jar = FnHttpTestSupport.functionJar(dir, "park", "fixture.wiring.ParkingFn", parkingSource(started, release));
        var manifest = FnHttpTestSupport.manifest("p", false, 1, 5000, "fixture.wiring.ParkingFn",
                "[{\"path\":\"/*\",\"auth\":\"none\"}]"); // maxConcurrency 1
        var entry = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jar, manifest, null, null, null);
        RecordingObserver observer = new RecordingObserver();
        var options = FnHttpServer.Options.of(0, 512, "http://127.0.0.1:1", observer).withHost("127.0.0.1");
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry), 50, options);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<HttpResponse<byte[]>> first = CompletableFuture.supplyAsync(
                    () -> h.get("/functions/" + ADDR.render() + "/x"), executor);
            awaitFile(started);

            var second = h.get("/functions/" + ADDR.render() + "/x");
            assertThat(second.statusCode()).as("mutant: count refusals as ok").isEqualTo(429);

            Files.writeString(release, "x");
            assertThat(first.get(30, java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        }

        assertThat(observer.refusals()).as("mutant: count refusals as ok")
                .containsExactly(new RecordingObserver.Refused("busy", ADDR));
        assertThat(observer.completions()).extracting(RecordingObserver.Completed::outcome).containsExactly("ok");
        assertThat(observer.entries()).as("mutant: skip the endpoint match; pass the full path — only the FIRST call entered")
                .hasSize(1);
    }

    private static void awaitFile(Path marker) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(marker)) return;
            Thread.sleep(10);
        }
        throw new AssertionError("timed out waiting for " + marker);
    }

    // ── timeout: refused? no — ENTERED, completed(timeout), exited only once the worker returns ──

    @Test
    void timeoutEntersCompletesWithTimeoutThenExitsOnlyOnceTheWorkerActuallyReturns(@TempDir Path dir) throws Exception {
        Path started = dir.resolve("started");
        Path release = dir.resolve("release");
        Path jar = FnHttpTestSupport.functionJar(dir, "park", "fixture.wiring.ParkingFn", parkingSource(started, release));
        var manifest = FnHttpTestSupport.manifest("p", false, 5, 200, "fixture.wiring.ParkingFn",
                "[{\"path\":\"/*\",\"auth\":\"none\",\"timeoutMs\":200}]");
        var entry = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jar, manifest, null, null, null);
        RecordingObserver observer = new RecordingObserver();
        var options = FnHttpServer.Options.of(0, 512, "http://127.0.0.1:1", observer).withHost("127.0.0.1");
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry), 50, options)) {
            assertThat(h.get("/functions/" + ADDR.render() + "/x").statusCode()).isEqualTo(504);

            assertThat(observer.entries()).hasSize(1);
            assertThat(observer.completions()).extracting(RecordingObserver.Completed::outcome)
                    .as("mutant: collapse two outcomes").containsExactly("timeout");
            assertThat(observer.exits()).as("mutant: release the permit at the deadline — exit must wait too")
                    .isEmpty();

            Files.writeString(release, "x");
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline && observer.exits().isEmpty()) {
                Thread.sleep(10);
            }
            assertThat(observer.exits()).as("freed once the worker actually returns").hasSize(1);
        }
    }

    // ── unauthorized: unversioned webhook, wrong/missing signature ──

    @Test
    void unversionedUnauthorizedRefusesWithTheKnownAddress(@TempDir Path dir) throws Exception {
        Path jar = FnHttpTestSupport.functionJar(dir, "status", "fixture.wiring.StatusFn", statusFnSource());
        var manifest = FnHttpTestSupport.manifest("p", false, 10, 5000, "fixture.wiring.StatusFn",
                "[{\"path\":\"/events/*\",\"auth\":\"webhook\"}]");
        var entry = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jar, manifest, "secret", "app_1", "clt_1");
        RecordingObserver observer = new RecordingObserver();
        var options = FnHttpServer.Options.of(0, 512, "http://127.0.0.1:1", observer).withHost("127.0.0.1");
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry), 50, options)) {
            assertThat(h.post("/functions/" + ADDR.render() + "/events/x", new byte[0]).statusCode()).isEqualTo(401);
        }
        assertThat(observer.refusals()).containsExactly(new RecordingObserver.Refused("unauthorized", ADDR));
        assertThat(observer.entries()).as("mutant: count refusals as ok").isEmpty();
        assertThat(observer.completions()).isEmpty();
    }

    // ── unavailable: a lazy function whose load fails ──

    @Test
    void unavailableRefusesWithTheKnownAddressWhenLoadFails(@TempDir Path dir) throws Exception {
        Path jar = FnHttpTestSupport.functionJar(dir, "status", "fixture.wiring.StatusFn", statusFnSource());
        // Wrong entrypoint on purpose: the jar has StatusFn, the manifest names a class
        // that isn't there — ENTRYPOINT_NOT_FOUND, so ensureLoaded never succeeds.
        var manifest = FnHttpTestSupport.manifest("p", false, 10, 5000, "fixture.wiring.DoesNotExist",
                "[{\"path\":\"/*\",\"auth\":\"none\"}]");
        var entry = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jar, manifest, null, null, null);
        RecordingObserver observer = new RecordingObserver();
        var options = FnHttpServer.Options.of(0, 512, "http://127.0.0.1:1", observer).withHost("127.0.0.1");
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry), 50, options)) {
            assertThat(h.get("/functions/" + ADDR.render() + "/x").statusCode()).isEqualTo(503);
        }
        assertThat(observer.refusals()).containsExactly(new RecordingObserver.Refused("unavailable", ADDR));
        assertThat(observer.entries()).isEmpty();
    }

    // ── not_found: an unversioned call to an address nobody has ever heard of ──

    @Test
    void unversionedNotFoundRefusesWithNullAddress(@TempDir Path dir) throws Exception {
        Path jar = FnHttpTestSupport.functionJar(dir, "status", "fixture.wiring.StatusFn", statusFnSource());
        var manifest = FnHttpTestSupport.manifest("p", false, 10, 5000, "fixture.wiring.StatusFn",
                "[{\"path\":\"/*\",\"auth\":\"none\"}]");
        var entry = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jar, manifest, null, null, null);
        RecordingObserver observer = new RecordingObserver();
        var options = FnHttpServer.Options.of(0, 512, "http://127.0.0.1:1", observer).withHost("127.0.0.1");
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry), 50, options)) {
            assertThat(h.get("/functions/nope.svc.fn/x").statusCode()).isEqualTo(404);
        }
        assertThat(observer.refusals()).as("mutant: label by requested address, not null")
                .containsExactly(new RecordingObserver.Refused("not_found", null));
    }

    // ── permitsReady: called exactly once, before any request ──

    @Test
    void permitsReadyIsCalledExactlyOnce(@TempDir Path dir) throws Exception {
        Path jar = FnHttpTestSupport.functionJar(dir, "status", "fixture.wiring.StatusFn", statusFnSource());
        var manifest = FnHttpTestSupport.manifest("p", false, 10, 5000, "fixture.wiring.StatusFn",
                "[{\"path\":\"/*\",\"auth\":\"none\"}]");
        var entry = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jar, manifest, null, null, null);
        RecordingObserver observer = new RecordingObserver();
        var options = FnHttpServer.Options.of(0, 512, "http://127.0.0.1:1", observer).withHost("127.0.0.1");
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry), 50, options)) {
            assertThat(h.server.port()).as("the server actually bound").isPositive();
            assertThat(observer.permitsReadyCallCount()).isEqualTo(1);
        }
    }
}
