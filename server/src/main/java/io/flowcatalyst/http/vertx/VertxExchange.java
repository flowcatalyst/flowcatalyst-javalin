package io.flowcatalyst.http.vertx;

import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Group;
import io.flowcatalyst.http.HttpCookie;
import io.flowcatalyst.platform.shared.json.Json;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import tools.jackson.core.JacksonException;

/// [Exchange] over a Vert.x [RoutingContext] for dispatch model B
/// (`docs/spec/vertx-listener.md` §1): the request side reads the already
/// parsed request (the `BodyHandler` ran on the loop before dispatch); the
/// response side **buffers** status, headers, cookies and body while the
/// chain runs on the request's virtual thread, and [#write] flushes it in one
/// go on the event loop. Nothing here touches the socket from the handler's
/// thread.
final class VertxExchange implements Exchange {
    private static final String CONTENT_TYPE = "Content-Type";

    private final RoutingContext rc;
    private final Group group;
    private final byte[] requestBody;
    private final boolean oversized;
    /// Non-null only for a `Routes.putStreaming` exchange
    /// (`docs/spec/function-artifact-upload.md` §3); `requestBody`/`oversized`
    /// are meaningless on one of these — `body()`/`bodyAsBytes()` throw.
    private final InputStream streamedRequestBody;
    private final Map<String, Object> attributes = new HashMap<>();
    private final Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final List<String> setCookies = new ArrayList<>(2);
    private int status = 200;
    private byte[] body;
    /// Non-null only after [#resultStream] — the response-side counterpart
    /// to [#streamedRequestBody]; mutually exclusive with `body`.
    private InputStream streamedBody;
    private boolean skip;

    /// `requestBody` is what the listener buffered (up to its limit);
    /// `oversized` means the request carried more than that and the body
    /// accessors answer 413 — lazily, exactly as Javalin's `maxRequestSize`,
    /// so a handler that checks `contentLength()` first still gets its say.
    VertxExchange(RoutingContext rc, Group group, byte[] requestBody, boolean oversized) {
        this.rc = rc;
        this.group = group;
        this.requestBody = requestBody;
        this.oversized = oversized;
        this.streamedRequestBody = null;
    }

    /// A streaming exchange (`Routes.putStreaming`, spec §3): `bodyStream`
    /// is a [VertxBodyInputStream] that has not pulled a single byte off the
    /// socket yet — it does so lazily, on its own first `read()`.
    VertxExchange(RoutingContext rc, Group group, InputStream bodyStream) {
        this.rc = rc;
        this.group = group;
        this.requestBody = null;
        this.oversized = false;
        this.streamedRequestBody = Objects.requireNonNull(bodyStream, "bodyStream");
    }

    @Override
    public Group group() {
        return group;
    }

    private byte[] requestBody() {
        if (streamedRequestBody != null) {
            throw new IllegalStateException("body()/bodyAsBytes() is not available on a streaming exchange; use bodyStream()");
        }
        if (oversized) throw new io.flowcatalyst.http.HttpException(413, "Request body exceeds max size");
        return requestBody;
    }

    // ── request ──────────────────────────────────────────────────────────

    @Override
    public String pathParam(String name) {
        String v = rc.pathParam(name);
        if (v == null) throw new IllegalArgumentException("'" + name + "' is not a valid path-param for '" + rc.currentRoute().getPath() + "'.");
        return v;
    }

    @Override
    public String queryParam(String name) {
        return rc.queryParams().get(name);
    }

    @Override
    public String formParam(String name) {
        return rc.request().getFormAttribute(name);
    }

    @Override
    public String header(String name) {
        return rc.request().getHeader(name);
    }

    @Override
    public String body() {
        return new String(requestBody(), StandardCharsets.UTF_8);
    }

    @Override
    public byte[] bodyAsBytes() {
        return requestBody();
    }

    @Override
    public InputStream bodyStream() {
        if (streamedRequestBody == null) {
            throw new IllegalStateException("bodyStream() is only available on a streaming route (Routes.putStreaming)");
        }
        return streamedRequestBody;
    }

    @Override
    public <T> T bodyAsClass(Class<T> type) {
        try {
            return Json.MAPPER.readValue(body(), type);
        } catch (JacksonException e) {
            throw Json.invalidJson(e);
        }
    }

    @Override
    public String path() {
        return rc.request().path();
    }

    @Override
    public String method() {
        return rc.request().method().name();
    }

    @Override
    public String ip() {
        var addr = rc.request().remoteAddress();
        return addr == null ? "" : addr.host();
    }

    @Override
    public String scheme() {
        return rc.request().scheme();
    }

    @Override
    public long contentLength() {
        String v = rc.request().getHeader("Content-Length");
        if (v == null) return -1;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public String cookie(String name) {
        var c = rc.request().getCookie(name);
        return c == null ? null : c.getValue();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T attribute(String key) {
        return (T) attributes.get(key);
    }

    @Override
    public void attribute(String key, Object value) {
        attributes.put(key, value);
    }

    // ── response (buffered) ──────────────────────────────────────────────

    @Override
    public Exchange status(int code) {
        this.status = code;
        return this;
    }

    @Override
    public int statusCode() {
        return status;
    }

    @Override
    public Exchange header(String name, String value) {
        var l = new ArrayList<String>(1);
        l.add(value);
        headers.put(name, l);
        return this;
    }

    @Override
    public Exchange addHeader(String name, String value) {
        headers.computeIfAbsent(name, k -> new ArrayList<>(1)).add(value);
        return this;
    }

    @Override
    public Exchange contentType(String value) {
        return header(CONTENT_TYPE, value);
    }

    @Override
    public Exchange json(Object value) {
        dropStreamed();
        // Newline-terminated, as Go's json.Encoder writes it.
        body = Json.writeLine(value).getBytes(StandardCharsets.UTF_8);
        if (!headers.containsKey(CONTENT_TYPE)) contentType("application/json");
        return this;
    }

    @Override
    public Exchange html(String value) {
        dropStreamed();
        contentType("text/html; charset=utf-8");
        body = value.getBytes(StandardCharsets.UTF_8);
        return this;
    }

    @Override
    public Exchange result(String value) {
        dropStreamed();
        body = value.getBytes(StandardCharsets.UTF_8);
        return this;
    }

    @Override
    public Exchange result(byte[] value) {
        dropStreamed();
        body = value;
        return this;
    }

    @Override
    public Exchange result(InputStream value) {
        try (value) {
            body = value.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("reading the response stream", e);
        }
        dropStreamed();
        return this;
    }

    @Override
    public Exchange resultStream(InputStream value, long contentLength) {
        body = null;
        streamedBody = Objects.requireNonNull(value, "value");
        header("Content-Length", Long.toString(contentLength));
        return this;
    }

    @Override
    public InputStream resultInputStream() {
        return body == null ? null : new ByteArrayInputStream(body);
    }

    /// The stream set by [#resultStream], or `null` when the response is an
    /// ordinary buffered one. [VertxListener] alone reads this — it decides
    /// whether to call [#write] or run the streaming pump instead.
    InputStream streamedBody() {
        return streamedBody;
    }

    @Override
    public Exchange redirect(String location, int code) {
        status = code;
        header("Location", location);
        return this;
    }

    /// Encoded by hand rather than through Netty's cookie encoder, which spells
    /// `HTTPOnly` and orders attributes its own way; this is Go's `net/http`
    /// order, which the Javalin/Jetty listener also matches for the parity corpus.
    @Override
    public Exchange cookie(HttpCookie cookie) {
        var sb = new StringBuilder().append(cookie.name()).append('=').append(cookie.value());
        if (cookie.path() != null) sb.append("; Path=").append(cookie.path());
        if (cookie.maxAge() >= 0) {
            // Max-Age and Expires both, as Go's net/http and Jetty write them (the parity
            // corpus compares the attribute set).
            sb.append("; Max-Age=").append(cookie.maxAge());
            sb.append("; Expires=").append(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
                    java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(cookie.maxAge())));
        }
        if (cookie.httpOnly()) sb.append("; HttpOnly");
        if (cookie.secure()) sb.append("; Secure");
        if (cookie.sameSite() != null) {
            sb.append("; SameSite=").append(switch (cookie.sameSite()) {
                case STRICT -> "Strict";
                case LAX -> "Lax";
                case NONE -> "None";
            });
        }
        setCookies.add(sb.toString());
        return this;
    }

    @Override
    public Exchange removeCookie(String name, String path) {
        setCookies.add(name + "=; Path=" + path + "; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT");
        return this;
    }

    @Override
    public void skipRemainingHandlers() {
        skip = true;
    }

    boolean skipped() {
        return skip;
    }

    /// Replaces whatever the chain produced — the deadline's `503`, or the
    /// generic 500 for an exception thrown after the handler already called
    /// [#resultStream]. Closes a pending streamed body rather than merely
    /// dropping the reference: nobody else will ever reach `streamResponse`'s
    /// `try (streamed)` for an exchange this method touches (VertxListener
    /// only calls it when the streaming pump was never going to run), so a
    /// leaked store `InputStream` — a file handle, an S3 connection — would
    /// otherwise sit open forever.
    void override(int code, byte[] jsonBody) {
        status = code;
        headers.clear();
        setCookies.clear();
        body = jsonBody;
        if (streamedBody != null) {
            closeQuietly(streamedBody);
            streamedBody = null;
        }
        contentType("application/json");
    }

    /// A buffered body replaces a pending [#resultStream] — the case that
    /// matters is an exception mapper answering `json(...)` after the handler
    /// had already set a stream: without this the pump would send the store's
    /// bytes under the error's status, and nothing would close the stream if
    /// it did not.
    private void dropStreamed() {
        if (streamedBody == null) return;
        closeQuietly(streamedBody);
        streamedBody = null;
        headers.remove("Content-Length");
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // best-effort: the response is already being overridden regardless
        }
    }

    private void writeHead(HttpServerResponse resp, boolean bodiless) {
        resp.setStatusCode(status);
        for (var e : headers.entrySet()) {
            if (bodiless && e.getKey().equalsIgnoreCase(CONTENT_TYPE)) continue;
            resp.putHeader(e.getKey(), e.getValue());
        }
        if (!bodiless && !headers.containsKey(CONTENT_TYPE)) {
            // Javalin's default content type for a raw `result(...)`.
            resp.putHeader(CONTENT_TYPE, "text/plain");
        }
        for (String c : setCookies) resp.headers().add("Set-Cookie", c);
    }

    /// Flushes the buffered response. Called on the event loop, once; never
    /// for a streamed response (`streamedBody() != null`) — `VertxListener`
    /// runs its own pump for those, sharing only [#writeStreamedHead].
    /// Returns the write's own completion future — FIX 3: a caller that
    /// needs to close the connection afterward (an abandoned streaming
    /// upload, HTTP/1.x) must wait for THIS to complete first; closing
    /// eagerly (or tagging `Connection: close` and trusting Vert.x's own
    /// close-after-write) raced an unread request body still sitting on the
    /// same connection and lost the response to a TCP reset — reproduced
    /// empirically while pinning FIX 3's own test.
    io.vertx.core.Future<Void> write(HttpServerResponse resp) {
        if (resp.ended() || resp.closed()) return io.vertx.core.Future.succeededFuture();
        boolean bodiless = status == 204 || body == null;
        writeHead(resp, bodiless);
        return bodiless ? resp.end() : resp.end(Buffer.buffer(body));
    }

    /// The head-only counterpart of [#write], for `VertxListener`'s
    /// streaming pump (spec §4): status, headers (including the
    /// `Content-Length` [#resultStream] already set) and cookies, no body.
    void writeStreamedHead(HttpServerResponse resp) {
        writeHead(resp, false);
    }
}
