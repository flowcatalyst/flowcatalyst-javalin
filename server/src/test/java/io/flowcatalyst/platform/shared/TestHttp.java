package io.flowcatalyst.platform.shared;

import io.flowcatalyst.platform.shared.json.JavalinJsonMapper;
import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
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

    /// A route the harness registers for itself, to prove the connector is
    /// serving before any test issues its first real request. Named to be
    /// unmistakable and to collide with nothing a test would mount.
    private static final String READY_PATH = "/__testhttp_ready";

    /// The loopback address, used for both the bind and every request. A
    /// bare `localhost` can resolve differently for the two sides (IPv4 vs
    /// IPv6), and a port that is free on one family can be another
    /// process's on the other — on 2026-09-05 a whole test class talked to
    /// a local SOCKS proxy that way and read its HTML as 404s.
    static final String HOST = "127.0.0.1";

    /// Each instance answers its readiness probe with its own nonce, so the
    /// probe proves it reached THIS server and not whatever else holds the
    /// port; a foreign answer rebinds on a fresh port.
    private final String nonce = java.util.UUID.randomUUID().toString();

    public TestHttp(Consumer<JavalinConfig> configure) {
        Javalin started = null;
        AssertionError last = null;
        for (int attempt = 0; attempt < 3 && started == null; attempt++) {
            Javalin candidate = Javalin.create(cfg -> {
                cfg.startup.showJavalinBanner = false;
                cfg.jsonMapper(new JavalinJsonMapper());
                io.flowcatalyst.platform.shared.http.ResponseDefaults.register(cfg);
                // Registered BEFORE the caller's routes so a catch-all of theirs
                // still wins for every other path.
                cfg.routes.get(READY_PATH, ctx -> ctx.result(nonce));
                configure.accept(cfg);
            }).start(HOST, 0);
            try {
                awaitReady(candidate.port(), READY_PATH);
                started = candidate;
            } catch (ForeignServer e) {
                last = e;
                candidate.stop();
            }
        }
        if (started == null) {
            throw last;
        }
        this.app = started;
    }

    /// The readiness probe answered, but not with this instance's nonce:
    /// another process owns the port on the address the client used.
    static final class ForeignServer extends AssertionError {
        ForeignServer(int port, String body) {
            super("port " + port + " answered the readiness probe with a foreign body: "
                    + (body.length() > 80 ? body.substring(0, 80) + "…" : body));
        }
    }

    public int port() {
        return app.port();
    }

    /// Blocks until a request to `probePath` actually comes back, then returns.
    ///
    /// Called from the constructor against [#READY_PATH], so **every instance
    /// is ready before it is handed to a test** and no test needs to know this
    /// race exists. Public because a caller that rebinds or otherwise wants to
    /// re-check can.
    ///
    /// A freshly bound Jetty connector occasionally drops the very first
    /// connection on a JDK `HttpClient` — "header parser received no bytes",
    /// "EOF reached while reading" — a harness/OS race with nothing to do with
    /// routing. Without this, the drop surfaces as an ERROR in whichever test
    /// happens to go first, which reads like a real failure of that test. It
    /// did exactly that to `DashboardHandlerTest` on 2026-08-27, in the one
    /// test that builds its own instance inside the test method.
    ///
    /// **One attempt is not enough.** A connector that rejects the first
    /// request may reject the second, so absorbing exactly one failure just
    /// moves the problem to the next call — where it arrives as a body that
    /// parses to something missing the field under test, i.e. a
    /// `NullPointerException` that looks like an ordering bug and is not one.
    /// This retries until it genuinely answers, so a test past this line is
    /// talking to a server that works.
    ///
    /// `probePath` must be a route this instance actually registers; the
    /// response is discarded, so any status will do.
    ///
    /// Each probe carries its own short timeout. Without one, a first
    /// connection that Jetty accepts but does not answer (the race this
    /// method exists to absorb) hangs the single probe past the whole budget,
    /// and the failure reads "never became ready" after exactly one attempt.
    /// Several bounded probes inside a generous budget is what makes the
    /// retry loop actually retry.
    public void awaitReady(String probePath) {
        awaitReady(port(), probePath);
    }

    private void awaitReady(int port, String probePath) {
        long deadline = System.nanoTime() + READY_BUDGET.toNanos();
        RuntimeException last = null;
        while (System.nanoTime() < deadline) {
            try {
                var r = client.send(HttpRequest.newBuilder(URI.create("http://" + HOST + ":" + port + probePath))
                        .GET().timeout(READY_PROBE_TIMEOUT).build(), HttpResponse.BodyHandlers.ofString());
                if (probePath.equals(READY_PATH) && !nonce.equals(r.body())) {
                    throw new ForeignServer(port, r.body());
                }
                return;
            } catch (IOException e) {
                last = new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for the test server", e);
            }
        }
        throw new AssertionError("test server never became ready at " + probePath, last);
    }

    /// Per-probe bound (see [#awaitReady]); the overall budget is generous
    /// because a full suite run is exactly when the JVM is busiest.
    private static final Duration READY_PROBE_TIMEOUT = Duration.ofMillis(500);
    private static final Duration READY_BUDGET = Duration.ofSeconds(20);

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
