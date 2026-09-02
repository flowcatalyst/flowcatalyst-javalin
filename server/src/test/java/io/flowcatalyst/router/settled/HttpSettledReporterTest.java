package io.flowcatalyst.router.settled;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/// [HttpSettledReporter] against a loopback server — the wire shape
/// (`docs/spec/dispatch-seam.md` §6), the chunking, and the "never blocks a
/// caller" property that is the whole reason [SettledReporter#report] is
/// allowed to sit on a pool worker's return path
/// (router-specification.md §5.4).
class HttpSettledReporterTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger requestCount = new AtomicInteger();
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();
    private final Map<Integer, Integer> statusByRequest = new ConcurrentHashMap<>();
    private volatile int responseStatus = 200;
    private volatile Duration handlerDelay = Duration.ZERO;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/dispatch/settled", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        var n = requestCount.incrementAndGet();
        if (!handlerDelay.isZero()) {
            try {
                Thread.sleep(handlerDelay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requestBodies.add(body);
        statusByRequest.put(n, responseStatus);
        exchange.sendResponseHeaders(responseStatus, -1);
        exchange.close();
    }

    @Test
    @DisplayName("the request body is byte-exact: {\"reason\":..., \"jobs\":[{\"id\":...,\"token\":...}]}")
    void bodyShapeIsByteExact() {
        var reporter = new HttpSettledReporter(baseUrl);

        reporter.report(new SettledReport("POOL-A", "g", "head failed under BLOCK_ON_ERROR",
                List.of(new SettledJob("job-1", "tok-1"), new SettledJob("job-2", "tok-2"))));

        await(() -> !requestBodies.isEmpty());
        assertThat(requestBodies.getFirst()).isEqualTo(
                "{\"reason\":\"head failed under BLOCK_ON_ERROR\","
                        + "\"jobs\":[{\"id\":\"job-1\",\"token\":\"tok-1\"},{\"id\":\"job-2\",\"token\":\"tok-2\"}]}");
    }

    @Test
    @DisplayName("a batch over the chunk size is sent as two sequential requests, 1000 then the remainder")
    void chunksAtOneThousand() {
        var reporter = new HttpSettledReporter(baseUrl);
        var jobs = IntStream.range(0, 1001)
                .mapToObj(i -> new SettledJob("job-" + i, "tok-" + i))
                .toList();

        reporter.report(new SettledReport("POOL-A", "g", "head failed under BLOCK_ON_ERROR", jobs));

        await(() -> requestCount.get() == 2);
        // No third request is coming: give any stray extra a moment to show.
        sleepBriefly();
        assertThat(requestCount.get()).isEqualTo(2);
        assertThat(requestBodies).hasSize(2);
        assertThat(countJobs(requestBodies.get(0))).isEqualTo(1000);
        assertThat(countJobs(requestBodies.get(1))).isEqualTo(1);
    }

    @Test
    @DisplayName("report() returns at once even when the server hangs past the timeout, and the timeout is logged")
    void neverBlocksTheCaller() {
        // The handler sleeps far longer than the client-side timeout below,
        // so the ONLY way this call can return quickly is by never waiting
        // on the network at all — a synchronous implementation would be
        // pinned to (at minimum) the 800ms client timeout before it could
        // return, which the 150ms assertion below is deliberately tighter
        // than. (A weaker bound, such as one merely below the handler's own
        // delay, would pass even for a synchronous call that happened to be
        // bounded by a short client timeout — this is why the assertion
        // window has to sit strictly inside the client timeout, not just
        // inside the handler delay.)
        handlerDelay = Duration.ofSeconds(3);
        var reporter = new HttpSettledReporter(baseUrl, Duration.ofMillis(800));
        var log = (Logger) LoggerFactory.getLogger(HttpSettledReporter.class);
        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        log.addAppender(captured);
        try {
            var startedAt = System.nanoTime();
            reporter.report(new SettledReport("POOL-A", "g", "head failed under BLOCK_ON_ERROR",
                    List.of(new SettledJob("job-1", "tok-1"))));
            var took = Duration.ofNanos(System.nanoTime() - startedAt);

            assertThat(took)
                    .as("report() must not wait on the network — it hands the call to its own virtual thread")
                    .isLessThan(Duration.ofMillis(150));

            // The async attempt still has to actually run and time out —
            // proving this is not just "the call returned early", but that
            // the failure was truly observed and logged, not lost.
            await(() -> !captured.list.isEmpty(), 5_000);
            assertThat(captured.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("settled-message hook failed");
            });
        } finally {
            log.detachAppender(captured);
        }
    }

    @Test
    @DisplayName("a non-200 from the server is logged, not thrown")
    void non200IsLoggedNotThrown() {
        responseStatus = 500;
        var reporter = new HttpSettledReporter(baseUrl, Duration.ofSeconds(2));
        var log = (Logger) LoggerFactory.getLogger(HttpSettledReporter.class);
        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        log.addAppender(captured);
        try {
            reporter.report(new SettledReport("POOL-A", "g", "head failed under BLOCK_ON_ERROR",
                    List.of(new SettledJob("job-1", "tok-1"))));

            await(() -> !captured.list.isEmpty());
            assertThat(captured.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .contains("settled-message hook failed")
                        .contains("500");
            });
        } finally {
            log.detachAppender(captured);
        }
    }

    @Test
    @DisplayName("an empty job list is never posted")
    void emptyJobListIsNotPosted() {
        var reporter = new HttpSettledReporter(baseUrl);

        reporter.report(new SettledReport("POOL-A", "g", "head failed under BLOCK_ON_ERROR", List.of()));

        sleepBriefly();
        assertThat(requestCount.get()).isZero();
    }

    /// Cheap enough for a golden-body test elsewhere in this class: counts
    /// `"id":` occurrences, which is exactly the number of job objects in
    /// the array given the pinned shape.
    private static int countJobs(String body) {
        return body.split("\"id\":", -1).length - 1;
    }

    private static final int AWAIT_MILLIS = 5_000;

    private static void await(BooleanSupplier condition) {
        await(condition, AWAIT_MILLIS);
    }

    private static void await(BooleanSupplier condition, long timeoutMillis) {
        long deadline = System.nanoTime() + Duration.ofMillis(timeoutMillis).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleepBriefly();
        }
        throw new AssertionError("condition not met within " + timeoutMillis + "ms");
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(Duration.ofMillis(20));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
