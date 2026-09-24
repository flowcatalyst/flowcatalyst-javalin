package io.flowcatalyst.platform.ingest.api;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.audit.AuditLog;
import io.flowcatalyst.platform.audit.AuditLogRepository;
import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.dispatchjob.DispatchJob;
import io.flowcatalyst.platform.dispatchjob.DispatchJobKind;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository;
import io.flowcatalyst.platform.dispatchjob.DispatchJobStatus;
import io.flowcatalyst.platform.dispatchjob.Protocol;
import io.flowcatalyst.platform.dispatchjob.RetryStrategy;
import io.flowcatalyst.platform.event.Event;
import io.flowcatalyst.platform.event.EventRepository;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.platform.shared.auth.ClaimsResolver;
import io.flowcatalyst.platform.shared.auth.JwtVerifier;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.tsid.Tsid;
import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static io.flowcatalyst.db.generated.Tables.APP_APPLICATIONS;
import static io.flowcatalyst.db.generated.Tables.AUD_LOGS;
import static io.flowcatalyst.db.generated.Tables.MSG_DISPATCH_JOBS;
import static io.flowcatalyst.db.generated.Tables.MSG_EVENTS;
import static io.flowcatalyst.db.generated.Tables.TNT_CLIENTS;
import static org.assertj.core.api.Assertions.assertThat;

/// The five `/api/*` ingest routes end to end through Javalin
/// (`docs/spec/sdk-ingest.md` §7): happy paths, the whole-batch rejections,
/// the permission/authentication gates, the defaults table, the duplicate/
/// SKIPPED facts, and memoisation. Every assertion is on an observable
/// effect — a row count, a stored column, a result in a named position —
/// never on "a method was called".
@SuppressWarnings("deprecation") // JsonNode#asText() — see EventApiTest
class IngestApiTest {

    private static final DataSourceHandle DB = new DataSourceHandle();

    private static final String CLIENT_A = "cli_" + DB.run + "0000a";
    private static final String CLIENT_B = "cli_" + DB.run + "0000b";
    private static final String APP_CODE = "app-" + DB.run;
    private static String appId;

    private static final String[] ANCHOR = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:*:*:*"};
    private static final String[] EVENTS_WRITER = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, CLIENT_A,
            Authenticator.TEST_PERMISSIONS, "platform:messaging:batch:events-write"};
    private static final String[] DISPATCH_WRITER = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, CLIENT_A,
            Authenticator.TEST_PERMISSIONS, "platform:messaging:batch:dispatch-jobs-write"};
    private static final String NO_PERMISSION_PRINCIPAL = EntityType.PRINCIPAL.generate();
    private static final String[] NO_PERMISSION = {
            Authenticator.TEST_PRINCIPAL, NO_PERMISSION_PRINCIPAL,
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, CLIENT_A,
            Authenticator.TEST_PERMISSIONS, "platform:messaging:event-type:view"};
    private static final String AUDIT_CALLER_PRINCIPAL = EntityType.PRINCIPAL.generate();
    private static final String[] AUDIT_CALLER = {
            Authenticator.TEST_PRINCIPAL, AUDIT_CALLER_PRINCIPAL,
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, CLIENT_A};

    private static CountingLookup<Client> clientLookup;
    private static CountingLookup<Application> applicationLookup;
    private static EventRepository eventRepo;
    private static DispatchJobRepository dispatchJobRepo;
    private static AuditLogRepository auditLogRepo;
    private static TestHttp http;

    @BeforeAll
    static void start() {
        DB.db.insertInto(TNT_CLIENTS).set(TNT_CLIENTS.ID, CLIENT_A).set(TNT_CLIENTS.NAME, "A").set(TNT_CLIENTS.IDENTIFIER, CLIENT_A).execute();
        DB.db.insertInto(TNT_CLIENTS).set(TNT_CLIENTS.ID, CLIENT_B).set(TNT_CLIENTS.NAME, "B").set(TNT_CLIENTS.IDENTIFIER, CLIENT_B).execute();
        appId = Tsid.generateWithPrefix("app");
        DB.db.insertInto(APP_APPLICATIONS).set(APP_APPLICATIONS.ID, appId).set(APP_APPLICATIONS.CODE, APP_CODE)
                .set(APP_APPLICATIONS.NAME, "App").execute();

        eventRepo = new EventRepository(DB.ds);
        dispatchJobRepo = new DispatchJobRepository(DB.ds);
        auditLogRepo = new AuditLogRepository(DB.ds);
        var clientRepo = new ClientRepository(DB.ds);
        var applicationRepo = new ApplicationRepository(DB.ds);
        clientLookup = new CountingLookup<>(clientRepo::findByIdentifier);
        applicationLookup = new CountingLookup<>(applicationRepo::findByCode);
        var state = new IngestApi.State(eventRepo, dispatchJobRepo, auditLogRepo, clientLookup, applicationLookup);

        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before("/api/*", auth);
            IngestApi.register(routes, state);
        });
    }

    @AfterAll
    static void stop() {
        http.close();
    }

    private static JsonNode json(HttpResponse<String> r) {
        try {
            return Json.MAPPER.readTree(r.body());
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + r.body(), e);
        }
    }

    private static String uniqueType(String tag) {
        return "it." + DB.run + "." + tag + "." + UUID.randomUUID().toString().substring(0, 8).toLowerCase(Locale.ROOT);
    }

    private static int countEventsByType(String type) {
        return DB.db.fetchCount(MSG_EVENTS, MSG_EVENTS.TYPE.eq(type));
    }

    private static int countJobsByCode(String code) {
        return DB.db.fetchCount(MSG_DISPATCH_JOBS, MSG_DISPATCH_JOBS.CODE.eq(code));
    }

    private static int countAuditByEntityType(String entityType) {
        return DB.db.fetchCount(AUD_LOGS, AUD_LOGS.ENTITY_TYPE.eq(entityType));
    }

    // ── Events: singular ─────────────────────────────────────────────────

    @Test
    void singularCreatePersistsAndRespondsWithTheEnvelope() {
        String type = uniqueType("singular");
        var body = """
                {"eventType":"%s","source":"src","subject":"subj","data":{"n":1},
                 "messageGroup":"grp","correlationId":"corr-1","causationId":"cause-1"}
                """.formatted(type);
        var r = http.post("/api/events", body, EVENTS_WRITER);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        var env = json(r);
        var event = env.get("event");
        assertThat(event.get("eventType").asText()).isEqualTo(type);
        assertThat(event.get("specVersion").asText()).isEqualTo("1.0");
        assertThat(event.get("data").get("n").asInt()).isEqualTo(1);
        assertThat(event.get("deduplicationId").asText()).startsWith(type + "-");
        assertThat(event.get("id").asText()).hasSize(13);
        assertThat(env.get("dispatchJobCount").asInt()).isEqualTo(0);
        assertThat(env.get("isDuplicate").asBoolean()).isFalse();

        assertThat(countEventsByType(type)).isEqualTo(1);
        var row = DB.db.selectFrom(MSG_EVENTS).where(MSG_EVENTS.TYPE.eq(type)).fetchOne();
        assertThat(row.getClientId()).as("non-anchor caller, no clientId given: defaults to the caller's first client").isEqualTo(CLIENT_A);
        assertThat(row.getMessageGroup()).isEqualTo("grp");
    }

    @Test
    void singularMissingDataIsRejectedAndWritesNothing() {
        String type = uniqueType("nodatasing");
        var r = http.post("/api/events", """
                {"eventType":"%s","source":"src","data":null}
                """.formatted(type), EVENTS_WRITER);
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json(r).get("error").asText()).isEqualTo("VALIDATION");
        assertThat(countEventsByType(type)).isEqualTo(0);
    }

    @Test
    void singularDefaultsTheCallersFirstClientForANonAnchorButNotForAnAnchor() {
        String typeA = uniqueType("defaultclient");
        var r = http.post("/api/events", """
                {"eventType":"%s","source":"src","data":{}}
                """.formatted(typeA), EVENTS_WRITER);
        assertThat(r.statusCode()).isEqualTo(201);
        assertThat(DB.db.selectFrom(MSG_EVENTS).where(MSG_EVENTS.TYPE.eq(typeA)).fetchOne().getClientId()).isEqualTo(CLIENT_A);

        String typeAnchor = uniqueType("anchornodefault");
        http.post("/api/events", """
                {"eventType":"%s","source":"src","data":{}}
                """.formatted(typeAnchor), ANCHOR);
        assertThat(DB.db.selectFrom(MSG_EVENTS).where(MSG_EVENTS.TYPE.eq(typeAnchor)).fetchOne().getClientId()).isNull();
    }

    @Test
    void singularTenantGuardRejectsAnInaccessibleClientIdAndWritesNothing() {
        String type = uniqueType("tenantsing");
        var r = http.post("/api/events", """
                {"eventType":"%s","source":"src","data":{},"clientId":"%s"}
                """.formatted(type, CLIENT_B), EVENTS_WRITER);
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(json(r).get("error").asText()).isEqualTo("FORBIDDEN");
        assertThat(countEventsByType(type)).isEqualTo(0);
    }

    @Test
    void singularEventPersistsIdenticallyToABatchOfOne() {
        String typeS = uniqueType("parityS");
        String typeB = uniqueType("parityB");
        // ANCHOR on both sides: the singular-only client-default (spec §5 D3) only fires for a
        // non-anchor caller, so using it here would make the two paths persist different
        // clientId values for a reason unrelated to what this test is pinning.
        http.post("/api/events", """
                {"eventType":"%s","source":"src","subject":"subj","data":{"n":9},
                 "messageGroup":"grp","correlationId":"corr","causationId":"cause",
                 "contextData":[{"key":"k","value":"v"}]}
                """.formatted(typeS), ANCHOR);
        http.post("/api/events/batch", """
                {"items":[{"type":"%s","source":"src","subject":"subj","data":{"n":9},
                 "messageGroup":"grp","correlationId":"corr","causationId":"cause",
                 "contextData":[{"key":"k","value":"v"}]}]}
                """.formatted(typeB), ANCHOR);

        var single = DB.db.selectFrom(MSG_EVENTS).where(MSG_EVENTS.TYPE.eq(typeS)).fetchOne();
        var batch = DB.db.selectFrom(MSG_EVENTS).where(MSG_EVENTS.TYPE.eq(typeB)).fetchOne();
        assertThat(single.getSpecVersion()).isEqualTo(batch.getSpecVersion());
        assertThat(single.getSource()).isEqualTo(batch.getSource());
        assertThat(single.getSubject()).isEqualTo(batch.getSubject());
        assertThat(single.getData()).isEqualTo(batch.getData());
        assertThat(single.getMessageGroup()).isEqualTo(batch.getMessageGroup());
        assertThat(single.getCorrelationId()).isEqualTo(batch.getCorrelationId());
        assertThat(single.getCausationId()).isEqualTo(batch.getCausationId());
        assertThat(single.getContextData()).isEqualTo(batch.getContextData());
        assertThat(single.getClientId()).isEqualTo(batch.getClientId());
    }

    // ── Events: batch ────────────────────────────────────────────────────

    @Test
    void batchHappyPathWritesRowsAndReturnsResultsInInputOrder() {
        String t1 = uniqueType("order1");
        String t2 = uniqueType("order2");
        var r = http.post("/api/events/batch", """
                {"items":[{"type":"%s","source":"s","data":{}},{"type":"%s","source":"s","data":{}}]}
                """.formatted(t1, t2), EVENTS_WRITER);
        assertThat(r.statusCode()).isEqualTo(201);
        var results = json(r).get("results");
        assertThat(results).hasSize(2);
        assertThat(results.get(0).get("status").asText()).isEqualTo("SUCCESS");
        assertThat(results.get(1).get("status").asText()).isEqualTo("SUCCESS");
        var row1 = DB.db.selectFrom(MSG_EVENTS).where(MSG_EVENTS.TYPE.eq(t1)).fetchOne();
        var row2 = DB.db.selectFrom(MSG_EVENTS).where(MSG_EVENTS.TYPE.eq(t2)).fetchOne();
        assertThat(results.get(0).get("id").asText()).isEqualTo(row1.getId());
        assertThat(results.get(1).get("id").asText()).isEqualTo(row2.getId());
    }

    @Test
    void batchEmptyItemsRespondsTwoHundredAndOneAndWritesNothing() {
        var r = http.post("/api/events/batch", "{\"items\":[]}", EVENTS_WRITER);
        assertThat(r.statusCode()).as("events: 201 even when empty, spec §2").isEqualTo(201);
        assertThat(json(r).get("results")).isEmpty();
    }

    @Test
    void batchOverTheLimitRejectsAndWritesNothing() {
        String tag = uniqueType("toolarge");
        var items = new StringBuilder("{\"items\":[");
        for (int i = 0; i < 1001; i++) {
            if (i > 0) items.append(',');
            items.append("{\"type\":\"").append(tag).append("\",\"source\":\"s\",\"data\":{}}");
        }
        items.append("]}");
        var r = http.post("/api/events/batch", items.toString(), EVENTS_WRITER);
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json(r).get("error").asText()).isEqualTo("BATCH_TOO_LARGE");
        assertThat(countEventsByType(tag)).isEqualTo(0);
    }

    @Test
    void aValidationFailureOnOneItemReportsItInItsSlotAndTheOthersAreWritten() {
        // Owner ruling 2026-09-06 #10a: partial success with honest per-item results.
        String t1 = uniqueType("wholea");
        String t2 = uniqueType("wholeb");
        String t3 = uniqueType("wholec");
        var r = http.post("/api/events/batch", """
                {"items":[{"type":"%s","source":"s","data":{}},
                           {"type":"%s","source":"s","data":null},
                           {"type":"%s","source":"s","data":{}}]}
                """.formatted(t1, t2, t3), EVENTS_WRITER);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        var results = json(r).get("results");
        assertThat(results).hasSize(3);
        assertThat(results.get(0).get("status").asText()).isEqualTo("SUCCESS");
        assertThat(results.get(1).get("status").asText()).isEqualTo("BAD_REQUEST");
        assertThat(results.get(1).get("error").asText()).isEqualTo("data is required");
        assertThat(results.get(1).get("id").asText()).as("no id was supplied").isEmpty();
        assertThat(results.get(2).get("status").asText()).isEqualTo("SUCCESS");
        assertThat(countEventsByType(t1)).as("the valid items are written").isEqualTo(1);
        assertThat(countEventsByType(t2)).as("the invalid one is not").isEqualTo(0);
        assertThat(countEventsByType(t3)).isEqualTo(1);

        var missing = http.post("/api/events/batch", """
                {"items":[{"id":"evt_MINE","source":"s","data":{}},{"type":"%s","data":{}}]}
                """.formatted(t1), EVENTS_WRITER);
        assertThat(missing.statusCode()).isEqualTo(201);
        assertThat(json(missing).get("results").get(0).get("error").asText()).isEqualTo("type is required");
        assertThat(json(missing).get("results").get(0).get("id").asText()).as("the item's own id is echoed").isEqualTo("evt_MINE");
        assertThat(json(missing).get("results").get(1).get("error").asText()).isEqualTo("source is required");
    }

    @Test
    void aTenantViolationOnOneItemRejectsTheWholeBatchWritingNoneOfTheN() {
        String t1 = uniqueType("tenwholea");
        String t2 = uniqueType("tenwholeb");
        var r = http.post("/api/events/batch", """
                {"items":[{"type":"%s","source":"s","data":{}},
                           {"type":"%s","source":"s","data":{},"clientId":"%s"}]}
                """.formatted(t1, t2, CLIENT_B), EVENTS_WRITER);
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(countEventsByType(t1)).isEqualTo(0);
        assertThat(countEventsByType(t2)).isEqualTo(0);
    }

    @Test
    void anUnknownClientCodeLeavesTheEventUnscopedButStillSucceeds() {
        String type = uniqueType("unknowncode");
        var r = http.post("/api/events/batch", """
                {"items":[{"type":"%s","source":"s","data":{},"clientCode":"no-such-client-code-xyz"}]}
                """.formatted(type), EVENTS_WRITER);
        assertThat(r.statusCode()).isEqualTo(201);
        assertThat(json(r).get("results").get(0).get("status").asText()).isEqualTo("SUCCESS");
        var row = DB.db.selectFrom(MSG_EVENTS).where(MSG_EVENTS.TYPE.eq(type)).fetchOne();
        assertThat(row).isNotNull();
        assertThat(row.getClientId()).as("spec §5 D2: unknown code -> unscoped, not rejected").isNull();
    }

    @Test
    void clientCodeIsMemoisedOncePerDistinctCodePerRequest() {
        int before = clientLookup.calls.get();
        String t1 = uniqueType("memoa");
        String t2 = uniqueType("memob");
        String identifier = CLIENT_A;
        var r = http.post("/api/events/batch", """
                {"items":[{"type":"%s","source":"s","data":{},"clientCode":"%s"},
                           {"type":"%s","source":"s","data":{},"clientCode":"%s"}]}
                """.formatted(t1, identifier, t2, identifier), EVENTS_WRITER);
        assertThat(r.statusCode()).isEqualTo(201);
        assertThat(clientLookup.calls.get() - before).as("one lookup for two items sharing one code").isEqualTo(1);
        assertThat(DB.db.selectFrom(MSG_EVENTS).where(MSG_EVENTS.TYPE.eq(t1)).fetchOne().getClientId()).isEqualTo(CLIENT_A);
        assertThat(DB.db.selectFrom(MSG_EVENTS).where(MSG_EVENTS.TYPE.eq(t2)).fetchOne().getClientId()).isEqualTo(CLIENT_A);
    }

    @Test
    void aRepeatedDeduplicationIdWritesExactlyOneRow() {
        // Exercised at the repository the HTTP layer itself calls, with an explicit shared
        // createdAt: the wire path computes createdAt from the wall clock per item, and two
        // independent Instant.now() calls are not guaranteed to land on the same microsecond
        // that the (deduplication_id, created_at) conflict target compares on — asserting the
        // real SQL semantics this way pins the same fact without a clock-timing race.
        Instant sharedCreatedAt = Instant.now();
        String dedup = "dedup-" + UUID.randomUUID();
        String type = uniqueType("dupe");
        var data = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        var first = new Event(Tsid.generate(), "1.0", type, "s", null, sharedCreatedAt, data, List.of(),
                dedup, null, null, null, null, sharedCreatedAt, null);
        var second = new Event(Tsid.generate(), "1.0", type, "s", null, sharedCreatedAt, data, List.of(),
                dedup, null, null, null, null, sharedCreatedAt, null);
        eventRepo.insertBatch(List.of(first, second));
        assertThat(countEventsByType(type)).as("ON CONFLICT DO NOTHING drops the second row").isEqualTo(1);
        var stored = DB.db.selectFrom(MSG_EVENTS).where(MSG_EVENTS.TYPE.eq(type)).fetchOne();
        assertThat(stored.getId()).isEqualTo(first.id());
    }

    @Test
    void aRepeatedDeduplicationIdStillReportsSuccessOnTheWire() {
        // The insert is all-or-nothing per statement, not per-row-checked (Go's comment:
        // "every persisted job reports SUCCESS"), so the response never distinguishes a
        // dropped duplicate from a fresh row (spec §5 D4) regardless of whether the two
        // items actually land in the same microsecond here.
        String dedup = "dedup-" + UUID.randomUUID();
        String type = uniqueType("dupewire");
        var r = http.post("/api/events/batch", """
                {"items":[{"type":"%s","source":"s","data":{},"deduplicationId":"%s"},
                           {"type":"%s","source":"s","data":{},"deduplicationId":"%s"}]}
                """.formatted(type, dedup, type, dedup), EVENTS_WRITER);
        assertThat(r.statusCode()).isEqualTo(201);
        var results = json(r).get("results");
        assertThat(results.get(0).get("status").asText()).isEqualTo("SUCCESS");
        assertThat(results.get(1).get("status").asText()).isEqualTo("SUCCESS");
    }

    // ── Events: permission gate ──────────────────────────────────────────

    @Test
    void eventsRoutesRequireTheEventsWritePermission() {
        // Schema-valid bodies (CreateEventRequest requires eventType/source/data; BatchRequest
        // requires items): schema validation runs before the handler's own coarse permission
        // check (spec §3), so an empty {} would 400 VALIDATION before ever reaching
        // PERMISSION_REQUIRED / UNAUTHENTICATED — not what this test means to exercise.
        var bodies = Map.of(
                "/api/events", "{\"eventType\":\"x\",\"source\":\"x\",\"data\":{}}",
                "/api/events/batch", "{\"items\":[]}");
        for (String path : List.of("/api/events", "/api/events/batch")) {
            var body = bodies.get(path);
            var denied = http.post(path, body, NO_PERMISSION);
            assertThat(denied.statusCode()).as(path).isEqualTo(403);
            assertThat(json(denied).get("error").asText()).as(path).isEqualTo("PERMISSION_REQUIRED");
            assertThat(json(denied).get("message").asText()).as(path)
                    .isEqualTo("permission required: platform:messaging:batch:events-write");

            var anon = http.post(path, body);
            assertThat(anon.statusCode()).as(path).isEqualTo(403);
            assertThat(json(anon).get("error").asText()).as(path).isEqualTo("UNAUTHENTICATED");
        }
    }

    // ── Dispatch jobs: batch ─────────────────────────────────────────────

    @Test
    void dispatchBatchHappyPathAppliesEveryDefaultAndWritesTheRow() {
        String code = uniqueType("djdefaults");
        var r = http.post("/api/dispatch-jobs/batch", """
                {"items":[{"code":"%s","targetUrl":"https://target.test/hook"}]}
                """.formatted(code), DISPATCH_WRITER);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        assertThat(json(r).get("results").get(0).get("status").asText()).isEqualTo("SUCCESS");

        var row = DB.db.selectFrom(MSG_DISPATCH_JOBS).where(MSG_DISPATCH_JOBS.CODE.eq(code)).fetchOne();
        assertThat(row.getId()).hasSize(13);
        assertThat(row.getKind()).isEqualTo(DispatchJobKind.EVENT.name());
        assertThat(row.getMode()).isEqualTo(DispatchMode.NEXT_ON_ERROR.name());
        assertThat(row.getPayloadContentType()).isEqualTo("application/json");
        assertThat(row.getSequence()).isEqualTo(99);
        assertThat(row.getTimeoutSeconds()).isEqualTo(30);
        assertThat(row.getMaxRetries()).isEqualTo(3);
        assertThat(row.getProtocol()).isEqualTo(Protocol.HTTP_WEBHOOK.name());
        assertThat(row.getRetryStrategy()).isEqualTo(RetryStrategy.EXPONENTIAL.wire());
        assertThat(row.getStatus()).isEqualTo(DispatchJobStatus.PENDING.name());
    }

    @Test
    void dispatchBatchInvalidKindRejectsTheWholeBatchWritingNothing() {
        String code = uniqueType("badkind");
        var r = http.post("/api/dispatch-jobs/batch", """
                {"items":[{"kind":"NOT_A_KIND","code":"%s","targetUrl":"https://target.test/hook"}]}
                """.formatted(code), DISPATCH_WRITER);
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json(r).get("error").asText()).isEqualTo("INVALID_KIND");
        assertThat(countJobsByCode(code)).isEqualTo(0);
    }

    @Test
    void dispatchBatchOverTheLimitRejectsAndWritesNothing() {
        String tag = uniqueType("djtoolarge");
        var items = new StringBuilder("{\"items\":[");
        for (int i = 0; i < 1001; i++) {
            if (i > 0) items.append(',');
            items.append("{\"code\":\"").append(tag).append("\",\"targetUrl\":\"https://target.test/hook\"}");
        }
        items.append("]}");
        var r = http.post("/api/dispatch-jobs/batch", items.toString(), DISPATCH_WRITER);
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json(r).get("error").asText()).isEqualTo("BATCH_TOO_LARGE");
        assertThat(countJobsByCode(tag)).isEqualTo(0);
    }

    @Test
    void dispatchBatchEmptyItemsRespondsTwoHundredAndWritesNothing() {
        var r = http.post("/api/dispatch-jobs/batch", "{\"items\":[]}", DISPATCH_WRITER);
        assertThat(r.statusCode()).as("dispatch jobs: 200 when empty, unlike events").isEqualTo(200);
        assertThat(json(r).get("results")).isEmpty();
    }

    @Test
    void dispatchBatchTenantViolationRejectsTheWholeBatchWritingNothing() {
        String c1 = uniqueType("djtenwholea");
        String c2 = uniqueType("djtenwholeb");
        var r = http.post("/api/dispatch-jobs/batch", """
                {"items":[{"code":"%s","targetUrl":"https://target.test/hook"},
                           {"code":"%s","targetUrl":"https://target.test/hook","clientId":"%s"}]}
                """.formatted(c1, c2, CLIENT_B), DISPATCH_WRITER);
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(countJobsByCode(c1)).isEqualTo(0);
        assertThat(countJobsByCode(c2)).isEqualTo(0);
    }

    @Test
    void aRepeatedSuppliedDispatchJobIdWritesExactlyOneRow() {
        // Deterministic repository-level check — see the events dedup test for why.
        Instant sharedCreatedAt = Instant.now();
        String id = Tsid.generate();
        String code = uniqueType("djdupe");
        var first = job(id, code, sharedCreatedAt);
        var second = job(id, code, sharedCreatedAt); // same id + createdAt: the conflict target
        dispatchJobRepo.insertBatch(List.of(first, second));
        assertThat(countJobsByCode(code)).as("ON CONFLICT (id, created_at) DO NOTHING drops the second row").isEqualTo(1);
    }

    @Test
    void aRepeatedSuppliedDispatchJobIdStillReportsSuccessOnTheWire() {
        String id = Tsid.generate();
        String code = uniqueType("djdupewire");
        var r = http.post("/api/dispatch-jobs/batch", """
                {"items":[{"id":"%s","code":"%s","targetUrl":"https://target.test/hook"},
                           {"id":"%s","code":"%s","targetUrl":"https://target.test/hook"}]}
                """.formatted(id, code, id, code), DISPATCH_WRITER);
        assertThat(r.statusCode()).isEqualTo(201);
        var results = json(r).get("results");
        assertThat(results.get(0).get("status").asText()).isEqualTo("SUCCESS");
        assertThat(results.get(1).get("status").asText()).isEqualTo("SUCCESS");
        assertThat(results.get(0).get("id").asText()).isEqualTo(id);
        assertThat(results.get(1).get("id").asText()).isEqualTo(id);
    }

    private static DispatchJob job(String id, String code, Instant createdAt) {
        return new DispatchJob(id, null, DispatchJobKind.EVENT, code, null, null, "https://target.test/hook",
                Protocol.HTTP_WEBHOOK, null, "application/json", false, null, null, null, null, null, null,
                null, DispatchMode.NEXT_ON_ERROR, 99, 30, null, 3, RetryStrategy.EXPONENTIAL,
                DispatchJobStatus.PENDING, 0, null, List.of(), null, null, null, createdAt, createdAt, null, null, null, null, null);
    }

    // ── Dispatch jobs: singular ──────────────────────────────────────────

    @Test
    void singularDispatchJobPersistsIdenticallyToABatchOfOne() {
        String codeS = uniqueType("djparityS");
        String codeB = uniqueType("djparityB");
        String itemFields = """
                "kind":"EVENT","source":"src","subject":"subj","targetUrl":"https://target.test/hook",
                "payload":"{\\"k\\":\\"v\\"}","serviceAccountId":"sa1","eventId":"evt123","correlationId":"corr",
                "subscriptionId":"sub123","messageGroup":"mg-1"
                """;
        http.post("/api/dispatch-jobs", "{\"code\":\"" + codeS + "\"," + itemFields + "}", DISPATCH_WRITER);
        http.post("/api/dispatch-jobs/batch", "{\"items\":[{\"code\":\"" + codeB + "\"," + itemFields + "}]}", DISPATCH_WRITER);

        var single = DB.db.selectFrom(MSG_DISPATCH_JOBS).where(MSG_DISPATCH_JOBS.CODE.eq(codeS)).fetchOne();
        var batch = DB.db.selectFrom(MSG_DISPATCH_JOBS).where(MSG_DISPATCH_JOBS.CODE.eq(codeB)).fetchOne();
        assertThat(single.getKind()).isEqualTo(batch.getKind());
        assertThat(single.getSource()).isEqualTo(batch.getSource());
        assertThat(single.getSubject()).isEqualTo(batch.getSubject());
        assertThat(single.getTargetUrl()).isEqualTo(batch.getTargetUrl());
        assertThat(single.getPayload()).isEqualTo(batch.getPayload());
        assertThat(single.getEventId()).isEqualTo(batch.getEventId());
        assertThat(single.getCorrelationId()).isEqualTo(batch.getCorrelationId());
        assertThat(single.getSubscriptionId()).isEqualTo(batch.getSubscriptionId());
        assertThat(single.getMessageGroup()).isEqualTo(batch.getMessageGroup());
        assertThat(single.getSequence()).isEqualTo(batch.getSequence()).isEqualTo(99);
        assertThat(single.getTimeoutSeconds()).isEqualTo(batch.getTimeoutSeconds()).isEqualTo(30);
        assertThat(single.getMaxRetries()).isEqualTo(batch.getMaxRetries()).isEqualTo(3);
        assertThat(single.getPayloadContentType()).isEqualTo(batch.getPayloadContentType()).isEqualTo("application/json");
        assertThat(single.getProtocol()).isEqualTo(batch.getProtocol()).isEqualTo(Protocol.HTTP_WEBHOOK.name());
        assertThat(single.getStatus()).isEqualTo(batch.getStatus()).isEqualTo(DispatchJobStatus.PENDING.name());
    }

    @Test
    void singularDispatchJobRequiredFieldsAreValidated() {
        record Case(String body, String missing) {
        }
        var cases = List.of(
                new Case("{\"targetUrl\":\"https://t\",\"payload\":\"{}\",\"serviceAccountId\":\"sa\"}", "code"),
                new Case("{\"code\":\"c\",\"payload\":\"{}\",\"serviceAccountId\":\"sa\"}", "targetUrl"),
                new Case("{\"code\":\"c\",\"targetUrl\":\"https://t\",\"serviceAccountId\":\"sa\"}", "payload"),
                new Case("{\"code\":\"c\",\"targetUrl\":\"https://t\",\"payload\":\"{}\"}", "serviceAccountId"));
        for (var c : cases) {
            var r = http.post("/api/dispatch-jobs", c.body(), DISPATCH_WRITER);
            assertThat(r.statusCode()).as(c.missing()).isEqualTo(400);
            assertThat(json(r).get("error").asText()).as(c.missing()).isEqualTo("VALIDATION");
            assertThat(json(r).get("message").asText()).as(c.missing()).contains(c.missing());
        }
    }

    @Test
    void singularDispatchJobInvalidRetryStrategyIsRejected() {
        String code = uniqueType("djbadretry");
        var r = http.post("/api/dispatch-jobs", """
                {"code":"%s","targetUrl":"https://t","payload":"{}","serviceAccountId":"sa","retryStrategy":"NOT_A_STRATEGY"}
                """.formatted(code), DISPATCH_WRITER);
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json(r).get("error").asText()).isEqualTo("INVALID_RETRY_STRATEGY");
        assertThat(countJobsByCode(code)).isEqualTo(0);
    }

    @Test
    void singularOnlyFieldsPersistIncludingAnExplicitZeroSequenceOverride() {
        String code = uniqueType("djsingularonly");
        var r = http.post("/api/dispatch-jobs", """
                {"code":"%s","targetUrl":"https://t","payload":"{}","serviceAccountId":"sa",
                 "retryStrategy":"FIXED_DELAY","idempotencyKey":"idem-1","metadata":{"b":"2","a":"1"},"sequence":0}
                """.formatted(code), DISPATCH_WRITER);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        var row = DB.db.selectFrom(MSG_DISPATCH_JOBS).where(MSG_DISPATCH_JOBS.CODE.eq(code)).fetchOne();
        assertThat(row.getRetryStrategy()).isEqualTo(RetryStrategy.FIXED.wire());
        assertThat(row.getIdempotencyKey()).isEqualTo("idem-1");
        assertThat(row.getSequence()).as("explicit 0 must win over the 0->99 default").isEqualTo(0);
        var metadata = Json.MAPPER.readTree(row.getMetadata().data());
        assertThat(metadata.get(0).get("key").asText()).isEqualTo("a");
        assertThat(metadata.get(0).get("value").asText()).isEqualTo("1");
        assertThat(metadata.get(1).get("key").asText()).isEqualTo("b");
        assertThat(metadata.get(1).get("value").asText()).isEqualTo("2");
    }

    // ── Dispatch jobs: queue priority (dispatch-job-priority spec) ──────

    /// T1 (storage half): `queue: HIGH_PRIORITY` on the singular create is
    /// stored. Mutant: ignore the field on create — this must fail.
    @Test
    void singularDispatchJobQueueStoredWhenRecognised() {
        String code = uniqueType("djqueuehi");
        var r = http.post("/api/dispatch-jobs", """
                {"code":"%s","targetUrl":"https://t","payload":"{}","serviceAccountId":"sa","queue":"HIGH_PRIORITY"}
                """.formatted(code), DISPATCH_WRITER);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        var row = DB.db.selectFrom(MSG_DISPATCH_JOBS).where(MSG_DISPATCH_JOBS.CODE.eq(code)).fetchOne();
        assertThat(row.getQueue()).isEqualTo("HIGH_PRIORITY");
    }

    /// T2: an absent `queue` stores `null`, never a silently-defaulted
    /// `DEFAULT` — "not asked for" must stay distinguishable from "asked for
    /// DEFAULT". Mutant: default the column to `DEFAULT` on create.
    @Test
    void singularDispatchJobQueueAbsentStoresNull() {
        String code = uniqueType("djqueueabsent");
        var r = http.post("/api/dispatch-jobs", """
                {"code":"%s","targetUrl":"https://t","payload":"{}","serviceAccountId":"sa"}
                """.formatted(code), DISPATCH_WRITER);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        var row = DB.db.selectFrom(MSG_DISPATCH_JOBS).where(MSG_DISPATCH_JOBS.CODE.eq(code)).fetchOne();
        assertThat(row.getQueue()).as("an absent queue field must store null, not a defaulted value").isNull();
    }

    /// T3: `queue: "workers-high"` is a 400 naming the field, on both the
    /// singular and batch endpoints, and no job row is written either way.
    /// Mutant: accept any string.
    @Test
    void dispatchJobInvalidQueueRejectedOnBothEndpointsWritingNothing() {
        String singularCode = uniqueType("djqueuebadsingular");
        var r1 = http.post("/api/dispatch-jobs", """
                {"code":"%s","targetUrl":"https://t","payload":"{}","serviceAccountId":"sa","queue":"workers-high"}
                """.formatted(singularCode), DISPATCH_WRITER);
        assertThat(r1.statusCode()).isEqualTo(400);
        assertThat(json(r1).get("error").asText()).isEqualTo("INVALID_QUEUE");
        assertThat(json(r1).get("message").asText()).as("the 400 must name the offending field").contains("queue");
        assertThat(countJobsByCode(singularCode)).isEqualTo(0);

        String batchCode = uniqueType("djqueuebadbatch");
        var r2 = http.post("/api/dispatch-jobs/batch", """
                {"items":[{"code":"%s","targetUrl":"https://t","queue":"workers-high"}]}
                """.formatted(batchCode), DISPATCH_WRITER);
        assertThat(r2.statusCode()).isEqualTo(400);
        assertThat(json(r2).get("error").asText()).isEqualTo("INVALID_QUEUE");
        assertThat(countJobsByCode(batchCode)).as("an invalid queue must not persist a job row on the batch path either")
                .isEqualTo(0);
    }

    /// T4: a batch's per-item `queue` is honoured independently — one item
    /// HIGH_PRIORITY, one absent — not the first item's value applied to
    /// every job. Mutant: read the first item's value for all.
    @Test
    void dispatchBatchPerItemQueueIsHonouredIndependently() {
        String hiCode = uniqueType("djqueueitemhi");
        String absentCode = uniqueType("djqueueitemabsent");
        var r = http.post("/api/dispatch-jobs/batch", """
                {"items":[
                    {"code":"%s","targetUrl":"https://target.test/hook","queue":"HIGH_PRIORITY"},
                    {"code":"%s","targetUrl":"https://target.test/hook"}
                ]}
                """.formatted(hiCode, absentCode), DISPATCH_WRITER);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);

        var hiRow = DB.db.selectFrom(MSG_DISPATCH_JOBS).where(MSG_DISPATCH_JOBS.CODE.eq(hiCode)).fetchOne();
        assertThat(hiRow.getQueue()).as("the first item's HIGH_PRIORITY must be honoured").isEqualTo("HIGH_PRIORITY");

        var absentRow = DB.db.selectFrom(MSG_DISPATCH_JOBS).where(MSG_DISPATCH_JOBS.CODE.eq(absentCode)).fetchOne();
        assertThat(absentRow.getQueue()).as("the second item's absent queue must stay absent, not inherit the first's")
                .isNull();
    }

    @Test
    void dispatchJobRoutesRequireTheDispatchJobsWritePermission() {
        for (String path : List.of("/api/dispatch-jobs", "/api/dispatch-jobs/batch")) {
            var denied = http.post(path, "{}", NO_PERMISSION);
            assertThat(denied.statusCode()).as(path).isEqualTo(403);
            assertThat(json(denied).get("error").asText()).as(path).isEqualTo("PERMISSION_REQUIRED");
            assertThat(json(denied).get("message").asText()).as(path)
                    .isEqualTo("permission required: platform:messaging:batch:dispatch-jobs-write");

            var anon = http.post(path, "{}");
            assertThat(anon.statusCode()).as(path).isEqualTo(403);
            assertThat(json(anon).get("error").asText()).as(path).isEqualTo("UNAUTHENTICATED");
        }
    }

    // ── Audit logs ───────────────────────────────────────────────────────

    @Test
    void auditUnauthenticatedIsFourOhOneNotTheUsualFourOhThree() {
        var r = http.post("/api/audit-logs/batch", "{\"items\":[]}");
        assertThat(r.statusCode()).as("spec §7: audit unauthenticated -> 401").isEqualTo(401);
    }

    @Test
    void anyAuthenticatedPrincipalIsAllowedRegardlessOfPermissions() {
        String entityType = uniqueType("anyauth");
        var r = http.post("/api/audit-logs/batch", """
                {"items":[{"entityType":"%s","entityId":"e1","operation":"CREATE","principalId":"prn_TESTACTOR0000001"}]}
                """.formatted(entityType), AUDIT_CALLER);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(json(r).get("results").get(0).get("status").asText()).isEqualTo("SUCCESS");
    }

    @Test
    void auditBatchOverTheLimitRejectsAndWritesNothing() {
        String tag = uniqueType("audtoolarge");
        var items = new StringBuilder("{\"items\":[");
        for (int i = 0; i < 101; i++) {
            if (i > 0) items.append(',');
            items.append("{\"entityType\":\"").append(tag).append("\",\"entityId\":\"e\",\"operation\":\"CREATE\"}");
        }
        items.append("]}");
        var r = http.post("/api/audit-logs/batch", items.toString(), AUDIT_CALLER);
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json(r).get("error").asText()).isEqualTo("BATCH_TOO_LARGE");
        assertThat(countAuditByEntityType(tag)).isEqualTo(0);
    }

    @Test
    void theThreeSkippedCasesLandAtTheRightPositionsAndAreNotWritten() {
        String et = uniqueType("skipped");
        var r = http.post("/api/audit-logs/batch", """
                {"items":[
                   {"entityType":"%s","entityId":"e1","operation":"CREATE","principalId":"prn_TESTACTOR0000001"},
                   {"entityType":"%s","entityId":"e2","operation":"CREATE","principalId":"prn_TESTACTOR0000001","applicationCode":"no-such-app-code"},
                   {"entityType":"%s","entityId":"e3","operation":"CREATE","principalId":"prn_TESTACTOR0000001","clientCode":"no-such-client-code"},
                   {"entityType":"%s","entityId":"e4","operation":"CREATE","principalId":"prn_TESTACTOR0000001","clientCode":"%s"},
                   {"entityType":"%s","entityId":"e5","operation":"CREATE","principalId":"prn_TESTACTOR0000001"}
                ]}
                """.formatted(et, et, et, et, CLIENT_B, et), AUDIT_CALLER);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        var results = json(r).get("results");
        assertThat(results).hasSize(5);
        assertThat(results.get(0).get("status").asText()).isEqualTo("SUCCESS");
        assertThat(results.get(1).get("status").asText()).isEqualTo("SKIPPED");
        assertThat(results.get(1).get("id").asText()).isEmpty();
        assertThat(results.get(2).get("status").asText()).isEqualTo("SKIPPED");
        assertThat(results.get(2).get("id").asText()).isEmpty();
        assertThat(results.get(3).get("status").asText()).as("resolved client not accessible to the caller").isEqualTo("SKIPPED");
        assertThat(results.get(3).get("id").asText()).isEmpty();
        assertThat(results.get(4).get("status").asText()).isEqualTo("SUCCESS");
        assertThat(countAuditByEntityType(et)).as("only the two SUCCESS items are written").isEqualTo(2);
    }

    @Test
    void applicationAndClientCodesAreEachMemoisedOncePerDistinctCodePerRequest() {
        int appBefore = applicationLookup.calls.get();
        int clientBefore = clientLookup.calls.get();
        String et = uniqueType("audmemo");
        var r = http.post("/api/audit-logs/batch", """
                {"items":[
                   {"entityType":"%s","entityId":"e1","operation":"CREATE","principalId":"prn_TESTACTOR0000001","applicationCode":"%s","clientCode":"%s"},
                   {"entityType":"%s","entityId":"e2","operation":"CREATE","principalId":"prn_TESTACTOR0000001","applicationCode":"%s","clientCode":"%s"}
                ]}
                """.formatted(et, APP_CODE, CLIENT_A, et, APP_CODE, CLIENT_A), AUDIT_CALLER);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(applicationLookup.calls.get() - appBefore).isEqualTo(1);
        assertThat(clientLookup.calls.get() - clientBefore).isEqualTo(1);
        var rows = DB.db.selectFrom(AUD_LOGS).where(AUD_LOGS.ENTITY_TYPE.eq(et)).fetch();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getApplicationId()).isEqualTo(appId);
        assertThat(rows.get(0).getClientId()).isEqualTo(CLIENT_A);
    }

    @Test
    void performedAtParsesRfc3339AndFallsBackToNowOnMalformed() {
        String et = uniqueType("performedat");
        Instant before = Instant.now();
        var r = http.post("/api/audit-logs/batch", """
                {"items":[
                   {"entityType":"%s","entityId":"e1","operation":"CREATE","principalId":"prn_TESTACTOR0000001","performedAt":"2026-01-02T03:04:05Z"},
                   {"entityType":"%s","entityId":"e2","operation":"CREATE","principalId":"prn_TESTACTOR0000001","performedAt":"not-a-timestamp"}
                ]}
                """.formatted(et, et), AUDIT_CALLER);
        Instant after = Instant.now();
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        var rows = DB.db.selectFrom(AUD_LOGS).where(AUD_LOGS.ENTITY_TYPE.eq(et)).orderBy(AUD_LOGS.ENTITY_ID.asc()).fetch();
        assertThat(rows.get(0).getPerformedAt().toInstant()).isEqualTo(Instant.parse("2026-01-02T03:04:05Z"));
        assertThat(rows.get(1).getPerformedAt().toInstant()).isBetween(before, after);
    }

    /// docs/spec/audit-redaction.md test 4 (the ingest backstop): an
    /// SDK-posted audit item's `operationData` is redacted before it is
    /// stored — SDK versions predating the source-side redaction, or apps
    /// writing their own outbox rows, are still covered by the name rule.
    /// Mutant: remove the `AuditRedaction.redact(item.operationData(), ...)`
    /// call in `IngestApi#batchIngestAuditLogs` -> this fails, since the
    /// stored `operation_json` would then contain the literal secret.
    @Test
    void auditIngestBackstopRedactsOperationDataBeforeStoring() {
        String et = uniqueType("redactbackstop");
        var r = http.post("/api/audit-logs/batch", """
                {"items":[
                   {"entityType":"%s","entityId":"e1","operation":"CREATE","principalId":"prn_TESTACTOR0000001",
                    "operationData":{"password":"hunter2","name":"kept"}}
                ]}
                """.formatted(et), AUDIT_CALLER);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(json(r).get("results").get(0).get("status").asText()).isEqualTo("SUCCESS");

        var rows = DB.db.selectFrom(AUD_LOGS).where(AUD_LOGS.ENTITY_TYPE.eq(et)).fetch();
        assertThat(rows).hasSize(1);
        String operationJson = rows.get(0).getOperationJson().data();
        assertThat(operationJson).as("the plaintext password never reaches the row").doesNotContain("hunter2");
        var parsed = Json.MAPPER.readTree(operationJson);
        assertThat(parsed.get("password").asString()).isEqualTo("***");
        assertThat(parsed.get("name").asString()).as("non-secret fields are untouched").isEqualTo("kept");
    }

    @Test
    void anAuditItemWithoutPrincipalIdIsRefusedInItsSlotAndTheRestLands() {
        // Owner ruling 2026-09-06 #10b: never attributed to the caller.
        String et = uniqueType("principalrequired");
        String explicitPrincipal = EntityType.PRINCIPAL.generate();
        var r = http.post("/api/audit-logs/batch", """
                {"items":[
                   {"entityType":"%s","entityId":"e1","operation":"CREATE"},
                   {"entityType":"%s","entityId":"e2","operation":"CREATE","principalId":"%s"},
                   {"entityType":"%s","entityId":"e3","operation":"CREATE","principalId":"   "}
                ]}
                """.formatted(et, et, explicitPrincipal, et), AUDIT_CALLER);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        var results = json(r).get("results");
        assertThat(results.get(0).get("status").asText()).isEqualTo("BAD_REQUEST");
        assertThat(results.get(0).get("error").asText()).isEqualTo("principalId is required");
        assertThat(results.get(0).get("id").asText()).isEmpty();
        assertThat(results.get(1).get("status").asText()).isEqualTo("SUCCESS");
        assertThat(results.get(2).get("status").asText()).as("blank is absent").isEqualTo("BAD_REQUEST");
        var rows = DB.db.selectFrom(AUD_LOGS).where(AUD_LOGS.ENTITY_TYPE.eq(et)).fetch();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getEntityId()).isEqualTo("e2");
        assertThat(rows.getFirst().getPrincipalId()).isEqualTo(explicitPrincipal);
    }

    // ── Test scaffolding ─────────────────────────────────────────────────

    private static final class DataSourceHandle {
        final javax.sql.DataSource ds = TestPg.dataSource();
        final DSLContext db = DSL.using(ds, SQLDialect.POSTGRES);
        final String run = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    }

    /// Wraps a repository lookup to count calls — how [IngestApi.State]'s
    /// per-request memoisation is pinned (spec §3.1, §4.3): the wrapper
    /// counts every call that reaches it, so a passing memoisation test
    /// would fail if the cache stopped being consulted.
    private static final class CountingLookup<T> implements Function<String, Optional<T>> {
        final Function<String, Optional<T>> delegate;
        final AtomicInteger calls = new AtomicInteger();

        CountingLookup(Function<String, Optional<T>> delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<T> apply(String code) {
            calls.incrementAndGet();
            return delegate.apply(code);
        }
    }
}
