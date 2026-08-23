package io.flowcatalyst.platform.scheduledjob.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.flowcatalyst.platform.scheduledjob.InstanceStatus;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobInstanceRepository;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository;
import io.flowcatalyst.platform.scheduledjob.api.ScheduledJobApi.CompleteInstanceRequest;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.platform.shared.auth.ClaimsResolver;
import io.flowcatalyst.platform.shared.auth.JwtVerifier;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// The fifteen `/api/scheduled-jobs` routes end to end through Javalin: the
/// authenticator's test headers, the coarse permission gates, the lockfile
/// status codes and body shapes, the instance projection routes and the
/// error envelope.
class ScheduledJobApiTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final String CLIENT = "cli_" + RUN + "_api";
    private static final String OTHER_CLIENT = "cli_" + RUN + "_apo";

    private static final String ANCHOR_PRINCIPAL = EntityType.PRINCIPAL.generate();
    private static final String[] ANCHOR = {
            Authenticator.TEST_PRINCIPAL, ANCHOR_PRINCIPAL,
            Authenticator.TEST_SCOPE, "ANCHOR"};
    private static final String[] VIEWER = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, CLIENT,
            Authenticator.TEST_PERMISSIONS, "platform:messaging:scheduled-job:view"};
    private static final String[] CLIENT_WRITER = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, CLIENT,
            Authenticator.TEST_PERMISSIONS, "platform:messaging:scheduled-job:view,platform:messaging:scheduled-job:create,platform:messaging:scheduled-job:fire"};

    private static final ScheduledJobApi.State state = new ScheduledJobApi.State(
            new ScheduledJobRepository(TestPg.dataSource()),
            new ScheduledJobInstanceRepository(TestPg.dataSource()),
            new UnitOfWork(TestPg.dataSource(), new PlatformSink(Json.MAPPER)));
    private static TestHttp http;

    @BeforeAll
    static void start() {
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        http = new TestHttp(cfg -> {
            HttpError.install(cfg.routes);
            cfg.routes.before("/api/*", auth);
            ScheduledJobApi.register(cfg.routes, state);
        });
    }

    @AfterAll
    static void stop() {
        http.close();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static JsonNode json(HttpResponse<String> r) {
        try {
            return Json.MAPPER.readTree(r.body());
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + r.body(), e);
        }
    }

    private static String code(String tag) {
        return "sjapi" + RUN + "-" + tag;
    }

    /// Creates a job as anchor; `extraJson` is appended inside the body object.
    private static String create(String code, String extraJson, String... as) {
        var r = http.post("/api/scheduled-jobs",
                "{\"code\":\"" + code + "\",\"name\":\"Job " + code + "\",\"crons\":[\"0 0 * * * *\"],\"concurrent\":false,\"tracksCompletion\":false" + extraJson + "}",
                as.length == 0 ? ANCHOR : as);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        var id = json(r).get("id").asText();
        assertThat(id).startsWith("sjb_");
        return id;
    }

    // ── Happy paths ────────────────────────────────────────────────────────

    @Test
    void createThenReadByIdByCodeAndInList() {
        String code = code("read");
        String id = create(code, ",\"description\":\"desc\",\"timezone\":\"Europe/Amsterdam\",\"payload\":{\"k\":1},\"timeoutSeconds\":30,\"targetUrl\":\"https://t\"");

        var created = http.post("/api/scheduled-jobs", "{\"code\":\"" + code("read2") + "\",\"name\":\"Two\",\"crons\":[\"0 0 * * * *\"],\"concurrent\":true,\"tracksCompletion\":true}", ANCHOR);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).matches("\\{\"id\":\"sjb_[0-9A-Z]{13}\"}\n");

        var get = http.get("/api/scheduled-jobs/" + id, ANCHOR);
        assertThat(get.statusCode()).isEqualTo(200);
        var j = json(get);
        assertThat(j.get("id").asText()).isEqualTo(id);
        assertThat(j.get("code").asText()).isEqualTo(code);
        assertThat(j.get("name").asText()).isEqualTo("Job " + code);
        assertThat(j.get("description").asText()).isEqualTo("desc");
        assertThat(j.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(j.get("crons")).hasSize(1);
        assertThat(j.get("crons").get(0).asText()).isEqualTo("0 0 * * * *");
        assertThat(j.get("timezone").asText()).isEqualTo("Europe/Amsterdam");
        assertThat(j.get("payload").get("k").asInt()).isEqualTo(1);
        assertThat(j.get("concurrent").asBoolean()).isFalse();
        assertThat(j.get("tracksCompletion").asBoolean()).isFalse();
        assertThat(j.get("timeoutSeconds").asInt()).isEqualTo(30);
        assertThat(j.get("deliveryMaxAttempts").asInt()).as("domain default").isEqualTo(3);
        assertThat(j.get("targetUrl").asText()).isEqualTo("https://t");
        assertThat(j.get("version").asInt()).isEqualTo(1);
        assertThat(j.get("hasActiveInstance").asBoolean()).isFalse();
        assertThat(j.get("createdBy").asText()).isEqualTo(ANCHOR_PRINCIPAL);
        assertThat(j.has("clientId")).as("null optionals omitted").isFalse();
        assertThat(j.has("lastFiredAt")).isFalse();
        assertThat(j.has("updatedBy")).isFalse();
        assertThat(j.get("createdAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z");

        var byCode = http.get("/api/scheduled-jobs/by-code/" + code, ANCHOR);
        assertThat(byCode.statusCode()).isEqualTo(200);
        assertThat(json(byCode).get("id").asText()).isEqualTo(id);

        var list = http.get("/api/scheduled-jobs?search=" + code("read") + "&size=1&page=1", ANCHOR);
        assertThat(list.statusCode()).isEqualTo(200);
        var page = json(list);
        assertThat(page.get("data")).hasSize(1);
        assertThat(page.get("page").asInt()).isEqualTo(1);
        assertThat(page.get("size").asInt()).isEqualTo(1);
        assertThat(page.get("total").asLong()).isEqualTo(2);
        assertThat(page.get("total_pages").asInt()).isEqualTo(2);
        assertThat(page.get("data").get(0).get("code").asText()).as("ordered by code").isEqualTo(code("read2"));

        var filtered = http.get("/api/scheduled-jobs?search=" + code("read") + "&status=PAUSED", ANCHOR);
        assertThat(json(filtered).get("total").asLong()).isZero();
    }

    @Test
    void lifecycleUpdatePauseResumeArchiveFireInstancesLogsCompleteDelete() {
        String id = create(code("life"), ",\"tracksCompletion\":true");

        var update = http.put("/api/scheduled-jobs/" + id, "{\"name\":\"Renamed\",\"crons\":[\"0 30 9 * * 1-5\"],\"deliveryMaxAttempts\":7}", ANCHOR);
        assertThat(update.statusCode()).as(update.body()).isEqualTo(204);
        assertThat(update.body()).isEmpty();
        var afterUpdate = json(http.get("/api/scheduled-jobs/" + id, ANCHOR));
        assertThat(afterUpdate.get("name").asText()).isEqualTo("Renamed");
        assertThat(afterUpdate.get("crons").get(0).asText()).isEqualTo("0 30 9 * * 1-5");
        assertThat(afterUpdate.get("deliveryMaxAttempts").asInt()).isEqualTo(7);
        assertThat(afterUpdate.get("tracksCompletion").asBoolean()).as("absent = untouched").isTrue();
        assertThat(afterUpdate.get("version").asInt()).isEqualTo(2);
        assertThat(afterUpdate.get("updatedBy").asText()).isEqualTo(ANCHOR_PRINCIPAL);

        assertThat(http.post("/api/scheduled-jobs/" + id + "/pause", null, ANCHOR).statusCode()).isEqualTo(204);
        assertThat(json(http.get("/api/scheduled-jobs/" + id, ANCHOR)).get("status").asText()).isEqualTo("PAUSED");
        assertThat(http.post("/api/scheduled-jobs/" + id + "/resume", null, ANCHOR).statusCode()).isEqualTo(204);
        assertThat(json(http.get("/api/scheduled-jobs/" + id, ANCHOR)).get("status").asText()).isEqualTo("ACTIVE");

        // Fire (with a body) → 202 {id, scheduledJobId, instanceId}, id == instanceId.
        var fire = http.post("/api/scheduled-jobs/" + id + "/fire", "{\"correlationId\":\"corr-" + RUN + "\"}", ANCHOR);
        assertThat(fire.statusCode()).as(fire.body()).isEqualTo(202);
        var fired = json(fire);
        String instanceId = fired.get("instanceId").asText();
        assertThat(instanceId).startsWith("sji_");
        assertThat(fired.get("id").asText()).isEqualTo(instanceId);
        assertThat(fired.get("scheduledJobId").asText()).isEqualTo(id);
        assertThat(json(http.get("/api/scheduled-jobs/" + id, ANCHOR)).get("hasActiveInstance").asBoolean()).isTrue();

        // Fire without a body works too.
        var bare = http.post("/api/scheduled-jobs/" + id + "/fire", null, ANCHOR);
        assertThat(bare.statusCode()).as(bare.body()).isEqualTo(202);

        var instances = json(http.get("/api/scheduled-jobs/" + id + "/instances?status=QUEUED", ANCHOR));
        assertThat(instances.get("total").asLong()).isEqualTo(2);
        assertThat(instances.get("data").get(0).get("triggerKind").asText()).isEqualTo("MANUAL");
        assertThat(instances.get("data").get(0).get("status").asText()).isEqualTo("QUEUED");

        var inst = http.get("/api/scheduled-jobs/instances/" + instanceId, ANCHOR);
        assertThat(inst.statusCode()).isEqualTo(200);
        var i = json(inst);
        assertThat(i.get("id").asText()).isEqualTo(instanceId);
        assertThat(i.get("scheduledJobId").asText()).isEqualTo(id);
        assertThat(i.get("jobCode").asText()).isEqualTo(code("life"));
        assertThat(i.get("correlationId").asText()).isEqualTo("corr-" + RUN);
        assertThat(i.get("deliveryAttempts").asInt()).isZero();
        assertThat(i.has("scheduledFor")).isFalse();
        assertThat(i.has("completedAt")).isFalse();

        var log = http.post("/api/scheduled-jobs/instances/" + instanceId + "/log", "{\"level\":\"INFO\",\"message\":\"hello\",\"metadata\":{\"a\":1}}", ANCHOR);
        assertThat(log.statusCode()).as(log.body()).isEqualTo(204);
        var logs = http.get("/api/scheduled-jobs/instances/" + instanceId + "/logs", ANCHOR);
        assertThat(logs.statusCode()).isEqualTo(200);
        assertThat(json(logs).isArray()).as("bare array").isTrue();
        assertThat(json(logs)).hasSize(1);
        assertThat(json(logs).get(0).get("level").asText()).isEqualTo("INFO");
        assertThat(json(logs).get(0).get("message").asText()).isEqualTo("hello");
        assertThat(json(logs).get(0).get("metadata").get("a").asInt()).isEqualTo(1);
        assertThat(json(logs).get(0).get("instanceId").asText()).isEqualTo(instanceId);

        // SDK dialect completion.
        var complete = http.post("/api/scheduled-jobs/instances/" + instanceId + "/complete", "{\"status\":\"SUCCESS\",\"result\":{\"rows\":3}}", ANCHOR);
        assertThat(complete.statusCode()).as(complete.body()).isEqualTo(204);
        var completed = json(http.get("/api/scheduled-jobs/instances/" + instanceId, ANCHOR));
        assertThat(completed.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(completed.get("completionStatus").asText()).isEqualTo("SUCCESS");
        assertThat(completed.get("completionResult").get("rows").asInt()).isEqualTo(3);
        assertThat(completed.has("completedAt")).isTrue();

        assertThat(http.post("/api/scheduled-jobs/" + id + "/archive", null, ANCHOR).statusCode()).isEqualTo(204);
        assertThat(json(http.get("/api/scheduled-jobs/" + id, ANCHOR)).get("status").asText()).isEqualTo("ARCHIVED");
        var fireArchived = http.post("/api/scheduled-jobs/" + id + "/fire", null, ANCHOR);
        assertThat(fireArchived.statusCode()).isEqualTo(409);
        assertThat(json(fireArchived).get("error").asText()).isEqualTo("ARCHIVED");

        assertThat(http.delete("/api/scheduled-jobs/" + id, ANCHOR).statusCode()).isEqualTo(204);
        assertThat(http.get("/api/scheduled-jobs/" + id, ANCHOR).statusCode()).isEqualTo(404);
    }

    @ParameterizedTest(name = "[{0}] status={1} completionStatus={2} → {3}/{4}")
    @CsvSource(delimiter = '|', nullValues = "null", textBlock = """
            sdk success              | SUCCESS   | null    | COMPLETED       | SUCCESS
            sdk failure              | FAILURE   | null    | COMPLETED       | FAILURE
            sdk lowercase            | success   | null    | COMPLETED       | SUCCESS
            spa completed + outcome  | COMPLETED | SUCCESS | COMPLETED       | SUCCESS
            spa failed instance      | FAILED    | null    | FAILED          | null
            spa delivery_failed      | DELIVERY_FAILED | null | DELIVERY_FAILED | null
            empty defaults completed | null      | null    | COMPLETED       | null
            explicit completion wins | SUCCESS   | FAILURE | COMPLETED       | FAILURE
            unknown status lenient   | bogus     | null    | QUEUED          | null
            """)
    void completeInstanceResolvesBothDialects(String rule, String status, String completionStatus, String expectedStatus, String expectedCompletion) {
        var c = new CompleteInstanceRequest(status, completionStatus, null, null).resolve();
        assertThat(c.status()).as(rule).isEqualTo(InstanceStatus.parse(expectedStatus));
        assertThat(c.completionStatus()).as(rule).isEqualTo(expectedCompletion);
    }

    @Test
    void completionResultPrefersTheExplicitFieldOverTheSdkAlias() throws Exception {
        var both = new CompleteInstanceRequest(null, null, Json.MAPPER.readTree("{\"a\":1}"), Json.MAPPER.readTree("{\"b\":2}")).resolve();
        assertThat(both.result().get("a").asInt()).isEqualTo(1);
        var alias = new CompleteInstanceRequest(null, null, null, Json.MAPPER.readTree("{\"b\":2}")).resolve();
        assertThat(alias.result().get("b").asInt()).isEqualTo(2);
    }

    // ── Gates, scope and errors ────────────────────────────────────────────

    @Test
    void clientScopedVisibilityAppliesToListGetAndInstances() {
        String mine = create(code("vis-mine"), ",\"clientId\":\"" + CLIENT + "\"");
        String theirs = create(code("vis-theirs"), ",\"clientId\":\"" + OTHER_CLIENT + "\"");
        String platform = create(code("vis-platform"), "");

        var list = json(http.get("/api/scheduled-jobs?search=" + code("vis-"), VIEWER));
        assertThat(list.get("total").asLong()).as("platform rows + own client's").isEqualTo(2);
        assertThat(list.get("data").findValuesAsText("id")).containsExactlyInAnyOrder(mine, platform);

        assertThat(http.get("/api/scheduled-jobs/" + mine, VIEWER).statusCode()).isEqualTo(200);
        assertThat(http.get("/api/scheduled-jobs/" + platform, VIEWER).statusCode()).isEqualTo(200);
        var forbidden = http.get("/api/scheduled-jobs/" + theirs, VIEWER);
        assertThat(forbidden.statusCode()).isEqualTo(403);
        assertThat(json(forbidden).get("error").asText()).isEqualTo("FORBIDDEN");
        assertThat(json(forbidden).get("message").asText()).isEqualTo("No access to this scheduled job");
        assertThat(http.get("/api/scheduled-jobs/by-code/" + code("vis-theirs") + "?clientId=" + OTHER_CLIENT, VIEWER).statusCode()).isEqualTo(403);
        assertThat(http.get("/api/scheduled-jobs/by-code/" + code("vis-mine") + "?clientId=" + CLIENT, VIEWER).statusCode()).isEqualTo(200);
        assertThat(http.get("/api/scheduled-jobs/by-code/" + code("vis-mine"), VIEWER).statusCode()).as("absent clientId = platform scope").isEqualTo(404);

        var platformOnly = json(http.get("/api/scheduled-jobs?search=" + code("vis-") + "&clientId=platform", ANCHOR));
        assertThat(platformOnly.get("data").findValuesAsText("id")).containsExactly(platform);

        // A fired instance of another client's job is invisible to the viewer; the writer can log on its own.
        String theirInstance = json(http.post("/api/scheduled-jobs/" + theirs + "/fire", null, ANCHOR)).get("instanceId").asText();
        var instForbidden = http.get("/api/scheduled-jobs/instances/" + theirInstance, VIEWER);
        assertThat(instForbidden.statusCode()).isEqualTo(403);
        assertThat(json(instForbidden).get("message").asText()).isEqualTo("No access to this instance");
        var logForbidden = http.post("/api/scheduled-jobs/instances/" + theirInstance + "/log", "{\"level\":\"INFO\",\"message\":\"x\"}", CLIENT_WRITER);
        assertThat(logForbidden.statusCode()).isEqualTo(403);
        assertThat(json(logForbidden).get("error").asText()).isEqualTo("SCOPE_FORBIDDEN");
        String myInstance = json(http.post("/api/scheduled-jobs/" + mine + "/fire", null, CLIENT_WRITER)).get("instanceId").asText();
        assertThat(http.post("/api/scheduled-jobs/instances/" + myInstance + "/log", "{\"level\":\"INFO\",\"message\":\"x\"}", CLIENT_WRITER).statusCode()).isEqualTo(204);
        assertThat(http.post("/api/scheduled-jobs/instances/" + myInstance + "/complete", "{\"status\":\"FAILURE\"}", CLIENT_WRITER).statusCode()).isEqualTo(204);
    }

    @Test
    void coarseGatesAreEnforcedInTheHandlers() {
        String id = create(code("gate"), "");
        var denied = http.post("/api/scheduled-jobs", "{\"code\":\"" + code("gate2") + "\",\"name\":\"X\",\"crons\":[\"0 0 * * * *\"],\"concurrent\":false,\"tracksCompletion\":false}", VIEWER);
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(json(denied).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");
        assertThat(http.put("/api/scheduled-jobs/" + id, "{\"name\":\"X\"}", VIEWER).statusCode()).isEqualTo(403);
        assertThat(http.post("/api/scheduled-jobs/" + id + "/pause", null, VIEWER).statusCode()).isEqualTo(403);
        assertThat(http.post("/api/scheduled-jobs/" + id + "/fire", null, VIEWER).statusCode()).isEqualTo(403);
        assertThat(http.delete("/api/scheduled-jobs/" + id, VIEWER).statusCode()).isEqualTo(403);
        // The client writer holds create + fire but not delete; a platform job is out of its scope anyway.
        assertThat(http.delete("/api/scheduled-jobs/" + id, CLIENT_WRITER).statusCode()).isEqualTo(403);
        var scoped = http.post("/api/scheduled-jobs/" + id + "/pause", null, CLIENT_WRITER);
        assertThat(scoped.statusCode()).isEqualTo(403);
        assertThat(json(scoped).get("error").asText()).isEqualTo("SCOPE_FORBIDDEN");
        // Creating a platform-scoped job as a client writer is refused; in its own client it works.
        assertThat(json(http.post("/api/scheduled-jobs", "{\"code\":\"" + code("gate3") + "\",\"name\":\"X\",\"crons\":[\"0 0 * * * *\"],\"concurrent\":false,\"tracksCompletion\":false}", CLIENT_WRITER))
                .get("error").asText()).isEqualTo("SCOPE_FORBIDDEN");
        create(code("gate4"), ",\"clientId\":\"" + CLIENT + "\"", CLIENT_WRITER);
    }

    @Test
    void errorsUseTheEnvelopeWithTheLockfileCodes() {
        var notFound = http.get("/api/scheduled-jobs/sjb_doesnotexist1", ANCHOR);
        assertThat(notFound.statusCode()).isEqualTo(404);
        assertThat(json(notFound).get("error").asText()).isEqualTo("ScheduledJob_NOT_FOUND");
        assertThat(json(notFound).get("message").asText()).isEqualTo("ScheduledJob not found: sjb_doesnotexist1");
        assertThat(http.get("/api/scheduled-jobs/by-code/nope-" + RUN, ANCHOR).statusCode()).isEqualTo(404);
        var instNotFound = http.get("/api/scheduled-jobs/instances/sji_doesnotexist1", ANCHOR);
        assertThat(instNotFound.statusCode()).isEqualTo(404);
        assertThat(json(instNotFound).get("error").asText()).isEqualTo("ScheduledJobInstance_NOT_FOUND");
        assertThat(http.post("/api/scheduled-jobs/instances/sji_doesnotexist1/complete", "{}", ANCHOR).statusCode()).isEqualTo(404);
        assertThat(json(http.get("/api/scheduled-jobs/instances/sji_doesnotexist1/logs", ANCHOR))).as("unknown instance → empty array").isEmpty();

        var bad = http.post("/api/scheduled-jobs", "{\"code\":\"" + code("bad") + "\",\"name\":\"X\",\"crons\":[\"* * * * *\"],\"concurrent\":false,\"tracksCompletion\":false}", ANCHOR);
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(json(bad).get("error").asText()).isEqualTo("CRON_INVALID_SHAPE");
        var badCode = http.post("/api/scheduled-jobs", "{\"code\":\"Bad_Code\",\"name\":\"X\",\"crons\":[\"0 0 * * * *\"],\"concurrent\":false,\"tracksCompletion\":false}", ANCHOR);
        assertThat(json(badCode).get("error").asText()).isEqualTo("INVALID_CODE_FORMAT");
        var dup = http.post("/api/scheduled-jobs", "{\"code\":\"" + code("dup") + "\",\"name\":\"X\",\"crons\":[\"0 0 * * * *\"],\"concurrent\":false,\"tracksCompletion\":false}", ANCHOR);
        assertThat(dup.statusCode()).isEqualTo(201);
        var dup2 = http.post("/api/scheduled-jobs", "{\"code\":\"" + code("dup") + "\",\"name\":\"X\",\"crons\":[\"0 0 * * * *\"],\"concurrent\":false,\"tracksCompletion\":false}", ANCHOR);
        assertThat(dup2.statusCode()).isEqualTo(409);
        assertThat(json(dup2).get("error").asText()).isEqualTo("CODE_EXISTS");
        String id = json(dup).get("id").asText();
        var emptyCrons = http.put("/api/scheduled-jobs/" + id, "{\"crons\":[]}", ANCHOR);
        assertThat(emptyCrons.statusCode()).isEqualTo(400);
        assertThat(json(emptyCrons).get("error").asText()).isEqualTo("CRONS_REQUIRED");
        var badPage = http.get("/api/scheduled-jobs?page=x", ANCHOR);
        assertThat(badPage.statusCode()).isEqualTo(400);
        assertThat(json(badPage).get("error").asText()).isEqualTo("VALIDATION");
        var noLevel = http.post("/api/scheduled-jobs/instances/" + json(http.post("/api/scheduled-jobs/" + id + "/fire", null, ANCHOR)).get("instanceId").asText() + "/log",
                "{\"message\":\"m\"}", ANCHOR);
        assertThat(noLevel.statusCode()).isEqualTo(400);
        assertThat(json(noLevel).get("error").asText()).isEqualTo("LEVEL_REQUIRED");
    }
}
