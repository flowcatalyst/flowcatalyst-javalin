package io.flowcatalyst.fnhost.http;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.flowcatalyst.fnhost.reconcile.DesiredDocument;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.server.Logging;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/// D4b (`docs/spec/function-context.md` §2): X5 (`config()`/`secrets()`,
/// settings-triggered reload) and X6 (MDC on the worker thread). Real HTTP
/// against a real [FnHttpServer], fixture functions from
/// [io.flowcatalyst.fnhost.load.FixtureJars] via [FnHttpTestSupport].
class ContextReloadAndLoggingTest {

    private static final FunctionAddress ADDR = FnHttpTestSupport.ADDR_A;

    // ── X5: config()/secrets() require() ────────────────────────────────

    private static final String REQUIRE_SOURCE = """
            package fixture.http;
            import io.flowcatalyst.function.*;

            public final class RequireFn implements Function {
                public Result handle(Request in, FunctionContext ctx) throws Exception {
                    try {
                        ctx.config().require("missing_key");
                        return Result.json(200, "{\\"unexpected\\":true}");
                    } catch (IllegalStateException e) {
                        String msg = e.getMessage().replace("\\"", "'");
                        return Result.json(200, "{\\"error\\":\\"" + msg + "\\"}");
                    }
                }
            }
            """;

    @Test
    void x5_requireOnAnUndeclaredOrUnsetKeyThrowsIllegalStateExceptionNamingTheKey(@TempDir Path dir) throws Exception {
        Path jar = FnHttpTestSupport.functionJar(dir, "require", "fixture.http.RequireFn", REQUIRE_SOURCE);
        var manifest = FnHttpTestSupport.manifest("p", false, 5, 5000, "fixture.http.RequireFn",
                "[{\"path\":\"/*\",\"auth\":\"none\"}]");
        // No config/secrets on the entry at all: "missing_key" is both undeclared and unset —
        // either way, spec §2 says the same thing happens (IllegalStateException naming the key).
        var entry = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jar, manifest, null, null, null);
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry))) {
            var resp = h.get("/functions/" + ADDR.render() + "/x");
            // mutant: config().require() falls back to the API jar's own default
            // (NoSuchElementException) instead of the host's own IllegalStateException — the
            // fixture's catch clause would then miss it entirely and the call would 500.
            assertThat(resp.statusCode()).as("mutant: require() throws NoSuchElementException, not caught here").isEqualTo(200);
            String body = new String(resp.body(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(body).as("the message names the key, never a value").contains("missing_key");
        }
    }

    // ── X5: a settings change reloads the function in place ────────────

    private static String reloadSource(Path initLog, Path started, Path release) {
        return """
                package fixture.http;
                import io.flowcatalyst.function.*;
                import java.nio.file.*;

                public final class ReloadFn implements Function {
                    private volatile String initValue;

                    public void init(FunctionContext ctx) throws Exception {
                        initValue = ctx.config().require("greeting");
                        Files.writeString(Path.of("%s"), initValue + "\\n", StandardOpenOption.CREATE,
                                StandardOpenOption.APPEND);
                    }

                    public Result handle(Request in, FunctionContext ctx) throws Exception {
                        String before = ctx.config().require("greeting");
                        boolean firstCall = !Files.exists(Path.of("%s"));
                        if (firstCall) {
                            Files.writeString(Path.of("%s"), "x");
                            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(90).toNanos();
                            while (!Files.exists(Path.of("%s")) && System.nanoTime() < deadline) {
                                try { Thread.sleep(15); } catch (InterruptedException ignored) { }
                            }
                        }
                        String after = ctx.config().require("greeting");
                        return Result.json(200, "{\\"before\\":\\"" + before + "\\",\\"after\\":\\"" + after
                                + "\\",\\"init\\":\\"" + initValue + "\\"}");
                    }
                }
                """.formatted(path(initLog), path(started), path(started), path(release));
    }

    @Test
    void x5_settingsChangeReloadsInitRunsAgainAndAnInFlightCallOnTheOldInstanceSeesTheOldValuesThroughout(
            @TempDir Path dir) throws Exception {
        Path initLog = dir.resolve("init-log");
        Path started = dir.resolve("started");
        Path release = dir.resolve("release");
        Path jar = FnHttpTestSupport.functionJar(dir, "reload", "fixture.http.ReloadFn",
                reloadSource(initLog, started, release));
        var manifest = FnHttpTestSupport.manifest("p", true, 5, 20000, "fixture.http.ReloadFn",
                "[{\"path\":\"/*\",\"auth\":\"none\"}]");
        var oldEntry = entryWithConfig(jar, manifest, "v1", 1, Map.of("greeting", "old"));

        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(oldEntry))) {
            // init() ran once already, synchronously, as part of Harness#start's own reconcile.
            assertThat(Files.readAllLines(initLog)).as("init() ran once at load").containsExactly("old");

            var executor = Executors.newVirtualThreadPerTaskExecutor();
            CompletableFuture<HttpResponse<byte[]>> parked =
                    CompletableFuture.supplyAsync(() -> h.get("/functions/" + ADDR.render() + "/x"), executor);
            awaitFile(started);

            // Republish the SAME version with DIFFERENT settings — reconcileOnce's own
            // applyLoadOutcome blocks inside the displaced (still-parked) instance's close()
            // drain, so this must run off the test thread, same shape as H13's promote.
            var newEntry = entryWithConfig(jar, manifest, "v1", 1, Map.of("greeting", "new"));
            CompletableFuture<Void> republish =
                    CompletableFuture.runAsync(() -> h.publish(FnHttpTestSupport.oneFunction(newEntry)), executor);

            // init() running a second time — with the NEW value — is observable the moment the
            // swap happens, well before the displaced instance finishes draining (attachContextAndInit
            // completes, and the new version is registered, before Reconciler ever calls close()
            // on the displaced one).
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline && Files.readAllLines(initLog).size() < 2) {
                Thread.sleep(10);
            }
            assertThat(Files.readAllLines(initLog))
                    .as("mutant: mutate the context in place — init() must run again with a fresh context")
                    .containsExactly("old", "new");

            // A NEW call (the "started" marker already exists, so this fixture instance does not
            // park) must already see the NEW settings — the swapped-in version's own context.
            var afterSwap = h.get("/functions/" + ADDR.render() + "/x");
            assertThat(afterSwap.statusCode()).isEqualTo(200);
            var afterSwapJson = FnHttpTestSupport.json(afterSwap.body());
            assertThat(afterSwapJson.path("before").asString()).isEqualTo("new");
            assertThat(afterSwapJson.path("after").asString()).isEqualTo("new");

            // Release the call parked on the OLD instance: it must see the OLD value at both
            // reads — a half-updated context (or one mutated in place) would show "new" for the
            // second read even though the call started under "old".
            Files.writeString(release, "x");
            var parkedResp = parked.get(15, TimeUnit.SECONDS);
            assertThat(parkedResp.statusCode()).isEqualTo(200);
            var parkedJson = FnHttpTestSupport.json(parkedResp.body());
            assertThat(parkedJson.path("before").asString())
                    .as("mutant: mutate the context in place").isEqualTo("old");
            assertThat(parkedJson.path("after").asString())
                    .as("mutant: mutate the context in place — the in-flight call sees old values THROUGHOUT")
                    .isEqualTo("old");
            assertThat(parkedJson.path("init").asString()).isEqualTo("old");

            republish.get(15, TimeUnit.SECONDS);
        }
    }

    private static DesiredDocument.Entry entryWithConfig(Path jar, io.flowcatalyst.platform.function.Manifest manifest,
                                                           String versionId, int version, Map<String, String> config) {
        return new DesiredDocument.Entry(ADDR, "fnc_1", versionId, version, DesiredDocument.Role.LIVE,
                DesiredDocument.Mode.WARM, FnHttpTestSupport.digestOf(jar), FnHttpTestSupport.fileRef(jar), null, null,
                manifest, null, null, null, config, Map.of(), List.of());
    }

    // ── X6: MDC on the worker thread — the FUNCTION's own log line ─────

    private static String loggingSource() {
        return """
                package fixture.http;
                import io.flowcatalyst.function.*;
                import java.lang.System.Logger.Level;

                public final class LoggingFn implements Function {
                    public Result handle(Request in, FunctionContext ctx) throws Exception {
                        ctx.logger().log(Level.INFO, "hello from the function");
                        return Result.ack();
                    }
                }
                """;
    }

    @Test
    void x6_theFunctionsOwnLogLineCarriesFunctionVersionExecutionIdAndCorrelationId(@TempDir Path dir) throws Exception {
        Path jar = FnHttpTestSupport.functionJar(dir, "logging", "fixture.http.LoggingFn", loggingSource());
        var manifest = FnHttpTestSupport.manifest("p", false, 5, 5000, "fixture.http.LoggingFn",
                "[{\"path\":\"/*\",\"auth\":\"none\"}]");
        var entry = FnHttpTestSupport.liveEntry(ADDR, "fnc_1", "v1", 1, jar, manifest, null, null, null);

        var fnLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("fn." + ADDR.render());
        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        fnLogger.addAppender(captured);
        fnLogger.setLevel(ch.qos.logback.classic.Level.INFO);
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.oneFunction(entry))) {
            var resp = h.get("/functions/" + ADDR.render() + "/x", "X-Correlation-Id", "corr-fn-1");
            assertThat(resp.statusCode()).isEqualTo(200);

            ILoggingEvent event = captured.list.stream()
                    .filter(e -> e.getFormattedMessage().contains("hello from the function"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("expected the function's own log line on fn." + ADDR.render()));
            Map<String, String> mdc = event.getMDCPropertyMap();
            assertThat(mdc.get(Logging.MdcKeys.FUNCTION))
                    .as("mutant: set MDC on the request thread only — the function's own log line would carry nothing")
                    .isEqualTo(ADDR.render());
            assertThat(mdc.get(Logging.MdcKeys.VERSION)).isEqualTo("1");
            assertThat(mdc.get(Logging.MdcKeys.EXECUTION_ID)).isNotBlank();
            assertThat(mdc.get(Logging.MdcKeys.CORRELATION_ID)).isEqualTo("corr-fn-1");

            // A host log line issued well AFTER the invocation (this test's own logger, on the
            // test's own thread, which never had these keys set) must never carry them either —
            // the absence half of the pin.
            var afterAppender = new ListAppender<ILoggingEvent>();
            afterAppender.start();
            var thisLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ContextReloadAndLoggingTest.class);
            thisLogger.addAppender(afterAppender);
            thisLogger.info("a host log line issued after the invocation completed");
            ILoggingEvent after = afterAppender.list.get(afterAppender.list.size() - 1);
            assertThat(after.getMDCPropertyMap().get(Logging.MdcKeys.EXECUTION_ID))
                    .as("a host log line after the invocation must not carry the invocation's MDC")
                    .isNull();
        } finally {
            fnLogger.detachAppender(captured);
        }
    }

    private static String path(Path p) {
        return p.toString().replace("\\", "\\\\");
    }

    private static void awaitFile(Path marker) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
        while (!Files.exists(marker)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + marker);
            }
            Thread.sleep(10);
        }
    }
}
