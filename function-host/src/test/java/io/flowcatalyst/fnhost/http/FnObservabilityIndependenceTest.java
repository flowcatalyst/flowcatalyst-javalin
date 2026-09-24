package io.flowcatalyst.fnhost.http;

import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/// P7 (`docs/spec/function-host-process.md` §2): the observability listener
/// answers while the FUNCTION listener's single event loop is blocked — its
/// own [io.vertx.core.Vertx] instance, its own event loop, entirely separate
/// from [FnHttpServer]'s.
class FnObservabilityIndependenceTest {

    private static final io.flowcatalyst.platform.function.FunctionAddress ADDR = FnHttpTestSupport.ADDR_A;

    @Test
    void observabilityAnswersWhileTheFunctionListenersOneEventLoopIsBlocked(@TempDir Path dir) throws Exception {
        Path started = dir.resolve("started");
        Path release = dir.resolve("release");
        Path jar = FnHttpTestSupport.functionJar(dir, "park", "fixture.observability.ParkingFn", """
                package fixture.observability;
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
                """.formatted(path(started), path(release)));
        var manifest = FnHttpTestSupport.manifest("p", false, 5, 5000, "fixture.observability.ParkingFn",
                "[{\"path\":\"/*\",\"auth\":\"none\"}]");
        var entry = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jar, manifest, null, null, null);

        // ONE event loop thread for the function listener — the same seam H1/H11c use to
        // make "the event loop is not blocked" mean something.
        var options = new FnHttpServer.Options("127.0.0.1", 0, 512, "http://127.0.0.1:1",
                java.time.Clock.systemUTC(), 1);
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry), 50, options);
             var observability = FnObservability.start(h.reconciler, new PrometheusRegistry(),
                     FnObservability.Options.of(0).withHost("127.0.0.1"));
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {

            CompletableFuture<HttpResponse<byte[]>> parked = CompletableFuture.supplyAsync(
                    () -> h.get("/functions/" + ADDR.render() + "/x"), executor);
            awaitFile(started);

            // The function listener's own event loop is now occupied handling (well, having
            // dispatched) the parked request — a second request to the SAME listener would
            // normally still get an event-loop slot (accept is cheap), so the real proof is
            // that the OBSERVABILITY listener, a completely different Vert.x instance, answers
            // promptly regardless.
            HttpClient client = HttpClient.newHttpClient();
            long start = System.nanoTime();
            HttpResponse<String> health = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + observability.port() + "/health"))
                            .timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(health.body()).contains("\"UP\"");
            assertThat(elapsedMs).as("mutant: one shared Vert.x server — must not wait on the parked function call")
                    .isLessThan(2000);

            Files.writeString(release, "x");
            assertThat(parked.get(30, java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        }
    }

    private static String path(Path p) {
        return p.toString().replace("\\", "\\\\");
    }

    private static void awaitFile(Path marker) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(marker)) return;
            Thread.sleep(10);
        }
        throw new AssertionError("timed out waiting for " + marker);
    }
}
