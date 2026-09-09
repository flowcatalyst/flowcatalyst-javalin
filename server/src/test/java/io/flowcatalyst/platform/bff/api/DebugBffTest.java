package io.flowcatalyst.platform.bff.api;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.dispatchjob.DispatchJobFixture;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository;
import io.flowcatalyst.platform.event.EventFixture;
import io.flowcatalyst.platform.event.EventRepository;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.platform.shared.auth.ClaimsResolver;
import io.flowcatalyst.platform.shared.auth.JwtVerifier;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.tsid.Tsid;
import org.jooq.JSONB;
import org.jooq.Query;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static io.flowcatalyst.db.generated.Tables.MSG_DISPATCH_JOBS;
import static io.flowcatalyst.db.generated.Tables.MSG_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;

/// The `/bff/debug/*` raw views end to end (missed-port fix): the
/// permission gate, the bare-array/DTO shape (`eventType` naming, `omitempty`
/// parity with Go), newest-first ordering, and the `size` default/clamp.
/// Rows are seeded straight into the write-side tables (`msg_events`,
/// `msg_dispatch_jobs`) — the shape these routes actually read, matching
/// `EventRepositoryTest`/`StreamFixture`'s pattern rather than the read-side
/// fixtures the ordinary list routes use.
///
/// [TestPg]'s isolation rule ("never table-wide counts") is honoured by the
/// size tests below via a self-seeded FLOOR: each seeds ≥120 of its own rows
/// so the response-length assertions hold regardless of what other test
/// classes have added to the shared, never-truncated database — the ambient
/// rows can only ever push counts up, never below this class's own floor.
@SuppressWarnings("deprecation")
class DebugBffTest {

    private static final String[] ANCHOR = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "ANCHOR"};
    private static final String[] EVENT_RAW_VIEWER = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_PERMISSIONS, "platform:messaging:event:view-raw"};
    private static final String[] DISPATCH_RAW_VIEWER = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_PERMISSIONS, "platform:messaging:dispatch-job:view-raw"};
    private static final String[] NO_RAW_PERMISSION = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_PERMISSIONS, "platform:messaging:event:view,platform:messaging:dispatch-job:view"};

    private static final Instant NOW = Instant.now();
    private static TestHttp http;

    // Shape/ordering pair (events): fullEvent is newest, minimalEvent is oldest.
    private static String fullEvent;
    private static String minimalEvent;
    private static final String EVENT_TYPE = "appdbg" + EventFixture.RUN + ":orders:order:created";

    // Shape/ordering pair (dispatch jobs): fullJob is newest, minimalJob is oldest.
    private static String fullJob;
    private static String minimalJob;
    private static final String JOB_CODE = "dbg" + DispatchJobFixture.RUN + ":orders:order:created";
    private static final String PAYLOAD = "héllo wörld 🚀"; // multibyte UTF-8 + a surrogate pair, on purpose

    @BeforeAll
    static void start() {
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        var state = new DebugBff.State(new EventRepository(EventFixture.DS), new DispatchJobRepository(DispatchJobFixture.DS));
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before("/bff/*", auth);
            DebugBff.register(routes, state);
        });

        seedEventShapePair();
        seedJobShapePair();
        seedEventSizeFloor();
        seedJobSizeFloor();
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

    private static JsonNode ok(HttpResponse<String> r) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        var body = json(r);
        assertThat(body.isArray()).as("a bare JSON array").isTrue();
        return body;
    }

    private static JsonNode okObject(HttpResponse<String> r) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        var body = json(r);
        assertThat(body.isObject()).as("a single JSON object, not an array").isTrue();
        return body;
    }

    private static List<String> ids(JsonNode array) {
        return array.valueStream().map(n -> n.get("id").asText()).toList();
    }

    // ── Permission gate ───────────────────────────────────────────────────

    @Test
    void eachRouteRequiresItsOwnRawPermissionIndependently() {
        assertThat(http.get("/bff/debug/events", NO_RAW_PERMISSION).statusCode()).isEqualTo(403);
        assertThat(http.get("/bff/debug/dispatch-jobs", NO_RAW_PERMISSION).statusCode()).isEqualTo(403);

        // event:view-raw grants events but NOT dispatch-jobs
        assertThat(http.get("/bff/debug/events", EVENT_RAW_VIEWER).statusCode()).isEqualTo(200);
        assertThat(http.get("/bff/debug/dispatch-jobs", EVENT_RAW_VIEWER).statusCode()).isEqualTo(403);

        // dispatch-job:view-raw grants dispatch-jobs but NOT events
        assertThat(http.get("/bff/debug/dispatch-jobs", DISPATCH_RAW_VIEWER).statusCode()).isEqualTo(200);
        assertThat(http.get("/bff/debug/events", DISPATCH_RAW_VIEWER).statusCode()).isEqualTo(403);

        assertThat(http.get("/bff/debug/events", ANCHOR).statusCode()).isEqualTo(200);
        assertThat(http.get("/bff/debug/dispatch-jobs", ANCHOR).statusCode()).isEqualTo(200);
    }

    // ── Events: shape, eventType naming, omitempty, ordering ────────────────

    /// Insertion order is deliberately the OPPOSITE of time order (the older,
    /// minimal row is written first): a physical/insertion-order table scan
    /// would then put `minimalEvent` before `fullEvent`, so the
    /// newest-first assertion in [#eventsRouteIsNewestFirst] only passes
    /// because of the repository's `ORDER BY created_at DESC` — not by
    /// accident of insertion order (the trap this class's javadoc warns
    /// about: a test that would pass either way pins nothing).
    private static void seedEventShapePair() {
        // Minimal row: blank subject/deduplicationId (Go's rawFromEntity nils these
        // on "", not just on NULL) and NULL for every other optional column.
        minimalEvent = Tsid.generate();
        EventFixture.DB.insertInto(MSG_EVENTS)
                .set(MSG_EVENTS.ID, minimalEvent)
                .set(MSG_EVENTS.TYPE, EVENT_TYPE)
                .set(MSG_EVENTS.SOURCE, "test://debug")
                .set(MSG_EVENTS.SUBJECT, "")
                .set(MSG_EVENTS.TIME, NOW.minusSeconds(30).atOffset(ZoneOffset.UTC))
                .set(MSG_EVENTS.DEDUPLICATION_ID, "")
                .set(MSG_EVENTS.CREATED_AT, NOW.minusSeconds(30).atOffset(ZoneOffset.UTC))
                .execute();

        fullEvent = Tsid.generate();
        EventFixture.DB.insertInto(MSG_EVENTS)
                .set(MSG_EVENTS.ID, fullEvent)
                .set(MSG_EVENTS.SPEC_VERSION, "1.0")
                .set(MSG_EVENTS.TYPE, EVENT_TYPE)
                .set(MSG_EVENTS.SOURCE, "test://debug")
                .set(MSG_EVENTS.SUBJECT, "platform.order." + fullEvent)
                .set(MSG_EVENTS.TIME, NOW.atOffset(ZoneOffset.UTC))
                .set(MSG_EVENTS.DATA, JSONB.jsonb("{\"n\":1}"))
                .set(MSG_EVENTS.CORRELATION_ID, "corr-" + fullEvent)
                .set(MSG_EVENTS.CAUSATION_ID, "caus-" + fullEvent)
                .set(MSG_EVENTS.DEDUPLICATION_ID, EVENT_TYPE + "-" + fullEvent)
                .set(MSG_EVENTS.MESSAGE_GROUP, "grp-" + fullEvent)
                .set(MSG_EVENTS.CLIENT_ID, "cli_" + EventFixture.RUN + "0debug1")
                .set(MSG_EVENTS.CONTEXT_DATA, JSONB.jsonb("[{\"key\":\"principalId\",\"value\":\"prn_x\"}]"))
                .set(MSG_EVENTS.CREATED_AT, NOW.atOffset(ZoneOffset.UTC))
                .execute();
    }

    @Test
    void fullEventRowHasEveryFieldNamedExactlyLikeGo() {
        var body = ok(http.get("/bff/debug/events?size=1000", ANCHOR));
        var row = find(body, fullEvent);
        assertThat(row.propertyNames()).containsExactly(
                "id", "specVersion", "eventType", "source", "subject", "time", "data",
                "messageGroup", "correlationId", "causationId", "deduplicationId", "contextData", "clientId");
        assertThat(row.get("eventType").asText()).isEqualTo(EVENT_TYPE);
        assertThat(row.has("type")).as("Go names the column eventType, never type").isFalse();
        assertThat(row.get("deduplicationId").asText()).isEqualTo(EVENT_TYPE + "-" + fullEvent);
        assertThat(row.get("contextData")).hasSize(1);
        assertThat(row.get("contextData").get(0).get("key").asText()).isEqualTo("principalId");
        assertThat(row.get("clientId").asText()).isEqualTo("cli_" + EventFixture.RUN + "0debug1");
    }

    @Test
    void minimalEventRowOmitsBlankSubjectAndDeduplicationIdAndEveryNullOptional() {
        var body = ok(http.get("/bff/debug/events?size=1000", ANCHOR));
        var row = find(body, minimalEvent);
        assertThat(row.propertyNames()).as("blank subject/dedup and every NULL optional are omitted, not emitted empty/null")
                .containsExactly("id", "specVersion", "eventType", "source", "time");
    }

    @Test
    void eventsRouteIsNewestFirst() {
        var body = ok(http.get("/bff/debug/events?size=1000", ANCHOR));
        var order = ids(body);
        assertThat(order.indexOf(fullEvent)).as("fullEvent (newest) must precede minimalEvent (30s older)")
                .isGreaterThanOrEqualTo(0).isLessThan(order.indexOf(minimalEvent));
    }

    // ── Event detail: same permission, same mapper, 404, no tenant scoping ─

    /// Pins the 200 shape end to end: not just "some JSON came back" but the
    /// exact fields the list already proves, at the detail route.
    @Test
    void eventDetailReturnsTheFullShapeById() {
        var row = okObject(http.get("/bff/debug/events/" + fullEvent, ANCHOR));
        assertThat(row.propertyNames()).containsExactly(
                "id", "specVersion", "eventType", "source", "subject", "time", "data",
                "messageGroup", "correlationId", "causationId", "deduplicationId", "contextData", "clientId");
        assertThat(row.get("id").asText()).isEqualTo(fullEvent);
        assertThat(row.get("eventType").asText()).isEqualTo(EVENT_TYPE);
    }

    /// Would fail if the detail handler read the projected table, dropped a
    /// field the list mapper keeps, or mapped a different entity by mistake
    /// — a weaker "the id matches" assertion would miss all three.
    @Test
    void eventDetailIsByteIdenticalToTheSameRowInTheList() {
        var listRow = find(ok(http.get("/bff/debug/events?size=1000", ANCHOR)), fullEvent);
        var detailRow = okObject(http.get("/bff/debug/events/" + fullEvent, ANCHOR));
        assertThat(detailRow).isEqualTo(listRow);
    }

    /// 404, not an empty/omitted body — pins that an unknown id is a real
    /// miss, not a silently-empty success.
    @Test
    void eventDetailIsNotFoundForAnUnknownId() {
        String missing = Tsid.generate();
        var r = http.get("/bff/debug/events/" + missing, ANCHOR);
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(r.body()).isEqualTo("{\"error\":\"Event_NOT_FOUND\",\"message\":\"Event not found: " + missing + "\"}\n");
    }

    /// Same gate as the list (`event:view-raw`), not the regular `event:view`
    /// — a principal with only `event:view` must still be forbidden here.
    @Test
    void eventDetailRequiresTheRawPermissionNotTheRegularOne() {
        assertThat(http.get("/bff/debug/events/" + fullEvent, NO_RAW_PERMISSION).statusCode()).isEqualTo(403);
        assertThat(http.get("/bff/debug/events/" + fullEvent, EVENT_RAW_VIEWER).statusCode()).isEqualTo(200);
    }

    /// No tenant scoping (deliberate, see [DebugBff] javadoc): a principal
    /// scoped to a different client still reads a row belonging to another
    /// client, exactly as the list already does.
    @Test
    void eventDetailIsNotClientScoped() {
        String[] otherClientViewer = {
                Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
                Authenticator.TEST_SCOPE, "cli_" + EventFixture.RUN + "9other9",
                Authenticator.TEST_PERMISSIONS, "platform:messaging:event:view-raw"};
        var row = okObject(http.get("/bff/debug/events/" + fullEvent, otherClientViewer));
        assertThat(row.get("id").asText()).isEqualTo(fullEvent);
        assertThat(row.get("clientId").asText()).isEqualTo("cli_" + EventFixture.RUN + "0debug1");
    }

    // ── Dispatch jobs: shape, payloadLength (UTF-8 bytes), attemptHistoryCount, ordering ──

    /// Insertion order is deliberately the OPPOSITE of time order, same
    /// reasoning as [#seedEventShapePair].
    private static void seedJobShapePair() {
        minimalJob = Tsid.generate();
        DispatchJobFixture.DB.insertInto(MSG_DISPATCH_JOBS)
                .set(MSG_DISPATCH_JOBS.ID, minimalJob)
                .set(MSG_DISPATCH_JOBS.CODE, JOB_CODE)
                .set(MSG_DISPATCH_JOBS.TARGET_URL, "https://hook.example/" + minimalJob)
                .set(MSG_DISPATCH_JOBS.CREATED_AT, NOW.minusSeconds(30).atOffset(ZoneOffset.UTC))
                .set(MSG_DISPATCH_JOBS.UPDATED_AT, NOW.minusSeconds(30).atOffset(ZoneOffset.UTC))
                .execute();

        fullJob = Tsid.generate();
        DispatchJobFixture.DB.insertInto(MSG_DISPATCH_JOBS)
                .set(MSG_DISPATCH_JOBS.ID, fullJob)
                .set(MSG_DISPATCH_JOBS.EXTERNAL_ID, "ext-" + fullJob)
                .set(MSG_DISPATCH_JOBS.SOURCE, "src-" + fullJob)
                .set(MSG_DISPATCH_JOBS.KIND, "EVENT")
                .set(MSG_DISPATCH_JOBS.CODE, JOB_CODE)
                .set(MSG_DISPATCH_JOBS.SUBJECT, "subj-" + fullJob)
                .set(MSG_DISPATCH_JOBS.EVENT_ID, Tsid.generate())
                .set(MSG_DISPATCH_JOBS.CORRELATION_ID, "corr-" + fullJob)
                .set(MSG_DISPATCH_JOBS.TARGET_URL, "https://hook.example/" + fullJob)
                .set(MSG_DISPATCH_JOBS.CLIENT_ID, "cli_" + DispatchJobFixture.RUN + "0debug1")
                .set(MSG_DISPATCH_JOBS.SUBSCRIPTION_ID, Tsid.generate())
                .set(MSG_DISPATCH_JOBS.SERVICE_ACCOUNT_ID, Tsid.generate())
                .set(MSG_DISPATCH_JOBS.DISPATCH_POOL_ID, Tsid.generate())
                .set(MSG_DISPATCH_JOBS.MESSAGE_GROUP, "grp-" + fullJob)
                .set(MSG_DISPATCH_JOBS.MODE, "IMMEDIATE")
                .set(MSG_DISPATCH_JOBS.SEQUENCE, 3)
                .set(MSG_DISPATCH_JOBS.STATUS, "FAILED")
                .set(MSG_DISPATCH_JOBS.ATTEMPT_COUNT, 2)
                .set(MSG_DISPATCH_JOBS.MAX_RETRIES, 5)
                .set(MSG_DISPATCH_JOBS.LAST_ERROR, "boom")
                .set(MSG_DISPATCH_JOBS.TIMEOUT_SECONDS, 45)
                .set(MSG_DISPATCH_JOBS.RETRY_STRATEGY, "fixed")
                .set(MSG_DISPATCH_JOBS.IDEMPOTENCY_KEY, "idem-" + fullJob)
                .set(MSG_DISPATCH_JOBS.CREATED_AT, NOW.atOffset(ZoneOffset.UTC))
                .set(MSG_DISPATCH_JOBS.UPDATED_AT, NOW.atOffset(ZoneOffset.UTC))
                .set(MSG_DISPATCH_JOBS.SCHEDULED_FOR, NOW.plusSeconds(60).atOffset(ZoneOffset.UTC))
                .set(MSG_DISPATCH_JOBS.COMPLETED_AT, NOW.atOffset(ZoneOffset.UTC))
                .set(MSG_DISPATCH_JOBS.PAYLOAD_CONTENT_TYPE, "application/json")
                .set(MSG_DISPATCH_JOBS.PAYLOAD, PAYLOAD)
                .execute();
        // An attempt DOES exist for this job — attemptHistoryCount must still read 0
        // (Go never hydrates Attempts from a repository read; neither does this port).
        DispatchJobFixture.seedAttempt(fullJob, 1, false, 500, "boom", "HTTP_ERROR", NOW.minusSeconds(5));
    }

    @Test
    void fullJobRowHasEveryFieldAndPayloadLengthIsUtf8Bytes() {
        var body = ok(http.get("/bff/debug/dispatch-jobs?size=1000", ANCHOR));
        var row = find(body, fullJob);
        assertThat(row.propertyNames()).containsExactly(
                "id", "externalId", "source", "kind", "code", "subject", "eventId", "correlationId", "targetUrl",
                "protocol", "clientId", "subscriptionId", "serviceAccountId", "dispatchPoolId", "messageGroup",
                "mode", "sequence", "status", "attemptCount", "maxRetries", "lastError", "timeoutSeconds",
                "retryStrategy", "idempotencyKey", "createdAt", "updatedAt", "scheduledFor", "completedAt",
                "payloadContentType", "payloadLength", "attemptHistoryCount");

        int utf8Bytes = PAYLOAD.getBytes(StandardCharsets.UTF_8).length;
        assertThat(utf8Bytes).as("the payload must actually exercise the byte-vs-char distinction")
                .isNotEqualTo(PAYLOAD.length());
        assertThat(row.get("payloadLength").asInt()).isEqualTo(utf8Bytes);

        assertThat(row.get("attemptHistoryCount").asInt())
                .as("Go's rawFromEntity: len(j.Attempts), and Attempts is never hydrated by any repository read")
                .isZero();
        assertThat(row.get("status").asText()).isEqualTo("FAILED");
        assertThat(row.get("retryStrategy").asText()).isEqualTo("fixed");
    }

    @Test
    void minimalJobRowOmitsEveryNullOptional() {
        var body = ok(http.get("/bff/debug/dispatch-jobs?size=1000", ANCHOR));
        var row = find(body, minimalJob);
        assertThat(row.propertyNames()).containsExactly(
                "id", "kind", "code", "targetUrl", "protocol", "mode", "sequence", "status", "attemptCount",
                "maxRetries", "timeoutSeconds", "retryStrategy", "createdAt", "updatedAt", "payloadContentType",
                "payloadLength", "attemptHistoryCount");
        assertThat(row.get("payloadLength").asInt()).isZero();
        assertThat(row.get("attemptHistoryCount").asInt()).isZero();
    }

    @Test
    void dispatchJobsRouteIsNewestFirst() {
        var body = ok(http.get("/bff/debug/dispatch-jobs?size=1000", ANCHOR));
        var order = ids(body);
        assertThat(order.indexOf(fullJob)).as("fullJob (newest) must precede minimalJob (30s older)")
                .isGreaterThanOrEqualTo(0).isLessThan(order.indexOf(minimalJob));
    }

    // ── size: absent → 50, honoured, out-of-range → the 100 clamp ──────────

    /// A self-seeded floor of 120 rows guarantees the response-length
    /// assertions below hold no matter what other test classes have added to
    /// the shared, never-truncated [io.flowcatalyst.testpg.TestPg] database
    /// (ambient rows can only push the true total up, never below 120).
    private static final int SIZE_FLOOR = 120;

    private static void seedEventSizeFloor() {
        List<Query> batch = new ArrayList<>();
        for (int i = 0; i < SIZE_FLOOR; i++) {
            String id = Tsid.generate();
            batch.add(EventFixture.DB.insertInto(MSG_EVENTS)
                    .set(MSG_EVENTS.ID, id)
                    .set(MSG_EVENTS.TYPE, EVENT_TYPE)
                    .set(MSG_EVENTS.SOURCE, "test://debug-size")
                    .set(MSG_EVENTS.TIME, NOW.minusSeconds(100).plusMillis(i).atOffset(ZoneOffset.UTC))
                    .set(MSG_EVENTS.CREATED_AT, NOW.minusSeconds(100).plusMillis(i).atOffset(ZoneOffset.UTC)));
        }
        EventFixture.DB.batch(batch).execute();
    }

    private static void seedJobSizeFloor() {
        List<Query> batch = new ArrayList<>();
        for (int i = 0; i < SIZE_FLOOR; i++) {
            String id = Tsid.generate();
            batch.add(DispatchJobFixture.DB.insertInto(MSG_DISPATCH_JOBS)
                    .set(MSG_DISPATCH_JOBS.ID, id)
                    .set(MSG_DISPATCH_JOBS.CODE, JOB_CODE)
                    .set(MSG_DISPATCH_JOBS.TARGET_URL, "https://hook.example/" + id)
                    .set(MSG_DISPATCH_JOBS.CREATED_AT, NOW.minusSeconds(100).plusMillis(i).atOffset(ZoneOffset.UTC))
                    .set(MSG_DISPATCH_JOBS.UPDATED_AT, NOW.minusSeconds(100).plusMillis(i).atOffset(ZoneOffset.UTC)));
        }
        DispatchJobFixture.DB.batch(batch).execute();
    }

    @Test
    void eventsSizeIsAbsentFiftyHonouredAndClampedAtOneHundred() {
        assertThat(ok(http.get("/bff/debug/events", ANCHOR))).as("size absent defaults to 50, not the repository's own 100 fallback").hasSize(50);
        assertThat(ok(http.get("/bff/debug/events?size=0", ANCHOR))).as("size<=0 is the same as absent").hasSize(50);
        assertThat(ok(http.get("/bff/debug/events?size=7", ANCHOR))).as("size is honoured when in range").hasSize(7);
        assertThat(ok(http.get("/bff/debug/events?size=5000", ANCHOR)))
                .as("size=5000 clamps to 100, not 1000 (repository guard's own default), despite the doc string's claim of \"max 1000\"")
                .hasSize(100);
    }

    @Test
    void dispatchJobsSizeIsAbsentFiftyHonouredAndClampedAtOneHundred() {
        assertThat(ok(http.get("/bff/debug/dispatch-jobs", ANCHOR))).as("size absent defaults to 50").hasSize(50);
        assertThat(ok(http.get("/bff/debug/dispatch-jobs?size=0", ANCHOR))).as("size<=0 is the same as absent").hasSize(50);
        assertThat(ok(http.get("/bff/debug/dispatch-jobs?size=7", ANCHOR))).as("size is honoured when in range").hasSize(7);
        assertThat(ok(http.get("/bff/debug/dispatch-jobs?size=5000", ANCHOR)))
                .as("size=5000 clamps to 100, not 1000")
                .hasSize(100);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static JsonNode find(JsonNode array, String id) {
        return array.valueStream().filter(n -> n.get("id").asText().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no row with id " + id + " in response"));
    }
}
