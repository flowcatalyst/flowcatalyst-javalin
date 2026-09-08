package io.flowcatalyst.server.transport;

import com.sun.net.httpserver.HttpServer;
import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.router.observability.PoolMetricsCollector;
import io.flowcatalyst.router.policy.BreakerRegistry;
import io.flowcatalyst.router.policy.CircuitBreaker;
import io.flowcatalyst.router.pool.HttpMediator;
import io.flowcatalyst.router.pool.HttpVersion;
import io.flowcatalyst.router.pool.JdkTransport;
import io.flowcatalyst.router.wire.MediationType;
import io.flowcatalyst.router.wire.Message;
import io.flowcatalyst.platform.dispatchjob.processing.SubscriberDelivery;
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/// `docs/spec/router-h2.md` §4: the outbound mediation client prefers h2
/// when deployed and pins 1.1 under fcdev, falls back to 1.1 against a
/// target that cannot negotiate h2, and records the negotiated version
/// (`fc_router_mediation_http_version_total`).
///
/// **Why the end-to-end cases use TLS+ALPN, not cleartext h2c** (both
/// fixtures come from `server/transport`'s own connector wiring —
/// [Listeners.tls] and [Listeners.h2c] respectively): every real mediation
/// call is a POST carrying a body (§3's `buildRequest`). Verified
/// empirically while writing this suite — a bare cleartext
/// `HTTP2CServerConnectionFactory` listener (mirroring [Listeners.h2c])
/// never upgrades a `java.net.http.HttpClient` request to h2 when that
/// request carries a body, even on a second request over the same reused
/// connection; the client silently keeps the connection on HTTP/1.1
/// regardless of whether `Version.HTTP_2` was requested. That makes a
/// cleartext h2c fixture unable to distinguish "pinned 1.1" from "defaulted
/// to prefer 2 but silently never got there" for this mediator's request
/// shape — a test built on it would pass even with `.version(...)` deleted.
/// TLS+ALPN (mirroring [Listeners.tls], the same pattern `TlsAlpnTest`
/// uses) negotiates the version during the TLS handshake, before any body
/// is sent, so it reaches HTTP/2 on the very first bodied POST — confirmed
/// the same way, empirically — which is what makes it able to actually pin
/// the claim.
///
/// [#defaultClientPinsTheRequestedVersion] additionally pins the exact
/// `.version(devMode ? HTTP_1_1 : HTTP_2)` builder call directly (no
/// network needed): `HttpClient#version()` reports the version a client was
/// built to request, so deleting that call or hard-coding either branch is
/// caught deterministically, without depending on a live negotiation.
///
/// The "cannot negotiate h2 at all" target is a plain JDK [HttpServer]
/// (HTTP/1.1 only, no TLS), the same fixture
/// `HttpMediatorTest`/`MediationConformanceTest` already use for the
/// mediator's outcome-mapping tests.
class HttpMediatorVersionTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final String KEYSTORE_PASSWORD = "changeit";

    private org.eclipse.jetty.server.Server jettyServer;
    private HttpServer legacyServer;

    @AfterEach
    void stop() throws Exception {
        if (jettyServer != null) {
            jettyServer.stop();
        }
        if (legacyServer != null) {
            legacyServer.stop(0);
        }
    }

    @Test
    void defaultClientPinsTheRequestedVersion() {
        assertThat(HttpMediator.defaultClient(true).version())
                .as("dev mode")
                .isEqualTo(HttpClient.Version.HTTP_1_1);
        assertThat(HttpMediator.defaultClient(false).version())
                .as("deployed mode")
                .isEqualTo(HttpClient.Version.HTTP_2);
    }

    @Test
    void devModeNegotiatesHttp1Dot1AgainstAnH2CapableTarget(@TempDir Path dir) throws Exception {
        int port = startTlsAlpnServer(dir);
        var client = trustingClient(HttpClient.Version.HTTP_1_1, dir.resolve("fc.p12"));

        var metrics = new PoolMetricsCollector(FIXED);
        var mediator = mediator(client, metrics);

        mediator.deliver(message("https://localhost:" + port + "/hook"), true);

        // Pin: the JDK client defaults to preferring HTTP/2 even over
        // cleartext (`Version.HTTP_2` is `HttpClient.Builder`'s own
        // default), so this assertion only holds because dev mode pins 1.1
        // explicitly.
        assertThat(metrics.httpVersionCount(HttpVersion.HTTP_1_1)).isEqualTo(1);
        assertThat(metrics.httpVersionCount(HttpVersion.HTTP_2)).isEqualTo(0);
    }

    @Test
    void deployedModeNegotiatesHttp2AgainstAnH2CapableTarget(@TempDir Path dir) throws Exception {
        int port = startTlsAlpnServer(dir);
        var client = trustingClient(HttpClient.Version.HTTP_2, dir.resolve("fc.p12"));

        var metrics = new PoolMetricsCollector(FIXED);
        var mediator = mediator(client, metrics);

        mediator.deliver(message("https://localhost:" + port + "/hook"), true);

        assertThat(metrics.httpVersionCount(HttpVersion.HTTP_2)).isEqualTo(1);
        assertThat(metrics.httpVersionCount(HttpVersion.HTTP_1_1)).isEqualTo(0);
    }

    @Test
    void deployedModeFallsBackToHttp1Dot1AgainstATargetThatCannotNegotiateH2() throws Exception {
        legacyServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        legacyServer.createContext("/hook", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        legacyServer.start();
        String baseUrl = "http://127.0.0.1:" + legacyServer.getAddress().getPort() + "/hook";

        var metrics = new PoolMetricsCollector(FIXED);
        // The real, unmodified production factory: no TLS/self-signed-cert
        // trust wrinkle applies here since the target is plain HTTP/1.1.
        var mediator = mediator(HttpMediator.defaultClient(false), metrics);

        mediator.deliver(message(baseUrl), true);

        assertThat(metrics.httpVersionCount(HttpVersion.HTTP_1_1)).isEqualTo(1);
        assertThat(metrics.httpVersionCount(HttpVersion.HTTP_2)).isEqualTo(0);
    }

    @Test
    void theHttpVersionCounterIncrementsOncePerDeliveredRequestWithTheRightLabel(@TempDir Path dir) throws Exception {
        int port = startTlsAlpnServer(dir);
        var client = trustingClient(HttpClient.Version.HTTP_2, dir.resolve("fc.p12"));

        var metrics = new PoolMetricsCollector(FIXED);
        var mediator = mediator(client, metrics);
        var target = "https://localhost:" + port + "/hook";

        assertThat(metrics.httpVersionCount(HttpVersion.HTTP_2)).isEqualTo(0);

        mediator.deliver(message(target), true);
        assertThat(metrics.httpVersionCount(HttpVersion.HTTP_2)).isEqualTo(1);

        mediator.deliver(message(target), true);
        mediator.deliver(message(target), true);
        assertThat(metrics.httpVersionCount(HttpVersion.HTTP_2)).isEqualTo(3);
        // Never mislabelled onto the other counter.
        assertThat(metrics.httpVersionCount(HttpVersion.HTTP_1_1)).isEqualTo(0);
    }

    @Test
    void subscriberDeliveryDefaultClientHasAThirtySecondConnectTimeout() {
        var client = SubscriberDelivery.defaultClient();

        assertThat(client.connectTimeout())
                .as("connect timeout")
                .isPresent()
                .contains(Duration.ofSeconds(30));
    }

    private static HttpMediator mediator(HttpClient client, PoolMetricsCollector metrics) {
        var breakers = new BreakerRegistry(CircuitBreaker.Config.DEFAULTS, FIXED);
        return new HttpMediator(new JdkTransport(client), Duration.ofSeconds(10), breakers, FIXED,
                (severity, category, text) -> { }, metrics);
    }

    private static Message message(String target) {
        return new Message("m1", "", null, null, MediationType.HTTP, target,
                null, false, DispatchMode.IMMEDIATE);
    }

    /// Mirrors `HttpMediator.defaultClient(devMode)`'s shape (connect
    /// timeout, redirects never, the requested version) with a trusting
    /// [SSLContext] added for the test's self-signed certificate —
    /// `defaultClient` itself is exercised directly, without any TLS
    /// wrinkle, by [#defaultClientPinsTheRequestedVersion] and by
    /// [#deployedModeFallsBackToHttp1Dot1AgainstATargetThatCannotNegotiateH2].
    private static HttpClient trustingClient(HttpClient.Version version, Path keystore) throws Exception {
        return HttpClient.newBuilder()
                .version(version)
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NEVER)
                .sslContext(trustingSslContext(keystore))
                .build();
    }

    /// The same connector shape `Listeners.tls()` installs (TLS 1.2/1.3
    /// with ALPN -> h2, http/1.1), built from a keystore generated the same
    /// way `TlsAlpnTest` does (`keytool` off `java.home`, no `openssl`
    /// dependency in tests).
    private int startTlsAlpnServer(Path dir) throws Exception {
        Path keystore = dir.resolve("fc.p12");
        generateKeystore(keystore);

        var scf = new SslContextFactory.Server();
        scf.setKeyStorePath(keystore.toString());
        scf.setKeyStorePassword(KEYSTORE_PASSWORD);

        jettyServer = new org.eclipse.jetty.server.Server();
        var httpConfig = new HttpConfiguration();
        httpConfig.addCustomizer(new SecureRequestCustomizer());
        var connector = new ServerConnector(jettyServer,
                new SslConnectionFactory(scf, "alpn"),
                new ALPNServerConnectionFactory("h2", "http/1.1"),
                new HTTP2ServerConnectionFactory(httpConfig),
                new HttpConnectionFactory(httpConfig));
        connector.setPort(0);
        jettyServer.addConnector(connector);
        jettyServer.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception {
                Content.Source.consumeAll(request);
                response.setStatus(200);
                callback.succeeded();
                return true;
            }
        });
        jettyServer.start();
        return connector.getLocalPort();
    }

    private static void generateKeystore(Path keystore) throws Exception {
        String keytool = System.getProperty("java.home") + "/bin/keytool";
        var process = new ProcessBuilder(keytool,
                "-genkeypair", "-alias", "fc", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "3650", "-storetype", "PKCS12", "-keystore", keystore.toString(),
                "-storepass", KEYSTORE_PASSWORD, "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1")
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("keytool exited " + exit + ": " + output);
        }
    }

    private static SSLContext trustingSslContext(Path keystore) throws Exception {
        var trustStore = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(keystore)) {
            trustStore.load(in, KEYSTORE_PASSWORD.toCharArray());
        }
        var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        var sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, tmf.getTrustManagers(), null);
        return sslContext;
    }
}
