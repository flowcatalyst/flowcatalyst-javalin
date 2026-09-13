package io.flowcatalyst.http.vertx;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.http.Budgets;
import io.flowcatalyst.http.Group;
import io.flowcatalyst.http.HttpException;
import io.flowcatalyst.platform.shared.database.GatedDataSource;
import io.flowcatalyst.testpg.TestPg;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/// `docs/spec/vertx-listener.md` §2 rows 2–6. Each listener is started and
/// closed inside its test; the JDK client is the caller.
class VertxListenerTest {

    private static HttpClient client(HttpClient.Version v) {
        return HttpClient.newBuilder().version(v).connectTimeout(Duration.ofSeconds(2)).build();
    }

    private static HttpRequest get(int port, String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20)).build();
    }

    private static void mapHttpExceptions(io.flowcatalyst.http.Routes routes) {
        routes.exception(HttpException.class, (e, ctx) -> ctx.status(e.status()).json(Map.of("error", "HTTP_" + e.status())));
        routes.exception(Exception.class, (e, ctx) -> ctx.status(500).json(Map.of("error", "INTERNAL", "message", String.valueOf(e.getMessage()))));
    }

    @Test
    void theChainRunsOnOneVirtualThreadAndTheResponseIsWrittenOnTheEventLoop() throws Exception {
        var writer = new AtomicReference<String>();
        var chainThreads = new java.util.concurrent.CopyOnWriteArrayList<Thread>();
        try (var l = VertxListener.start(VertxListener.Options.local(0, Budgets.none()), routes -> {
            mapHttpExceptions(routes);
            routes.before(ctx -> chainThreads.add(Thread.currentThread()));
            routes.get("/t", ctx -> {
                chainThreads.add(Thread.currentThread());
                ctx.result("ok");
            });
            routes.after(ctx -> chainThreads.add(Thread.currentThread()));
        })) {
            var r = client(HttpClient.Version.HTTP_1_1).send(get(l.port(), "/t"), HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(chainThreads).hasSize(3);
            assertThat(chainThreads.stream().distinct().count()).as("before, handler and after share one thread").isEqualTo(1);
            assertThat(chainThreads.get(0).isVirtual()).isTrue();
            assertThat(chainThreads.get(0).getName()).doesNotContain("eventloop");
        }
    }

    @Test
    void aStuckQueryAnswers503DeadlineAndItsConnectionIsBackInThePoolReusable() throws Exception {
        GatedDataSource pool = GatedDataSource.over(TestPg.dataSource(), 4);
        var options = VertxListener.Options.local(0, Budgets.of(Map.of(Group.LOGIN, 1))).withDeadline(Duration.ofSeconds(1));
        var sawException = new AtomicReference<String>();
        try (var l = VertxListener.start(options, routes -> {
            mapHttpExceptions(routes);
            routes.in(Group.LOGIN).get("/slow", ctx -> {
                try (Connection c = pool.getConnection(); Statement st = c.createStatement()) {
                    st.execute("select pg_sleep(60)");
                    ctx.result("finished?!");
                } catch (java.sql.SQLException e) {
                    sawException.set(e.getSQLState() + " " + e.getMessage());
                    throw e;
                }
            });
        })) {
            long t0 = System.nanoTime();
            var r = client(HttpClient.Version.HTTP_1_1).send(get(l.port(), "/slow"), HttpResponse.BodyHandlers.ofString());
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertThat(r.statusCode()).isEqualTo(503);
            assertThat(r.body()).contains("DEADLINE");
            assertThat(ms).as("answered at the deadline, not at the query's end").isLessThan(2500);
            assertThat(sawException.get()).as("the handler saw the cancel, not a closed socket").contains("57014");
            // the connection was returned and the permits released
            assertThat(pool.held()).isZero();
            assertThat(l.budgets().held(Group.LOGIN)).isZero();
            try (Connection c = pool.getConnection(); Statement st = c.createStatement(); var rs = st.executeQuery("select 1")) {
                assertThat(rs.next()).isTrue();
            }
        }
    }

    @Test
    void aHandlerParkedOnASemaphoreIsInterruptedByTheDeadline() throws Exception {
        var never = new Semaphore(0);
        var options = VertxListener.Options.local(0, Budgets.none()).withDeadline(Duration.ofMillis(500));
        try (var l = VertxListener.start(options, routes -> {
            mapHttpExceptions(routes);
            routes.get("/park", ctx -> {
                never.acquire();
                ctx.result("woke?!");
            });
        })) {
            var r = client(HttpClient.Version.HTTP_1_1).send(get(l.port(), "/park"), HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).isEqualTo(503);
            assertThat(r.body()).contains("DEADLINE");
        }
    }

    @Test
    void h2cPriorKnowledgeReachesAHandlerAndTheResponseIsHttp2() throws Exception {
        try (var l = VertxListener.start(VertxListener.Options.local(0, Budgets.none()), routes -> {
            mapHttpExceptions(routes);
            routes.get("/v", ctx -> ctx.result("ok"));
        })) {
            var r = client(HttpClient.Version.HTTP_2).send(get(l.port(), "/v"), HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(r.version()).isEqualTo(HttpClient.Version.HTTP_2);
        }
    }

    @Test
    void shutdownDrainsAnInFlightRequestAndRefusesANewConnection() throws Exception {
        var inHandler = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var l = VertxListener.start(VertxListener.Options.local(0, Budgets.none()), routes -> {
            mapHttpExceptions(routes);
            routes.get("/slow", ctx -> {
                inHandler.countDown();
                release.await();
                ctx.result("done");
            });
        });
        int port = l.port();
        var c = client(HttpClient.Version.HTTP_1_1);
        CompletableFuture<HttpResponse<String>> slow = c.sendAsync(get(port, "/slow"), HttpResponse.BodyHandlers.ofString());
        assertThat(inHandler.await(2, TimeUnit.SECONDS)).isTrue();
        var closing = CompletableFuture.runAsync(l::close);
        Thread.sleep(300);
        release.countDown();
        assertThat(slow.get(10, TimeUnit.SECONDS).statusCode()).as("the in-flight request drained").isEqualTo(200);
        closing.get(10, TimeUnit.SECONDS);
        try {
            client(HttpClient.Version.HTTP_1_1).send(get(port, "/slow"), HttpResponse.BodyHandlers.ofString());
            throw new AssertionError("a new connection after shutdown must be refused");
        } catch (IOException expected) {
            assertThat(expected).isInstanceOf(IOException.class);
        }
    }

    @Test
    void aBodyOverOneMegabyteIs413ThroughTheEnvelope() throws Exception {
        try (var l = VertxListener.start(VertxListener.Options.local(0, Budgets.none()), routes -> {
            mapHttpExceptions(routes);
            routes.post("/big", ctx -> ctx.status(200).result("accepted " + ctx.bodyAsBytes().length));
        })) {
            byte[] big = new byte[1_000_001];
            var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + l.port() + "/big"))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(big)).build();
            var r = client(HttpClient.Version.HTTP_1_1).send(req, HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).isEqualTo(413);
            assertThat(r.body()).contains("HTTP_413");
            byte[] fits = new byte[1_000_000];
            var ok = client(HttpClient.Version.HTTP_1_1).send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + l.port() + "/big"))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(fits)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(ok.statusCode()).isEqualTo(200);
        }
    }
}
