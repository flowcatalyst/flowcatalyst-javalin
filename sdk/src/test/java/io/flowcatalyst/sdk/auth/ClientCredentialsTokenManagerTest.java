package io.flowcatalyst.sdk.auth;

import com.sun.net.httpserver.HttpServer;
import io.flowcatalyst.sdk.error.FlowCatalystException;
import io.flowcatalyst.sdk.error.SdkError;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// A failed token fetch says what failed: the HTTP status leads (a proxy's 503
/// never reads like a refusal), the endpoint's own reason follows, and a
/// connection failure names the exception rather than "null". Mutants: drop the
/// status; message = getMessage().
class ClientCredentialsTokenManagerTest {

    @Test
    void aNonSuccessStatusIsNamedWithTheEndpointsReason() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/oauth/token", exchange -> {
            byte[] body = "{\"error\":\"temporarily_unavailable\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var manager = new ClientCredentialsTokenManager(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "id", "secret", null);
            var e = assertThrows(FlowCatalystException.class, manager::getToken);
            var failed = assertInstanceOf(SdkError.TokenFetchFailed.class, e.error());
            assertEquals("Token fetch failed (HTTP 503): temporarily_unavailable", failed.message());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aConnectionFailureNamesTheExceptionNotNull() throws Exception {
        int deadPort;
        try (var probe = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            deadPort = probe.getLocalPort();
        }
        var manager = new ClientCredentialsTokenManager("http://127.0.0.1:" + deadPort, "id", "secret", null);
        var e = assertThrows(FlowCatalystException.class, manager::getToken);
        var failed = assertInstanceOf(SdkError.TokenFetchFailed.class, e.error());
        assertTrue(failed.message().startsWith("Token fetch failed: "), failed.message());
        assertFalse(failed.message().contains("null"), failed.message());
        assertNotNull(failed.cause());
    }
}
