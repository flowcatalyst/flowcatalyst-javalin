package io.flowcatalyst.eventloop;

import com.sun.source.util.Plugin;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

/// The check, end to end through javac (`docs/spec/eventloop-check.md`). Each failing case asserts
/// the compilation fails AND names the blocking line, so a check that fails for the wrong reason,
/// or on the wrong line, does not pass. Each passing case is paired with a failing one on the same
/// shape, so "no error" cannot come from a check that never runs.
class EventLoopCheckTest {

    private static final String IMPORTS = """
            package app;
            import io.vertx.core.*;
            import io.flowcatalyst.eventloop.*;
            import java.util.List;
            import java.util.concurrent.CompletableFuture;
            import java.util.concurrent.ExecutorService;
            """;

    private static Compile.Result app(String body) {
        return Compile.sources(Map.of("app/App.java", IMPORTS + "class App {\n" + body + "\n}\n"));
    }

    /// The 1-based line of the first line of `body` containing `marker`, as it sits in App.java.
    private static int line(String body, String marker) {
        List<String> lines = (IMPORTS + "class App {\n" + body).lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(marker)) return i + 1;
        }
        throw new IllegalArgumentException(marker);
    }

    private static void assertRefused(Compile.Result r, String file, int line, String... fragments) {
        assertThat(r.success()).as("compilation must fail: " + r.errors()).isFalse();
        assertThat(r.errors()).as("an error on " + file + ":" + line)
                .anySatisfy(e -> {
                    assertThat(e).startsWith(file + ":" + line + " [EventLoopCheck]");
                    for (String f : fragments) assertThat(e).contains(f);
                });
    }

    @Test
    void sleepingInAVertxCallbackFailsTheCompilation() {
        String body = """
                void start(Vertx vertx) {
                    vertx.setTimer(10, id -> {
                        try { Thread.sleep(5); } catch (InterruptedException e) { }
                    });
                }
                """;
        assertRefused(app(body), "App.java", line(body, "Thread.sleep"),
                "Thread.sleep", "InterruptedException", "the Vert.x callback at App.java:" + line(body, "setTimer"));
    }

    @Test
    void codeTheLoopNeverReachesMayBlock() {
        Compile.Result r = app("""
                void offLoop() throws InterruptedException { Thread.sleep(5); }
                void start(Vertx vertx) { vertx.setTimer(10, id -> System.out.println(id)); }
                """);
        assertThat(r.errors()).isEmpty();
        assertThat(r.success()).isTrue();
    }

    @Test
    void aBlockingCallIsFoundThroughSourceMethodsInOtherFiles() {
        Compile.Result r = Compile.sources(Map.of(
                "app/App.java", IMPORTS + """
                        class App {
                            void start(Vertx vertx, Helper helper) { vertx.runOnContext(v -> helper.work()); }
                        }
                        """,
                "app/Helper.java", """
                        package app;
                        class Helper {
                            void work() { deeper(); }
                            private void deeper() { new java.util.concurrent.CompletableFuture<String>().join(); }
                        }
                        """));
        assertRefused(r, "Helper.java", 4, "CompletableFuture.join", "via Helper.work(…) -> Helper.deeper(…)");
    }

    @Test
    void jdbcIsRecognisedByTheReceiverTypeNotTheDeclaringType() {
        // unwrap is declared on java.sql.Wrapper; the receiver is a Connection.
        String body = """
                void start(Vertx vertx, java.sql.Connection c) {
                    vertx.runOnContext(v -> {
                        try { c.unwrap(Object.class); } catch (java.sql.SQLException e) { }
                    });
                }
                """;
        assertRefused(app(body), "App.java", line(body, "unwrap"), "Connection.unwrap", "JDBC");
    }

    @Test
    void aLambdaHandedToAnExecutorIsNotLoopCode() {
        String handedOff = """
                void start(Vertx vertx, ExecutorService pool) {
                    vertx.runOnContext(v -> pool.submit(() -> { Thread.sleep(5); return null; }));
                }
                """;
        assertThat(app(handedOff).errors()).isEmpty();
    }

    @Test
    void aLambdaRunInPlaceByLoopCodeIsLoopCode() {
        String inPlace = """
                void start(Vertx vertx, List<Future<String>> futures) {
                    vertx.runOnContext(v -> futures.forEach(f -> f.await()));
                }
                """;
        assertRefused(app(inPlace), "App.java", line(inPlace, "f.await"), "Future.await");
    }

    @Test
    void anOffEventLoopParameterMovesTheLambdaOffTheLoop() {
        String workers = """
                static void submit(@OffEventLoop Runnable task) { Thread.ofVirtual().start(task); }
                void start(Vertx vertx) {
                    vertx.runOnContext(v -> submit(() -> { try { Thread.sleep(5); } catch (InterruptedException e) { } }));
                }
                """;
        assertThat(app(workers).errors()).isEmpty();
        // The same shape without the marker is loop code: the marker is what made it pass.
        String unmarked = workers.replace("@OffEventLoop ", "");
        assertRefused(app(unmarked), "App.java", line(unmarked, "Thread.sleep"), "Thread.sleep");
    }

    @Test
    void anOnEventLoopParameterMakesTheLambdaLoopCode() {
        String helper = """
                static void onLoop(Vertx vertx, @OnEventLoop Runnable task) { vertx.runOnContext(v -> task.run()); }
                void fromAVirtualThread(Vertx vertx) {
                    onLoop(vertx, () -> new CompletableFuture<String>().join());
                }
                """;
        assertRefused(app(helper), "App.java", line(helper, ".join()"), "CompletableFuture.join");
    }

    @Test
    void executeBlockingIsNotLoopCode() {
        assertThat(app("""
                void start(Vertx vertx) {
                    vertx.runOnContext(v -> vertx.executeBlocking(() -> { Thread.sleep(5); return 1; }));
                }
                """).errors()).isEmpty();
    }

    @Test
    void aFunctionPassedToAVertxMethodIsLoopCode() {
        // Future.map takes java.util.function.Function; it runs on the loop all the same.
        String body = """
                void start(Future<String> f, CompletableFuture<String> other) {
                    f.map(s -> other.join());
                }
                """;
        assertRefused(app(body), "App.java", line(body, "other.join"), "CompletableFuture.join");
    }

    @Test
    void aMethodReferenceCallbackIsFollowed() {
        String body = """
                void start(Vertx vertx) { vertx.runOnContext(this::tick); }
                void tick(Void v) { try { Thread.sleep(1); } catch (InterruptedException e) { } }
                """;
        assertRefused(app(body), "App.java", line(body, "Thread.sleep"), "Thread.sleep", "the Vert.x callback this::tick");
    }

    @Test
    void aClassImplementingAVertxHandlerIsLoopCode() {
        String body = """
                static final class Reader implements Handler<String> {
                    @Override public void handle(String path) {
                        try { new java.io.FileInputStream(path).close(); } catch (java.io.IOException e) { }
                    }
                }
                """;
        assertRefused(app(body), "App.java", line(body, "FileInputStream"),
                "new FileInputStream", "Reader.handle(…), which overrides a Vert.x method");
    }

    @Test
    void anOnEventLoopMethodIsLoopCode() {
        String body = """
                @OnEventLoop void calledByVertxSomehow(java.io.InputStream in) {
                    try { in.read(); } catch (java.io.IOException e) { }
                }
                """;
        assertRefused(app(body), "App.java", line(body, "in.read"), "InputStream.read", "marked @OnEventLoop");
    }

    @Test
    void aByteArrayStreamIsNotBlockingIo() {
        assertThat(app("""
                void start(Vertx vertx) {
                    vertx.runOnContext(v -> new java.io.ByteArrayInputStream(new byte[1]).read());
                }
                """).errors()).isEmpty();
    }

    @Test
    void aMonitorOnTheLoopIsAllowedButWaitingOnItIsNot() {
        String brief = """
                private final Object monitor = new Object();
                void start(Vertx vertx) {
                    vertx.runOnContext(v -> {
                        synchronized (monitor) { monitor.notifyAll(); }
                    });
                }
                """;
        assertThat(app(brief).errors()).isEmpty();
        String waiting = brief.replace("monitor.notifyAll();", "try { monitor.wait(); } catch (InterruptedException e) { }");
        assertRefused(app(waiting), "App.java", line(waiting, "monitor.wait"), "Object.wait");
    }

    @Test
    void javacFindsThePluginByItsName() {
        // -Xplugin:EventLoopCheck resolves through this service registration.
        assertThat(ServiceLoader.load(Plugin.class, EventLoopCheck.class.getClassLoader()).stream()
                .map(ServiceLoader.Provider::get)
                .map(Plugin::getName))
                .contains(EventLoopCheck.NAME);
    }
}
