package io.flowcatalyst.server.transport;

import io.flowcatalyst.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `docs/spec/http-transport.md` §4.2: TLS 1.2/1.3 with ALPN -> h2 (and a
/// fallback to http/1.1), for both TLS material forms (§2), plus the three
/// startup errors a bad/partial/doubled configuration must raise instead of
/// silently falling back to a plain listener.
class TlsAlpnTest {

    private static final String PASSWORD = "changeit";
    private static final String ALIAS = "fc";

    private Server.Running running;

    @AfterEach
    void stop() {
        if (running != null) {
            running.stop();
        }
    }

    @Test
    void keystoreFormServesH2OverAlpnAndFallsBackToHttp1(@TempDir Path dir) throws Exception {
        Path keystore = dir.resolve("fc.p12");
        generateKeystore(keystore);

        int tlsPort = TransportTestSupport.freePort();
        running = TransportTestSupport.start(Map.of(
                "FC_API_PORT", String.valueOf(TransportTestSupport.freePort()),
                "FC_TLS_PORT", String.valueOf(tlsPort),
                "FC_TLS_KEYSTORE_PATH", keystore.toString(),
                "FC_TLS_KEYSTORE_PASSWORD", PASSWORD));

        SSLContext trustingContext = trustingSslContext(keystore, PASSWORD);
        assertHealthOverTls(tlsPort, trustingContext, HttpClient.Version.HTTP_2);
        assertHealthOverTls(tlsPort, trustingContext, HttpClient.Version.HTTP_1_1);
    }

    /// The PEM pair, converted from the SAME keystore with JDK APIs alone
    /// (no `openssl`, per the brief) — the point is that the two forms in §2
    /// are equivalent inputs to the same TLS listener, not two code paths
    /// that happen to both compile.
    @Test
    void pemFormServesH2OverAlpn(@TempDir Path dir) throws Exception {
        Path keystore = dir.resolve("fc.p12");
        generateKeystore(keystore);

        Path certPem = dir.resolve("fc.crt");
        Path keyPem = dir.resolve("fc.key");
        exportPem(keystore, certPem, keyPem);

        int tlsPort = TransportTestSupport.freePort();
        running = TransportTestSupport.start(Map.of(
                "FC_API_PORT", String.valueOf(TransportTestSupport.freePort()),
                "FC_TLS_PORT", String.valueOf(tlsPort),
                "FC_TLS_CERT_PATH", certPem.toString(),
                "FC_TLS_KEY_PATH", keyPem.toString()));

        SSLContext trustingContext = trustingSslContext(keystore, PASSWORD);
        assertHealthOverTls(tlsPort, trustingContext, HttpClient.Version.HTTP_2);
    }

    @Test
    void wrongKeystorePasswordIsAStartupErrorNamingThePath(@TempDir Path dir) throws Exception {
        Path keystore = dir.resolve("fc.p12");
        generateKeystore(keystore);

        var overrides = Map.of(
                "FC_API_PORT", String.valueOf(TransportTestSupport.freePort()),
                "FC_TLS_PORT", String.valueOf(TransportTestSupport.freePort()),
                "FC_TLS_KEYSTORE_PATH", keystore.toString(),
                "FC_TLS_KEYSTORE_PASSWORD", "not-the-real-password");

        assertThatThrownBy(() -> TransportTestSupport.start(overrides))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(keystore.toString());
    }

    @Test
    void missingKeystoreFileIsAStartupErrorNamingThePath() {
        var missing = "/no/such/file/fc.p12";
        var overrides = Map.of(
                "FC_API_PORT", String.valueOf(TransportTestSupport.freePort()),
                "FC_TLS_PORT", String.valueOf(TransportTestSupport.freePort()),
                "FC_TLS_KEYSTORE_PATH", missing,
                "FC_TLS_KEYSTORE_PASSWORD", PASSWORD);

        assertThatThrownBy(() -> TransportTestSupport.start(overrides))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(missing);
    }

    @Test
    void bothTlsFormsSetAtOnceIsAStartupError(@TempDir Path dir) throws Exception {
        Path keystore = dir.resolve("fc.p12");
        generateKeystore(keystore);
        Path certPem = dir.resolve("fc.crt");
        Path keyPem = dir.resolve("fc.key");
        exportPem(keystore, certPem, keyPem);

        var overrides = Map.of(
                "FC_API_PORT", String.valueOf(TransportTestSupport.freePort()),
                "FC_TLS_PORT", String.valueOf(TransportTestSupport.freePort()),
                "FC_TLS_KEYSTORE_PATH", keystore.toString(),
                "FC_TLS_KEYSTORE_PASSWORD", PASSWORD,
                "FC_TLS_CERT_PATH", certPem.toString(),
                "FC_TLS_KEY_PATH", keyPem.toString());

        assertThatThrownBy(() -> TransportTestSupport.start(overrides))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static void assertHealthOverTls(int tlsPort, SSLContext sslContext, HttpClient.Version version)
            throws IOException, InterruptedException {
        var client = HttpClient.newBuilder().sslContext(sslContext).version(version).build();
        var response = client.send(
                HttpRequest.newBuilder(URI.create("https://localhost:" + tlsPort + "/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.version()).isEqualTo(version);
    }

    /// `keytool` from `java.home` (§4.2: no `openssl` dependency in tests) —
    /// a self-signed PKCS#12 with a SAN covering `localhost`, so the JDK
    /// client's default hostname verification passes.
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

    /// A trust-only [KeyStore]/[SSLContext] built from the SAME PKCS#12: the
    /// default `TrustManagerFactory` trusts every certificate a `KeyStore`
    /// holds, key entries included, so the keystore doubles as its own
    /// truststore for a self-signed test certificate.
    private static SSLContext trustingSslContext(Path keystore, String password) throws Exception {
        var trustStore = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(keystore)) {
            trustStore.load(in, password.toCharArray());
        }
        var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        var sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, tmf.getTrustManagers(), null);
        return sslContext;
    }

    /// Converts the keystore's single entry into a leaf-first PEM chain and
    /// an unencrypted PKCS#8 PEM key, with JDK APIs alone
    /// ([CertificateFactory]/Base64 for the chain, [PrivateKey#getEncoded]
    /// for the key) — `docs/spec/http-transport.md` §4.2 forbids an
    /// `openssl` dependency in the tests.
    private static void exportPem(Path keystore, Path certPem, Path keyPem) throws Exception {
        var ks = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(keystore)) {
            ks.load(in, PASSWORD.toCharArray());
        }
        Certificate[] chain = ks.getCertificateChain(ALIAS);
        var certText = new StringBuilder();
        for (Certificate certificate : chain) {
            certText.append("-----BEGIN CERTIFICATE-----\n")
                    .append(mimeBase64(certificate.getEncoded()))
                    .append("\n-----END CERTIFICATE-----\n");
        }
        Files.writeString(certPem, certText.toString());

        PrivateKey key = (PrivateKey) ks.getKey(ALIAS, PASSWORD.toCharArray());
        var keyText = "-----BEGIN PRIVATE KEY-----\n"
                + mimeBase64(key.getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        Files.writeString(keyPem, keyText);
    }

    private static String mimeBase64(byte[] der) {
        return Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
    }
}
