package io.flowcatalyst.fcdev.fn;

import io.flowcatalyst.router.wire.WebhookSigner;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/// `fn invoke` — spec §4 E7: "`--webhook` produces a signature the host
/// accepts; without it a `webhook` endpoint is 401". [FakeHost] verifies the
/// signature the SAME way the real function host's `WebhookVerifier` does
/// (`function-host-listener.md` §3): `hex(HMAC-SHA256(secret, timestamp ‖
/// body))`, so this pins the actual bytes signed, not just "some header was
/// sent".
class InvokeCommandTest {

    /// A tiny fake function host: a `webhook` endpoint that checks the
    /// signature itself (mirroring the real host's `WebhookVerifier`), and a
    /// `none`-auth endpoint that always answers 200.
    static final class FakeHost implements AutoCloseable {
        private final com.sun.net.httpserver.HttpServer server;
        final java.util.concurrent.atomic.AtomicReference<String> receivedBody = new java.util.concurrent.atomic.AtomicReference<>();

        FakeHost(String secret) throws Exception {
            server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/functions/app.svc.fn/hook", ex -> {
                byte[] body = ex.getRequestBody().readAllBytes();
                receivedBody.set(new String(body, StandardCharsets.UTF_8));
                String ts = ex.getRequestHeaders().getFirst("X-FlowCatalyst-Timestamp");
                String sig = ex.getRequestHeaders().getFirst("X-FlowCatalyst-Signature");
                if (ts == null || sig == null) {
                    ex.sendResponseHeaders(401, -1);
                    ex.close();
                    return;
                }
                String expected = WebhookSigner.sign(secret, ts, body);
                if (!expected.equals(sig)) {
                    ex.sendResponseHeaders(401, -1);
                } else {
                    byte[] ok = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(200, ok.length);
                    ex.getResponseBody().write(ok);
                }
                ex.close();
            });
            server.createContext("/functions/app.svc.fn/open", ex -> {
                ex.sendResponseHeaders(200, -1);
                ex.close();
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    @Test
    void webhookWithTheRightSecretSignsAndIs200NoBody() throws Exception {
        try (var host = new FakeHost("shh-its-a-secret")) {
            var r = FnCliTestSupport.run(Map.of(), "fn", "invoke", "app.svc.fn", "--path", "/hook",
                    "--host-url", host.url(), "--webhook", "--signing-secret", "shh-its-a-secret");
            assertThat(r.exit()).as(r.out() + r.err()).isZero();
            assertThat(r.out()).contains("HTTP 200");
        }
    }

    @Test
    void webhookWithTheWrongSecretIs401AndExits1() throws Exception {
        try (var host = new FakeHost("the-real-secret")) {
            var r = FnCliTestSupport.run(Map.of(), "fn", "invoke", "app.svc.fn", "--path", "/hook",
                    "--host-url", host.url(), "--webhook", "--signing-secret", "the-wrong-secret");
            assertThat(r.exit()).isEqualTo(1);
            assertThat(r.out()).contains("HTTP 401");
        }
    }

    /// Mutant: sign the wrong bytes (e.g. omit the timestamp, or sign a
    /// re-serialised body instead of the exact bytes sent) — this test signs
    /// a NON-EMPTY body and checks the host, which recomputes the HMAC over
    /// the exact bytes it received, accepts it.
    @Test
    void webhookSignsTheExactBodyBytesSent(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        try (var host = new FakeHost("body-secret")) {
            java.nio.file.Path bodyFile = dir.resolve("body.json");
            java.nio.file.Files.writeString(bodyFile, "{\"n\":1}");
            var r = FnCliTestSupport.run(Map.of(), "fn", "invoke", "app.svc.fn", "--path", "/hook",
                    "--host-url", host.url(), "--webhook", "--signing-secret", "body-secret",
                    "--body", bodyFile.toString(), "--method", "POST");
            assertThat(r.exit()).as(r.out() + r.err()).isZero();
            assertThat(host.receivedBody.get()).isEqualTo("{\"n\":1}");
        }
    }

    /// Without `--webhook`, no signature is sent — the webhook endpoint
    /// answers 401, and `fn invoke` reports it (never treats a failing
    /// function call as a CLI bug).
    @Test
    void withoutWebhookFlagAWebhookEndpointIs401() throws Exception {
        try (var host = new FakeHost("shh-its-a-secret")) {
            var r = FnCliTestSupport.run(Map.of(), "fn", "invoke", "app.svc.fn", "--path", "/hook",
                    "--host-url", host.url());
            assertThat(r.exit()).isEqualTo(1);
            assertThat(r.out()).contains("HTTP 401");
        }
    }

    @Test
    void openEndpointIs200AndExits0() throws Exception {
        try (var host = new FakeHost("unused")) {
            var r = FnCliTestSupport.run(Map.of(), "fn", "invoke", "app.svc.fn", "--path", "/open",
                    "--host-url", host.url());
            assertThat(r.exit()).as(r.out() + r.err()).isZero();
            assertThat(r.out()).contains("HTTP 200");
        }
    }

    @Test
    void webhookWithoutSigningSecretIsAUsageError() {
        var r = FnCliTestSupport.run(Map.of(), "fn", "invoke", "app.svc.fn", "--webhook");
        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("--signing-secret");
    }

    // ── versioned call: the CLI's own bearer token is attached automatically ──

    /// A fake platform + function host combined: mints a token at
    /// `/oauth/token` (so `root.client().bearerToken()` has something real to
    /// fetch) and records the `Authorization` header a VERSIONED call arrives
    /// with at `/functions/app.svc.fn:1/hook` — pins
    /// `function-invocation.md` §2's "a versioned call gets the CLI's own
    /// bearer token automatically", independent of the real function host's
    /// own token verification (exercised end-to-end elsewhere).
    static final class FakeVersionedHost implements AutoCloseable {
        private final com.sun.net.httpserver.HttpServer server;
        final AtomicReference<String> receivedAuthorization = new AtomicReference<>();
        final AtomicReference<String> receivedAuthorizationUnversioned = new AtomicReference<>();

        FakeVersionedHost() throws Exception {
            server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/oauth/token", ex -> {
                byte[] body = "{\"access_token\":\"fake-cli-token\",\"expires_in\":3600}"
                        .getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
                ex.close();
            });
            server.createContext("/functions/app.svc.fn:1/hook", ex -> {
                receivedAuthorization.set(ex.getRequestHeaders().getFirst("Authorization"));
                byte[] ok = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, ok.length);
                ex.getResponseBody().write(ok);
                ex.close();
            });
            // An UNVERSIONED call to the same path — no bearer token should ever be
            // attached here (spec: only a versioned call gets one automatically).
            server.createContext("/functions/app.svc.fn/hook", ex -> {
                receivedAuthorizationUnversioned.set(ex.getRequestHeaders().getFirst("Authorization"));
                byte[] ok = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, ok.length);
                ex.getResponseBody().write(ok);
                ex.close();
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /// Mutant: never add the `Authorization` header on a versioned call — both
    /// this test and `FnCliEndToEndTest`'s "versioned call carries the CLI's
    /// own bearer token" step must die.
    @Test
    void versionedInvokeAttachesTheCliOwnBearerTokenAutomatically() throws Exception {
        try (var host = new FakeVersionedHost()) {
            var r = FnCliTestSupport.run(Map.of(), "fn", "invoke", "app.svc.fn:1", "--path", "/hook",
                    "--method", "POST", "--host-url", host.url(), "--platform-url", host.url(),
                    "--client-id", "cid", "--client-secret", "csecret");
            assertThat(r.exit()).as(r.out() + r.err()).isZero();
            assertThat(host.receivedAuthorization.get())
                    .as("a versioned call must carry the CLI's own bearer token")
                    .isEqualTo("Bearer fake-cli-token");
        }
    }

    /// The flip side, same fixture: an UNVERSIONED call gets no such header —
    /// only a versioned call is special-cased (spec §4).
    @Test
    void unversionedInvokeDoesNotAttachABearerToken() throws Exception {
        try (var host = new FakeVersionedHost()) {
            var r = FnCliTestSupport.run(Map.of(), "fn", "invoke", "app.svc.fn", "--path", "/hook",
                    "--method", "POST", "--host-url", host.url(), "--platform-url", host.url(),
                    "--client-id", "cid", "--client-secret", "csecret");
            assertThat(r.exit()).as(r.out() + r.err()).isZero();
            assertThat(host.receivedAuthorizationUnversioned.get()).isNull();
        }
    }
}
