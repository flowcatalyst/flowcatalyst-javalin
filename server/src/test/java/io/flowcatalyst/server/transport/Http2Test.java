package io.flowcatalyst.server.transport;

import io.flowcatalyst.server.Server;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `docs/spec/http-transport.md` §4.1: h2c (cleartext HTTP/2, by prior
/// knowledge and by upgrade) on the plain API listener, HTTP/1.1 unaffected
/// on the same port, and the metrics listener untouched (HTTP/1.1 only).
class Http2Test {

    private Server.Running running;

    @AfterEach
    void stop() {
        if (running != null) {
            running.stop();
        }
    }

    /// Jetty's own HTTP/2 client, cleartext, speaking the h2c preface
    /// straight away (no `Upgrade:` round trip) — this is what an ALB target
    /// group with protocol version `HTTP2` does to a target (spec §1's whole
    /// reason for putting h2c on the plain listener).
    @Test
    void h2cByPriorKnowledge() throws Exception {
        int apiPort = TransportTestSupport.freePort();
        running = TransportTestSupport.start(Map.of("FC_API_PORT", String.valueOf(apiPort)));

        var client = new HttpClient(new HttpClientTransportOverHTTP2(new HTTP2Client()));
        client.start();
        try {
            ContentResponse response = client.GET("http://127.0.0.1:" + apiPort + "/health");
            assertThat(response.getStatus()).isEqualTo(200);
            // The behaviour under test: the connection actually negotiated
            // HTTP/2 rather than merely getting a 200 some other way (e.g. a
            // client-side fallback) — a wrong/missing HTTP2CServerConnectionFactory
            // would make this either fail to connect or answer over HTTP/1.1.
            assertThat(response.getVersion()).isEqualTo(HttpVersion.HTTP_2);
        } finally {
            client.stop();
        }
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
    /// ([io.flowcatalyst.server.Metrics]) is untouched plain HTTP/1.1 —
    /// an h2c prior-knowledge attempt against it must NOT succeed. Without
    /// this test, a refactor that accidentally routed [Listeners] onto the
    /// metrics port too would pass every other assertion here.
    @Test
    void metricsListenerRejectsH2cPriorKnowledge() throws Exception {
        int apiPort = TransportTestSupport.freePort();
        running = TransportTestSupport.start(Map.of("FC_API_PORT", String.valueOf(apiPort)));

        var client = new HttpClient(new HttpClientTransportOverHTTP2(new HTTP2Client()));
        client.setConnectTimeout(2000);
        client.start();
        try {
            // The metrics Jetty has no HTTP2CServerConnectionFactory, so an
            // h2c preface is meaningless bytes to it: the request never
            // completes cleanly (connection reset, or an idle-timeout on a
            // preface it cannot parse).
            assertThatThrownBy(() -> client.GET("http://127.0.0.1:" + running.metricsPort() + "/health"))
                    .isInstanceOfAny(ExecutionException.class, TimeoutException.class);
        } finally {
            client.stop();
        }
    }
}
