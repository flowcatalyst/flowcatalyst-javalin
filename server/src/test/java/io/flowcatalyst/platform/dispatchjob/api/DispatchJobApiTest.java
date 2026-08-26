package io.flowcatalyst.platform.dispatchjob.api;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.dispatchjob.DispatchJobFixture;
import io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.Seed;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.platform.shared.auth.ClaimsResolver;
import io.flowcatalyst.platform.shared.auth.JwtVerifier;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.tsid.Tsid;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.RUN;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.code;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.seed;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.seedAttempt;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.seedWriteRow;
import static org.assertj.core.api.Assertions.assertThat;

/// The ten `/api/dispatch-jobs` routes end to end through Javalin (spec §3–7):
/// the two gates, the bare-array list envelopes, SQL-side scoping, the
/// detail / raw / attempts reads with 404 + scope, the facets, the aliases
/// and the requeue write.
@SuppressWarnings("deprecation")
class DispatchJobApiTest {

    private static final String CLIENT_A = "cli_apa" + RUN;
    private static final String CLIENT_B = "cli_apb" + RUN;

    private static final String[] ANCHOR = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "ANCHOR"};
    private static final String[] VIEWER_A = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, CLIENT_A,
            Authenticator.TEST_PERMISSIONS, "platform:messaging:dispatch-job:view"};
    private static final String[] RAW_VIEWER_A = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, CLIENT_A,
            Authenticator.TEST_PERMISSIONS, "platform:messaging:dispatch-job:view-raw"};
    private static final String[] NO_PERMISSION = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, CLIENT_A,
            Authenticator.TEST_PERMISSIONS, "platform:messaging:event-type:view"};

    private static final String CODE = code("api");
    private static final Instant BASE = Instant.now().minusSeconds(120);
    private static TestHttp http;
    private static String jobA;      // tenant A, PENDING
    private static String jobB;      // tenant B, FAILED
    private static String jobP;      // platform-scoped, COMPLETED
    private static String eventId;

    @BeforeAll
    static void start() {
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        var state = new DispatchJobApi.State(new DispatchJobRepository(DispatchJobFixture.DS),
                new UnitOfWork(DispatchJobFixture.DS, new PlatformSink(Json.MAPPER)));
        http = new TestHttp(cfg -> {
            HttpError.install(cfg.routes);
            cfg.routes.before("/api/*", auth);
            DispatchJobApi.register(cfg.routes, state);
        });

        eventId = Tsid.generate();
        jobA = seed(Seed.of(CODE).withClientId(CLIENT_A).withEventId(eventId).withCreatedAt(BASE.plusSeconds(3))
                .withMetadataJson("[{\"key\":\"tenant\",\"value\":\"a\"}]").withPayload("{\"n\":1}"));
        jobB = seed(Seed.of(CODE).withClientId(CLIENT_B).withEventId(eventId).withCreatedAt(BASE.plusSeconds(2)).failed(3, "boom"));
        jobP = seed(Seed.of(CODE).withEventId(eventId).withCreatedAt(BASE.plusSeconds(1)).withStatus("COMPLETED"));
        seedAttempt(jobA, 1, false, 502, "bad gateway", "HTTP_ERROR", BASE.plusSeconds(4));
        seedAttempt(jobA, 2, true, 200, null, null, BASE.plusSeconds(5));
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

    private static List<String> ids(JsonNode array) {
        var out = new ArrayList<String>();
        array.forEach(n -> out.add(n.get("id").asText()));
        return out;
    }

    // ── Gates (spec §3) ────────────────────────────────────────────────────

    @Test
    void listRequiresTheViewPermissionAndRawListsTheRawOne() {
        assertThat(http.get("/api/dispatch-jobs").statusCode()).isEqualTo(403);
        var r = http.get("/api/dispatch-jobs", NO_PERMISSION);
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(json(r).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");
        assertThat(http.get("/api/dispatch-jobs/list-raw", VIEWER_A).statusCode()).isEqualTo(403);
        assertThat(http.get("/api/dispatch-jobs/raw", VIEWER_A).statusCode()).isEqualTo(403);
        assertThat(http.get("/api/dispatch-jobs/" + jobA + "/raw", VIEWER_A).statusCode()).isEqualTo(403);
        assertThat(http.get("/api/dispatch-jobs/list-raw?codes=" + CODE, RAW_VIEWER_A).statusCode()).isEqualTo(200);
        assertThat(http.get("/api/dispatch-jobs/raw?codes=" + CODE, RAW_VIEWER_A).statusCode()).isEqualTo(200);
        assertThat(http.get("/api/dispatch-jobs/" + jobA + "/raw", RAW_VIEWER_A).statusCode()).isEqualTo(200);
        assertThat(http.get("/api/dispatch-jobs/" + jobA, RAW_VIEWER_A).statusCode()).as("raw alone is not view").isEqualTo(403);
    }

    // ── Lists (spec §3–4) ──────────────────────────────────────────────────

    @Test
    void listIsABareArrayOfTheReadShapeNewestFirst() {
        var r = http.get("/api/dispatch-jobs?codes=" + CODE, ANCHOR);
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode body = json(r);
        assertThat(body.isArray()).as("bare array, no envelope").isTrue();
        assertThat(ids(body)).containsExactly(jobA, jobB, jobP);
        JsonNode first = body.get(0);
        assertThat(first.get("code").asText()).isEqualTo(CODE);
        assertThat(first.get("application").asText()).isEqualTo("api" + RUN);
        assertThat(first.get("subdomain").asText()).isEqualTo("orders");
        assertThat(first.get("aggregate").asText()).isEqualTo("order");
        assertThat(first.get("status").asText()).isEqualTo("PENDING");
        assertThat(first.get("kind").asText()).isEqualTo("EVENT");
        assertThat(first.get("mode").asText()).isEqualTo("IMMEDIATE");
        assertThat(first.get("dispatchMode").asText()).isEqualTo("IMMEDIATE");
        assertThat(first.get("eventId").asText()).isEqualTo(eventId);
        assertThat(first.get("clientId").asText()).isEqualTo(CLIENT_A);
        assertThat(first.get("attemptCount").asInt()).isZero();
        assertThat(first.get("createdAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z");
        assertThat(first.has("clientIdentifier")).isFalse();
        assertThat(first.has("priority")).isFalse();
        assertThat(first.has("payload")).isFalse();
    }

    @Test
    void listIsScopedToTheCallersTenantsInSql() {
        assertThat(ids(json(http.get("/api/dispatch-jobs?codes=" + CODE, VIEWER_A)))).containsExactly(jobA, jobP);
        assertThat(ids(json(http.get("/api/dispatch-jobs?codes=" + CODE + "&clientIds=" + CLIENT_B, VIEWER_A))))
                .as("cannot reach across tenants by filter").isEmpty();
    }

    @Test
    void listHonoursSortSizeOffsetAndStatusFilters() {
        assertThat(ids(json(http.get("/api/dispatch-jobs?codes=" + CODE + "&sort=createdAt.asc", ANCHOR))))
                .containsExactly(jobP, jobB, jobA);
        assertThat(ids(json(http.get("/api/dispatch-jobs?codes=" + CODE + "&size=1&offset=1", ANCHOR))))
                .containsExactly(jobB);
        assertThat(ids(json(http.get("/api/dispatch-jobs?codes=" + CODE + "&statuses=FAILED,COMPLETED", ANCHOR))))
                .containsExactly(jobB, jobP);
        assertThat(ids(json(http.get("/api/dispatch-jobs?codes=" + CODE + "&since=garbage", ANCHOR))))
                .as("unparseable since is ignored").hasSize(3);
        var bad = http.get("/api/dispatch-jobs?codes=" + CODE + "&limit=ten&size=1&offset=x", ANCHOR);
        assertThat(bad.statusCode()).isEqualTo(400);
        JsonNode err = json(bad);
        assertThat(err.get("error").asText()).isEqualTo("VALIDATION");
        JsonNode errors = err.get("details").get("errors");
        assertThat(errors).as("every bad parameter, in limit, offset, size order").hasSize(2);
        assertThat(errors.get(0).get("location").asText()).isEqualTo("query.limit");
        assertThat(errors.get(0).get("value").asText()).isEqualTo("ten");
        assertThat(errors.get(1).get("location").asText()).isEqualTo("query.offset");
    }

    @Test
    void byEventListsTheEventsJobsVisibleToTheCallerAndTheAliasMatches() {
        assertThat(ids(json(http.get("/api/dispatch-jobs/event/" + eventId, ANCHOR)))).containsExactly(jobA, jobB, jobP);
        assertThat(ids(json(http.get("/api/dispatch-jobs/by-event/" + eventId, ANCHOR)))).containsExactly(jobA, jobB, jobP);
        assertThat(ids(json(http.get("/api/dispatch-jobs/event/" + eventId, VIEWER_A))))
                .as("platform-scoped job is anchor/super-admin only here").containsExactly(jobA);
        assertThat(json(http.get("/api/dispatch-jobs/event/" + Tsid.generate(), ANCHOR))).isEmpty();
    }

    @Test
    void filterOptionsReturnsTheSixFacets() {
        var r = http.get("/api/dispatch-jobs/filter-options", VIEWER_A);
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode body = json(r);
        for (String facet : List.of("statuses", "codes", "clientIds", "dispatchPoolIds", "subscriptionIds", "kinds")) {
            assertThat(body.get(facet).isArray()).as(facet).isTrue();
        }
        var codes = new ArrayList<String>();
        body.get("codes").forEach(n -> codes.add(n.asText()));
        assertThat(codes).contains(CODE);
    }

    // ── Detail, raw, attempts (spec §3, §7) ────────────────────────────────

    @Test
    void getByIdReturnsTheFullShapeWithEmptyListsOmitted() {
        var r = http.get("/api/dispatch-jobs/" + jobA, VIEWER_A);
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode body = json(r);
        assertThat(body.get("id").asText()).isEqualTo(jobA);
        assertThat(body.get("code").asText()).isEqualTo(CODE);
        assertThat(body.get("payload").asText()).isEqualTo("{\"n\":1}");
        assertThat(body.get("payloadContentType").asText()).isEqualTo("application/json");
        assertThat(body.get("dataOnly").asBoolean()).isTrue();
        assertThat(body.get("protocol").asText()).isEqualTo("HTTP_WEBHOOK");
        assertThat(body.get("retryStrategy").asText()).isEqualTo("exponential");
        assertThat(body.get("sequence").asInt()).isEqualTo(99);
        assertThat(body.get("timeoutSeconds").asInt()).isEqualTo(30);
        assertThat(body.get("maxRetries").asInt()).isEqualTo(3);
        assertThat(body.get("metadata").get(0).get("key").asText()).isEqualTo("tenant");
        assertThat(body.has("attempts")).as("never hydrated").isFalse();
        assertThat(body.has("lastError")).isFalse();

        JsonNode b = json(http.get("/api/dispatch-jobs/" + jobB, ANCHOR));
        assertThat(b.has("metadata")).as("empty list omitted").isFalse();
        assertThat(b.get("lastError").asText()).isEqualTo("boom");
        assertThat(b.get("status").asText()).isEqualTo("FAILED");
        assertThat(b.get("durationMillis").asLong()).isEqualTo(777L);

        assertThat(json(http.get("/api/dispatch-jobs/" + jobA + "/raw", ANCHOR))).as("raw is the same body").isEqualTo(json(http.get("/api/dispatch-jobs/" + jobA, ANCHOR)));
    }

    @Test
    void getByIdIsNotFoundOrForbiddenOutsideTheCallersScope() {
        var missing = http.get("/api/dispatch-jobs/" + Tsid.generate(), ANCHOR);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(json(missing).get("error").asText()).isEqualTo("DispatchJob_NOT_FOUND");

        var other = http.get("/api/dispatch-jobs/" + jobB, VIEWER_A);
        assertThat(other.statusCode()).isEqualTo(403);
        assertThat(json(other).get("error").asText()).isEqualTo("SCOPE_FORBIDDEN");

        assertThat(http.get("/api/dispatch-jobs/" + jobP, VIEWER_A).statusCode()).as("platform-scoped is anchor-only").isEqualTo(403);
        assertThat(http.get("/api/dispatch-jobs/" + jobB + "/attempts", VIEWER_A).statusCode()).isEqualTo(403);
        assertThat(http.get("/api/dispatch-jobs/" + Tsid.generate() + "/attempts", ANCHOR).statusCode()).isEqualTo(404);
    }

    @Test
    void attemptsIsABareArrayOldestFirst() {
        var r = http.get("/api/dispatch-jobs/" + jobA + "/attempts", VIEWER_A);
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode body = json(r);
        assertThat(body.isArray()).isTrue();
        assertThat(body).hasSize(2);
        assertThat(body.get(0).get("attemptNumber").asInt()).isEqualTo(1);
        assertThat(body.get(0).get("success").asBoolean()).isFalse();
        assertThat(body.get(0).get("responseCode").asInt()).isEqualTo(502);
        assertThat(body.get(0).get("errorType").asText()).isEqualTo("HTTP_ERROR");
        assertThat(body.get(0).get("errorMessage").asText()).isEqualTo("bad gateway");
        assertThat(body.get(1).get("success").asBoolean()).isTrue();
        assertThat(body.get(1).has("errorType")).isFalse();
        assertThat(body.get(1).get("responseBody").asText()).isEqualTo("ok");
        assertThat(json(http.get("/api/dispatch-jobs/" + jobB + "/attempts", ANCHOR))).isEmpty();
    }

    // ── Requeue (spec §6) ──────────────────────────────────────────────────

    @Test
    void requeueResetsTheCallersJobsAndReportsTheCount() {
        String own = seedWriteRow(Seed.of(code("apirq")).withClientId(CLIENT_A).failed(3, "x"));
        String other = seedWriteRow(Seed.of(code("apirq")).withClientId(CLIENT_B).failed(3, "x"));

        var r = http.post("/api/dispatch-jobs/requeue",
                "{\"ids\":[\"" + own + "\",\"" + other + "\",\"" + Tsid.generate() + "\"]}", VIEWER_A);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(json(r).get("requeued").asInt()).isEqualTo(1);
        assertThat(json(http.get("/api/dispatch-jobs/" + own, VIEWER_A)).get("status").asText()).isEqualTo("PENDING");
        assertThat(json(http.get("/api/dispatch-jobs/" + other, ANCHOR)).get("status").asText()).isEqualTo("FAILED");

        var anchor = http.post("/api/dispatch-jobs/requeue", "{\"ids\":[\"" + other + "\"]}", ANCHOR);
        assertThat(json(anchor).get("requeued").asInt()).isEqualTo(1);
    }

    @Test
    void requeueRequiresTheViewPermissionAndTheIdsField() {
        assertThat(http.post("/api/dispatch-jobs/requeue", "{\"ids\":[]}", NO_PERMISSION).statusCode()).isEqualTo(403);
        assertThat(json(http.post("/api/dispatch-jobs/requeue", "{\"ids\":[]}", VIEWER_A)).get("requeued").asInt()).isZero();
        var missing = http.post("/api/dispatch-jobs/requeue", "{}", ANCHOR);
        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(json(missing).get("error").asText()).isEqualTo("IDS_REQUIRED");
    }
}
