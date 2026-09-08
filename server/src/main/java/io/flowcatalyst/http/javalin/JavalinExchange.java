package io.flowcatalyst.http.javalin;

import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.HttpCookie;
import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.HttpStatus;
import io.javalin.http.SameSite;

import java.io.InputStream;

/// [Exchange] over a Javalin [Context]: delegates every method, translating
/// only the shapes that differ (`HttpCookie` ↔ `Cookie`, `int` ↔ `long`
/// content length, `String` ↔ `HttpStatus`).
public final class JavalinExchange implements Exchange {

    private final Context ctx;

    public JavalinExchange(Context ctx) {
        this.ctx = ctx;
    }

    /// The underlying Javalin context, for adapter-internal code that needs
    /// it (e.g. installing the bodiless-response hook).
    public Context raw() {
        return ctx;
    }

    // ── Request ──────────────────────────────────────────────────────────

    @Override
    public String pathParam(String name) {
        return ctx.pathParam(name);
    }

    @Override
    public String queryParam(String name) {
        return ctx.queryParam(name);
    }

    @Override
    public String formParam(String name) {
        return ctx.formParam(name);
    }

    @Override
    public String header(String name) {
        return ctx.header(name);
    }

    @Override
    public String body() {
        return ctx.body();
    }

    @Override
    public byte[] bodyAsBytes() {
        return ctx.bodyAsBytes();
    }

    @Override
    public <T> T bodyAsClass(Class<T> type) {
        return ctx.bodyAsClass(type);
    }

    @Override
    public String path() {
        return ctx.path();
    }

    @Override
    public String method() {
        return ctx.method().name();
    }

    @Override
    public String ip() {
        return ctx.ip();
    }

    @Override
    public String scheme() {
        return ctx.scheme();
    }

    @Override
    public long contentLength() {
        return ctx.contentLength();
    }

    @Override
    public String cookie(String name) {
        return ctx.cookie(name);
    }

    @Override
    public <T> T attribute(String key) {
        return ctx.attribute(key);
    }

    @Override
    public void attribute(String key, Object value) {
        ctx.attribute(key, value);
    }

    // ── Response ─────────────────────────────────────────────────────────

    @Override
    public Exchange status(int code) {
        ctx.status(code);
        return this;
    }

    @Override
    public int statusCode() {
        return ctx.statusCode();
    }

    @Override
    public Exchange header(String name, String value) {
        ctx.header(name, value);
        return this;
    }

    @Override
    public Exchange addHeader(String name, String value) {
        ctx.addHeader(name, value);
        return this;
    }

    @Override
    public Exchange contentType(String value) {
        ctx.contentType(value);
        return this;
    }

    @Override
    public Exchange json(Object body) {
        ctx.json(body);
        return this;
    }

    @Override
    public Exchange html(String body) {
        ctx.html(body);
        return this;
    }

    @Override
    public Exchange result(String body) {
        ctx.result(body);
        return this;
    }

    @Override
    public Exchange result(byte[] body) {
        ctx.result(body);
        return this;
    }

    @Override
    public Exchange result(InputStream body) {
        ctx.result(body);
        return this;
    }

    @Override
    public InputStream resultInputStream() {
        return ctx.resultInputStream();
    }

    @Override
    public Exchange redirect(String location, int status) {
        ctx.redirect(location, HttpStatus.forStatus(status));
        return this;
    }

    @Override
    public Exchange cookie(HttpCookie cookie) {
        ctx.cookie(new Cookie(
                cookie.name(),
                cookie.value(),
                cookie.path(),
                cookie.maxAge(),
                cookie.secure(),
                cookie.httpOnly(),
                null,
                sameSite(cookie.sameSite())));
        return this;
    }

    private static SameSite sameSite(HttpCookie.SameSite s) {
        return switch (s) {
            case STRICT -> SameSite.STRICT;
            case LAX -> SameSite.LAX;
            case NONE -> SameSite.NONE;
        };
    }

    @Override
    public Exchange removeCookie(String name, String path) {
        ctx.removeCookie(name, path);
        return this;
    }

    @Override
    public void skipRemainingHandlers() {
        // Not delegated to ctx.skipRemainingHandlers() — see
        // SkipRemainingHandlersSignal for why.
        throw new SkipRemainingHandlersSignal();
    }
}
