package io.flowcatalyst.platform.ingest.api;

import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.audit.AuditLogRepository;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.connection.ConnectionRepository;
import io.flowcatalyst.platform.dispatchjob.DispatchJobLifecycle;
import io.flowcatalyst.platform.dispatchjob.processing.DeliverySigningGuard;
import io.flowcatalyst.platform.event.EventRepository;
import io.flowcatalyst.platform.serviceaccount.SigningAccounts;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.platform.shared.auth.ClaimsResolver;
import io.flowcatalyst.platform.shared.auth.JwtVerifier;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.platform.subscription.SubscriptionRepository;
import io.flowcatalyst.sdk.tsid.Tsid;
import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static io.flowcatalyst.db.generated.Tables.APP_APPLICATIONS;
import static io.flowcatalyst.db.generated.Tables.TNT_CLIENTS;
import static org.assertj.core.api.Assertions.assertThat;

/// Owner ruling 2026-10-12 (Go `8bf67d1`, F13): `POST /api/events/batch`
/// unwraps an event `data`, and `POST /api/audit-logs/batch` an
/// `operationData`, that the SDK outbox writers sent as a JSON string holding
/// an encoded object or array, so it is stored as the object; a value sent as
/// itself, and any other string, is stored as sent; the audit redaction runs
/// after the unwrap. The singular `POST /api/events` is unchanged. Driven with
/// the Rust SDK's golden wire fixtures (copied verbatim from
/// `flowcatalyst-go/clients/rust-sdk/tests/fixtures/wire`, Go `544158f`, into
/// `src/test/resources/ingest/rust-sdk-wire`): the bytes the SDK writes into
/// `outbox_messages.payload`, which the outbox processor sends unchanged as one
/// batch item. Before the fix each stored value below was a JSON string.
class EncodedDataIngestTest {

    private static final javax.sql.DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);

    /// An anchor holding everything, for every application: the fixtures'
    /// `orders` application and `acme` client exist in this class's database.
    private static final String[] ANCHOR = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_ALL_APPLICATIONS, "true",
            Authenticator.TEST_PERMISSIONS, "platform:*:*:*"};

    private static String clientId;
    private static TestHttp http;

    @BeforeAll
    static void start() {
        clientId = "cli_" + Tsid.generate().substring(0, 13);
        DB.insertInto(TNT_CLIENTS).set(TNT_CLIENTS.ID, clientId).set(TNT_CLIENTS.NAME, "Acme")
                .set(TNT_CLIENTS.IDENTIFIER, "acme").execute();
        DB.insertInto(APP_APPLICATIONS).set(APP_APPLICATIONS.ID, Tsid.generateWithPrefix("app"))
                .set(APP_APPLICATIONS.CODE, "orders").set(APP_APPLICATIONS.NAME, "Orders").execute();

        var subscriptions = new SubscriptionRepository(DS);
        var state = new IngestApi.State(new EventRepository(DS), new DispatchJobLifecycle(DS), new AuditLogRepository(DS),
                new ClientRepository(DS)::findByIdentifier, new ApplicationRepository(DS)::findByCode, subscriptions::findById,
                new DeliverySigningGuard(subscriptions::findById, new ConnectionRepository(DS)::findById, SigningAccounts.reach(DS)));
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before("/api/*", auth);
            routes.before("/bff/*", auth);
            IngestApi.register(routes, state);
            IngestApi.registerEventsBatchAt(routes, "/bff/events/batch", state);
        });
    }

    @AfterAll
    static void stop() {
        http.close();
    }

    private static String fixture(String name) {
        try (InputStream in = EncodedDataIngestTest.class.getResourceAsStream("/ingest/rust-sdk-wire/" + name)) {
            assertThat(in).as(name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode json(HttpResponse<String> r) {
        return Json.MAPPER.readTree(r.body());
    }

    /// Posts `items` to a batch route; every result must be SUCCESS. Answers the ids.
    private static List<String> ingest(String path, int status, String... items) {
        var r = http.post(path, "{\"items\":[" + String.join(",", items) + "]}", ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        var results = json(r).get("results");
        assertThat(results).hasSize(items.length);
        var ids = new java.util.ArrayList<String>();
        for (JsonNode res : results) {
            assertThat(res.get("status").asString()).as(r.body()).isEqualTo("SUCCESS");
            ids.add(res.get("id").asString());
        }
        return ids;
    }

    /// `[jsonb_typeof, value]` of a stored column.
    private static List<Object> stored(String sql, String id) {
        var row = DB.fetchOne(sql, id);
        assertThat(row).as(id).isNotNull();
        return List.of(row.get(0, String.class), Json.MAPPER.readTree(row.get(1, String.class)));
    }

    private static List<Object> storedEvent(String id) {
        return stored("SELECT jsonb_typeof(data), data::text FROM msg_events WHERE id = ?", id);
    }

    private static List<Object> storedAudit(String id) {
        return stored("SELECT jsonb_typeof(operation_json), operation_json::text FROM aud_logs WHERE id = ?", id);
    }

    private static List<Object> expect(String type, String json) {
        return List.of(type, Json.MAPPER.readTree(json));
    }

    @Test
    void theEventsBatchUnwrapsEncodedDataAndKeepsEverythingElse() {
        // The Rust SDK's full event (the Java/TS/Laravel/framework form): data is an encoded object.
        String full = fixture("event-full.json");
        assertThat(Json.MAPPER.readTree(full).get("data").isString()).as("the fixture carries data encoded").isTrue();
        String id = ingest("/api/events/batch", 201, full).getFirst();
        assertThat(storedEvent(id)).isEqualTo(expect("object", "{\"orderId\":\"ord_1\",\"total\":1500}"));
        assertThat(DB.fetchValue("SELECT client_id FROM msg_events WHERE id = ?", id)).as("clientCode acme resolved").isEqualTo(clientId);

        // The minimal fixture has no source, which the batch requires (refused in its slot).
        var r = http.post("/api/events/batch", "{\"items\":[" + fixture("event-minimal.json") + "]}", ANCHOR);
        assertThat(r.statusCode()).isEqualTo(201);
        assertThat(json(r).get("results").get(0).get("status").asString()).isEqualTo("BAD_REQUEST");
        assertThat(json(r).get("results").get(0).get("error").asString()).isEqualTo("source is required");
        ObjectNode minimal = (ObjectNode) Json.MAPPER.readTree(fixture("event-minimal.json"));
        minimal.put("source", "orders");
        id = ingest("/api/events/batch", 201, minimal.toString()).getFirst();
        assertThat(storedEvent(id)).isEqualTo(expect("object", "{}"));

        String[][] cases = {
                {"{\"orderId\":\"ord_2\"}", "object", "{\"orderId\":\"ord_2\"}"}, // the Go SDK's form: the value itself
                {"\"[{\\\"line\\\":1}]\"", "array", "[{\"line\":1}]"},        // an encoded array
                {"\"[]\"", "array", "[]"},                                    // PHP's json_encode([])
                {"\"hello\"", "string", "\"hello\""},                         // a string meant as a string
                {"\"null\"", "string", "\"null\""},                           // Java's encoding of a null map: kept
                {"\"42\"", "string", "\"42\""},
                {"\"{not json\"", "string", "\"{not json\""},
                {"\"\\\"{\\\\\\\"a\\\\\\\":1}\\\"\"", "string", "\"\\\"{\\\\\\\"a\\\\\\\":1}\\\"\""}, // encoded twice: kept
                {"7", "number", "7"},
        };
        for (String[] c : cases) {
            id = ingest("/api/events/batch", 201,
                    "{\"type\":\"orders:sales:order:created\",\"source\":\"orders\",\"data\":" + c[0] + "}").getFirst();
            assertThat(storedEvent(id)).as(c[0]).isEqualTo(expect(c[1], c[2]));
        }

        // The SPA's /bff/events/batch is the same handler (no duplicated body), so it unwraps too.
        id = ingest("/bff/events/batch", 201,
                "{\"type\":\"orders:sales:order:created\",\"source\":\"orders\",\"data\":\"{\\\"a\\\":1}\"}").getFirst();
        assertThat(storedEvent(id)).isEqualTo(expect("object", "{\"a\":1}"));
    }

    /// The singular POST /api/events receives the value itself and is not
    /// changed: a string data stays a string.
    @Test
    void theSingularCreateStoresAStringDataAsSent() {
        var r = http.post("/api/events",
                "{\"eventType\":\"orders:sales:order:created\",\"source\":\"orders\",\"data\":\"{\\\"a\\\":1}\"}", ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        assertThat(storedEvent(json(r).get("event").get("id").asString())).isEqualTo(expect("string", "\"{\\\"a\\\":1}\""));
    }

    @Test
    void theAuditBatchUnwrapsEncodedOperationDataBeforeRedacting() {
        String full = fixture("audit-log-full.json");
        assertThat(Json.MAPPER.readTree(full).get("operationData").isString())
                .as("the fixture carries operationData encoded").isTrue();
        var ids = ingest("/api/audit-logs/batch", 200,
                full,
                "{\"entityType\":\"Order\",\"entityId\":\"ord_2\",\"operation\":\"PlaceOrder\",\"principalId\":\"prn_x\","
                        + "\"operationData\":\"{\\\"password\\\":\\\"hunter2\\\",\\\"total\\\":7}\"}",
                "{\"entityType\":\"Order\",\"entityId\":\"ord_3\",\"operation\":\"PlaceOrder\",\"principalId\":\"prn_x\","
                        + "\"operationData\":{\"total\":8}}",
                "{\"entityType\":\"Order\",\"entityId\":\"ord_4\",\"operation\":\"PlaceOrder\",\"principalId\":\"prn_x\","
                        + "\"operationData\":\"plain text\"}",
                "{\"entityType\":\"Order\",\"entityId\":\"ord_5\",\"operation\":\"PlaceOrder\",\"principalId\":\"prn_x\","
                        + "\"operationData\":\"null\"}");

        assertThat(storedAudit(ids.get(0))).isEqualTo(expect("object", "{\"apiKey\":\"***\",\"total\":1500}"));
        assertThat(DB.fetchValue("SELECT client_id FROM aud_logs WHERE id = ?", ids.get(0))).as("clientCode acme resolved").isEqualTo(clientId);
        assertThat(storedAudit(ids.get(1))).as("the redaction backstop sees inside an unwrapped value")
                .isEqualTo(expect("object", "{\"password\":\"***\",\"total\":7}"));
        assertThat(storedAudit(ids.get(2))).isEqualTo(expect("object", "{\"total\":8}"));
        assertThat(storedAudit(ids.get(3))).isEqualTo(expect("string", "\"plain text\""));
        assertThat(storedAudit(ids.get(4))).isEqualTo(expect("string", "\"null\""));
    }
}
