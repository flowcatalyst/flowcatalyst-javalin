package io.flowcatalyst.sdk.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.flowcatalyst.sdk.FlowCatalystClient;
import io.flowcatalyst.sdk.StubServer;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The router's in-flight checks carry the platform bearer token
 * (docs/spec/router-api-auth.md rule 8): the router refuses a call without
 * one once it enforces platform tokens.
 */
class RouterResourceTest {

    private StubServer platform;
    private StubServer router;

    @BeforeEach
    void setUp() throws Exception {
        platform = new StubServer();
        router = new StubServer();
    }

    @AfterEach
    void tearDown() {
        platform.close();
        router.close();
    }

    private FlowCatalystClient client() {
        return FlowCatalystClient.builder()
                .baseUrl(platform.baseUrl())
                .routerBaseUrl(router.baseUrl())
                .clientCredentials("id", "secret")
                .retryDelay(Duration.ofMillis(1))
                .build();
    }

    @Test
    void theInFlightChecksSendThePlatformToken() {
        platform.stubToken("tok-1");
        router.on("GET", "/monitoring/in-flight-messages/check", 200,
                "{\"messageId\":\"m1\",\"inPipeline\":false}");
        router.on("POST", "/monitoring/in-flight-messages/check-batch", 200, "{\"m1\":true}");

        var client = client();
        client.router().inPipeline("m1");
        assertEquals(true, client.router().inPipelineBatch(List.of("m1")).get("m1"));

        assertEquals(2, router.requests.size());
        for (var request : router.requests) {
            assertEquals("Bearer tok-1", request.authorization(), request.pathAndQuery());
        }
        assertTrue(router.requests.getLast().body().contains("\"messageIds\":[\"m1\"]"));
    }
}
