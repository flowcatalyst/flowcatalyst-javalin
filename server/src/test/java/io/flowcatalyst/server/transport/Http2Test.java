package io.flowcatalyst.server.transport;

import io.flowcatalyst.server.Server;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.RequestOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/// `docs/spec/http-transport.md` §4.1: h2c (cleartext HTTP/2, by prior
/// knowledge and by upgrade) on the plain API listener, HTTP/1.1 unaffected
/// on the same port, and the metrics listener untouched (HTTP/1.1 only).
///
/// The prior-knowledge probe needs a client that speaks h2c without an
/// `Upgrade:` round trip; the JDK's own `HttpClient` never does (only
/// upgrade), so this uses Vert.x's own `HttpClient` — the same
/// `setHttp2ClearTextUpgrade(false)` shape `VertxMediationClient` uses in
/// production (`docs/spec/router-h2.md` §5) — as a throwaway test client,
/// not part of the seam (`NoFrameworkLeakTest`'s allow-list names this file
/// for exactly that reason).
class Http2Test {

    private Server.Running running;
    private Vertx vertx;

    @AfterEach
    void stop() {
        if (running != null) {
            running.stop();
        }
        if (vertx != null) {
            vertx.close();
        }
    }

    /// Vert.x's client, cleartext, speaking the h2c preface straight away (no
    /// `Upgrade:` round trip) — this is what an ALB target group with
    /// protocol version `HTTP2` does to a target (spec §1's whole reason for
    /// putting h2c on the plain listener).
    @Test
    void h2cByPriorKnowledge() throws Exception {
        int apiPort = TransportTestSupport.freePort();
        running = TransportTestSupport.start(Map.of("FC_API_PORT", String.valueOf(apiPort)));

        vertx = Vertx.vertx();
        var client = vertx.createHttpClient(new HttpClientOptions()
                .setProtocolVersion(HttpVersion.HTTP_2)
                .setHttp2ClearTextUpgrade(false));
        var options = new RequestOptions().setMethod(HttpMethod.GET).setHost("127.0.0.1").setPort(apiPort).setURI("/health");
        var response = client.request(options)
                .compose(req -> req.send())
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(200);
        // The behaviour under test: the connection actually negotiated
        // HTTP/2 rather than merely getting a 200 some other way (e.g. a
        // client-side fallback) — a wrong/missing h2c setup would make this
        // either fail to connect or answer over HTTP/1.1.
        assertThat(response.version()).isEqualTo(HttpVersion.HTTP_2);
    }

    /// The JDK's own `HttpClient`, which negotiates h2c via the
    /// `Upgrade: h2c` header dance on its first request and then reuses the
    /// upgraded connection — by the second request it must be running HTTP/2,
    /// not merely have received a 200.
    @Test
    void h2cByUpgrade() throws Exception {
        int apiPort = TransportTestSupport.freePort();
        running = TransportTestSupport.start(Map.of("FC_API_PORT", String.valueOf(apiPort)));

        var client = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_2)
                .build();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + apiPort + "/health")).GET().build();

        client.send(request, HttpResponse.BodyHandlers.discarding());
        var second = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(second.version()).isEqualTo(java.net.http.HttpClient.Version.HTTP_2);
    }

    /// The same listener still answers a plain HTTP/1.1 client — h2c is
    /// additive, not a replacement (`ServerTest`/`TestHttp` already cover
    /// this shape for every other route; this pins it specifically for the
    /// connector this unit changed).
    @Test
    void http1Dot1StillWorks() throws Exception {
        int apiPort = TransportTestSupport.freePort();
        running = TransportTestSupport.start(Map.of("FC_API_PORT", String.valueOf(apiPort)));

        var client = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .build();
        var response = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + apiPort + "/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.version()).isEqualTo(java.net.http.HttpClient.Version.HTTP_1_1);
    }

    /// §4 item 4 ("nothing else moved"): the metrics listener
    /// ([io.flowcatalyst.server.Metrics]) is untouched plain HTTP/1.1 — an
    /// h2c upgrade attempt against it never reaches HTTP/2. The mirror image
    /// of [#h2cByUpgrade]: the SAME client/request shape that reaches HTTP/2
    /// by the second request against the API listener stays HTTP/1.1 for
    /// both requests here. Without this test, a refactor that accidentally
    /// enabled h2c on the metrics listener too would pass every other
    /// assertion here.
    @Test
    void metricsListenerNeverUpgradesToH2c() throws Exception {
        int apiPort = TransportTestSupport.freePort();
        running = TransportTestSupport.start(Map.of("FC_API_PORT", String.valueOf(apiPort)));

        var client = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_2)
                .build();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + running.metricsPort() + "/health")).GET().build();

        client.send(request, HttpResponse.BodyHandlers.discarding());
        var second = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(second.version()).isEqualTo(java.net.http.HttpClient.Version.HTTP_1_1);
    }
}
