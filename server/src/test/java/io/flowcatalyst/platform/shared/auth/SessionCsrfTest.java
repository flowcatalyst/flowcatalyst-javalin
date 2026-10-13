package io.flowcatalyst.platform.shared.auth;

import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The cookie-session guard's rule matrix (Go `middleware/crossorigin_test.go`),
/// through the real listener: a stand-in authenticator binds a context whose
/// credential the request names (`X-Test-Credential`), so the guard sees
/// exactly what the real [Authenticator] would hand it. The end-to-end half,
/// a real session cookie through the composed platform, is
/// `io.flowcatalyst.server.SessionCsrfIntegrationTest`.
class SessionCsrfTest {

    static final String TRUSTED = "https://platform.example.com";
    private static TestHttp http;
    private static final HttpClient CLIENT = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @BeforeAll
    static void start() {
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before(ctx -> {
                String credential = ctx.header("X-Test-Credential");
                if (credential == null) return;
                var ac = new AuthContext("prn_test", Scope.ANCHOR, "a@example.com", List.of(), List.of(), List.of(), true,
                        List.of("platform:*:*:*")).withCredential(AuthContext.Credential.valueOf(credential));
                Auth.bind(ctx, ac);
            });
            routes.before(new SessionCsrf(List.of(TRUSTED)));
            for (String path : new String[] {"/api/x", "/bff/x", "/oauth/thing", "/.well-known/x", "/auth/me/thing"}) {
                routes.get(path, ctx -> ctx.result("ok"));
                routes.post(path, ctx -> ctx.result("ok"));
                routes.put(path, ctx -> ctx.result("ok"));
                routes.patch(path, ctx -> ctx.result("ok"));
                routes.delete(path, ctx -> ctx.result("ok"));
            }
            routes.putStreaming("/api/functions/{address}/artifacts/{digest}", ctx -> {
                ctx.bodyStream().readAllBytes();
                ctx.result("ok");
            });
        });
    }

    @AfterAll
    static void stop() {
        http.close();
    }

    private static final String JSON = "application/json";
    private static final String HOST_ORIGIN_PLACEHOLDER = "<self>";

    /// One request: `body` null = no body; a `contentType` of null sends none.
    private static HttpResponse<String> send(String method, String path, String credential, String secFetchSite,
                                             String origin, String body, String contentType) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + http.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (credential != null) b.header("X-Test-Credential", credential);
        if (secFetchSite != null) b.header("Sec-Fetch-Site", secFetchSite);
        if (origin != null) b.header("Origin", HOST_ORIGIN_PLACEHOLDER.equals(origin) ? "http://127.0.0.1:" + http.port() : origin);
        if (contentType != null) b.header("Content-Type", contentType);
        return CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertRefused(HttpResponse<String> r, int status, String code) throws Exception {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        assertThat(Json.MAPPER.readTree(r.body()).get("error").asString()).isEqualTo(code);
        assertThat(r.headers().firstValue("Cache-Control")).contains("no-store");
    }

    private static void assertPassed(HttpResponse<String> r) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.body()).isEqualTo("ok");
    }

    @Test
    void provenanceOfACookieAuthenticatedUnsafeRequest() throws Exception {
        String s = "SESSION_COOKIE";
        // Sec-Fetch-Site decides when present.
        assertPassed(send("POST", "/api/x", s, "same-origin", null, "{}", JSON));
        assertPassed(send("POST", "/api/x", s, "none", null, "{}", JSON));
        assertRefused(send("POST", "/api/x", s, "same-site", "https://evil.example.com", "{}", JSON), 403, "CROSS_ORIGIN_REQUEST");
        assertRefused(send("POST", "/api/x", s, "cross-site", "https://attacker.test", "{}", JSON), 403, "CROSS_ORIGIN_REQUEST");
        // A same-site value with the request's own Origin is still refused (Sec-Fetch-Site wins) ...
        assertRefused(send("POST", "/api/x", s, "same-site", HOST_ORIGIN_PLACEHOLDER, "{}", JSON), 403, "CROSS_ORIGIN_REQUEST");
        // ... unless the Origin is a trusted origin.
        assertPassed(send("POST", "/api/x", s, "same-site", TRUSTED, "{}", JSON));
        assertRefused(send("POST", "/api/x", s, "same-site", TRUSTED + ":8443", "{}", JSON), 403, "CROSS_ORIGIN_REQUEST");

        // Without Sec-Fetch-Site, the Origin must name the Host or a trusted origin.
        assertPassed(send("POST", "/api/x", s, null, HOST_ORIGIN_PLACEHOLDER, "{}", JSON));
        assertPassed(send("POST", "/api/x", s, null, TRUSTED, "{}", JSON));
        assertRefused(send("POST", "/api/x", s, null, "https://evil.example.com", "{}", JSON), 403, "CROSS_ORIGIN_REQUEST");
        assertRefused(send("POST", "/api/x", s, null, "http://127.0.0.1:1", "{}", JSON), 403, "CROSS_ORIGIN_REQUEST");
        assertRefused(send("POST", "/api/x", s, null, "null", "{}", JSON), 403, "CROSS_ORIGIN_REQUEST");
        // Neither header: refused (stricter than CrossOriginProtection).
        assertRefused(send("POST", "/api/x", s, null, null, "{}", JSON), 403, "CROSS_ORIGIN_REQUEST");
        // A cross-origin request with a non-JSON body is a 403, not a 415 (provenance first).
        assertRefused(send("POST", "/api/x", s, "cross-site", "https://attacker.test", "{}", "text/plain"), 403, "CROSS_ORIGIN_REQUEST");

        // Every unsafe method, with and without a body.
        for (String m : new String[] {"PUT", "PATCH", "DELETE", "POST"}) {
            assertRefused(send(m, "/api/x", s, "same-site", "https://evil.example.com", null, null), 403, "CROSS_ORIGIN_REQUEST");
            assertRefused(send(m, "/bff/x", s, null, "https://evil.example.com", "{}", JSON), 403, "CROSS_ORIGIN_REQUEST");
            assertPassed(send(m, "/bff/x", s, "same-origin", null, null, null));
        }
    }

    @Test
    void jsonOnlyBodiesForACookieAuthenticatedUnsafeRequest() throws Exception {
        String s = "SESSION_COOKIE";
        for (String ct : new String[] {"text/plain", "text/plain;charset=UTF-8", "application/x-www-form-urlencoded",
                "multipart/form-data; boundary=x", "application/jsonx", "application/json; charset", "application/json; a=1; a=2",
                "application/json; charset=\"utf-8", "application", "json", "/json", "application/json, text/plain"}) {
            assertRefused(send("POST", "/api/x", s, "same-origin", null, "{}", ct), 415, "UNSUPPORTED_MEDIA_TYPE");
        }
        // A body with no Content-Type at all.
        assertRefused(send("PUT", "/bff/x", s, "same-origin", null, "{}", null), 415, "UNSUPPORTED_MEDIA_TYPE");

        for (String ct : new String[] {"application/json", "Application/JSON", "application/json; charset=utf-8",
                "application/json;charset=\"utf-8\"", "application/json;", "application/merge-patch+json",
                "application/vnd.api+json; charset=utf-8"}) {
            assertPassed(send("POST", "/api/x", s, "same-origin", null, "{}", ct));
        }
        // A body-less request needs no Content-Type, and may declare any.
        assertPassed(send("POST", "/api/x", s, "same-origin", null, null, null));
        assertPassed(send("POST", "/api/x", s, "same-origin", null, null, "text/plain"));
        assertPassed(send("DELETE", "/api/x", s, "none", null, null, "application/x-www-form-urlencoded"));
    }

    /// Java divergence: the SPA's Publish drawer uploads a function artifact
    /// with the cookie as `application/octet-stream`; only that route, PUT,
    /// and those two raw types escape the JSON rule, never provenance.
    @Test
    void theArtifactUploadRouteTakesRawBytesFromTheSpa() throws Exception {
        String s = "SESSION_COOKIE";
        String upload = "/api/functions/acme:fn/artifacts/sha256-abc";
        assertPassed(send("PUT", upload, s, "same-origin", null, "\0asm", "application/octet-stream"));
        assertPassed(send("PUT", upload, s, null, HOST_ORIGIN_PLACEHOLDER, "\0asm", "application/wasm"));
        assertRefused(send("PUT", upload, s, "same-origin", null, "\0asm", "text/plain"), 415, "UNSUPPORTED_MEDIA_TYPE");
        assertRefused(send("PUT", upload, s, "same-origin", null, "\0asm", null), 415, "UNSUPPORTED_MEDIA_TYPE");
        assertRefused(send("PUT", upload, s, "same-site", "https://evil.example.com", "\0asm", "application/octet-stream"),
                403, "CROSS_ORIGIN_REQUEST");
        assertRefused(send("PUT", "/api/x", s, "same-origin", null, "\0asm", "application/octet-stream"), 415, "UNSUPPORTED_MEDIA_TYPE");
        assertRefused(send("POST", "/api/x", s, "same-origin", null, "\0asm", "application/wasm"), 415, "UNSUPPORTED_MEDIA_TYPE");
        assertThat(SessionCsrf.isArtifactUpload("POST", upload, "application/octet-stream")).isFalse();
        assertThat(SessionCsrf.isArtifactUpload("PUT", upload + "/x", "application/octet-stream")).isFalse();
    }

    @Test
    void aChunkedBodyOfUnknownLengthIsABody() throws Exception {
        var body = "{}".getBytes(StandardCharsets.UTF_8);
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + http.port() + "/api/x"))
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)))
                .header("X-Test-Credential", "SESSION_COOKIE").header("Sec-Fetch-Site", "same-origin")
                .header("Content-Type", "text/plain");
        assertRefused(CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString()), 415, "UNSUPPORTED_MEDIA_TYPE");
        b.setHeader("Content-Type", "application/json");
        assertPassed(CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString()));
    }

    @Test
    void notAppliedOutsideCookieAuthenticatedUnsafeRequests() throws Exception {
        String foreign = "https://evil.example.com";
        // Safe methods, cookie or not.
        assertPassed(send("GET", "/api/x", "SESSION_COOKIE", "cross-site", foreign, null, null));
        assertThat(send("HEAD", "/api/x", "SESSION_COOKIE", "cross-site", foreign, null, null).statusCode()).isNotEqualTo(403);
        // Bearer, test headers, in-process and no context at all: never refused, any body.
        for (String credential : new String[] {"BEARER_TOKEN", "TEST_HEADERS", "IN_PROCESS", null}) {
            assertPassed(send("POST", "/api/x", credential, "cross-site", foreign, "a=b", "application/x-www-form-urlencoded"));
            assertPassed(send("DELETE", "/api/x", credential, null, null, null, null));
        }
        // The OAuth/OIDC surface, even with a cookie session bound.
        assertPassed(send("POST", "/oauth/thing", "SESSION_COOKIE", "cross-site", foreign, "grant_type=x",
                "application/x-www-form-urlencoded"));
        assertPassed(send("POST", "/.well-known/x", "SESSION_COOKIE", null, null, "x", "text/plain"));
        // Not a prefix lookalike: /auth/me/thing is guarded.
        assertRefused(send("POST", "/auth/me/thing", "SESSION_COOKIE", "same-site", foreign, null, null), 403, "CROSS_ORIGIN_REQUEST");
    }

    @Test
    void trustedOriginsAndPublicOrigins() {
        assertThat(SessionCsrf.publicOrigins("https://platform.example.com/some/path")).containsExactly("https://platform.example.com");
        assertThat(SessionCsrf.publicOrigins("http://localhost:8080")).containsExactly("http://localhost:8080");
        assertThat(SessionCsrf.publicOrigins("platform.example.com")).isEmpty();
        assertThat(SessionCsrf.publicOrigins("ftp://platform.example.com")).isEmpty();
        assertThat(SessionCsrf.publicOrigins("")).isEmpty();
        assertThatThrownBy(() -> new SessionCsrf(List.of("ftp://x"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(new Provenance(List.of("", TRUSTED)).check(null, TRUSTED, "internal:9000", true)).isNull();
    }
}
