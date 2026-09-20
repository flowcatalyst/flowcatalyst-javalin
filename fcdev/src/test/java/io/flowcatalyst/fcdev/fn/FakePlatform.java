package io.flowcatalyst.fcdev.fn;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.flowcatalyst.platform.shared.json.Json;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/// A minimal local platform double for `fcdev fn` tests: mints a token at
/// `/oauth/token` and dispatches everything else to handlers registered by
/// exact path. Not the real platform — no auth, no persistence — just
/// enough wire shape for [FnClient]/every `fn` subcommand to exercise its
/// own logic (error mapping, output modes, polling, digest handling)
/// against something deterministic and in-process.
final class FakePlatform implements AutoCloseable {

    private final HttpServer server;
    private final Map<String, HttpHandler> handlers = new LinkedHashMap<>();
    final CopyOnWriteArrayList<String> requestLog = new CopyOnWriteArrayList<>();
    final java.util.concurrent.atomic.AtomicInteger tokenRequests = new java.util.concurrent.atomic.AtomicInteger();

    private FakePlatform(HttpServer server) {
        this.server = server;
    }

    static FakePlatform start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        var platform = new FakePlatform(server);
        server.createContext("/oauth/token", ex -> {
            platform.tokenRequests.incrementAndGet();
            byte[] body = ("{\"access_token\":\"fake-token\",\"expires_in\":3600}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/", ex -> {
            platform.requestLog.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath());
            HttpHandler h = platform.handlers.get(ex.getRequestMethod() + " " + ex.getRequestURI().getPath());
            if (h == null) {
                writeError(ex, 404, "NOT_FOUND", "no handler for " + ex.getRequestURI().getPath());
                return;
            }
            h.handle(ex);
        });
        server.start();
        return platform;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void on(String method, String path, HttpHandler handler) {
        handlers.put(method + " " + path, handler);
    }

    static void writeJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    static void writeNoBody(HttpExchange ex, int status) throws IOException {
        ex.sendResponseHeaders(status, -1);
        ex.close();
    }

    static void writeError(HttpExchange ex, int status, String code, String message) throws IOException {
        writeError(ex, status, code, message, Map.of());
    }

    static void writeError(HttpExchange ex, int status, String code, String message, Map<String, Object> details) throws IOException {
        writeJson(ex, status, Map.of("error", code, "message", message, "details", details));
    }

    static String bodyOf(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
