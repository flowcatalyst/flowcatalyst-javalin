package io.flowcatalyst.fcdev.fn;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [FnClient]: envelope→exception mapping and token caching — the two things
/// every `fn` subcommand relies on without re-testing per command.
class FnClientTest {

    @Test
    void nonEnvelopeErrorBodyMapsToAGenericHttpCode() throws Exception {
        try (var platform = FakePlatform.start()) {
            platform.on("GET", "/api/functions/x.y.z", ex -> {
                ex.getResponseHeaders().add("Content-Type", "text/plain");
                byte[] body = "boom".getBytes();
                ex.sendResponseHeaders(500, body.length);
                ex.getResponseBody().write(body);
                ex.close();
            });
            var client = new FnClient(platform.baseUrl(), "id", "secret");
            assertThatThrownBy(() -> client.get("/api/functions/x.y.z"))
                    .isInstanceOf(FnClientException.class)
                    .satisfies(e -> {
                        var fe = (FnClientException) e;
                        assertThat(fe.status()).isEqualTo(500);
                        assertThat(fe.code()).isEqualTo("HTTP_500");
                    });
        }
    }

    @Test
    void envelopeErrorBodyCarriesCodeMessageAndDetails() throws Exception {
        try (var platform = FakePlatform.start()) {
            platform.on("GET", "/api/functions/x.y.z", ex ->
                    FakePlatform.writeError(ex, 409, "VERSION_DIGEST_EXISTS", "digest is already published as version 3",
                            Map.of("version", 3)));
            var client = new FnClient(platform.baseUrl(), "id", "secret");
            assertThatThrownBy(() -> client.get("/api/functions/x.y.z"))
                    .isInstanceOf(FnClientException.class)
                    .satisfies(e -> {
                        var fe = (FnClientException) e;
                        assertThat(fe.status()).isEqualTo(409);
                        assertThat(fe.code()).isEqualTo("VERSION_DIGEST_EXISTS");
                        assertThat(fe.getMessage()).isEqualTo("digest is already published as version 3");
                        assertThat(fe.details()).containsEntry("version", 3);
                    });
        }
    }

    @Test
    void successReturnsTheBody() throws Exception {
        try (var platform = FakePlatform.start()) {
            platform.on("GET", "/api/functions/x.y.z", ex -> FakePlatform.writeJson(ex, 200, Map.of("ok", true)));
            var client = new FnClient(platform.baseUrl(), "id", "secret");
            assertThat(client.get("/api/functions/x.y.z").path("ok").asBoolean()).isTrue();
        }
    }

    @Test
    void noBodyResponseReturnsNull() throws Exception {
        try (var platform = FakePlatform.start()) {
            platform.on("DELETE", "/api/functions/x.y.z", ex -> FakePlatform.writeNoBody(ex, 204));
            var client = new FnClient(platform.baseUrl(), "id", "secret");
            client.delete("/api/functions/x.y.z");
        }
    }

    /// The bearer token is minted once and reused — never re-fetched per
    /// call (`TokenManager`'s own contract; pinned here at the [FnClient]
    /// boundary since every `fn` subcommand depends on it not hammering
    /// `/oauth/token`).
    @Test
    void tokenIsCachedAcrossCalls() throws Exception {
        try (var platform = FakePlatform.start()) {
            AtomicInteger calls = new AtomicInteger();
            platform.on("GET", "/api/functions/x.y.z", ex -> {
                calls.incrementAndGet();
                FakePlatform.writeJson(ex, 200, Map.of());
            });
            var client = new FnClient(platform.baseUrl(), "id", "secret");
            client.get("/api/functions/x.y.z");
            client.get("/api/functions/x.y.z");
            client.get("/api/functions/x.y.z");
            assertThat(calls.get()).isEqualTo(3);
            assertThat(platform.tokenRequests.get()).isEqualTo(1);
        }
    }

    @Test
    void postSendsTheBodyAsJson() throws Exception {
        try (var platform = FakePlatform.start()) {
            var received = new java.util.concurrent.atomic.AtomicReference<String>();
            platform.on("POST", "/api/functions", ex -> {
                received.set(FakePlatform.bodyOf(ex));
                FakePlatform.writeJson(ex, 201, Map.of("id", "fnc_1"));
            });
            var client = new FnClient(platform.baseUrl(), "id", "secret");
            ObjectNode body = tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode().put("name", "x");
            client.post("/api/functions", body);
            assertThat(received.get()).contains("\"name\":\"x\"");
        }
    }
}
