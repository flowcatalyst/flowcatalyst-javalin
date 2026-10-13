package io.flowcatalyst.server;

import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Group;
import io.flowcatalyst.http.Handler;
import io.flowcatalyst.http.Routes;
import io.flowcatalyst.platform.shared.httperror.HttpError;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/// Serves the embedded Vue SPA (`classpath:frontend/`). Behaviour mirrors the
/// Go `frontend.Handler` (Go F16, `ff9e495`). Registered LAST, for every
/// method, as `/` and `/<path>`, so every API route wins first; it therefore
/// sees every request no route matched (and a `GET` whose path an API route
/// holds for another method, e.g. `GET /auth/login`, a Vue history route):
///
///  1. Paths under the API prefixes (`/api`, `/bff`, `/oauth`,
///     `/.well-known`, `/control`, `/q`, by whole first segment): **404** with
///     the platform's JSON envelope (`NOT_FOUND`, "No route for GET /api/x"),
///     never the shell, whatever the method.
///  2. Methods other than `GET` and `HEAD`: **405**, `Allow: GET, HEAD`.
///  3. An exact file under `frontend/`: served with its MIME type and a
///     content-hash `ETag` (304 on a matching `If-None-Match`); under
///     `assets/` (Vite's hash-suffixed names) `public, max-age=31536000,
///     immutable`, any other file `no-cache`.
///  4. A missing file (anything under `assets/`, or a path whose extension is
///     a static-asset type): **404**, `no-store`. A stale hashed chunk must
///     fail as a 404, not load `index.html` as JavaScript.
///  5. Anything else (an extension-less client route): `index.html`,
///     `no-cache` with its `ETag`, so Vue Router's history mode takes over and
///     a new deploy's shell is picked up on the next load.
///
/// Every response carries `X-Content-Type-Options: nosniff`,
/// `Referrer-Policy: same-origin`, `X-Frame-Options: DENY` and a CSP limited to
/// `frame-ancestors 'none'; object-src 'none'; base-uri 'self'` (no script or
/// style policy: the SPA has not been run under one).
public final class Frontend {

    private static final String ROOT = "frontend/";
    private static final String ASSETS_CACHE = "public, max-age=31536000, immutable";
    private static final String REVALIDATE = "no-cache";

    /// The first path segments the platform's APIs own; the SPA has no client routes under them.
    private static final Set<String> API_PREFIXES = Set.of("api", "bff", "oauth", ".well-known", "control", "q");

    /// The file types a missing path is a 404 for, rather than a client route.
    /// An allow-list, not "has a dot": client routes carry identifiers that may contain dots.
    private static final Set<String> ASSET_EXTENSIONS = Set.of(
            "js", "mjs", "cjs", "css", "map", "json", "webmanifest", "txt", "xml", "wasm", "html", "htm",
            "png", "jpg", "jpeg", "gif", "svg", "ico", "webp", "avif", "bmp",
            "woff", "woff2", "ttf", "otf", "eot");

    /// Restricts framing, plugins and `<base>` only. PrimeVue injects `<style>`
    /// elements at runtime and the Swagger UI chunk uses the `Function`
    /// constructor; the SPA has not been run under a script or style policy.
    static final String CONTENT_SECURITY_POLICY = "frame-ancestors 'none'; object-src 'none'; base-uri 'self'";

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
    /// Each embedded file's ETag: the content never changes for the life of the process.
    private final Map<String, String> etags = new ConcurrentHashMap<>();

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

    /// Mounts the fallback routes. Call after every API route is registered.
    public void register(Routes routes) {
        // Classpath assets only, never the database: unbounded (Group.NO_DB).
        var noDb = routes.in(Group.NO_DB);
        Handler serve = this::serve;
        for (String path : new String[] {"/", "/<path>"}) {
            noDb.get(path, serve);
            noDb.head(path, serve);
            noDb.post(path, serve);
            noDb.put(path, serve);
            noDb.patch(path, serve);
            noDb.delete(path, serve);
        }
    }

    /// Whether `path` lies under one of the API prefixes, by whole first
    /// segment (`/apiary` is a client route; `/api` and `/api/x` are not).
    static boolean isApiPath(String path) {
        String p = path.startsWith("/") ? path.substring(1) : path;
        int slash = p.indexOf('/');
        return API_PREFIXES.contains(slash < 0 ? p : p.substring(0, slash));
    }

    void serve(Exchange ctx) throws IOException {
        setSecurityHeaders(ctx);
        String path = ctx.path();
        if (isApiPath(path)) {
            HttpError.write(ctx, 404, "NOT_FOUND", "No route for " + ctx.method() + " " + path, Map.of());
            ctx.header("Cache-Control", "no-store");
            return;
        }
        if (!ctx.method().equals("GET") && !ctx.method().equals("HEAD")) {
            ctx.header("Allow", "GET, HEAD");
            plainStatus(ctx, 405, "Method Not Allowed");
            return;
        }
        String rel = path.startsWith("/") ? path.substring(1) : path;
        // The router has already removed dot segments; a ".." left over (an
        // encoded one) never reaches the class loader.
        if (rel.isEmpty() || rel.contains("..") || rel.equals("index.html")) {
            serveFile(ctx, "index.html", REVALIDATE);
            return;
        }
        URL url = loader.getResource(ROOT + rel);
        if (url != null && isRegularFile(url)) {
            serveFile(ctx, rel, rel.startsWith("assets/") ? ASSETS_CACHE : REVALIDATE);
            return;
        }
        if (rel.startsWith("assets/") || rel.equals("assets") || ASSET_EXTENSIONS.contains(extension(rel))) {
            plainStatus(ctx, 404, "Not Found");
            return;
        }
        serveFile(ctx, "index.html", REVALIDATE);
    }

    private static void setSecurityHeaders(Exchange ctx) {
        ctx.header("X-Content-Type-Options", "nosniff");
        ctx.header("Referrer-Policy", "same-origin");
        ctx.header("X-Frame-Options", "DENY");
        ctx.header("Content-Security-Policy", CONTENT_SECURITY_POLICY);
    }

    /// Serves `name` with its MIME type, `cacheControl` and its content-hash
    /// `ETag`; a matching `If-None-Match` is a 304 with no body.
    private void serveFile(Exchange ctx, String name, String cacheControl) throws IOException {
        byte[] bytes;
        try (InputStream in = loader.getResourceAsStream(ROOT + name)) {
            if (in == null) {
                if (name.equals("index.html")) {
                    ctx.status(500).contentType("text/plain; charset=utf-8").result("index.html missing from embedded frontend");
                } else {
                    plainStatus(ctx, 404, "Not Found");
                }
                return;
            }
            bytes = in.readAllBytes();
        }
        String etag = etags.computeIfAbsent(name, n -> etagOf(bytes));
        ctx.header("Cache-Control", cacheControl);
        ctx.header("ETag", etag);
        if (noneMatch(ctx.header("If-None-Match"), etag)) {
            ctx.status(304);
            return;
        }
        ctx.contentType(mimeFor(name));
        ctx.result(bytes);
    }

    /// Go `http.ServeContent`'s `If-None-Match` test: `*`, or any listed tag
    /// equal to `etag` by weak comparison (a `W/` prefix ignored).
    static boolean noneMatch(String header, String etag) {
        if (header == null || header.isBlank()) return false;
        for (String part : header.split(",")) {
            String tag = part.trim();
            if (tag.equals("*")) return true;
            if (tag.startsWith("W/")) tag = tag.substring(2);
            if (tag.equals(etag)) return true;
        }
        return false;
    }

    /// The first 16 bytes of the content's SHA-256, hex, quoted (Go `etagFor`).
    static String etagOf(byte[] content) {
        try {
            byte[] sum = MessageDigest.getInstance("SHA-256").digest(content);
            return "\"" + HexFormat.of().formatHex(sum, 0, 16) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void plainStatus(Exchange ctx, int status, String text) {
        ctx.status(status).contentType("text/plain; charset=utf-8").header("Cache-Control", "no-store")
                .result((text + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String extension(String path) {
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        return dot < 0 || dot < slash ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
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
        String ext = extension(path);
        return ext.isEmpty() ? "application/octet-stream" : MIME.getOrDefault(ext, "application/octet-stream");
    }
}
