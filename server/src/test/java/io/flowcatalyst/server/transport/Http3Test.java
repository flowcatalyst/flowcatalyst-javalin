package io.flowcatalyst.server.transport;

import io.flowcatalyst.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// `docs/spec/http-transport.md` §4.3: `Alt-Svc` is present on the TLS
/// listener and absent on the plain one whenever `FC_HTTP3_ENABLED=true`,
/// independent of whether the quiche native library actually loads on this
/// machine (`Http3#quicheLoadFailure`); the end-to-end h3 fetch is gated on
/// that probe with `Assumptions`, per the brief. On this machine (macOS
/// arm64) quiche DOES load — the fetch itself hits a separate
/// `SSLHandshakeException`/`UnsupportedOperationException` inside Jetty's
/// HTTP/3 test client that this test could not resolve within the brief's
/// one-hour HTTP/3 budget, so it is also `Assumptions.abort`ed with the
/// exact error rather than failing the suite; every server-side assertion
/// above it (connector built, Alt-Svc correct on both listeners) still runs
/// and still passes.
class Http3Test {

    private static final String PASSWORD = "changeit";
    private static final String ALIAS = "fc";

    private Server.Running running;

    @AfterEach
    void stop() {
        if (running != null) {
            try {
                running.stop();
            } catch (io.javalin.util.JavalinException e) {
                // docs/backlog.md "HTTP/3 sessions hold the graceful stop": after the h3 exchange
                // Jetty waits the whole stop timeout for the vanished client's QUIC session before
                // the connectors close (measured 31 s). The stop completes; only its timing is the
                // known limitation, so a TimeoutException here is tolerated and anything else is not.
                if (!(e.getCause() instanceof java.util.concurrent.TimeoutException)) throw e;
            }
        }
    }

    @Test
    void altSvcOnTlsListenerOnlyAndH3WhenQuicheLoads(@TempDir Path dir) throws Exception {
        Path keystore = dir.resolve("fc.p12");
        generateKeystore(keystore);

        int apiPort = TransportTestSupport.freePort();
        int tlsPort = TransportTestSupport.freePort();
        int http3Port = TransportTestSupport.freePort();
        running = TransportTestSupport.start(Map.of(
                "FC_API_PORT", String.valueOf(apiPort),
                "FC_TLS_PORT", String.valueOf(tlsPort),
                "FC_TLS_KEYSTORE_PATH", keystore.toString(),
                "FC_TLS_KEYSTORE_PASSWORD", PASSWORD,
                "FC_HTTP3_ENABLED", "true",
                "FC_HTTP3_PORT", String.valueOf(http3Port)));

        SSLContext trustingContext = trustingSslContext(keystore);

        // Present on the TLS listener.
        var tlsClient = java.net.http.HttpClient.newBuilder().sslContext(trustingContext).build();
        var tlsResponse = tlsClient.send(
                HttpRequest.newBuilder(URI.create("https://localhost:" + tlsPort + "/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(tlsResponse.statusCode()).isEqualTo(200);
        assertThat(tlsResponse.headers().allValues("Alt-Svc"))
                .as("Jetty auto-advertises a bare Alt-Svc once a QUIC connector shares the Server; "
                        + "Http3#altSvcHandler must overwrite that instead of losing to it")
                .containsExactly("h3=\":" + http3Port + "\"; ma=86400");

        // Absent on the plain h2c listener.
        var plainClient = java.net.http.HttpClient.newHttpClient();
        var plainResponse = plainClient.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + apiPort + "/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(plainResponse.statusCode()).isEqualTo(200);
        assertThat(plainResponse.headers().firstValue("Alt-Svc")).isEmpty();

        // The end-to-end HTTP/3 fetch: skipped, with the load error in the
        // message, when quiche does not load on this machine.
        var quicheFailure = Http3.quicheLoadFailure();
        Assumptions.assumeTrue(quicheFailure.isEmpty(),
                () -> "quiche native library did not load: " + quicheFailure.map(Object::toString).orElse(""));

        // The end-to-end h3 fetch uses curl when an HTTP/3-capable one is installed (Homebrew's
        // curl 8.x with nghttp3/ngtcp2 on this machine; Ubuntu's stock curl has no HTTP/3 and the
        // check is skipped there with a message). Jetty's own HTTP/3 client stack was tried first
        // (jetty-http3-client-transport + jetty-quic-quiche-client): it handshakes but fails its
        // control stream and then leaves a QUIC session that stalls the server's graceful stop for
        // the whole grace period — a client-side problem, so an independent client pins the server.
        var curl = http3Curl();
        Assumptions.assumeTrue(curl.isPresent(), "no HTTP/3-capable curl on this machine (Homebrew curl has one)");
        var probe = new ProcessBuilder(curl.get(), "-sk", "--http3-only", "-m", "10", "-o", "/dev/null",
                "-w", "%{http_version} %{http_code}", "https://127.0.0.1:" + http3Port + "/health")
                .redirectErrorStream(true).start();
        String out = new String(probe.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
        assertThat(probe.waitFor()).as("curl --http3-only: " + out).isZero();
        assertThat(out).as("served over h3 (curl's %{http_version} is 3 for HTTP/3)").isEqualTo("3 200");
    }


    private static SSLContext trustingSslContext(Path keystore) throws Exception {
        var trustStore = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(keystore)) {
            trustStore.load(in, PASSWORD.toCharArray());
        }
        var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        var sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, tmf.getTrustManagers(), null);
        return sslContext;
    }

    private static void generateKeystore(Path keystore) throws IOException, InterruptedException {
        String keytool = System.getProperty("java.home") + "/bin/keytool";
        var process = new ProcessBuilder(keytool,
                "-genkeypair",
                "-alias", ALIAS,
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "3650",
                "-storetype", "PKCS12",
                "-keystore", keystore.toString(),
                "-storepass", PASSWORD,
                "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1")
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("keytool exited " + exit + ": " + output);
        }
    }

    /// An HTTP/3-capable curl: Homebrew's first, then whatever `curl` is on PATH if its
    /// `-V` lists HTTP3.
    private static java.util.Optional<String> http3Curl() throws Exception {
        for (String candidate : java.util.List.of("/opt/homebrew/opt/curl/bin/curl", "/usr/local/opt/curl/bin/curl", "curl")) {
            try {
                var p = new ProcessBuilder(candidate, "-V").redirectErrorStream(true).start();
                String v = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                if (p.waitFor() == 0 && v.contains("HTTP3")) return java.util.Optional.of(candidate);
            } catch (java.io.IOException notInstalled) {
                // next candidate
            }
        }
        return java.util.Optional.empty();
    }
}
