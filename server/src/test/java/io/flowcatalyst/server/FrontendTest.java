package io.flowcatalyst.server;

import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.json.Json;
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
/// (`docs/spec/http-seam.md`) to pin the Go `frontend.Handler` semantics (Go
/// F16, `frontend/handler_test.go`) — and that a fallback route registered
/// last never shadows an API route.
class FrontendTest {

    private static TestHttp http;

    @BeforeAll
    static void start() {
        http = TestHttp.routes(routes -> {
            routes.get("/api/things", ctx -> ctx.json(Map.of("ok", true)));
            routes.post("/api/things/{id}/activate", ctx -> ctx.result("activated"));
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

    private static String contentType(HttpResponse<?> r) {
        return r.headers().firstValue("Content-Type").orElse("").replace(" ", "");
    }

    private static boolean isShell(HttpResponse<String> r) {
        return r.body() != null && r.body().contains("<div id=\"app\">");
    }

    private static String header(HttpResponse<?> r, String name) {
        return r.headers().firstValue(name).orElse("");
    }

    private static String someAsset(String suffix) throws Exception {
        Path assets = Path.of(FrontendTest.class.getClassLoader().getResource("frontend/assets").toURI());
        try (Stream<Path> files = Files.list(assets)) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(suffix)).findFirst().orElseThrow();
        }
    }

    @Test
    void clientRoutesGetTheShellRevalidatedWithAnEtag() {
        for (String p : new String[] {"/", "/dashboard", "/clients/clt_0ABC", "/authorization/roles/orders:admin",
                "/portal/login", "/apiary", "/some/vue/route", "/index.html", "/assets/../index.html"}) {
            var r = get(p, "Accept", "application/json");
            assertThat(r.statusCode()).as(p).isEqualTo(200);
            assertThat(isShell(r)).as(p).isTrue();
            assertThat(contentType(r)).as(p).isEqualTo("text/html;charset=utf-8");
            assertThat(header(r, "Cache-Control")).as(p).isEqualTo("no-cache");
            assertThat(header(r, "ETag")).as(p).matches("\"[0-9a-f]{32}\"");
        }
        // POST /auth/login exists; GET /auth/login is a Vue history route, so the shell.
        var login = get("/auth/login");
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(isShell(login)).isTrue();
    }

    @Test
    void theShellRevalidatesTo304() {
        String etag = header(get("/"), "ETag");
        assertThat(etag).isNotEmpty();
        for (String inm : new String[] {etag, "W/" + etag, "\"other\", " + etag, "*"}) {
            var r = get("/clients/x", "If-None-Match", inm);
            assertThat(r.statusCode()).as(inm).isEqualTo(304);
            assertThat(r.body()).isEmpty();
            assertThat(header(r, "ETag")).isEqualTo(etag);
            assertThat(header(r, "Cache-Control")).isEqualTo("no-cache");
        }
        assertThat(get("/", "If-None-Match", "\"stale\"").statusCode()).isEqualTo(200);
    }

    @Test
    void missingAssetsAre404NotTheShell() {
        for (String p : new String[] {"/assets/index-OLDHASH.js", "/assets/Page-OLD.css", "/assets/", "/assets",
                "/assets/no-extension", "/favicon.ico", "/robots.txt", "/some/deep/chunk-x.js", "/manifest.webmanifest",
                "/logo.PNG"}) {
            var r = get(p);
            assertThat(r.statusCode()).as(p).isEqualTo(404);
            assertThat(isShell(r)).as(p).isFalse();
            assertThat(header(r, "Cache-Control")).as(p).isEqualTo("no-store");
            assertThat(contentType(r)).as(p).isEqualTo("text/plain;charset=utf-8");
        }
    }

    @Test
    void assetsAreServedWithMimeAndCacheHeaders() throws Exception {
        String js = someAsset(".js");
        var r = get("/assets/" + js);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(contentType(r)).isEqualTo("text/javascript;charset=utf-8");
        assertThat(header(r, "Cache-Control")).isEqualTo("public, max-age=31536000, immutable");
        assertThat(header(r, "ETag")).matches("\"[0-9a-f]{32}\"").isNotEqualTo(header(get("/"), "ETag"));
        assertThat(r.body()).isNotBlank();
        assertThat(get("/assets/" + js, "If-None-Match", header(r, "ETag")).statusCode()).isEqualTo(304);

        var svg = get("/favicon.svg");
        assertThat(svg.statusCode()).isEqualTo(200);
        assertThat(contentType(svg)).isEqualTo("image/svg+xml");
        assertThat(header(svg, "Cache-Control")).isEqualTo("no-cache");
        assertThat(header(svg, "ETag")).isNotEmpty();
    }

    @Test
    void unknownApiPathsAre404JsonForEveryMethod() throws Exception {
        for (String p : new String[] {"/api/does-not-exist", "/api", "/bff/x", "/oauth/x", "/.well-known/x", "/control/x", "/q/x",
                "/api/things/x/y"}) {
            for (String m : new String[] {"GET", "POST", "PUT", "PATCH", "DELETE"}) {
                var r = http.send(m, p, null);
                assertThat(r.statusCode()).as(m + " " + p).isEqualTo(404);
                assertThat(contentType(r)).as(m + " " + p).isEqualTo("application/json");
                assertThat(header(r, "Cache-Control")).isEqualTo("no-store");
                var body = Json.MAPPER.readTree(r.body());
                assertThat(body.get("error").asString()).isEqualTo("NOT_FOUND");
                assertThat(body.get("message").asString()).isEqualTo("No route for " + m + " " + p);
            }
        }
        // A GET to an API route registered for POST only: the 404 envelope, not the shell
        // (Go answers 405 there; this adapter answers every method mismatch 404).
        var mismatch = get("/api/things/1/activate");
        assertThat(mismatch.statusCode()).isEqualTo(404);
        assertThat(isShell(mismatch)).isFalse();
        // API routes still win.
        var api = get("/api/things");
        assertThat(api.statusCode()).isEqualTo(200);
        assertThat(api.body()).isEqualTo("{\"ok\":true}\n");
    }

    @Test
    void unsafeMethodsOnClientRoutesAre405() {
        for (String m : new String[] {"POST", "PUT", "PATCH", "DELETE"}) {
            for (String p : new String[] {"/", "/clients/123", "/favicon.svg"}) {
                var r = http.send(m, p, null);
                assertThat(r.statusCode()).as(m + " " + p).isEqualTo(405);
                assertThat(header(r, "Allow")).isEqualTo("GET, HEAD");
                assertThat(isShell(r)).isFalse();
            }
        }
        // A registered POST still wins over the fallback.
        assertThat(http.send("POST", "/auth/login", null).body()).isEqualTo("posted");
    }

    @Test
    void headIsServedWithoutABody() throws Exception {
        var r = http.send("HEAD", "/", null);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).isEmpty();
        assertThat(header(r, "ETag")).isNotEmpty();
        assertThat(http.send("HEAD", "/assets/" + someAsset(".js"), null).statusCode()).isEqualTo(200);
        assertThat(http.send("HEAD", "/assets/missing.js", null).statusCode()).isEqualTo(404);
    }

    @Test
    void everyFallbackResponseCarriesTheSecurityHeaders() {
        for (var r : new HttpResponse<?>[] {get("/"), get("/favicon.svg"), get("/assets/missing.js"), get("/api/x"),
                http.send("POST", "/clients/1", null), get("/clients/1", "If-None-Match", "*")}) {
            assertThat(header(r, "X-Content-Type-Options")).as(r.uri().toString()).isEqualTo("nosniff");
            assertThat(header(r, "Referrer-Policy")).isEqualTo("same-origin");
            assertThat(header(r, "X-Frame-Options")).isEqualTo("DENY");
            assertThat(header(r, "Content-Security-Policy")).isEqualTo("frame-ancestors 'none'; object-src 'none'; base-uri 'self'");
        }
        // No script/style/connect policy: the SPA has not been run under one.
        assertThat(Frontend.CONTENT_SECURITY_POLICY).doesNotContain("default-src").doesNotContain("script-src")
                .doesNotContain("style-src").doesNotContain("connect-src");
    }

    @Test
    void apiPrefixesMatchByWholeSegment() {
        assertThat(Frontend.isApiPath("/api")).isTrue();
        assertThat(Frontend.isApiPath("/api/x")).isTrue();
        assertThat(Frontend.isApiPath("/.well-known/openid-configuration")).isTrue();
        assertThat(Frontend.isApiPath("/apiary")).isFalse();
        assertThat(Frontend.isApiPath("/qr")).isFalse();
        assertThat(Frontend.isApiPath("/auth/login")).isFalse();
        assertThat(Frontend.isApiPath("/")).isFalse();
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
