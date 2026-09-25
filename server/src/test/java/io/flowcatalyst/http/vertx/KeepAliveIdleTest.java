package io.flowcatalyst.http.vertx;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/// Owner ruling 2026-09-25 (backlog "Overnight review" item 10), over real
/// sockets with the idle time shortened to 300 ms: a keep-alive connection with
/// no request in flight is closed, and one whose request is still being handled
/// is not, however long the handler takes. (A connection that never completes a
/// first request is out of this class's reach; see its doc.)
class KeepAliveIdleTest {

    private static final Duration IDLE = Duration.ofMillis(300);

    private Vertx vertx;
    private HttpServer server;
    private int port;

    @BeforeEach
    void start() throws Exception {
        vertx = Vertx.vertx();
        server = vertx.createHttpServer(new HttpServerOptions().setHost("127.0.0.1").setPort(0));
        server.requestHandler(KeepAliveIdle.install(vertx, server, req -> {
            long delay = req.path().equals("/slow") ? 1_000 : 0;
            vertx.setTimer(Math.max(1, delay), id -> req.response().end("ok"));
        }, IDLE));
        port = server.listen().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS).actualPort();
    }

    @AfterEach
    void stop() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    /// Reads until the server closes (`-1`) or `within` passes; answers whether it closed.
    private static boolean closedWithin(Socket socket, Duration within) throws Exception {
        socket.setSoTimeout((int) within.toMillis());
        InputStream in = socket.getInputStream();
        try {
            while (in.read() != -1) {
                // drain a response, if any
            }
            return true;
        } catch (SocketTimeoutException e) {
            return false;
        }
    }

    @Test
    @DisplayName("an idle keep-alive connection is closed after the idle time")
    void anIdleKeepAliveConnectionIsClosed() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            socket.setSoTimeout(3_000);
            byte[] buf = new byte[256];
            int n = socket.getInputStream().read(buf);
            assertThat(new String(buf, 0, Math.max(n, 0), StandardCharsets.US_ASCII)).startsWith("HTTP/1.1 200");
            long idleFrom = System.nanoTime();
            assertThat(closedWithin(socket, Duration.ofSeconds(3))).as("closed once idle").isTrue();
            assertThat(Duration.ofNanos(System.nanoTime() - idleFrom))
                    .as("by the idle timer, not at once").isGreaterThanOrEqualTo(IDLE.minusMillis(50));
        }
    }

    @Test
    @DisplayName("a request handled for longer than the idle time is not cut; the connection closes once idle again")
    void aBusyConnectionIsNotCutAndClosesOnceIdle() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.getOutputStream().write("GET /slow HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            socket.setSoTimeout(3_000);
            byte[] buf = new byte[256];
            int n = socket.getInputStream().read(buf);
            String response = new String(buf, 0, Math.max(n, 0), StandardCharsets.US_ASCII);
            assertThat(response).as("the 1 s handler outlived a 300 ms idle time").startsWith("HTTP/1.1 200");
            assertThat(closedWithin(socket, Duration.ofSeconds(3))).as("then idle, then closed").isTrue();
        }
    }
}
