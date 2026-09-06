package io.flowcatalyst.http.vertx;

import io.flowcatalyst.http.Exchange;
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
    private final byte[] requestBody;
    private final boolean oversized;
    private final Map<String, Object> attributes = new HashMap<>();
    private final Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final List<String> setCookies = new ArrayList<>(2);
    private int status = 200;
    private byte[] body;
    private boolean skip;

    /// `requestBody` is what the listener buffered (up to its limit);
    /// `oversized` means the request carried more than that and the body
    /// accessors answer 413 — lazily, exactly as Javalin's `maxRequestSize`,
    /// so a handler that checks `contentLength()` first still gets its say.
    VertxExchange(RoutingContext rc, byte[] requestBody, boolean oversized) {
        this.rc = rc;
        this.requestBody = requestBody;
        this.oversized = oversized;
    }

    private byte[] requestBody() {
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
        // Newline-terminated, as `JavalinJsonMapper` (and Go's encoder) write it.
        body = Json.writeLine(value).getBytes(StandardCharsets.UTF_8);
        if (!headers.containsKey(CONTENT_TYPE)) contentType("application/json");
        return this;
    }

    @Override
    public Exchange html(String value) {
        contentType("text/html; charset=utf-8");
        body = value.getBytes(StandardCharsets.UTF_8);
        return this;
    }

    @Override
    public Exchange result(String value) {
        body = value.getBytes(StandardCharsets.UTF_8);
        return this;
    }

    @Override
    public Exchange result(byte[] value) {
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
        return this;
    }

    @Override
    public InputStream resultInputStream() {
        return body == null ? null : new ByteArrayInputStream(body);
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

    /// Replaces whatever the chain produced — the deadline's `503`.
    void override(int code, byte[] jsonBody) {
        status = code;
        headers.clear();
        setCookies.clear();
        body = jsonBody;
        contentType("application/json");
    }

    /// Flushes the buffered response. Called on the event loop, once.
    void write(HttpServerResponse resp) {
        if (resp.ended() || resp.closed()) return;
        resp.setStatusCode(status);
        boolean bodiless = status == 204 || body == null;
        for (var e : headers.entrySet()) {
            if (bodiless && e.getKey().equalsIgnoreCase(CONTENT_TYPE)) continue;
            resp.putHeader(e.getKey(), e.getValue());
        }
        if (!bodiless && !headers.containsKey(CONTENT_TYPE)) {
            // Javalin's default content type for a raw `result(...)`.
            resp.putHeader(CONTENT_TYPE, "text/plain");
        }
        for (String c : setCookies) resp.headers().add("Set-Cookie", c);
        if (bodiless) {
            resp.end();
        } else {
            resp.end(Buffer.buffer(body));
        }
    }
}
