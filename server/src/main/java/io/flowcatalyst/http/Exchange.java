package io.flowcatalyst.http;

import java.io.InputStream;

/// One HTTP request/response. The methods are named exactly as Javalin's
/// `Context` (`docs/spec/http-seam.md` §1) so a handler written against this
/// interface carries no framework import and the Vert.x adapter (Phase 2)
/// can implement the same shape. Mutators on the response side return
/// `Exchange` for chaining, matching Javalin's own fluent style.
public interface Exchange {

    // ── Request ──────────────────────────────────────────────────────────

    /// Decoded path parameter. `name` must be one the route actually
    /// declares — an unknown name is a programming error, not a client
    /// error, and throws (`IllegalArgumentException` on the Javalin
    /// adapter). A present parameter is never `null`.
    String pathParam(String name);

    /// First query value for `name`, or `null` when absent.
    String queryParam(String name);

    /// First form value for `name`, or `null` when absent.
    String formParam(String name);

    /// Request header, case-insensitive, or `null` when absent.
    String header(String name);

    /// The UTF-8 request body, `""` when empty.
    String body();

    /// The raw request body.
    byte[] bodyAsBytes();

    /// The request body deserialised through the platform JSON mapper. A
    /// malformed body raises the mapper's own exception.
    <T> T bodyAsClass(Class<T> type);

    /// The request path, without the query string.
    String path();

    /// Upper-case HTTP method, e.g. `"GET"`.
    String method();

    /// Remote address as the adapter reports it. No `X-Forwarded-For`
    /// parsing here — that is `RateLimit.ClientIP`'s job.
    String ip();

    /// Request scheme, e.g. `"http"`.
    String scheme();

    /// Request `Content-Length`, `-1` when absent.
    long contentLength();

    /// Request cookie value for `name`, or `null` when absent.
    String cookie(String name);

    /// Per-request attribute, or `null` when unset.
    <T> T attribute(String key);

    /// Sets a per-request attribute.
    void attribute(String key, Object value);

    // ── Response ─────────────────────────────────────────────────────────

    /// Sets the response status.
    Exchange status(int code);

    /// The response status set so far.
    int statusCode();

    /// Sets (replaces) a response header.
    Exchange header(String name, String value);

    /// Appends a response header rather than replacing it.
    Exchange addHeader(String name, String value);

    /// Sets the response `Content-Type`.
    Exchange contentType(String value);

    /// Serialises `body` through the platform JSON mapper and sets
    /// `Content-Type: application/json` unless a content type has already
    /// been set. Status is left untouched.
    Exchange json(Object body);

    /// Sets the body and `Content-Type: text/html; charset=utf-8`.
    Exchange html(String body);

    /// Sets the response body.
    Exchange result(String body);

    /// Sets the response body.
    Exchange result(byte[] body);

    /// Sets the response body to a stream; the adapter reads and closes it
    /// after the response ends.
    Exchange result(InputStream body);

    /// The body stream set by [#result(InputStream)] / [#result(String)] /
    /// [#result(byte[])], or `null` when no body has been set. Only
    /// `ResponseDefaults`'s bodiless rule needs this; the interface drops
    /// the method once that rule moves into the adapter and the class is
    /// deleted.
    InputStream resultInputStream();

    /// Sets the response to a redirect at the given status.
    Exchange redirect(String location, int status);

    /// Sets a response cookie.
    Exchange cookie(HttpCookie cookie);

    /// Expires a cookie at `name`/`path`.
    Exchange removeCookie(String name, String path);

    /// Stops the request's remaining chain: no later `before`, no route
    /// handler. `after` filters still run. For a filter that has already
    /// written the response — CORS preflight, 401, 429.
    void skipRemainingHandlers();
}
