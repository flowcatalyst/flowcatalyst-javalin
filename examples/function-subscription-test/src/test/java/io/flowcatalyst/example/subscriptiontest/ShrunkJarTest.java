package io.flowcatalyst.example.subscriptiontest;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.fnhost.load.JvmFunctionLoader;
import io.flowcatalyst.fnhost.load.LoadOutcome;
import io.flowcatalyst.fnhost.load.Loaded;
import io.flowcatalyst.fnhost.load.LoadedFunction;
import io.flowcatalyst.fnhost.load.Refused;
import io.flowcatalyst.function.Caller;
import io.flowcatalyst.function.FunctionAddress;
import io.flowcatalyst.function.Request;
import io.flowcatalyst.function.Result;
import io.flowcatalyst.platform.function.ClientCeilings;
import io.flowcatalyst.platform.function.FunctionLimits;
import io.flowcatalyst.platform.function.Manifest;
import io.flowcatalyst.platform.function.Runtime;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.testpg.TestPg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/// Runs the SHRUNK jar through the real host loader against a real Postgres
/// (the server test-jar's embedded instance): a delivery becomes a row, a
/// redelivery of the same envelope does not become a second one. Only
/// `mvn -Pexamples … verify` proves anything here — `test` alone has no jar.
class ShrunkJarTest {

    private static final io.flowcatalyst.platform.function.FunctionAddress PLATFORM_ADDRESS =
            io.flowcatalyst.platform.function.FunctionAddress.parse("platform.test.subscription-test");
    private static final FunctionAddress API_ADDRESS = new FunctionAddress("platform", "test", "subscription-test");
    private static final String ENTRYPOINT = "io.flowcatalyst.example.subscriptiontest.SubscriptionTestFunction";

    private static Path shrunkJar;
    private static DataSource db;

    @BeforeAll
    static void locateShrunkJarAndDatabase() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("target"))) {
            shrunkJar = files.filter(p -> p.getFileName().toString().endsWith("-shrunk.jar")).findFirst()
                    .orElseThrow(() -> new IllegalStateException("no *-shrunk.jar under target/ — run `mvn verify`"));
        }
        db = TestPg.newDatabase("subscriptiontest");
    }

    @Test
    void manifestParsesUnderTheRealParseStrict() throws IOException {
        var root = Json.MAPPER.readTree(Files.readString(Path.of("manifest.json")));
        FunctionLimits defaults = FunctionLimits.defaults();
        Manifest manifest = Manifest.parseStrict(root, Runtime.JVM, defaults, ClientCeilings.of(defaults));
        assertThat(manifest.entrypoint()).isEqualTo(ENTRYPOINT);
        assertThat(manifest.subscriptions()).hasSize(1);
        assertThat(manifest.subscriptions().get(0).eventType()).isEqualTo("platform:admin:eventtypes:synced");
        assertThat(manifest.secrets()).containsExactly("EVENTS_DSN");
        assertThat(manifest.db()).hasSize(1);
        assertThat(manifest.db().get(0).name().value()).isEqualTo("events");
        assertThat(manifest.db().get(0).secretRef()).isEqualTo("EVENTS_DSN");
    }

    @Test
    void aDeliveryBecomesARowAndARedeliveryDoesNot() throws Exception {
        LoadedFunction fn = load();
        try {
            FakeFunctionContext ctx = new FakeFunctionContext(API_ADDRESS, 1, Map.of(), Map.of(), new RecordingEvents())
                    .withDataSource(db);
            fn.init(ctx);

            String eventId = "evt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            byte[] envelope = envelope(eventId, "platform.eventtypes.platform");

            Result first = fn.invoke(request("/events/received", envelope), ctx);
            assertThat(first.status()).as("ack").isEqualTo(200);
            assertThat(rowsFor(eventId)).isEqualTo(1);
            assertThat(storedType(eventId)).isEqualTo("platform:admin:eventtypes:synced");
            // jsonb stores a canonical form (own spacing, own key order): assert through JSON, not text.
            var stored = Json.MAPPER.readTree(storedData(eventId));
            assertThat(stored.path("id").asString()).isEqualTo(eventId);
            assertThat(stored.path("subject").asString()).isEqualTo("platform.eventtypes.platform");
            assertThat(stored.path("data").path("updated").asInt()).isEqualTo(72);

            Result again = fn.invoke(request("/events/received", envelope), ctx);
            assertThat(again.status()).as("a redelivery is still acked").isEqualTo(200);
            assertThat(rowsFor(eventId)).as("mutant: no ON CONFLICT — a redelivery makes a second row").isEqualTo(1);

            Result health = fn.invoke(request("/healthz", new byte[0]), ctx);
            assertThat(health.status()).isEqualTo(200);
        } finally {
            fn.close();
        }
    }

    @Test
    void initCreatesTheTableAndIsIdempotent() throws Exception {
        LoadedFunction fn = load();
        try {
            FakeFunctionContext ctx = new FakeFunctionContext(API_ADDRESS, 1, Map.of(), Map.of(), new RecordingEvents())
                    .withDataSource(db);
            fn.init(ctx);
            fn.init(ctx);
            try (Connection c = db.getConnection(); Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("select count(*) from information_schema.tables where table_name = 'received_events'")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        } finally {
            fn.close();
        }
    }

    private static LoadedFunction load() {
        LoadOutcome outcome = new JvmFunctionLoader().load(shrunkJar, ENTRYPOINT, PLATFORM_ADDRESS, 1);
        if (outcome instanceof Refused refused) {
            throw new AssertionError("the shrunk jar did not load: " + refused);
        }
        return ((Loaded) outcome).function();
    }

    private static byte[] envelope(String id, String subject) {
        String json = "{\"id\":\"" + id + "\",\"type\":\"platform:admin:eventtypes:synced\",\"attemptNumber\":1,"
                + "\"subject\":\"" + subject + "\",\"correlationId\":\"corr-1\","
                + "\"data\":{\"applicationCode\":\"platform\",\"created\":0,\"updated\":72,\"deleted\":0}}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static Request request(String path, byte[] body) {
        return new Request(API_ADDRESS, 1, "inv_" + UUID.randomUUID(), body.length == 0 ? "GET" : "POST", path,
                "localhost", path, Map.of(), Map.of(), Map.of(), body, "127.0.0.1", Caller.Platform.INSTANCE);
    }

    private static int rowsFor(String eventId) throws Exception {
        return scalarInt("select count(*) from received_events where event_id = '" + eventId + "'");
    }

    private static String storedType(String eventId) throws Exception {
        return scalarString("select event_type from received_events where event_id = '" + eventId + "'");
    }

    private static String storedData(String eventId) throws Exception {
        return scalarString("select event_data::text from received_events where event_id = '" + eventId + "'");
    }

    private static int scalarInt(String sql) throws Exception {
        try (Connection c = db.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static String scalarString(String sql) throws Exception {
        try (Connection c = db.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
