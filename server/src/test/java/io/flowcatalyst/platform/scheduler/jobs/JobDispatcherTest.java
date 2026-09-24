package io.flowcatalyst.platform.scheduler.jobs;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.flowcatalyst.platform.scheduledjob.InstanceStatus;
import io.flowcatalyst.platform.scheduledjob.ScheduledJob;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobCode;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobInstance;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobInstanceRepository;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository;
import io.flowcatalyst.platform.scheduledjob.TriggerKind;
import io.flowcatalyst.platform.scheduledjob.cron.CronExpression;
import io.flowcatalyst.platform.serviceaccount.OutboundCredentials;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountRepository;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.router.wire.WebhookSigner;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static io.flowcatalyst.db.generated.Tables.IAM_SERVICE_ACCOUNTS;
import static org.assertj.core.api.Assertions.assertThat;

/// [JobDispatcher] against a real embedded Postgres and a stub HTTP target
/// (`docs/spec/scheduled-job-scheduler.md` §3).
class JobDispatcherTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final ScheduledJobRepository JOBS = new ScheduledJobRepository(DS);
    private static final ScheduledJobInstanceRepository INSTANCES = new ScheduledJobInstanceRepository(DS);
    private static final ServiceAccountRepository SERVICE_ACCOUNTS = new ServiceAccountRepository(DS, Optional.empty());

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    private static final List<String> JOB_IDS = new ArrayList<>();
    private static final List<String> INSTANCE_IDS = new ArrayList<>();
    private static final List<String> SERVICE_ACCOUNT_IDS = new ArrayList<>();
    private static int seq = 0;

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> responseBody = new AtomicReference<>("");
    private final AtomicReference<byte[]> lastBody = new AtomicReference<>();
    private final Map<String, String> lastHeaders = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @AfterAll
    static void cleanup() {
        DB.deleteFrom(io.flowcatalyst.db.generated.Tables.MSG_SCHEDULED_JOB_INSTANCES)
                .where(io.flowcatalyst.db.generated.Tables.MSG_SCHEDULED_JOB_INSTANCES.ID.in(INSTANCE_IDS)).execute();
        DB.deleteFrom(io.flowcatalyst.db.generated.Tables.MSG_SCHEDULED_JOBS)
                .where(io.flowcatalyst.db.generated.Tables.MSG_SCHEDULED_JOBS.ID.in(JOB_IDS)).execute();
        DB.deleteFrom(IAM_SERVICE_ACCOUNTS).where(IAM_SERVICE_ACCOUNTS.ID.in(SERVICE_ACCOUNT_IDS)).execute();
    }

    private void handle(HttpExchange exchange) throws IOException {
        calls.incrementAndGet();
        lastBody.set(exchange.getRequestBody().readAllBytes());
        lastHeaders.clear();
        exchange.getRequestHeaders().forEach((k, v) -> lastHeaders.put(k.toLowerCase(Locale.ROOT), v.getFirst()));
        var body = responseBody.get().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status.get(), body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    private static String persistJob(ScheduledJob job) {
        try (Connection conn = DS.getConnection()) {
            conn.setAutoCommit(false);
            JOBS.persist(job, DbTx.wrapForBootstrap(conn));
            conn.commit();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        JOB_IDS.add(job.id());
        return job.id();
    }

    private static ScheduledJob job(String targetUrl, int deliveryMaxAttempts, String applicationId,
                                     JsonNode payload, boolean tracksCompletion, boolean concurrent, Integer timeoutSeconds) {
        var code = ScheduledJobCode.parse("dispatch-" + RUN + "-" + (seq++));
        var def = new ScheduledJob.Definition("dispatcher test job", null,
                List.of(CronExpression.parse("0 0 0 1 1 *")), "UTC", payload, concurrent, tracksCompletion,
                timeoutSeconds, deliveryMaxAttempts, targetUrl);
        ScheduledJob j = ScheduledJob.create(code, def);
        if (applicationId != null) {
            j = j.withApplicationId(applicationId);
        }
        return j;
    }

    private static String queuedInstance(String jobId, String jobCode, TriggerKind kind, Instant scheduledFor,
                                          String correlationId) {
        String id = EntityType.SCHEDULED_JOB_INSTANCE.generate();
        Instant now = Instant.now();
        var inst = new ScheduledJobInstance(id, jobId, null, jobCode, kind, scheduledFor, now, null, null,
                InstanceStatus.QUEUED, 0, null, null, null, correlationId, now);
        INSTANCES.insert(inst);
        INSTANCE_IDS.add(id);
        return id;
    }

    private static String activeServiceAccount(String applicationId, String token, String signingSecret) {
        String id = EntityType.SERVICE_ACCOUNT.generate();
        DB.insertInto(IAM_SERVICE_ACCOUNTS)
                .set(IAM_SERVICE_ACCOUNTS.ID, id)
                .set(IAM_SERVICE_ACCOUNTS.CODE, "svc-" + RUN + "-" + (seq++))
                .set(IAM_SERVICE_ACCOUNTS.NAME, "dispatcher test service account")
                .set(IAM_SERVICE_ACCOUNTS.APPLICATION_ID, applicationId)
                .set(IAM_SERVICE_ACCOUNTS.ACTIVE, true)
                .set(IAM_SERVICE_ACCOUNTS.WH_AUTH_TYPE, "BEARER_TOKEN")
                .set(IAM_SERVICE_ACCOUNTS.WH_AUTH_TOKEN_REF, token)
                .set(IAM_SERVICE_ACCOUNTS.WH_SIGNING_SECRET_REF, signingSecret)
                .execute();
        SERVICE_ACCOUNT_IDS.add(id);
        return id;
    }

    private JobDispatcher dispatcher() {
        return dispatcher(applicationId -> OutboundCredentials.resolve(SERVICE_ACCOUNTS, applicationId));
    }

    private JobDispatcher dispatcher(Function<String, Optional<OutboundCredentials>> credentials) {
        return new JobDispatcher(JOBS, INSTANCES, HttpClient.newHttpClient(), Duration.ofSeconds(5), credentials,
                () -> true, 32, Clock.systemUTC());
    }

    // ── Envelope key set, byte-for-byte ─────────────────────────────────────

    @Test
    @DisplayName("the full envelope carries exactly the eleven camelCase keys")
    void envelopeCarriesEveryFieldWhenPresent() throws Exception {
        JsonNode payload = Json.MAPPER.readTree("{\"n\":1}");
        String jobId = persistJob(job(baseUrl, 3, null, payload, true, true, 45));
        String jobCode = JOBS.findById(jobId).orElseThrow().code();
        Instant slot = Instant.parse("2026-01-01T00:00:00Z");
        queuedInstance(jobId, jobCode, TriggerKind.CRON, slot, "corr-1");

        dispatcher().dispatchOnce();

        JsonNode envelope = Json.MAPPER.readTree(lastBody.get());
        assertThat(envelope.propertyNames()).containsExactlyInAnyOrder(
                "jobId", "jobCode", "instanceId", "scheduledFor", "firedAt", "triggerKind", "correlationId",
                "payload", "tracksCompletion", "timeoutSeconds", "concurrent");
        assertThat(envelope.get("jobId").asString()).isEqualTo(jobId);
        assertThat(envelope.get("jobCode").asString()).isEqualTo(jobCode);
        assertThat(envelope.get("triggerKind").asString()).isEqualTo("CRON");
        assertThat(envelope.get("correlationId").asString()).isEqualTo("corr-1");
        assertThat(envelope.get("tracksCompletion").asBoolean()).isTrue();
        assertThat(envelope.get("concurrent").asBoolean()).isTrue();
        assertThat(envelope.get("timeoutSeconds").asInt()).isEqualTo(45);
    }

    @Test
    @DisplayName("optional keys are dropped, never sent as null, when absent")
    void envelopeOmitsAbsentOptionalKeys() throws Exception {
        String jobId = persistJob(job(baseUrl, 3, null, null, false, false, null));
        String jobCode = JOBS.findById(jobId).orElseThrow().code();
        queuedInstance(jobId, jobCode, TriggerKind.MANUAL, null, null);

        dispatcher().dispatchOnce();

        JsonNode envelope = Json.MAPPER.readTree(lastBody.get());
        assertThat(envelope.propertyNames()).containsExactlyInAnyOrder(
                "jobId", "jobCode", "instanceId", "firedAt", "triggerKind", "tracksCompletion", "concurrent");
    }

    // ── 2xx → DELIVERED, not only 202 ───────────────────────────────────────

    @ParameterizedTest(name = "HTTP {0} -> DELIVERED")
    @ValueSource(ints = {200, 204})
    void any2xxMarksDelivered(int code) {
        status.set(code);
        String jobId = persistJob(job(baseUrl, 3, null, null, false, false, null));
        String jobCode = JOBS.findById(jobId).orElseThrow().code();
        String instanceId = queuedInstance(jobId, jobCode, TriggerKind.MANUAL, null, null);

        dispatcher().dispatchOnce();

        assertThat(INSTANCES.findById(instanceId).orElseThrow().status()).isEqualTo(InstanceStatus.DELIVERED);
    }

    // ── 500 -> QUEUED, attempts 1, message prefix ───────────────────────────

    @Test
    void serverErrorRequeuesWithAttemptOneAndAnHttp500Message() {
        status.set(500);
        responseBody.set("boom");
        String jobId = persistJob(job(baseUrl, 3, null, null, false, false, null));
        String jobCode = JOBS.findById(jobId).orElseThrow().code();
        String instanceId = queuedInstance(jobId, jobCode, TriggerKind.MANUAL, null, null);

        dispatcher().dispatchOnce();

        var reloaded = INSTANCES.findById(instanceId).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(InstanceStatus.QUEUED);
        assertThat(reloaded.deliveryAttempts()).isEqualTo(1);
        assertThat(reloaded.deliveryError()).startsWith("HTTP 500");
    }

    // ── the deliveryMaxAttempts-th failure -> DELIVERY_FAILED ───────────────

    @Test
    void exhaustingDeliveryMaxAttemptsMarksDeliveryFailed() {
        status.set(500);
        String jobId = persistJob(job(baseUrl, 2, null, null, false, false, null));
        String jobCode = JOBS.findById(jobId).orElseThrow().code();
        String instanceId = queuedInstance(jobId, jobCode, TriggerKind.MANUAL, null, null);
        JobDispatcher d = dispatcher();

        d.dispatchOnce(); // attempt 1/2 -> QUEUED
        assertThat(INSTANCES.findById(instanceId).orElseThrow().status()).isEqualTo(InstanceStatus.QUEUED);

        d.dispatchOnce(); // attempt 2/2 -> DELIVERY_FAILED
        assertThat(INSTANCES.findById(instanceId).orElseThrow().status()).isEqualTo(InstanceStatus.DELIVERY_FAILED);
    }

    // ── orphan / no-target exact messages ────────────────────────────────────

    @Test
    void anOrphanInstanceIsMarkedDeliveryFailedWithTheExactMessage() {
        String instanceId = queuedInstance("sjb_" + UUID.randomUUID().toString().replace("-", "").substring(0, 13),
                "gone-code", TriggerKind.MANUAL, null, null);

        dispatcher().dispatchOnce();

        var reloaded = INSTANCES.findById(instanceId).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(InstanceStatus.DELIVERY_FAILED);
        assertThat(reloaded.deliveryError()).isEqualTo("ScheduledJob no longer exists");
    }

    @Test
    void noTargetUrlFailsWithTheExactMessage() {
        String jobId = persistJob(job(null, 3, null, null, false, false, null));
        String jobCode = JOBS.findById(jobId).orElseThrow().code();
        String instanceId = queuedInstance(jobId, jobCode, TriggerKind.MANUAL, null, null);

        dispatcher().dispatchOnce();

        var reloaded = INSTANCES.findById(instanceId).orElseThrow();
        assertThat(reloaded.deliveryError()).isEqualTo("No target URL configured for job");
        assertThat(reloaded.status()).isEqualTo(InstanceStatus.QUEUED); // attempt 1 of 3, non-terminal
        assertThat(calls).hasValue(0); // never reached the HTTP target at all
    }

    // ── bearer + signature ───────────────────────────────────────────────────

    @Test
    @DisplayName("bearer and signature headers are present and the signature verifies over timestamp || body")
    void signsFiringWithTheApplicationsServiceAccountSecret() {
        String applicationId = EntityType.APPLICATION.generate();
        activeServiceAccount(applicationId, "tok-" + RUN, "sec-" + RUN);
        String jobId = persistJob(job(baseUrl, 3, applicationId, null, false, false, null));
        String jobCode = JOBS.findById(jobId).orElseThrow().code();
        queuedInstance(jobId, jobCode, TriggerKind.MANUAL, null, null);

        dispatcher().dispatchOnce();

        assertThat(lastHeaders.get("authorization")).isEqualTo("Bearer tok-" + RUN);
        String timestamp = lastHeaders.get("x-flowcatalyst-timestamp");
        String signature = lastHeaders.get("x-flowcatalyst-signature");
        assertThat(timestamp).isNotBlank();
        assertThat(signature).isEqualTo(WebhookSigner.sign("sec-" + RUN, timestamp, lastBody.get()));
    }

    @Test
    @DisplayName("no application linkage delivers unsigned")
    void absentApplicationDeliversUnsigned() {
        String jobId = persistJob(job(baseUrl, 3, null, null, false, false, null));
        String jobCode = JOBS.findById(jobId).orElseThrow().code();
        String instanceId = queuedInstance(jobId, jobCode, TriggerKind.MANUAL, null, null);

        dispatcher().dispatchOnce();

        assertThat(lastHeaders).doesNotContainKey("authorization").doesNotContainKey("x-flowcatalyst-signature");
        assertThat(INSTANCES.findById(instanceId).orElseThrow().status()).isEqualTo(InstanceStatus.DELIVERED);
    }

    // ── one slow target cannot hold the loop (security-fixes-2026-09-24 S3.4) ──
    //
    // A single loop delivers every tenant's scheduled jobs. The JDK request
    // timeout stops applying once headers arrive, so before this a target that
    // sent headers and then trickled its body held the loop for as long as it
    // liked, and a huge body was buffered whole.

    private static final Duration DEADLINE = Duration.ofSeconds(1);

    /// A target on its own server (its handler blocks while it trickles, so
    /// it must not share the fixture's single dispatcher thread).
    private record SlowTarget(HttpServer server, String url, AtomicBoolean stop, AtomicLong written) implements AutoCloseable {
        static SlowTarget start(int status, long contentLength, int chunkBytes, long pauseMillis, long maxBytes) throws IOException {
            var stop = new AtomicBoolean();
            var written = new AtomicLong();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/slow", exchange -> {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(status, contentLength);
                byte[] chunk = new byte[chunkBytes];
                java.util.Arrays.fill(chunk, (byte) 'x');
                try (var out = exchange.getResponseBody()) {
                    while (!stop.get() && written.get() < maxBytes) {
                        out.write(chunk);
                        out.flush();
                        written.addAndGet(chunk.length);
                        if (pauseMillis > 0) Thread.sleep(pauseMillis);
                    }
                } catch (IOException | InterruptedException e) {
                    // the dispatcher hung up — the point of the exercise
                }
            });
            server.start();
            return new SlowTarget(server, "http://127.0.0.1:" + server.getAddress().getPort() + "/slow", stop, written);
        }

        @Override
        public void close() {
            stop.set(true);
            server.stop(0);
        }
    }

    private JobDispatcher deadlineDispatcher() {
        return new JobDispatcher(JOBS, INSTANCES, HttpClient.newHttpClient(), DEADLINE,
                applicationId -> Optional.empty(), () -> true, 32, Clock.systemUTC());
    }

    private static String queuedFor(String targetUrl) {
        String jobId = persistJob(job(targetUrl, 3, null, null, false, false, null));
        return queuedInstance(jobId, JOBS.findById(jobId).orElseThrow().code(), TriggerKind.MANUAL, null, null);
    }

    @Test
    @DisplayName("headers then a trickling error body: the tick returns at the deadline and records a failure")
    void aTricklingErrorBodyCannotHoldTheLoopPastTheDeadline() throws Exception {
        // 1 byte every 100 ms for 10 s — never reaches the 500-byte snippet cap.
        try (var target = SlowTarget.start(500, 0, 1, 100, 100)) {
            String instanceId = queuedFor(target.url());

            long started = System.nanoTime();
            deadlineDispatcher().dispatchOnce();
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

            assertThat(elapsed).as("the tick is bounded by the 1 s deadline, not the 10 s body")
                    .isLessThan(Duration.ofSeconds(4));
            var reloaded = INSTANCES.findById(instanceId).orElseThrow();
            assertThat(reloaded.status()).isEqualTo(InstanceStatus.QUEUED);
            assertThat(reloaded.deliveryAttempts()).isEqualTo(1);
            assertThat(reloaded.deliveryError()).startsWith("Network/HTTP error: no complete response within");
        }
    }

    @Test
    @DisplayName("a 2xx whose body trickles is delivered at once: the status is the whole answer")
    void aTricklingSuccessBodyIsNotWaitedFor() throws Exception {
        try (var target = SlowTarget.start(200, 0, 1, 100, 100)) {
            String instanceId = queuedFor(target.url());

            long started = System.nanoTime();
            deadlineDispatcher().dispatchOnce();
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

            assertThat(elapsed).isLessThan(Duration.ofSeconds(4));
            assertThat(INSTANCES.findById(instanceId).orElseThrow().status())
                    .as("the target accepted it; its body never mattered").isEqualTo(InstanceStatus.DELIVERED);
        }
    }

    @Test
    @DisplayName("a huge error body is read only as far as the 500-byte snippet")
    void aHugeErrorBodyIsNotBuffered() throws Exception {
        long size = 256L * 1024 * 1024;
        try (var target = SlowTarget.start(500, size, 64 * 1024, 0, size)) {
            String instanceId = queuedFor(target.url());

            deadlineDispatcher().dispatchOnce();
            Thread.sleep(500); // let the target's writer notice the hang-up (or block on a full socket)

            var reloaded = INSTANCES.findById(instanceId).orElseThrow();
            assertThat(reloaded.deliveryError()).startsWith("HTTP 500 (expected 2xx): ")
                    .hasSize("HTTP 500 (expected 2xx): ".length() + 500);
            assertThat(target.written().get()).as("the dispatcher stopped reading long before the end of the body")
                    .isLessThan(size / 4);
        }
    }

    // ── credentials cache ────────────────────────────────────────────────────

    @Test
    @DisplayName("two dispatches for one application within a minute cost one credentials lookup")
    void credentialsCacheHitsOnceWithinTheTtl() {
        String applicationId = EntityType.APPLICATION.generate();
        AtomicInteger lookups = new AtomicInteger();
        Function<String, Optional<OutboundCredentials>> counting = id -> {
            lookups.incrementAndGet();
            return Optional.of(new OutboundCredentials("tok", "sec"));
        };
        Function<String, Optional<OutboundCredentials>> cached = OutboundCredentials.cached(counting, Clock.systemUTC());

        String jobId1 = persistJob(job(baseUrl, 3, applicationId, null, false, false, null));
        String jobCode1 = JOBS.findById(jobId1).orElseThrow().code();
        queuedInstance(jobId1, jobCode1, TriggerKind.MANUAL, null, null);
        String jobId2 = persistJob(job(baseUrl, 3, applicationId, null, false, false, null));
        String jobCode2 = JOBS.findById(jobId2).orElseThrow().code();
        queuedInstance(jobId2, jobCode2, TriggerKind.MANUAL, null, null);

        dispatcher(cached).dispatchOnce(); // both instances in one tick, same application

        assertThat(lookups).as("one cached lookup for two dispatches of the same application").hasValue(1);
    }
}
