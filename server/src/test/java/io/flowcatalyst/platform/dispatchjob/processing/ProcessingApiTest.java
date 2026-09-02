package io.flowcatalyst.platform.dispatchjob.processing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.flowcatalyst.db.generated.Tables;
import io.flowcatalyst.platform.dispatchjob.DispatchJob;
import io.flowcatalyst.platform.dispatchjob.DispatchJobFixture;
import io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.Seed;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository;
import io.flowcatalyst.platform.dispatchjob.DispatchJobStatus;
import io.flowcatalyst.platform.dispatchjob.settled.HmacTokenVerifier;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.router.wire.WebhookSigner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.DS;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.RUN;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.code;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.seedWriteRow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/// `POST /api/dispatch/process` end to end through Javalin, against a real
/// subscriber standing in as a loopback `HttpServer` (dispatch-seam spec
/// §5): the auth/terminal/hold-back short-circuits, the delivery outcome
/// table, retry-budget accounting, and signing.
@SuppressWarnings("deprecation") // JsonNode#asText() — see DispatchJobRepository's own class doc
class ProcessingApiTest {

    private static final String APP_KEY = "processing-test-app-key-" + RUN;
    private static TestHttp http;
    private static HmacTokenVerifier verifier;
    private static DispatchJobRepository repo;

    private static HttpServer subscriber;
    private static String subscriberUrl;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> responseBody = new AtomicReference<>("");
    private final Map<String, String> responseHeaders = new ConcurrentHashMap<>();
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicReference<byte[]> lastBody = new AtomicReference<>();
    private final Map<String, String> lastHeaders = new ConcurrentHashMap<>();

    @BeforeAll
    static void start() throws IOException {
        repo = new DispatchJobRepository(DS);
        verifier = HmacTokenVerifier.fromAppKey(APP_KEY);
        http = new TestHttp(cfg -> {
            HttpError.install(cfg.routes);
            ProcessingApi.register(cfg.routes, new ProcessingApi.State(repo, verifier, new SubscriberDelivery(SubscriberDelivery.defaultClient())));
        });
        subscriber = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        subscriber.start();
        subscriberUrl = "http://127.0.0.1:" + subscriber.getAddress().getPort() + "/hook";
    }

    @AfterAll
    static void stop() {
        http.close();
        subscriber.stop(0);
    }

    @BeforeEach
    void resetSubscriber() {
        status.set(200);
        responseBody.set("");
        responseHeaders.clear();
        hits.set(0);
        lastBody.set(null);
        lastHeaders.clear();
        subscriber.createContext("/hook", this::handle);
    }

    @AfterEach
    void removeContext() {
        subscriber.removeContext("/hook");
    }

    private void handle(HttpExchange exchange) throws IOException {
        hits.incrementAndGet();
        lastBody.set(exchange.getRequestBody().readAllBytes());
        exchange.getRequestHeaders().forEach((k, v) -> lastHeaders.put(k.toLowerCase(), v.getFirst()));
        responseHeaders.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        var body = responseBody.get().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status.get(), body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    // ── Fixture helpers ─────────────────────────────────────────────────

    private static String seedJob(Seed s) {
        String id = seedWriteRow(s);
        retarget(id, subscriberUrl);
        return id;
    }

    private static void retarget(String id, String url) {
        DispatchJobFixture.DB.update(Tables.MSG_DISPATCH_JOBS)
                .set(Tables.MSG_DISPATCH_JOBS.TARGET_URL, url)
                .where(Tables.MSG_DISPATCH_JOBS.ID.eq(id))
                .execute();
    }

    private static void backdate(String id, Instant scheduledFor) {
        DispatchJobFixture.DB.update(Tables.MSG_DISPATCH_JOBS)
                .set(Tables.MSG_DISPATCH_JOBS.SCHEDULED_FOR, scheduledFor.atOffset(ZoneOffset.UTC))
                .where(Tables.MSG_DISPATCH_JOBS.ID.eq(id))
                .execute();
    }

    private static DispatchJob reload(String id) {
        return repo.findById(id).orElseThrow();
    }

    private static JsonNode json(HttpResponse<String> r) {
        try {
            return Json.MAPPER.readTree(r.body());
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + r.body(), e);
        }
    }

    private static HttpResponse<String> process(String jobId) {
        String body = "{\"messageId\":\"%s\"}".formatted(jobId);
        return http.post("/api/dispatch/process", body, "Authorization", "Bearer " + verifier.sign(jobId));
    }

    // ── (a) valid token, subscriber 200 ─────────────────────────────────

    @Test
    void successfulDeliveryCompletesTheJobAndRecordsOneSuccessfulAttempt() {
        String id = seedJob(Seed.of(code("proc-ok")));
        status.set(201);

        var r = process(id);

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(json(r).get("ack").asBoolean()).isTrue();
        assertThat(hits.get()).isEqualTo(1);

        DispatchJob after = reload(id);
        assertThat(after.status()).isEqualTo(DispatchJobStatus.COMPLETED);

        var attempts = repo.attemptsByJob(id);
        assertThat(attempts).hasSize(1);
        var attempt = attempts.getFirst();
        assertThat(attempt.success()).isTrue();
        assertThat(attempt.responseCode()).isEqualTo(201);
        assertThat(attempt.attemptNumber()).isEqualTo(1);
    }

    // ── (b) bad token ────────────────────────────────────────────────────

    @Test
    void badTokenIsRejectedWithoutTouchingTheJobOrDelivering() {
        String id = seedJob(Seed.of(code("proc-badtoken")));

        var r = http.post("/api/dispatch/process", "{\"messageId\":\"%s\"}".formatted(id),
                "Authorization", "Bearer forged-token");

        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(json(r).get("ack").asBoolean()).isFalse();
        assertThat(hits.get()).as("no delivery attempted").isZero();
        assertThat(reload(id).status()).isEqualTo(DispatchJobStatus.PENDING);
        assertThat(repo.attemptsByJob(id)).as("no attempt row").isEmpty();
    }

    @Test
    void missingAuthorizationHeaderIsRejected() {
        String id = seedJob(Seed.of(code("proc-noauth")));

        var r = http.post("/api/dispatch/process", "{\"messageId\":\"%s\"}".formatted(id));

        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(hits.get()).isZero();
    }

    // ── malformed / empty messageId ─────────────────────────────────────

    @Test
    void malformedMessageIdIsA400ThatStillAcks() {
        var r = http.post("/api/dispatch/process", "not json");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json(r).get("ack").asBoolean()).isTrue();
        assertThat(hits.get()).isZero();
    }

    @Test
    void emptyMessageIdIsA400ThatStillAcks() {
        var r = http.post("/api/dispatch/process", "{\"messageId\":\"  \"}");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json(r).get("ack").asBoolean()).isTrue();
    }

    // ── (g) already-terminal job ────────────────────────────────────────

    @Test
    void alreadyCompletedJobAcksWithoutRedelivery() {
        String id = seedJob(Seed.of(code("proc-terminal")).withStatus("COMPLETED"));

        var r = process(id);

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(json(r).get("ack").asBoolean()).isTrue();
        assertThat(hits.get()).as("no re-delivery of a terminal job").isZero();
    }

    // ── job row gone ─────────────────────────────────────────────────────

    @Test
    void unknownJobIdAcksAsIfAlreadyHandled() {
        var r = process("bogus_job_id_" + RUN);

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(json(r).get("ack").asBoolean()).isTrue();
        assertThat(hits.get()).isZero();
    }

    // ── (c) retryable failure ladder + exhaustion ───────────────────────

    @Test
    void retryableFailuresClimbTheBackoffLadderThenFail() {
        // Default max_retries (DB default 3, DispatchJobFixture.seedWriteRow
        // does not override it): the spec's own formula is
        // attemptNumber >= maxRetries (processing.go:266), so the THIRD
        // failed attempt is the one that exhausts the budget, not the
        // fourth — pinned here against the seam spec's own table (spec §4,
        // §13 TestProcess_ExhaustedRetriesFails), not against a paraphrase.
        String id = seedJob(Seed.of(code("proc-retry")));
        status.set(500);

        Instant before1 = Instant.now();
        var r1 = process(id);
        assertThat(r1.statusCode()).isEqualTo(200);
        assertThat(json(r1).get("ack").asBoolean()).isTrue();
        DispatchJob after1 = reload(id);
        assertThat(after1.status()).isEqualTo(DispatchJobStatus.PENDING);
        assertThat(after1.attemptCount()).isEqualTo(1);
        assertThat(after1.scheduledFor()).isCloseTo(before1.plusSeconds(5), within(Duration.ofSeconds(4)));

        Instant before2 = Instant.now();
        var r2 = process(id);
        assertThat(r2.statusCode()).isEqualTo(200);
        DispatchJob after2 = reload(id);
        assertThat(after2.status()).isEqualTo(DispatchJobStatus.PENDING);
        assertThat(after2.attemptCount()).isEqualTo(2);
        assertThat(after2.scheduledFor()).isCloseTo(before2.plusSeconds(15), within(Duration.ofSeconds(4)));

        var r3 = process(id);
        assertThat(r3.statusCode()).isEqualTo(200);
        assertThat(json(r3).get("ack").asBoolean()).isTrue();
        DispatchJob after3 = reload(id);
        assertThat(after3.status()).as("retry budget exhausted at attemptNumber == maxRetries")
                .isEqualTo(DispatchJobStatus.FAILED);
        assertThat(after3.lastError()).contains("HTTP 500");

        assertThat(hits.get()).isEqualTo(3);
    }

    // ── (d) cooperative deferral (ack:false) spends no budget ───────────

    @Test
    void ackFalseDefersWithoutSpendingRetryBudget() {
        String id = seedJob(Seed.of(code("proc-defer")));
        status.set(200);
        responseBody.set("{\"ack\":false,\"delaySeconds\":7}");

        Instant before = Instant.now();
        var r = process(id);

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(json(r).get("ack").asBoolean()).isTrue();
        DispatchJob after = reload(id);
        assertThat(after.status()).isEqualTo(DispatchJobStatus.PENDING);
        assertThat(after.attemptCount()).as("a deferral is not a failure — no budget spent").isZero();
        assertThat(after.scheduledFor()).isCloseTo(before.plusSeconds(7), within(Duration.ofSeconds(4)));
    }

    // ── (e) 429 defers on Retry-After, also without spending budget ────

    @Test
    void rateLimitedDefersOnRetryAfterWithoutSpendingBudget() {
        String id = seedJob(Seed.of(code("proc-429")));
        status.set(429);
        responseHeaders.put("Retry-After", "11");

        Instant before = Instant.now();
        var r = process(id);

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(json(r).get("ack").asBoolean()).isTrue();
        DispatchJob after = reload(id);
        assertThat(after.status()).isEqualTo(DispatchJobStatus.PENDING);
        assertThat(after.attemptCount()).isZero();
        assertThat(after.scheduledFor()).isCloseTo(before.plusSeconds(11), within(Duration.ofSeconds(4)));
    }

    // ── (f) group hold-back ──────────────────────────────────────────────

    @Test
    void blockOnErrorJobHeldByAnEarlierFailedSiblingIsNeverDeliveredAndSpendsNoBudget() {
        String group = "grp-failed-" + RUN;
        seedJob(Seed.of(code("proc-sib")).withMode("BLOCK_ON_ERROR").withMessageGroup(group)
                .withSequence(1).withStatus("FAILED"));
        String id = seedJob(Seed.of(code("proc-held")).withMode("BLOCK_ON_ERROR").withMessageGroup(group)
                .withSequence(2));

        var r = process(id);

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(json(r).get("ack").asBoolean()).isTrue();
        assertThat(json(r).get("message").asText()).isEqualTo("group blocked");
        assertThat(hits.get()).as("held before any HTTP call").isZero();

        DispatchJob after = reload(id);
        assertThat(after.status()).isEqualTo(DispatchJobStatus.PENDING);
        assertThat(after.attemptCount()).as("a hold-back costs no retry budget").isZero();
    }

    @Test
    void blockOnErrorJobHeldByAnEarlierBackedOffSiblingIsNeverDelivered() {
        String group = "grp-backoff-" + RUN;
        String sibling = seedJob(Seed.of(code("proc-sib2")).withMode("BLOCK_ON_ERROR").withMessageGroup(group)
                .withSequence(1).withStatus("PENDING"));
        backdate(sibling, Instant.now().plusSeconds(300));
        String id = seedJob(Seed.of(code("proc-held2")).withMode("BLOCK_ON_ERROR").withMessageGroup(group)
                .withSequence(2));

        var r = process(id);

        assertThat(json(r).get("message").asText()).isEqualTo("group blocked");
        assertThat(hits.get()).isZero();
        assertThat(reload(id).status()).isEqualTo(DispatchJobStatus.PENDING);
        assertThat(reload(id).attemptCount()).isZero();
    }

    @Test
    void nextOnErrorJobInTheSameSituationIsDeliveredAnyway() {
        String group = "grp-nexterr-" + RUN;
        seedJob(Seed.of(code("proc-sib3")).withMode("NEXT_ON_ERROR").withMessageGroup(group)
                .withSequence(1).withStatus("FAILED"));
        String id = seedJob(Seed.of(code("proc-nexterr")).withMode("NEXT_ON_ERROR").withMessageGroup(group)
                .withSequence(2));

        var r = process(id);

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(hits.get()).as("NEXT_ON_ERROR never holds for a failed sibling").isEqualTo(1);
        assertThat(reload(id).status()).isEqualTo(DispatchJobStatus.COMPLETED);
    }

    // ── (h) signed delivery ──────────────────────────────────────────────

    @Test
    void signedDeliveryCarriesAVerifiableHmacSignature() throws IOException {
        String secret = "signing-secret-do-not-use-in-prod";
        DeliveryCredentials creds = job -> new DeliveryCredentials.Resolved("bearer-token-value", secret);
        try (TestHttp signedHttp = new TestHttp(cfg -> {
            HttpError.install(cfg.routes);
            ProcessingApi.register(cfg.routes, new ProcessingApi.State(repo, verifier,
                    new SubscriberDelivery(SubscriberDelivery.defaultClient()), creds, Clock.systemUTC()));
        })) {
            String id = seedJob(Seed.of(code("proc-signed")));

            String body = "{\"messageId\":\"%s\"}".formatted(id);
            var r = signedHttp.post("/api/dispatch/process", body, "Authorization", "Bearer " + verifier.sign(id));

            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(hits.get()).isEqualTo(1);
            assertThat(lastHeaders).containsKey("x-flowcatalyst-signature");
            assertThat(lastHeaders).containsKey("x-flowcatalyst-timestamp");
            assertThat(lastHeaders.get("authorization")).isEqualTo("Bearer bearer-token-value");

            String expected = WebhookSigner.sign(secret, lastHeaders.get("x-flowcatalyst-timestamp"), lastBody.get());
            assertThat(lastHeaders.get("x-flowcatalyst-signature")).isEqualTo(expected);

            assertThat(reload(id).status()).isEqualTo(DispatchJobStatus.COMPLETED);
        }
    }

    // ── (i) redirects are not followed and count as a failure ──────────

    @Test
    void redirectFromTheSubscriberIsNotFollowedAndCountsAsAFailure() {
        String id = seedJob(Seed.of(code("proc-redirect")));
        status.set(302);
        responseHeaders.put("Location", "http://127.0.0.1:1/elsewhere");

        var r = process(id);

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(json(r).get("ack").asBoolean()).isTrue();
        assertThat(hits.get()).as("exactly one call — no follow-up GET to Location").isEqualTo(1);

        DispatchJob after = reload(id);
        assertThat(after.status()).as("a redirect is a failure, not a success").isEqualTo(DispatchJobStatus.PENDING);
        assertThat(after.attemptCount()).as("counts as an ordinary retryable failure").isEqualTo(1);
    }
}
