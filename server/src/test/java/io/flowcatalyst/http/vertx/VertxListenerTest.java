package io.flowcatalyst.http.vertx;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.http.Group;
import io.flowcatalyst.http.HttpException;
import io.flowcatalyst.http.RequestWorkers;
import io.flowcatalyst.platform.shared.database.GatedDataSource;
import io.flowcatalyst.testpg.TestPg;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.buffer.Buffer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
        try (var l = VertxListener.start(VertxListener.Options.local(0), routes -> {
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
        var options = VertxListener.Options.local(0).withDeadline(Duration.ofSeconds(1));
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
            // the connection was returned and the permits released, and the LOGIN worker
            // that ran the handler is free again (docs/spec/admission.md §11.7 part B).
            assertThat(pool.held()).isZero();
            assertThat(l.workers().busy(Group.LOGIN)).isZero();
            try (Connection c = pool.getConnection(); Statement st = c.createStatement(); var rs = st.executeQuery("select 1")) {
                assertThat(rs.next()).isTrue();
            }
        }
    }

    @Test
    void aHandlerParkedOnASemaphoreIsInterruptedByTheDeadline() throws Exception {
        var never = new Semaphore(0);
        var options = VertxListener.Options.local(0).withDeadline(Duration.ofMillis(500));
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
        try (var l = VertxListener.start(VertxListener.Options.local(0), routes -> {
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
        var l = VertxListener.start(VertxListener.Options.local(0), routes -> {
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

    /// `docs/spec/admission.md` §11.7 part B item 4: a full group queue is refused at
    /// once, `503` + `Retry-After: 1`, without ever running the handler — and a DIFFERENT
    /// group on the SAME server is untouched (the isolation claim). Mutant: one shared
    /// queue across groups — the API_READ request would also be refused or blocked behind
    /// DISPATCH's backlog.
    @Test
    void aFullDispatchQueueIsRefusedAtOnceWhileApiReadOnTheSameServerStillAnswers() throws Exception {
        var dispatchStarted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var workers = RequestWorkers.of(Map.of(Group.DISPATCH, 1, Group.API_READ, 1));
        try (var l = VertxListener.start(VertxListener.Options.local(0, workers), routes -> {
            routes.exception(Exception.class, (e, ctx) -> ctx.status(500).result("err"));
            routes.in(Group.DISPATCH).get("/d", ctx -> {
                dispatchStarted.countDown();
                release.await();
                ctx.result("d-ok");
            });
            routes.in(Group.API_READ).get("/r", ctx -> ctx.result("r-ok"));
        })) {
            HttpClient client = HttpClient.newHttpClient();
            var dReq = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + l.port() + "/d")).timeout(Duration.ofSeconds(20)).build();
            // 1 running + 8 queued (bound = 8 * size = 8) fills DISPATCH's queue exactly.
            List<CompletableFuture<HttpResponse<String>>> filling = new java.util.ArrayList<>();
            for (int i = 0; i < 9; i++) filling.add(client.sendAsync(dReq, HttpResponse.BodyHandlers.ofString()));
            assertThat(dispatchStarted.await(2, TimeUnit.SECONDS)).isTrue();
            waitUntil(() -> l.workers().queued(Group.DISPATCH) == 8, Duration.ofSeconds(2));
            assertThat(l.workers().busy(Group.DISPATCH)).isEqualTo(1);
            assertThat(l.workers().queued(Group.DISPATCH)).isEqualTo(8);

            var overflow = client.send(dReq, HttpResponse.BodyHandlers.ofString());
            assertThat(overflow.statusCode()).as("the queue was already at its bound").isEqualTo(503);
            assertThat(overflow.body()).contains("OVERLOADED");
            assertThat(overflow.headers().firstValue("Retry-After")).contains("1");

            var rReq = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + l.port() + "/r")).timeout(Duration.ofSeconds(5)).build();
            var rResp = client.send(rReq, HttpResponse.BodyHandlers.ofString());
            assertThat(rResp.statusCode()).as("a different group on the same server is untouched").isEqualTo(200);
            assertThat(rResp.body()).isEqualTo("r-ok");

            release.countDown();
            for (var f : filling) assertThat(f.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        }
    }

    /// `docs/spec/admission.md` §11.7 part B item 4: every queued request carries a
    /// deadline; one that fires while STILL QUEUED (no worker ever took it) is answered
    /// `503` and its handler never runs. Mutant: arm the deadline only inside `runChain`
    /// (as before this unit) — a request stuck behind a busy worker for longer than the
    /// deadline would then run late instead of being refused.
    @Test
    void aQueuedRequestWhoseDeadlineFiresIsAnswered503AndItsHandlerNeverRuns() throws Exception {
        var release = new CountDownLatch(1);
        var handlerRan = new java.util.concurrent.atomic.AtomicBoolean();
        var workers = RequestWorkers.of(Map.of(Group.DISPATCH, 1));
        var options = VertxListener.Options.local(0, workers).withDeadline(Duration.ofMillis(400));
        try (var l = VertxListener.start(options, routes -> {
            routes.exception(Exception.class, (e, ctx) -> ctx.status(500).result("err"));
            routes.in(Group.DISPATCH).get("/d", ctx -> {
                handlerRan.set(true);
                ctx.result("ran");
            });
        })) {
            // Occupy the DISPATCH pool's one worker DIRECTLY through RequestWorkers, bypassing
            // the HTTP/runChain deadline machinery entirely — so the only deadline in play
            // below is the HTTP request's own QUEUED-phase one, not a race against some other
            // in-flight request's running-phase deadline (both use the same duration).
            var workerBusy = new CountDownLatch(1);
            workers.submit(Group.DISPATCH, () -> {
                workerBusy.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(workerBusy.await(2, TimeUnit.SECONDS)).isTrue();

            HttpClient client = HttpClient.newHttpClient();
            var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + l.port() + "/d")).timeout(Duration.ofSeconds(5)).build();
            var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            assertThat(resp.statusCode()).as("answered from the loop while still queued, never ran").isEqualTo(503);
            assertThat(resp.body()).contains("OVERLOADED");
            assertThat(handlerRan.get()).as("the handler must never have run").isFalse();
            release.countDown();
        }
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, Duration budget) throws InterruptedException {
        long deadline = System.nanoTime() + budget.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    @Test
    void aBodyOverOneMegabyteIs413ThroughTheEnvelope() throws Exception {
        try (var l = VertxListener.start(VertxListener.Options.local(0), routes -> {
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

    // ── FIX 1: a streaming exchange's deadline is a STALL deadline ──────────

    /// A body trickled in over 9 chunks, 150 ms apart (~1.35 s total, more
    /// than 3× a 400 ms deadline) still succeeds — progress, not total time,
    /// is what the streaming deadline watches. Mutant (a) "drop the progress
    /// check" answers 503 here instead.
    @Test
    void aSlowButProgressingStreamingUploadSurvivesPastTheDeadline() throws Exception {
        var options = VertxListener.Options.local(0).withDeadline(Duration.ofMillis(400));
        try (var l = VertxListener.start(options, routes -> {
            mapHttpExceptions(routes);
            routes.putStreaming("/slow-upload", ctx -> {
                try (InputStream in = ctx.bodyStream()) {
                    byte[] all = in.readAllBytes();
                    ctx.status(200).result("received " + all.length);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        })) {
            int chunkSize = 100;
            int chunks = 9;
            byte[] body = new byte[chunkSize * chunks];
            Arrays.fill(body, (byte) 'a');
            try (var socket = new Socket("127.0.0.1", l.port())) {
                socket.setSoTimeout(10_000);
                var out = socket.getOutputStream();
                out.write(("PUT /slow-upload HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/octet-stream\r\n"
                        + "Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
                for (int i = 0; i < chunks; i++) {
                    out.write(body, i * chunkSize, chunkSize);
                    out.flush();
                    Thread.sleep(150);
                }
                RawResponse r = readRawResponse(socket.getInputStream());
                assertThat(r.status()).as("mutant: drop the progress check").isEqualTo(200);
                assertThat(r.body()).isEqualTo("received " + body.length);
            }
        }
    }

    /// A body that stops arriving halfway through still fires the deadline —
    /// eventually, and only once a full window of silence has genuinely
    /// passed, never before the stall and never (mutant (b), "re-arm
    /// unconditionally / never fire") not at all. The socket read below is
    /// bounded (`SO_TIMEOUT`), so that mutant fails this test with a bounded
    /// `SocketTimeoutException` rather than hanging the suite.
    @Test
    void aStalledStreamingUploadFiresTheDeadlineOnlyAfterTheStallNotBefore() throws Exception {
        var options = VertxListener.Options.local(0).withDeadline(Duration.ofMillis(400));
        try (var l = VertxListener.start(options, routes -> {
            mapHttpExceptions(routes);
            routes.putStreaming("/stall-upload", ctx -> {
                try (InputStream in = ctx.bodyStream()) {
                    in.readAllBytes();
                    ctx.status(200).result("done");
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        })) {
            int totalSize = 2000;
            byte[] half = new byte[totalSize / 2];
            Arrays.fill(half, (byte) 'b');
            try (var socket = new Socket("127.0.0.1", l.port())) {
                socket.setSoTimeout(5000);
                var out = socket.getOutputStream();
                out.write(("PUT /stall-upload HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/octet-stream\r\n"
                        + "Content-Length: " + totalSize + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(half);
                out.flush();
                // stall: never send the rest, never close.

                long t0 = System.nanoTime();
                RawResponse r = readRawResponse(socket.getInputStream());
                long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
                assertThat(r.status()).as("mutant: re-arm unconditionally / never fire").isEqualTo(503);
                assertThat(r.body()).contains("DEADLINE");
                assertThat(elapsedMs).as("must not fire before a full deadline of silence").isGreaterThanOrEqualTo(400L);
                assertThat(elapsedMs).as("must fire within a small multiple of the deadline, not hang").isLessThan(1200L);
            }
        }
    }

    // ── FIX 2: the response pump's own stall timer ───────────────────────────

    /// A streamed GET whose client stops reading (but never closes the
    /// socket — TCP back-pressure, not a broken pipe) must still have its
    /// server-side `InputStream` closed, within a bounded time, by the
    /// response-side stall timer. Serves tens of MB, lazily generated, so
    /// the write genuinely back-pressures rather than fitting entirely in
    /// loopback socket buffers. Mutant: drop the timer — the latch never
    /// fires and the bounded `await` below fails instead of hanging.
    @Test
    void anAbandonedStreamedDownloadClosesTheStoreStreamWithinTheDeadline() throws Exception {
        var closedLatch = new CountDownLatch(1);
        long totalBytes = 64L * 1024 * 1024;
        var options = VertxListener.Options.local(0).withDeadline(Duration.ofMillis(400));
        try (var l = VertxListener.start(options, routes -> {
            mapHttpExceptions(routes);
            routes.get("/big-download", ctx -> ctx.contentType("application/octet-stream").status(200)
                    .resultStream(new LazyInputStream(totalBytes, closedLatch), totalBytes));
        })) {
            // A tiny advertised receive window forces the server's write to genuinely
            // back-pressure quickly, regardless of how generously an OS auto-tunes
            // loopback socket buffers by default (measured: 64 MB alone was not
            // enough on this machine for the write to ever actually block).
            try (var socket = new Socket()) {
                socket.setReceiveBufferSize(2048);
                socket.connect(new java.net.InetSocketAddress("127.0.0.1", l.port()));
                socket.setSoTimeout(10_000);
                var out = socket.getOutputStream();
                out.write("GET /big-download HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: keep-alive\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
                var in = socket.getInputStream();
                String statusLine = readLine(in);
                assertThat(statusLine).contains("200");
                String line;
                while (!(line = readLine(in)).isEmpty()) {
                    // drain headers
                }
                byte[] little = new byte[4096];
                int n = in.read(little);
                assertThat(n).isGreaterThan(0);
                // Stop reading entirely from here on — the socket stays open, nobody drains it.
                assertThat(closedLatch.await(10, TimeUnit.SECONDS))
                        .as("mutant: drop the response stall timer")
                        .isTrue();
            }
        }
    }

    // ── FIX 3: an abandoned streaming upload never closes the CONNECTION ────

    /// HTTP/1.1: a `putStreaming` handler that reads only part of the body
    /// then throws still gets a COMPLETE response, and the connection is
    /// closed only AFTER that response is confirmed written — closing
    /// eagerly (or merely tagging `Connection: close` and trusting Vert.x)
    /// raced the still-unread request body and lost the response to a reset.
    @Test
    void anAbandonedHttp1StreamingUploadGetsACompleteResponseThenTheConnectionCloses() throws Exception {
        try (var l = VertxListener.start(VertxListener.Options.local(0), routes -> {
            mapHttpExceptions(routes);
            routes.putStreaming("/u413", ctx -> {
                try (InputStream in = ctx.bodyStream()) {
                    byte[] buf = new byte[1024];
                    int off = 0;
                    while (off < buf.length) {
                        int n = in.read(buf, off, buf.length - off);
                        if (n == -1) break;
                        off += n;
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                throw new HttpException(413, "too big");
            });
        })) {
            byte[] body = new byte[64 * 1024];
            Arrays.fill(body, (byte) 'x');
            try (var socket = new Socket("127.0.0.1", l.port())) {
                socket.setSoTimeout(5000);
                var out = socket.getOutputStream();
                out.write(("PUT /u413 HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/octet-stream\r\n"
                        + "Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(body);
                out.flush();

                var in = socket.getInputStream();
                String statusLine = readLine(in);
                assertThat(statusLine).as("mutant: close/tag the connection before the write is confirmed — the response is lost").contains("413");
                List<String> headers = new ArrayList<>();
                String line;
                int contentLength = -1;
                while (!(line = readLine(in)).isEmpty()) {
                    headers.add(line);
                    if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                        contentLength = Integer.parseInt(line.substring(15).trim());
                    }
                }
                if (contentLength > 0) {
                    byte[] respBody = in.readNBytes(contentLength);
                    assertThat(respBody.length).isEqualTo(contentLength);
                }
                // the server must close the connection after the response — further reads hit EOF.
                int eof = in.read();
                assertThat(eof).as("the connection must be closed after an abandoned streaming body's response").isEqualTo(-1);
            }
        }
    }

    /// h2c: the SAME connection carries a concurrent, still-open sibling GET
    /// stream while a `putStreaming` upload is abandoned (reads part of the
    /// body, then throws). The sibling GET must still answer normally.
    /// Mutant: restore `request.connection().close()` in
    /// `VertxBodyInputStream#close` — tearing down the whole h2 connection
    /// fails the sibling stream too.
    @Test
    void anAbandonedH2cStreamingUploadDoesNotFailAConcurrentSiblingStream() throws Exception {
        var getStarted = new CountDownLatch(1);
        var releaseGet = new CountDownLatch(1);
        try (var l = VertxListener.start(VertxListener.Options.local(0), routes -> {
            mapHttpExceptions(routes);
            routes.get("/ok", ctx -> {
                getStarted.countDown();
                try {
                    releaseGet.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                ctx.result("ok");
            });
            routes.putStreaming("/u413h2", ctx -> {
                try (InputStream in = ctx.bodyStream()) {
                    byte[] buf = new byte[1024];
                    int off = 0;
                    while (off < buf.length) {
                        int n = in.read(buf, off, buf.length - off);
                        if (n == -1) break;
                        off += n;
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                throw new HttpException(413, "too big");
            });
        })) {
            Vertx vertx = Vertx.vertx();
            try {
                var client = vertx.createHttpClient(new HttpClientOptions()
                        .setProtocolVersion(HttpVersion.HTTP_2)
                        .setHttp2ClearTextUpgrade(false),
                        new io.vertx.core.http.PoolOptions().setHttp2MaxSize(1));

                var getOptions = new RequestOptions().setMethod(HttpMethod.GET).setHost("127.0.0.1").setPort(l.port()).setURI("/ok");
                var getFuture = client.request(getOptions)
                        .compose(req -> req.send())
                        .toCompletionStage().toCompletableFuture();
                assertThat(getStarted.await(5, TimeUnit.SECONDS)).isTrue();

                byte[] body = new byte[1024 * 1024];
                Arrays.fill(body, (byte) 'x');
                var putOptions = new RequestOptions().setMethod(HttpMethod.PUT).setHost("127.0.0.1").setPort(l.port()).setURI("/u413h2");
                var putResp = client.request(putOptions)
                        .compose(req -> req.send(Buffer.buffer(body)))
                        .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
                assertThat(putResp.statusCode()).isEqualTo(413);

                releaseGet.countDown();
                var getResp = getFuture.get(10, TimeUnit.SECONDS);
                assertThat(getResp.statusCode()).as("mutant: restore connection().close()").isEqualTo(200);

                // The sibling completing is not enough: a graceful close (GOAWAY) lets an
                // in-flight stream finish and still costs every later request a new
                // connection. The connection itself must survive — the next request on a
                // one-connection pool leaves from the same local port.
                int portBefore = getResp.request().connection().localAddress().port();
                releaseGet.countDown();
                var after = client.request(getOptions).compose(req -> req.send())
                        .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
                assertThat(after.statusCode()).isEqualTo(200);
                assertThat(after.request().connection().localAddress().port())
                        .as("the h2 connection outlives an abandoned upload on it").isEqualTo(portBefore);
            } finally {
                vertx.close();
            }
        }
    }

    // ── FIX 4(b): overriding a response that already set a streamed result ──

    /// A handler that calls `resultStream` and then throws an UNMAPPED
    /// exception reaches `VertxExchange#override` (no registered mapper here
    /// — the point is to exercise `runChain`'s own catch-all, not a mapped
    /// one). The store `InputStream` must be closed, never merely dropped.
    @Test
    void aHandlerThatSetsAStreamedResultThenThrowsAnUnmappedExceptionStillClosesTheStream() throws Exception {
        var closed = new CountDownLatch(1);
        try (var l = VertxListener.start(VertxListener.Options.local(0), routes -> {
            routes.get("/boom", ctx -> {
                ctx.resultStream(new LazyInputStream(1000, closed), 1000);
                throw new RuntimeException("boom after resultStream");
            });
        })) {
            var r = client(HttpClient.Version.HTTP_1_1).send(get(l.port(), "/boom"), HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).isEqualTo(500);
            assertThat(closed.await(2, TimeUnit.SECONDS)).as("mutant: override() drops the streamed body without closing it").isTrue();
        }
    }

    /// The mapped sibling of the test above: a mapper's buffered `json(...)`
    /// must REPLACE the stream the handler had set — the error's own body goes
    /// out, not the store's bytes under a 409, and the stream is closed.
    @Test
    void aMappedExceptionAfterAStreamedResultSendsTheMappersBodyAndClosesTheStream() throws Exception {
        var closed = new CountDownLatch(1);
        try (var l = VertxListener.start(VertxListener.Options.local(0), routes -> {
            routes.exception(IllegalStateException.class, (e, ctx) -> ctx.status(409).json(Map.of("error", "MAPPED")));
            routes.get("/boom", ctx -> {
                ctx.resultStream(new LazyInputStream(1000, closed), 1000);
                throw new IllegalStateException("mapped, after resultStream");
            });
        })) {
            var r = client(HttpClient.Version.HTTP_1_1).send(get(l.port(), "/boom"), HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).isEqualTo(409);
            assertThat(r.body()).contains("MAPPED");
            assertThat(closed.await(2, TimeUnit.SECONDS)).as("the replaced stream is closed").isTrue();
        }
    }

    // ── shared raw-HTTP/1.1 test helpers ─────────────────────────────────────

    private record RawResponse(int status, String body) {
    }

    private static RawResponse readRawResponse(InputStream in) throws IOException {
        String statusLine = readLine(in);
        int status = Integer.parseInt(statusLine.split(" ", 3)[1]);
        int contentLength = -1;
        String line;
        while (!(line = readLine(in)).isEmpty()) {
            if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                contentLength = Integer.parseInt(line.substring(15).trim());
            }
        }
        String body = "";
        if (contentLength > 0) {
            byte[] buf = in.readNBytes(contentLength);
            body = new String(buf, StandardCharsets.UTF_8);
        }
        return new RawResponse(status, body);
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\r') continue;
            if (c == '\n') break;
            sb.append((char) c);
        }
        return sb.toString();
    }

    /// Emits `total` zero bytes then EOF, without ever materialising them,
    /// and records when it is closed — FIX 2/FIX 4(b)'s shared fixture.
    private static final class LazyInputStream extends InputStream {
        private long remaining;
        private final CountDownLatch closedLatch;

        LazyInputStream(long total, CountDownLatch closedLatch) {
            this.remaining = total;
            this.closedLatch = closedLatch;
        }

        @Override
        public int read() {
            if (remaining <= 0) return -1;
            remaining--;
            return 0;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (remaining <= 0) return -1;
            int n = (int) Math.min(len, remaining);
            Arrays.fill(b, off, off + n, (byte) 0);
            remaining -= n;
            return n;
        }

        @Override
        public void close() {
            closedLatch.countDown();
        }
    }
}
