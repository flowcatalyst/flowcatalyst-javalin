package io.flowcatalyst.platform.shared;

import io.flowcatalyst.platform.shared.json.JavalinJsonMapper;
import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.function.Consumer;

/// Test harness: a Javalin app on an ephemeral port with the platform JSON
/// mapper, plus a tiny JDK `HttpClient` wrapper.
public final class TestHttp implements AutoCloseable {

    private final Javalin app;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    public TestHttp(Consumer<JavalinConfig> configure) {
        this.app = Javalin.create(cfg -> {
            cfg.startup.showJavalinBanner = false;
            cfg.jsonMapper(new JavalinJsonMapper());
            configure.accept(cfg);
        }).start(0);
    }

    public int port() {
        return app.port();
    }

    public HttpResponse<String> get(String path, String... headers) {
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port() + path)).GET(), headers);
    }

    public HttpResponse<String> post(String path, String body, String... headers) {
        return send("POST", path, body, headers);
    }

    public HttpResponse<String> put(String path, String body, String... headers) {
        return send("PUT", path, body, headers);
    }

    public HttpResponse<String> delete(String path, String... headers) {
        return send("DELETE", path, null, headers);
    }

    /// Any method; a non-null body is sent as `application/json` unless a
    /// `Content-Type` header is given explicitly.
    public HttpResponse<String> send(String method, String path, String body, String... headers) {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port() + path));
        if (body == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.method(method, HttpRequest.BodyPublishers.ofString(body));
            if (!hasContentType(headers)) b.header("Content-Type", "application/json");
        }
        return send(b, headers);
    }

    private static boolean hasContentType(String... headers) {
        for (int i = 0; i + 1 < headers.length; i += 2) {
            if ("Content-Type".equalsIgnoreCase(headers[i])) return true;
        }
        return false;
    }

    private HttpResponse<String> send(HttpRequest.Builder b, String... headers) {
        if (headers.length > 0) b.headers(headers);
        try {
            return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /// Stops the server **and closes the client**.
    ///
    /// The client used to be left open, which leaked its selector and
    /// executor: 1200 create/close cycles ended with 1401 live threads, and
    /// closing it brings that to 1203.
    ///
    /// The residual ~1 thread per instance is **not** ours and is not fixable
    /// from here — it is Javalin's own non-daemon helper
    /// (`io.javalin.jetty.JettyServer` line 41, parked in a sleep loop),
    /// created per instance and not reclaimed by `app.stop()`. Recorded so the
    /// next person to measure a rising thread count does not go looking for it
    /// in this class.
    @Override
    public void close() {
        app.stop();
        client.close();
    }
}
