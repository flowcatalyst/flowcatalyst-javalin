package io.flowcatalyst.server;

import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/// Serves the embedded Vue SPA (`classpath:frontend/`, a copy of the Go
/// repo's `frontend/dist`). Behaviour mirrors the Go `frontend.Handler`:
///
///  1. Exact-path match under `frontend/` → serve the file with the MIME type
///     for its extension, plus `Cache-Control: public, max-age=31536000,
///     immutable` for anything under `assets/` (Vite emits hash-suffixed
///     filenames, safe for long caching).
///  2. Anything else (unknown path, no extension, `..`, a directory) →
///     `index.html` with `Cache-Control: no-cache, no-store, must-revalidate`
///     so Vue Router's history mode takes over. No `Accept` sniffing — a REST
///     client hitting an unknown path gets the shell, as in Go.
///
/// Registered LAST, as `GET /` and `GET /<path>`, so every API route wins
/// first. That also reproduces Go's method-mismatch shim: a `GET` on a path
/// that only exists for `POST` (e.g. `/auth/login`) renders the SPA.
public final class Frontend {

    private static final String ROOT = "frontend/";
    private static final String ASSETS_CACHE = "public, max-age=31536000, immutable";
    private static final String SHELL_CACHE = "no-cache, no-store, must-revalidate";

    /// MIME table approximating Go's `mime.TypeByExtension` for the file
    /// types Vite emits. Unknown extensions fall back to octet-stream.
    private static final Map<String, String> MIME = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("htm", "text/html; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("json", "application/json"),
            Map.entry("map", "application/json"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("avif", "image/avif"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("otf", "font/otf"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("xml", "text/xml; charset=utf-8"),
            Map.entry("wasm", "application/wasm"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("webmanifest", "application/manifest+json"));

    private final ClassLoader loader;

    private Frontend(ClassLoader loader) {
        this.loader = loader;
    }

    /// The embedded SPA, if `frontend/index.html` is on the classpath — a
    /// backend-only build simply has nothing to serve.
    public static Optional<Frontend> embedded() {
        ClassLoader loader = Frontend.class.getClassLoader();
        return loader.getResource(ROOT + "index.html") == null
                ? Optional.empty()
                : Optional.of(new Frontend(loader));
    }

    /// [#embedded()] as the [Server.Spa] the composition root wants: the SPA
    /// when it was built in, [Server.Spa.None] otherwise.
    public static Server.Spa embeddedOrNone() {
        return embedded().<Server.Spa>map(Server.Spa.Embedded::new).orElse(Server.Spa.none());
    }

    /// Mounts the fallback routes on `config.routes`. Call after every API
    /// route is registered (Javalin 7 registers routes inside
    /// `Javalin.create(config -> …)`).
    public void register(JavalinDefaultRoutingApi routes) {
        routes.get("/", this::serve);
        routes.get("/<path>", this::serve);
    }

    void serve(Context ctx) throws IOException {
        String path = ctx.path();
        String rel = path.startsWith("/") ? path.substring(1) : path;
        if (rel.isEmpty() || rel.contains("..")) {
            serveIndex(ctx);
            return;
        }
        URL url = loader.getResource(ROOT + rel);
        if (url == null || !isRegularFile(url)) {
            serveIndex(ctx);
            return;
        }
        ctx.contentType(mimeFor(rel));
        if (rel.startsWith("assets/")) {
            ctx.header("Cache-Control", ASSETS_CACHE);
        }
        ctx.result(url.openStream());
    }

    private void serveIndex(Context ctx) throws IOException {
        InputStream in = loader.getResourceAsStream(ROOT + "index.html");
        if (in == null) {
            ctx.status(500).contentType("text/plain; charset=utf-8").result("index.html missing from embedded frontend");
            return;
        }
        ctx.contentType("text/html; charset=utf-8");
        ctx.header("Cache-Control", SHELL_CACHE);
        ctx.result(in);
    }

    private static boolean isRegularFile(URL url) {
        try {
            URLConnection connection = url.openConnection();
            if (connection instanceof JarURLConnection jar) {
                var entry = jar.getJarEntry();
                return entry != null && !entry.isDirectory();
            }
            if ("file".equals(url.getProtocol())) {
                return Files.isRegularFile(Path.of(url.toURI()));
            }
            return true;
        } catch (IOException | URISyntaxException _) {
            return false;
        }
    }

    static String mimeFor(String path) {
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        if (dot < 0 || dot < slash) return "application/octet-stream";
        String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        return MIME.getOrDefault(ext, "application/octet-stream");
    }
}
