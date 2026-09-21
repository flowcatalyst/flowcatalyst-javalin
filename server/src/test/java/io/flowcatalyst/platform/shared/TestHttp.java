package io.flowcatalyst.platform.shared;

import io.flowcatalyst.http.RouteRegistry;
import io.flowcatalyst.http.Routes;
import io.flowcatalyst.http.vertx.VertxListener;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.openapi.Lockfile;
import io.flowcatalyst.platform.shared.openapi.SchemaValidation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.function.Consumer;

/// Test harness: a [VertxListener] on an ephemeral port, plus a tiny JDK
/// `HttpClient` wrapper.
public final class TestHttp implements AutoCloseable {

    private final VertxListener vertx;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    /// The registry every instance is built through (see [#routes(Consumer)]).
    private final RouteRegistry registry;

    /// The loopback address, used for both the bind and every request. A
    /// bare `localhost` can resolve differently for the two sides (IPv4 vs
    /// IPv6), and a port that is free on one family can be another
    /// process's on the other.
    static final String HOST = "127.0.0.1";

    /// Built once per JVM (the startup keyword-walk over all 245 lockfile
    /// operations is otherwise repeated for every `TestHttp` instance a test
    /// class creates) and shared — [SchemaValidation] holds nothing but the
    /// resolved schema table, so one instance safely serves every test.
    private static final SchemaValidation SCHEMA_VALIDATION = SchemaValidation.build(Lockfile.load(Json.MAPPER));

    /// Builds a harness wired through the `io.flowcatalyst.http` seam
    /// (`docs/spec/http-seam.md`): the platform JSON mapper, the 404/405
    /// envelope and the bodiless-response rule are the adapter's, then the
    /// resulting `Routes` is handed to `configure`.
    public static TestHttp routes(Consumer<Routes> configure) {
        return new TestHttp(configure);
    }

    private TestHttp(Consumer<Routes> configure) {
        this.vertx = VertxListener.start(VertxListener.Options.local(0), routes -> {
            routes.before(SCHEMA_VALIDATION);
            configure.accept(routes);
        });
        this.registry = vertx.registry();
    }

    /// The registry this instance was built through.
    public RouteRegistry registry() {
        return registry;
    }

    public int port() {
        return vertx.port();
    }

    public HttpResponse<String> get(String path, String... headers) {
        return send(HttpRequest.newBuilder(URI.create("http://" + HOST + ":" + port() + path)).GET(), headers);
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

    /// Raw bytes, `application/octet-stream` unless a `Content-Type` header
    /// is given explicitly — for a streaming route
    /// (`docs/spec/function-artifact-upload.md` §3) that never accepts JSON.
    public HttpResponse<byte[]> putBytes(String path, byte[] body, String... headers) {
        var b = HttpRequest.newBuilder(URI.create("http://" + HOST + ":" + port() + path))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body));
        return sendBytes(b, headers, "application/octet-stream");
    }

    /// Streams `body` with NO declared `Content-Length` (chunked transfer
    /// encoding, discovered mid-stream on the server side) — the JDK
    /// `HttpClient` only omits `Content-Length` for a publisher whose
    /// `contentLength()` is unknown, which `BodyPublishers#ofInputStream`
    /// gives for free.
    public HttpResponse<byte[]> putStreamedBytes(String path, java.util.function.Supplier<java.io.InputStream> body,
            String... headers) {
        var b = HttpRequest.newBuilder(URI.create("http://" + HOST + ":" + port() + path))
                .PUT(HttpRequest.BodyPublishers.ofInputStream(body));
        return sendBytes(b, headers, "application/octet-stream");
    }

    public HttpResponse<byte[]> getBytes(String path, String... headers) {
        var b = HttpRequest.newBuilder(URI.create("http://" + HOST + ":" + port() + path)).GET();
        return sendBytes(b, headers, null);
    }

    private HttpResponse<byte[]> sendBytes(HttpRequest.Builder b, String[] headers, String defaultContentType) {
        if (defaultContentType != null && !hasContentType(headers)) b.header("Content-Type", defaultContentType);
        if (headers.length > 0) b.headers(headers);
        try {
            return client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /// Any method; a non-null body is sent as `application/json` unless a
    /// `Content-Type` header is given explicitly.
    public HttpResponse<String> send(String method, String path, String body, String... headers) {
        var b = HttpRequest.newBuilder(URI.create("http://" + HOST + ":" + port() + path));
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

    /// Stops the server and closes the client.
    @Override
    public void close() {
        vertx.close();
        client.close();
    }
}
