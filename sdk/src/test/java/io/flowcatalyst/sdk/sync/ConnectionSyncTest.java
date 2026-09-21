package io.flowcatalyst.sdk.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.flowcatalyst.sdk.FlowCatalystClient;
import io.flowcatalyst.sdk.StubServer;
import io.flowcatalyst.sdk.sync.Definitions.Connection;
import io.flowcatalyst.sdk.sync.Definitions.DefinitionSet;
import io.flowcatalyst.sdk.sync.Definitions.Subscription;
import io.flowcatalyst.sdk.sync.Definitions.SubscriptionEventType;
import io.flowcatalyst.sdk.sync.SyncResult.Category;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Single-set connection/subscription sync (Go's {@code ConnectionSyncTest},
 * ported): ordering and wire shape. Cross-set merging and per-client scoping
 * are covered separately in {@link MergedSyncTest} — this module scopes a
 * client to the whole {@link DefinitionSet} ({@link DefinitionSet#forClient}),
 * not per row, so there is no row-level client split to test here.
 */
class ConnectionSyncTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SYNC_OK =
            "{\"applicationCode\":\"orders\",\"created\":1,\"updated\":0,\"deleted\":0,"
                    + "\"syncedCodes\":[\"x\"]}";

    private StubServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new StubServer();
        server.stubToken("tok");
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private FlowCatalystClient client() {
        return FlowCatalystClient.builder()
                .baseUrl(server.baseUrl())
                .clientCredentials("id", "secret")
                .build();
    }

    private List<StubServer.Recorded> syncCalls() {
        return server.requests.stream().filter(r -> r.pathAndQuery().contains("/sync")).toList();
    }

    /// Mutant (4): subscriptions synced before connections — swap the order
    /// inside {@code DefinitionSynchronizer#syncInternal} and this fails.
    @Test
    void connectionsSyncBeforeSubscriptions() {
        server.on("POST", "/api/applications/orders/connections/sync", 200, SYNC_OK);
        server.on("POST", "/api/applications/orders/subscriptions/sync", 200, SYNC_OK);

        DefinitionSet set = DefinitionSet.define("orders")
                .withConnections(List.of(Connection.of("conn-a", "A")))
                .withSubscriptions(List.of(Subscription.of(
                        "sub-a", "Sub A", "https://a.example.com/hook",
                        List.of(SubscriptionEventType.of("orders:sales:order:created")))
                        .withConnectionCode("conn-a")));

        client().definitions().sync(set, SyncOptions.removingUnlisted());

        List<String> paths = syncCalls().stream().map(StubServer.Recorded::pathAndQuery).toList();
        assertEquals(2, paths.size());
        assertTrue(paths.get(0).startsWith("/api/applications/orders/connections/sync"),
                "connections first, was: " + paths);
        assertTrue(paths.get(1).startsWith("/api/applications/orders/subscriptions/sync"),
                "subscriptions second, was: " + paths);
    }

    /// Mutant (5): {@code connectionCode}/{@code sharedConnection} not
    /// serialized — drop either field from {@link Definitions.Subscription}
    /// (or its {@code @JsonInclude}) and this fails.
    @Test
    void connectionCodeAndSharedConnectionAreSerializedOnTheWire() throws Exception {
        server.on("POST", "/api/applications/orders/subscriptions/sync", 200, SYNC_OK);

        DefinitionSet set = DefinitionSet.define("orders")
                .withSubscriptions(List.of(Subscription.of(
                        "sub-a", "Sub A", "https://a.example.com/hook",
                        List.of(SubscriptionEventType.of("orders:sales:order:created")))
                        .withConnectionCode("shared-conn")
                        .withSharedConnection(true)));

        client().definitions().sync(set);

        JsonNode entry = MAPPER.readTree(syncCalls().get(0).body()).get("subscriptions").get(0);
        assertEquals("shared-conn", entry.get("connectionCode").asText());
        assertTrue(entry.get("sharedConnection").asBoolean());
    }

    /// {@code sharedConnection} is normalised to {@code null} (omitted) when
    /// false, and a per-row {@code connectionCode} without it means "this
    /// application's own namespace".
    @Test
    void sharedConnectionOmittedWhenFalse() throws Exception {
        server.on("POST", "/api/applications/orders/subscriptions/sync", 200, SYNC_OK);

        DefinitionSet set = DefinitionSet.define("orders")
                .withSubscriptions(List.of(Subscription.of(
                        "sub-a", "Sub A", "https://a.example.com/hook",
                        List.of(SubscriptionEventType.of("orders:sales:order:created")))
                        .withConnectionCode("conn-a")
                        .withSharedConnection(false)));

        client().definitions().sync(set);

        JsonNode entry = MAPPER.readTree(syncCalls().get(0).body()).get("subscriptions").get(0);
        assertEquals("conn-a", entry.get("connectionCode").asText());
        assertFalse(entry.has("sharedConnection"), "sharedConnection omitted when false");
    }

    @Test
    void duplicateConnectionCodeWithinOneSetFailsLocallyAndThrows() {
        DefinitionSet set = DefinitionSet.define("orders")
                .withConnections(List.of(
                        Connection.of("dup", "First"),
                        Connection.of("dup", "Second")));

        DefinitionSyncException ex = assertThrows(
                DefinitionSyncException.class, () -> client().definitions().sync(set));

        Category.Failed failed = assertInstanceOf(Category.Failed.class, ex.result().connections());
        assertTrue(failed.error().contains("dup"));
        assertTrue(syncCalls().isEmpty());
    }

    @Test
    void duplicateSubscriptionCodeWithinOneSetFailsLocallyAndThrows() {
        DefinitionSet set = DefinitionSet.define("orders")
                .withSubscriptions(List.of(
                        Subscription.of("dup", "First", "https://a.example.com/hook",
                                List.of(SubscriptionEventType.of("orders:sales:order:created"))),
                        Subscription.of("dup", "Second", "https://b.example.com/hook",
                                List.of(SubscriptionEventType.of("orders:sales:order:created")))));

        DefinitionSyncException ex = assertThrows(
                DefinitionSyncException.class, () -> client().definitions().sync(set));

        Category.Failed failed = assertInstanceOf(Category.Failed.class, ex.result().subscriptions());
        assertTrue(failed.error().contains("dup"));
        assertTrue(syncCalls().isEmpty());
    }
}
