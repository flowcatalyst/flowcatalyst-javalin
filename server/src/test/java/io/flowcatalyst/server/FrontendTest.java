package io.flowcatalyst.server;

import io.flowcatalyst.platform.shared.TestHttp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Drives the real embedded `frontend/` resources through the seam
/// (`docs/spec/http-seam.md`) to pin the Go `frontend.Handler` semantics —
/// and to verify the assumption that a fallback route registered last never
/// shadows an API route.
class FrontendTest {

    private static TestHttp http;

    @BeforeAll
    static void start() {
        http = TestHttp.routes(routes -> {
            routes.get("/api/things", ctx -> ctx.json(Map.of("ok", true)));
            routes.post("/auth/login", ctx -> ctx.result("posted"));
            Frontend.embedded().orElseThrow().register(routes);
        });
    }

    @AfterAll
    static void stop() {
        http.close();
    }

    private static HttpResponse<String> get(String path, String... headers) {
        return http.get(path, headers);
    }

    /// Jetty canonicalises well-known types (`text/html; charset=utf-8` → `text/html;charset=utf-8`);
    /// the header is semantically identical, so compare without whitespace.
    private static String contentType(HttpResponse<?> r) {
        return r.headers().firstValue("Content-Type").orElse("").replace(" ", "");
    }

    @Test
    void rootServesTheShellWithNoStore() {
        var r = get("/");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(contentType(r)).isEqualTo("text/html;charset=utf-8");
        assertThat(r.headers().firstValue("Cache-Control")).contains("no-cache, no-store, must-revalidate");
        assertThat(r.body()).contains("<div id=\"app\">");
    }

    @Test
    void unknownPathsAndTraversalFallBackToTheShellRegardlessOfAccept() {
        var r = get("/some/vue/route", "Accept", "application/json");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(contentType(r)).isEqualTo("text/html;charset=utf-8");
        var t = get("/assets/../index.html");
        assertThat(t.statusCode()).isEqualTo(200);
        assertThat(t.headers().firstValue("Cache-Control")).contains("no-cache, no-store, must-revalidate");
        // The shell by name is still the shell: never cacheable (mutant: serve it as a plain file).
        var byName = get("/index.html");
        assertThat(byName.statusCode()).isEqualTo(200);
        assertThat(byName.headers().firstValue("Cache-Control")).contains("no-cache, no-store, must-revalidate");
    }

    @Test
    void assetsAreServedWithMimeAndImmutableCache() throws Exception {
        Path assets = Path.of(FrontendTest.class.getClassLoader().getResource("frontend/assets").toURI());
        String js;
        try (Stream<Path> files = Files.list(assets)) {
            js = files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".js")).findFirst().orElseThrow();
        }
        var r = get("/assets/" + js);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(contentType(r)).isEqualTo("text/javascript;charset=utf-8");
        assertThat(r.headers().firstValue("Cache-Control")).contains("public, max-age=31536000, immutable");
        assertThat(r.body()).isNotBlank();

        var svg = get("/favicon.svg");
        assertThat(contentType(svg)).isEqualTo("image/svg+xml");
        assertThat(svg.headers().firstValue("Cache-Control")).isEmpty();
    }

    @Test
    void apiRoutesWinOverTheFallbackAndGetMethodMismatchRendersTheShell() {
        var api = get("/api/things");
        assertThat(api.statusCode()).isEqualTo(200);
        assertThat(api.body()).isEqualTo("{\"ok\":true}\n");

        // POST /auth/login exists; GET /auth/login is a Vue history route → SPA (Go's 405 shim)
        var login = get("/auth/login");
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(contentType(login)).isEqualTo("text/html;charset=utf-8");
    }

    @Test
    void mimeTable() {
        assertThat(Frontend.mimeFor("assets/app-1a2b.js")).isEqualTo("text/javascript; charset=utf-8");
        assertThat(Frontend.mimeFor("assets/app.css")).isEqualTo("text/css; charset=utf-8");
        assertThat(Frontend.mimeFor("assets/font.woff2")).isEqualTo("font/woff2");
        assertThat(Frontend.mimeFor("weird.ext.unknown")).isEqualTo("application/octet-stream");
        assertThat(Frontend.mimeFor("no-extension")).isEqualTo("application/octet-stream");
        assertThat(Frontend.mimeFor("dir.v2/file")).isEqualTo("application/octet-stream");
    }
}
