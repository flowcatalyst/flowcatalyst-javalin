package io.flowcatalyst.platform.dispatchjob.processing;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.flowcatalyst.db.generated.Tables;
import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ApplicationType;
import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientIdentifier;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.dispatchjob.AttemptErrorType;
import io.flowcatalyst.platform.dispatchjob.DispatchJob;
import io.flowcatalyst.platform.dispatchjob.DispatchJobFixture;
import io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.Seed;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository;
import io.flowcatalyst.platform.dispatchjob.DispatchJobStatus;
import io.flowcatalyst.platform.dispatchjob.settled.HmacTokenVerifier;
import io.flowcatalyst.platform.serviceaccount.OutboundCredentials;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountRepository;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.platform.subscription.Subscription;
import io.flowcatalyst.platform.subscription.SubscriptionRepository;
import io.flowcatalyst.router.wire.WebhookSigner;
import io.flowcatalyst.sdk.usecase.HasId;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import io.flowcatalyst.sdk.usecase.jdbc.Persist;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
    private static ClientRepository clientRepo;
    /// A second registration of the same route, wired with a real
    /// [ClientCodeResolver] over [#clientRepo] instead of
    /// [ClientCodeResolver#none] — the webhook-client-code tests (T1-T5, T7)
    /// need an actual `tnt_clients` row to resolve against.
    private static TestHttp clientCodeHttp;
    /// `tnt_clients` rows this class inserts directly (bypassing the use-case
    /// envelope — there is no client-creation flow to exercise here), for
    /// [#cleanup].
    private static final List<String> insertedClients = new ArrayList<>();

    // ── dispatch-delivery-credentials.md S1/S7 fixtures ─────────────────
    private static ApplicationRepository applicationRepo;
    private static SubscriptionRepository subscriptionRepo;
    private static io.flowcatalyst.platform.connection.ConnectionRepository connectionRepo;
    private static final List<String> insertedApplications = new ArrayList<>();
    private static final List<String> insertedSubscriptions = new ArrayList<>();
    private static final List<String> insertedServiceAccounts = new ArrayList<>();

    private static HttpServer subscriber;
    private static String subscriberUrl;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> responseBody = new AtomicReference<>("");
    private final Map<String, String> responseHeaders = new ConcurrentHashMap<>();
    private final AtomicInteger hits = new AtomicInteger();
    /// Holds the subscriber inside one delivery long enough for a second
    /// callback for the same job to reach the claim while the first is still
    /// in flight — the interleaving the claim exists to survive.
    private final AtomicLong subscriberDelayMillis = new AtomicLong();
    private final AtomicReference<byte[]> lastBody = new AtomicReference<>();
    private final Map<String, String> lastHeaders = new ConcurrentHashMap<>();

    @BeforeAll
    static void start() throws IOException {
        repo = new DispatchJobRepository(DS);
        verifier = HmacTokenVerifier.fromAppKey(APP_KEY);
        clientRepo = new ClientRepository(DS);
        applicationRepo = new ApplicationRepository(DS);
        subscriptionRepo = new SubscriptionRepository(DS);
        connectionRepo = new io.flowcatalyst.platform.connection.ConnectionRepository(DS);
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            ProcessingApi.register(routes, new ProcessingApi.State(repo, verifier, new SubscriberDelivery(SubscriberDelivery.defaultClient(), ClientCodeResolver.none())));
        });
        clientCodeHttp = TestHttp.routes(routes -> {
            HttpError.install(routes);
            ProcessingApi.register(routes, new ProcessingApi.State(repo, verifier,
                    new SubscriberDelivery(SubscriberDelivery.defaultClient(), new ClientCodeResolver(clientRepo::findById)),
                    DeliveryCredentials.none(), Clock.systemUTC()));
        });
        subscriber = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // A real executor, not the default in-line one: without it the stand-in
        // serialises every request and no test here could ever observe two
        // deliveries overlapping (which is what the duplicate-delivery mutant does).
        subscriber.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        subscriber.start();
        subscriberUrl = "http://127.0.0.1:" + subscriber.getAddress().getPort() + "/hook";
    }

    @AfterAll
    static void stop() {
        http.close();
        clientCodeHttp.close();
        subscriber.stop(0);
        if (!insertedClients.isEmpty()) {
            DispatchJobFixture.DB.deleteFrom(Tables.TNT_CLIENTS).where(Tables.TNT_CLIENTS.ID.in(insertedClients)).execute();
        }
        if (!insertedServiceAccounts.isEmpty()) {
            DispatchJobFixture.DB.deleteFrom(Tables.IAM_SERVICE_ACCOUNTS)
                    .where(Tables.IAM_SERVICE_ACCOUNTS.ID.in(insertedServiceAccounts)).execute();
        }
        if (!insertedSubscriptions.isEmpty()) {
            DispatchJobFixture.DB.deleteFrom(Tables.MSG_SUBSCRIPTIONS)
                    .where(Tables.MSG_SUBSCRIPTIONS.ID.in(insertedSubscriptions)).execute();
        }
        if (!insertedApplications.isEmpty()) {
            DispatchJobFixture.DB.deleteFrom(Tables.APP_APPLICATIONS)
                    .where(Tables.APP_APPLICATIONS.ID.in(insertedApplications)).execute();
        }
    }

    @BeforeEach
    void resetSubscriber() {
        status.set(200);
        responseBody.set("");
        responseHeaders.clear();
        hits.set(0);
        subscriberDelayMillis.set(0);
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
        long delay = subscriberDelayMillis.get();
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
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

    /// `msg_dispatch_jobs.data_only` defaults to `true` (V1__baseline.sql),
    /// and [DispatchJobFixture.Seed] never overrides it — every job `seedJob`
    /// creates is `dataOnly` unless this flips it, which the webhook-client-code
    /// envelope tests (T1/T2/T3/T5/T6) need to exercise `DeliveryPayload`'s
    /// non-`dataOnly` branch.
    private static void setDataOnly(String id, boolean dataOnly) {
        DispatchJobFixture.DB.update(Tables.MSG_DISPATCH_JOBS)
                .set(Tables.MSG_DISPATCH_JOBS.DATA_ONLY, dataOnly)
                .where(Tables.MSG_DISPATCH_JOBS.ID.eq(id))
                .execute();
    }

    /// Inserts a `tnt_clients` row directly — a raw insert, not
    /// `Client.create` + `ClientRepository#persist`, because this unit has no
    /// unit-of-work wiring and the client aggregate's own invariants are not
    /// what is under test here ([io.flowcatalyst.platform.client.ClientRepositoryTest]
    /// is the model for this pattern). Returns the new client's id.
    private static String insertClient(String identifier) {
        String id = EntityType.CLIENT.generate();
        DispatchJobFixture.DB.insertInto(Tables.TNT_CLIENTS)
                .set(Tables.TNT_CLIENTS.ID, id)
                .set(Tables.TNT_CLIENTS.NAME, "Processing test client " + identifier)
                .set(Tables.TNT_CLIENTS.IDENTIFIER, identifier)
                .set(Tables.TNT_CLIENTS.STATUS, "ACTIVE")
                .execute();
        insertedClients.add(id);
        return id;
    }

    // ── dispatch-delivery-credentials.md S1/S7 fixtures ─────────────────

    /// Application + Subscription go through the real aggregate `create()` +
    /// repository `persist` (unlike [#insertClient] above) because
    /// [DeliveryCredentials#forApplications] reads them back through
    /// [ApplicationRepository]/[SubscriptionRepository] the same way — the
    /// point of S1/S7 is that the REAL resolver chain works end to end, so
    /// the rows it reads should come from the real write path, not a
    /// hand-shaped row that happens to satisfy today's column list.
    private static String persistApplication(String code) {
        Application app = Application.create(ApplicationType.APPLICATION, code, code);
        persist(app, applicationRepo);
        insertedApplications.add(app.id());
        return app.id();
    }

    private static String persistSubscription(String code, String applicationCode) {
        Subscription sub = Subscription.create(code, code, "https://hook.example/" + code)
                .withApplicationCode(applicationCode);
        persist(sub, subscriptionRepo);
        insertedSubscriptions.add(sub.id());
        return sub.id();
    }

    private static <T extends HasId> void persist(T entity, Persist<T> repository) {
        try (Connection conn = DS.getConnection()) {
            conn.setAutoCommit(false);
            repository.persist(entity, DbTx.wrapForBootstrap(conn));
            conn.commit();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /// A raw `iam_service_accounts` row (same reasoning as [#insertClient]:
    /// webhook-credential encryption/rotation is not what S1/S7 pin) —
    /// `ACTIVE`, `BEARER_TOKEN`, both the token and secret plaintext (no app
    /// key configured for this class's [ServiceAccountRepository] reads).
    private static String activeServiceAccount(String applicationId, String token, String signingSecret) {
        String id = EntityType.SERVICE_ACCOUNT.generate();
        DispatchJobFixture.DB.insertInto(Tables.IAM_SERVICE_ACCOUNTS)
                .set(Tables.IAM_SERVICE_ACCOUNTS.ID, id)
                .set(Tables.IAM_SERVICE_ACCOUNTS.CODE, "proc-svc-" + RUN + "-" + id)
                .set(Tables.IAM_SERVICE_ACCOUNTS.NAME, "processing test service account")
                .set(Tables.IAM_SERVICE_ACCOUNTS.APPLICATION_ID, applicationId)
                .set(Tables.IAM_SERVICE_ACCOUNTS.ACTIVE, true)
                .set(Tables.IAM_SERVICE_ACCOUNTS.WH_AUTH_TYPE, "BEARER_TOKEN")
                .set(Tables.IAM_SERVICE_ACCOUNTS.WH_AUTH_TOKEN_REF, token)
                .set(Tables.IAM_SERVICE_ACCOUNTS.WH_SIGNING_SECRET_REF, signingSecret)
                .execute();
        insertedServiceAccounts.add(id);
        return id;
    }

    /// The real resolver chain (hand-off 2026-09-22): subscription →
    /// connection → application → oldest active service account, un-cached
    /// (no TTL interference between S1/S7's independent applications/tests —
    /// S6 in `DeliveryCredentialsTest` pins the cache itself). S1/S7 seed no
    /// connection, so this always falls through to the application step —
    /// `DeliveryCredentialsTest` T1-T5 pin the connection/subscription steps
    /// directly.
    private static DeliveryCredentials realDeliveryCredentials() {
        var serviceAccounts = new ServiceAccountRepository(DS, Optional.empty());
        return DeliveryCredentials.resolve(subscriptionRepo::findById, connectionRepo::findById, applicationRepo::findByCode,
                serviceAccountId -> OutboundCredentials.resolveById(serviceAccounts, serviceAccountId),
                applicationId -> OutboundCredentials.resolve(serviceAccounts, applicationId));
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
        return process(http, jobId);
    }

    /// Same call, against a caller-chosen [TestHttp] — the webhook-client-code
    /// tests each need their own `ProcessingApi.State` (a real or fake
    /// [ClientCodeResolver]) rather than the class's default [#http].
    private static HttpResponse<String> process(TestHttp httpClient, String jobId) {
        String body = "{\"messageId\":\"%s\"}".formatted(jobId);
        return httpClient.post("/api/dispatch/process", body, "Authorization", "Bearer " + verifier.sign(jobId));
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

    // ── oversized request body ──────────────────────────────────────────

    /// Audit finding (test-gap): request-body caps had no test before this
    /// unit. Go's own guard on this endpoint is 4 KiB
    /// ([ProcessingApi#MAX_REQUEST_BODY_BYTES]) — a caller that sends more
    /// gets the same 400 `ack:true` shape as a malformed body, never a
    /// buffered read of the whole oversized payload.
    @Test
    void oversizedRequestBodyIsA400ThatStillAcks() {
        String padding = "x".repeat(ProcessingApi.MAX_REQUEST_BODY_BYTES + 1);
        var r = http.post("/api/dispatch/process", "{\"messageId\":\"" + padding + "\"}");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json(r).get("ack").asBoolean()).isTrue();
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

    /// (a): a 2xx carrying `{"ack":false}` is a cooperative deferral, not a
    /// failure — but the subscriber DID answer, and that real HTTP status
    /// must land on the attempt row exactly like a genuine success or
    /// failure would. Mutant: pass `null` instead of the real status into
    /// `recordAttempt` for the `Deferred` branch — this must fail under it.
    @Test
    void ackFalseDeferralRecordsTheRealResponseCode() {
        String id = seedJob(Seed.of(code("proc-defer-rc")));
        status.set(200);
        responseBody.set("{\"ack\":false}");

        process(id);

        var attempts = repo.attemptsByJob(id);
        assertThat(attempts).hasSize(1);
        assertThat(attempts.getFirst().responseCode())
                .as("a 2xx ack=false deferral got a real HTTP response and must record it")
                .isEqualTo(200);
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

    /// (b): a 429 got a real HTTP response and must record it. Mutant: pass
    /// `null` instead of the real status — this must fail under it.
    @Test
    void rateLimitedDeferralRecordsTheRealResponseCode() {
        String id = seedJob(Seed.of(code("proc-429-rc")));
        status.set(429);

        process(id);

        var attempts = repo.attemptsByJob(id);
        assertThat(attempts).hasSize(1);
        assertThat(attempts.getFirst().responseCode())
                .as("a 429 got a real HTTP response and must record it")
                .isEqualTo(429);
    }

    /// (c): the negative case — a genuine transport failure (no HTTP
    /// response at all) must still record NO response code; carrying the
    /// real status for a deferral must not mean fabricating one where none
    /// exists. Port 1 on loopback: nothing listens there, so this is a
    /// connection failure, not a slow one. Mutant: fabricate a status (e.g.
    /// 0) for a transport failure — this must fail under it.
    @Test
    void transportFailureRecordsNoResponseCode() {
        String id = seedJob(Seed.of(code("proc-transport-rc")));
        retarget(id, "http://127.0.0.1:1/hook");

        process(id);

        var attempts = repo.attemptsByJob(id);
        assertThat(attempts).hasSize(1);
        assertThat(attempts.getFirst().responseCode())
                .as("a transport failure never got an HTTP response; response_code must stay unset")
                .isNull();
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
        try (TestHttp signedHttp = TestHttp.routes(routes -> {
            HttpError.install(routes);
            ProcessingApi.register(routes, new ProcessingApi.State(repo, verifier,
                    new SubscriberDelivery(SubscriberDelivery.defaultClient(), ClientCodeResolver.none()), creds, Clock.systemUTC()));
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

    // ── S1 (docs/spec/dispatch-delivery-credentials.md): end-to-end, the REAL resolver ──

    /// Unlike [#signedDeliveryCarriesAVerifiableHmacSignature] above (a
    /// hand-built `DeliveryCredentials` lambda), this wires
    /// [DeliveryCredentials#forApplications] against real `Subscription`/
    /// `Application`/service-account rows — the job's subscription resolves
    /// an application, which resolves its active service account's real
    /// bearer + signing secret, delivered to a real loopback subscriber.
    ///
    /// The signature is verified by recomputing it with
    /// [WebhookSigner#sign] — the exact `HMAC-SHA256(secret, timestamp ‖
    /// body)`, lower-case-hex formula
    /// `sdk/src/main/java/io/flowcatalyst/sdk/webhook/WebhookSignature#hmacHex`
    /// implements (this module does not depend on `sdk` — checked
    /// `server/pom.xml`, no `flowcatalyst-sdk` artifact — so the SDK class
    /// itself cannot be called from here); it both ACCEPTS under the
    /// resolved secret and is shown to DIFFER under another secret, the same
    /// two assertions `WebhookSignature.verify` would make (constant-time
    /// equal / not-equal on the same HMAC).
    ///
    /// Mutants: wire `DeliveryCredentials.none()` instead — `authorization`/
    /// `x-flowcatalyst-signature` would be absent, failing the `containsKey`
    /// assertions. Sign with a constant instead of the resolved secret — the
    /// "accepts with the right secret" assertion fails. Sign a different
    /// body than the one sent — the "verifies over the body actually
    /// delivered" assertion fails (the expected value is recomputed from
    /// `lastBody`, the bytes the subscriber actually received).
    @Test
    void s1_endToEndDeliveryIsSignedWithTheResolvedApplicationsServiceAccountCredentials() throws IOException {
        String appCode = "proc-s1-app-" + RUN;
        String token = "s1-token-" + RUN;
        String secret = "s1-secret-" + RUN;
        String appId = persistApplication(appCode);
        activeServiceAccount(appId, token, secret);
        // The job's OWN code names a DIFFERENT (non-existent) application —
        // proves resolution went through the subscription, not the code.
        String subscriptionId = persistSubscription("proc-s1-sub-" + RUN, appCode);

        try (TestHttp s1Http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            ProcessingApi.register(routes, new ProcessingApi.State(repo, verifier,
                    new SubscriberDelivery(SubscriberDelivery.defaultClient(), ClientCodeResolver.none()),
                    realDeliveryCredentials(), Clock.systemUTC()));
        })) {
            String id = seedJob(Seed.of(code("proc-s1-other-app")).withSubscriptionId(subscriptionId));
            status.set(200);
            lastHeaders.clear();
            lastBody.set(null);

            var r = process(s1Http, id);

            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(hits.get()).isEqualTo(1);
            assertThat(lastHeaders.get("authorization")).isEqualTo("Bearer " + token);
            assertThat(lastHeaders).containsKey("x-flowcatalyst-signature");
            assertThat(lastHeaders).containsKey("x-flowcatalyst-timestamp");

            String timestamp = lastHeaders.get("x-flowcatalyst-timestamp");
            byte[] deliveredBody = lastBody.get();
            String expected = WebhookSigner.sign(secret, timestamp, deliveredBody);
            assertThat(lastHeaders.get("x-flowcatalyst-signature"))
                    .as("verifies with the resolved application's own secret, over the body actually delivered")
                    .isEqualTo(expected);

            String underAnotherSecret = WebhookSigner.sign("a-completely-different-secret-" + RUN, timestamp, deliveredBody);
            assertThat(lastHeaders.get("x-flowcatalyst-signature"))
                    .as("the same signature does not verify under a different secret")
                    .isNotEqualTo(underAnotherSecret);

            assertThat(reload(id).status()).isEqualTo(DispatchJobStatus.COMPLETED);
        }
    }

    // ── S5 (docs/spec/dispatch-delivery-credentials.md): exactly one header, the other genuinely absent ──

    /// The resolver-side half of S5 (the value is `null`, never `""`) is
    /// pinned in `DeliveryCredentialsTest`; this half pins
    /// [SubscriberDelivery]'s request-building: given a token-only
    /// [DeliveryCredentials.Resolved], the wire carries `Authorization` and
    /// genuinely NO `X-FlowCatalyst-Signature`/`-Timestamp` at all — not an
    /// empty value for either.
    ///
    /// Mutant: send an empty `X-FlowCatalyst-Signature`/`-Timestamp` header
    /// instead of omitting it — `doesNotContainKey` fails under it, where a
    /// `.isEmpty()` assertion on the value would not.
    @Test
    void s5_tokenOnlyCredentialsSendExactlyTheAuthorizationHeader() {
        String token = "s5-token-only-" + RUN;
        DeliveryCredentials creds = job -> new DeliveryCredentials.Resolved(token, null);
        try (TestHttp s5Http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            ProcessingApi.register(routes, new ProcessingApi.State(repo, verifier,
                    new SubscriberDelivery(SubscriberDelivery.defaultClient(), ClientCodeResolver.none()), creds, Clock.systemUTC()));
        })) {
            String id = seedJob(Seed.of(code("proc-s5-tokenonly")));
            lastHeaders.clear();

            var r = process(s5Http, id);

            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(lastHeaders.get("authorization")).isEqualTo("Bearer " + token);
            assertThat(lastHeaders).as("no signing secret configured — no signature header").doesNotContainKey("x-flowcatalyst-signature");
            assertThat(lastHeaders).as("no signing secret configured — no timestamp header").doesNotContainKey("x-flowcatalyst-timestamp");
        }
    }

    /// The mirror case: secret-only credentials carry the signature headers
    /// and genuinely NO `Authorization` header — not an empty `Bearer `.
    ///
    /// Mutant: send `Authorization: Bearer ` (empty token) instead of
    /// omitting the header.
    @Test
    void s5_secretOnlyCredentialsSendExactlyTheSignatureHeadersNeverAuthorization() {
        String secret = "s5-secret-only-" + RUN;
        DeliveryCredentials creds = job -> new DeliveryCredentials.Resolved(null, secret);
        try (TestHttp s5Http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            ProcessingApi.register(routes, new ProcessingApi.State(repo, verifier,
                    new SubscriberDelivery(SubscriberDelivery.defaultClient(), ClientCodeResolver.none()), creds, Clock.systemUTC()));
        })) {
            String id = seedJob(Seed.of(code("proc-s5-secretonly")));
            lastHeaders.clear();
            lastBody.set(null);

            var r = process(s5Http, id);

            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(lastHeaders).as("no bearer token configured — no Authorization header at all").doesNotContainKey("authorization");
            assertThat(lastHeaders).containsKey("x-flowcatalyst-signature");
            assertThat(lastHeaders).containsKey("x-flowcatalyst-timestamp");

            String expected = WebhookSigner.sign(secret, lastHeaders.get("x-flowcatalyst-timestamp"), lastBody.get());
            assertThat(lastHeaders.get("x-flowcatalyst-signature")).isEqualTo(expected);
        }
    }

    // ── S7 (docs/spec/dispatch-delivery-credentials.md): a throwing resolver degrades to bare ──

    /// The resolver first computes the REAL credentials (so the secret
    /// genuinely exists in a local variable at the moment of failure — the
    /// strongest version of "the resolver had it and must not have logged
    /// it") and only then throws, simulating a failure after a successful
    /// lookup (e.g. a downstream audit write) rather than before one.
    ///
    /// Mutants: let the exception propagate out of `ProcessingApi` (delivery
    /// aborted, no 200/ack, no delivery) instead of degrading to bare — every
    /// assertion in the try block fails. Log the resolved secret anywhere in
    /// the captured WARN — the "no secret leaked" assertion fails.
    @Test
    void s7_throwingResolverDegradesToBareDeliveryWithWarnAndLeaksNoSecret() {
        String appCode = "proc-s7-app-" + RUN;
        String secret = "s7-secret-must-never-be-logged-" + RUN;
        String appId = persistApplication(appCode);
        activeServiceAccount(appId, "s7-token-" + RUN, secret);
        String subscriptionId = persistSubscription("proc-s7-sub-" + RUN, appCode);

        DeliveryCredentials real = realDeliveryCredentials();
        DeliveryCredentials throwing = job -> {
            real.resolve(job); // the secret exists right here, then is discarded
            throw new RuntimeException("simulated resolver failure after a successful lookup");
        };

        var log = (Logger) LoggerFactory.getLogger(ProcessingApi.class);
        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        log.addAppender(captured);
        try (TestHttp throwingHttp = TestHttp.routes(routes -> {
            HttpError.install(routes);
            ProcessingApi.register(routes, new ProcessingApi.State(repo, verifier,
                    new SubscriberDelivery(SubscriberDelivery.defaultClient(), ClientCodeResolver.none()),
                    throwing, Clock.systemUTC()));
        })) {
            String id = seedJob(Seed.of(code("proc-s7")).withSubscriptionId(subscriptionId));
            status.set(200);
            lastHeaders.clear();

            var r = process(throwingHttp, id);

            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(json(r).get("ack").asBoolean()).as("a throwing resolver does not abort the delivery").isTrue();
            assertThat(hits.get()).as("the delivery still went out, bare").isEqualTo(1);
            assertThat(lastHeaders).as("bare — no bearer").doesNotContainKey("authorization");
            assertThat(lastHeaders).as("bare — no signature").doesNotContainKey("x-flowcatalyst-signature");
            assertThat(reload(id).status()).isEqualTo(DispatchJobStatus.COMPLETED);

            assertThat(captured.list).as("a WARN was logged for the failed lookup")
                    .anySatisfy(e -> {
                        assertThat(e.getLevel()).isEqualTo(Level.WARN);
                        assertThat(e.getFormattedMessage()).contains("delivering unsigned");
                    });
            assertThat(captured.list).as("no captured log line's message carries the secret").allSatisfy(e ->
                    assertThat(e.getFormattedMessage()).doesNotContain(secret));
            assertThat(captured.list).as("no captured log line's key/value fields carry the secret").allSatisfy(e -> {
                if (e.getKeyValuePairs() != null) {
                    assertThat(e.getKeyValuePairs()).noneSatisfy(kv -> assertThat(String.valueOf(kv.value)).contains(secret));
                }
            });
        } finally {
            log.detachAppender(captured);
        }
    }

    // ── catch-up-2026-09-22.md C2: T6-T9 ────────────────────────────────

    /// T6 (first half): a FAILED attempt's error message gains
    /// `" (delivered unsigned: <reason>)"`, and the response body is kept on
    /// failure too (2026-09-22 addendum — before, only success stored it).
    /// Mutant: drop the suffix; drop the body.
    @Test
    void t6_failedAttemptCarriesTheUnsignedSuffixAndKeepsTheResponseBody() {
        String id = seedJob(Seed.of(code("proc-t6-unsigned-fail")));
        status.set(500);
        responseBody.set("Invalid webhook signature.");
        DeliveryCredentials bare = job -> DeliveryCredentials.Resolved.bare("test reason for t6");
        try (TestHttp t6Http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            ProcessingApi.register(routes, new ProcessingApi.State(repo, verifier,
                    new SubscriberDelivery(SubscriberDelivery.defaultClient(), ClientCodeResolver.none()), bare, Clock.systemUTC()));
        })) {
            var r = process(t6Http, id);
            assertThat(r.statusCode()).isEqualTo(200);

            var attempts = repo.attemptsByJob(id);
            assertThat(attempts).hasSize(1);
            var attempt = attempts.getFirst();
            assertThat(attempt.success()).isFalse();
            assertThat(attempt.errorMessage()).as("the reason is appended to the failure message")
                    .contains("(delivered unsigned: test reason for t6)");
            assertThat(attempt.responseBody()).as("the subscriber's stated reason is kept on failure too")
                    .isEqualTo("Invalid webhook signature.");
        }
    }

    /// T6 (second half): a SUCCESSFUL attempt carries no error message at
    /// all — unsigned or not, success has nothing to append the reason to.
    @Test
    void t6_successfulAttemptCarriesNoErrorMessageEvenWhenUnsigned() {
        String id = seedJob(Seed.of(code("proc-t6-unsigned-success")));
        status.set(200);
        DeliveryCredentials bare = job -> DeliveryCredentials.Resolved.bare("test reason for t6 success");
        try (TestHttp t6Http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            ProcessingApi.register(routes, new ProcessingApi.State(repo, verifier,
                    new SubscriberDelivery(SubscriberDelivery.defaultClient(), ClientCodeResolver.none()), bare, Clock.systemUTC()));
        })) {
            var r = process(t6Http, id);
            assertThat(r.statusCode()).isEqualTo(200);

            var attempts = repo.attemptsByJob(id);
            assertThat(attempts).hasSize(1);
            var attempt = attempts.getFirst();
            assertThat(attempt.success()).isTrue();
            assertThat(attempt.errorMessage()).as("a success carries no error message, unsigned or not").isNull();
        }
    }

    /// T7: `request_info` records the documented keys and no secret — the
    /// header list carries NAMES only (never `X-FlowCatalyst-Signature`'s or
    /// `Authorization`'s VALUE). Mutant: put header values in the list, or
    /// omit `signedBy`/the header names.
    @Test
    void t7_requestInfoRecordsWhatWasSentAndNeverASecret() {
        String secret = "t7-request-info-secret-" + RUN;
        String bearer = "t7-request-info-bearer-" + RUN;
        DeliveryCredentials creds = job -> DeliveryCredentials.Resolved.signed(bearer, secret, "t7-sa-code");
        String id = seedJob(Seed.of(code("proc-t7-requestinfo")));
        status.set(200);
        try (TestHttp t7Http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            ProcessingApi.register(routes, new ProcessingApi.State(repo, verifier,
                    new SubscriberDelivery(SubscriberDelivery.defaultClient(), ClientCodeResolver.none()), creds, Clock.systemUTC()));
        })) {
            var r = process(t7Http, id);
            assertThat(r.statusCode()).isEqualTo(200);

            var attempts = repo.attemptsByJob(id);
            assertThat(attempts).hasSize(1);
            var request = attempts.getFirst().request();
            assertThat(request).as("recorded on every attempt").isNotNull();
            assertThat(request.signedBy()).isEqualTo("t7-sa-code");
            assertThat(request.signature()).isTrue();
            assertThat(request.bearer()).isTrue();
            assertThat(request.timestamp()).isNotNull();
            assertThat(request.headers()).as("sorted header NAMES")
                    .contains("Authorization", "X-FlowCatalyst-Signature", "X-FlowCatalyst-Timestamp")
                    .isSorted();
            assertThat(request.headers()).as("names only — never the signature or bearer VALUE")
                    .noneMatch(h -> h.equals(secret) || h.equals(bearer) || h.contains(secret) || h.contains(bearer));
            assertThat(request.unsignedReason()).isNull();
            assertThat(request.target()).isEqualTo(subscriberUrl);

            String rawJson = rawRequestInfoJson(id);
            assertThat(rawJson).as("the stored JSON itself never carries the secret or the bearer value")
                    .doesNotContain(secret).doesNotContain(bearer);
        }
    }

    private static String rawRequestInfoJson(String jobId) {
        var row = DispatchJobFixture.DB.select(Tables.MSG_DISPATCH_JOB_ATTEMPTS.REQUEST_INFO)
                .from(Tables.MSG_DISPATCH_JOB_ATTEMPTS)
                .where(Tables.MSG_DISPATCH_JOB_ATTEMPTS.DISPATCH_JOB_ID.eq(jobId))
                .fetchOne(Tables.MSG_DISPATCH_JOB_ATTEMPTS.REQUEST_INFO);
        return row == null ? null : row.data();
    }

    /// T8: 401/403 are terminal on the FIRST attempt (a retry would send the
    /// identical credentials), with the documented log line; a 500 still
    /// retries. Mutant: retry a 401/403 like any other failure.
    @Test
    void t8_401FailsOnTheFirstAttemptWithoutRetrying() {
        String id = seedJob(Seed.of(code("proc-t8-401")));
        status.set(401);
        responseBody.set("Invalid webhook signature.");

        var log = (Logger) LoggerFactory.getLogger(ProcessingApi.class);
        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        log.addAppender(captured);
        try {
            var r = process(id);
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(hits.get()).as("exactly one delivery attempt — never retried").isEqualTo(1);

            DispatchJob after = reload(id);
            assertThat(after.status()).as("terminal on the FIRST 401, not after the retry ladder")
                    .isEqualTo(DispatchJobStatus.FAILED);

            assertThat(captured.list).anySatisfy(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.WARN);
                assertThat(e.getFormattedMessage()).contains("dispatch failed (subscriber refused credentials; not retried)");
            });
        } finally {
            log.detachAppender(captured);
        }
    }

    @Test
    void t8_403FailsOnTheFirstAttemptTheSameWayAs401() {
        String id = seedJob(Seed.of(code("proc-t8-403")));
        status.set(403);

        var r = process(id);

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(hits.get()).isEqualTo(1);
        assertThat(reload(id).status()).isEqualTo(DispatchJobStatus.FAILED);
    }

    @Test
    void t8_500StillRetriesUnlike401Or403() {
        String id = seedJob(Seed.of(code("proc-t8-500")));
        status.set(500);

        var r = process(id);

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(hits.get()).isEqualTo(1);
        DispatchJob after = reload(id);
        assertThat(after.status()).as("a 500 still retries — only 401/403 fail fast").isEqualTo(DispatchJobStatus.PENDING);
        assertThat(after.scheduledFor()).as("a retry is scheduled, not a terminal failure").isNotNull();
    }

    /// T9: the delivery signature is `HMAC-SHA256(secret, timestamp ‖ body)`,
    /// hex — pinned against an INDEPENDENTLY computed vector (python's
    /// `hmac`/`hashlib`, not `WebhookSigner` itself), so a change to the byte
    /// format (order, encoding, or the timestamp's shape) is caught. Mutant:
    /// change the timestamp format (e.g. drop the milliseconds, or use
    /// RFC 3339's 6-digit form) — the signature would no longer match this
    /// fixed vector.
    @Test
    void t9_signatureVectorMatchesAnIndependentlyComputedHmac() {
        String secret = "catchup-2026-09-22-vector-secret";
        String id = seedJob(Seed.of(code("proc-t9-vector")).withPayload("{\"hello\":\"world\"}"));
        setDataOnly(id, true); // the fixture default, made explicit: the raw payload is the exact signed body
        status.set(200);
        Clock fixedClock = Clock.fixed(Instant.parse("2026-09-22T16:07:13Z"), ZoneOffset.UTC);
        DeliveryCredentials creds = job -> DeliveryCredentials.Resolved.signed(null, secret, "t9-sa");
        try (TestHttp t9Http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            ProcessingApi.register(routes, new ProcessingApi.State(repo, verifier,
                    new SubscriberDelivery(SubscriberDelivery.defaultClient(), ClientCodeResolver.none()), creds, fixedClock));
        })) {
            lastHeaders.clear();
            var r = process(t9Http, id);

            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(lastHeaders.get("x-flowcatalyst-timestamp")).isEqualTo("2026-09-22T16:07:13.000Z");
            assertThat(lastHeaders.get("x-flowcatalyst-signature"))
                    .as("HMAC-SHA256(secret, timestamp + body), hex — independently computed vector")
                    .isEqualTo("65bfec50c89a7c4a9db269dcb5bd910371b1e6ecb705bbdfcd5aae33cb87d965");
        }
    }

    // ── oversized subscriber response is capped at the network read, not just on write ──

    /// Audit finding: `SubscriberDelivery` used to read the WHOLE response
    /// with `BodyHandlers.ofByteArray()` and truncate afterward — a hostile
    /// or chatty subscriber could balloon this process's memory before the
    /// cap ever ran. It now bounds the network read itself
    /// ([SubscriberDelivery#MAX_RESPONSE_BODY], 64 KiB) via
    /// `readNBytes`+discard. Pinned two ways: the stored attempt body is at
    /// most the cap (not the full 1 MiB the subscriber sent), AND the
    /// delivery still classifies as Delivered/COMPLETED — an oversized body
    /// is not itself a failure.
    @Test
    void oversizedResponseBodyIsCappedAtTheNetworkReadAndStillClassifiesAsDelivered() {
        String id = seedJob(Seed.of(code("proc-bigbody")));
        status.set(200);
        responseBody.set("x".repeat(1024 * 1024)); // 1 MiB, well over the 64 KiB cap

        var r = process(id);

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(json(r).get("ack").asBoolean()).isTrue();
        DispatchJob after = reload(id);
        assertThat(after.status()).as("an oversized body is not a delivery failure")
                .isEqualTo(DispatchJobStatus.COMPLETED);

        var attempts = repo.attemptsByJob(id);
        assertThat(attempts).hasSize(1);
        var attempt = attempts.getFirst();
        assertThat(attempt.success()).isTrue();
        assertThat(attempt.responseBody()).as("stored body is capped, not the full 1 MiB the subscriber sent")
                .hasSize(SubscriberDelivery.MAX_RESPONSE_BODY);
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

    // ── the claim: one delivery per job, however many callbacks arrive ──

    /// The duplicate-delivery guard (spec §5), and the reason the claim is a
    /// status-guarded conditional UPDATE rather than the unguarded flip it used
    /// to be: the `isTerminal()` check above it reads an UNLOCKED row, so two
    /// callbacks for one job — a queue redelivery racing an attempt still in
    /// flight, or a restarted router re-sending — both pass it. Only the claim's
    /// row count separates them.
    ///
    /// The subscriber is held for [#subscriberDelayMillis] so the loser reaches
    /// the claim while the winner is still inside its delivery; asserting that
    /// exactly one caller was told `already claimed` is what pins the loser to
    /// the CLAIM rather than to the terminal check it would hit if the two
    /// requests happened to serialise.
    ///
    /// Mutant: drop `AND status IN ('PENDING','QUEUED')` from
    /// `DispatchJobRepository#claimForDelivery` and the subscriber is called
    /// twice.
    @Test
    void twoConcurrentCallbacksForOneJobDeliverToTheSubscriberExactlyOnce() throws Exception {
        String id = seedJob(Seed.of(code("proc-race")));
        subscriberDelayMillis.set(400);

        var bothReady = new CyclicBarrier(2);
        List<HttpResponse<String>> responses;
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = threads.submit(() -> {
                bothReady.await();
                return process(id);
            });
            var second = threads.submit(() -> {
                bothReady.await();
                return process(id);
            });
            responses = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        }

        assertThat(hits.get()).as("the subscriber is called once per JOB, not once per callback").isEqualTo(1);
        assertThat(responses).allSatisfy(r -> {
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(json(r).get("ack").asBoolean()).as("both callbacks ACK — neither is redelivered").isTrue();
        });
        assertThat(responses.stream().filter(ProcessingApiTest::lostTheClaim).count())
                .as("exactly one caller lost the claim (and lost it to the claim, not to the terminal check)")
                .isEqualTo(1);

        assertThat(repo.attemptsByJob(id)).as("one delivery, one attempt row").hasSize(1);
        assertThat(reload(id).status()).isEqualTo(DispatchJobStatus.COMPLETED);
    }

    /// The same guard without the race: a row already `PROCESSING` is a delivery
    /// someone else owns. `isTerminal()` is false for `PROCESSING`, so before the
    /// claim this callback delivered a second time.
    @Test
    void aJobAlreadyBeingDeliveredIsAckedWithoutASecondDelivery() {
        String id = seedJob(Seed.of(code("proc-inflight")).withStatus("PROCESSING"));

        var r = process(id);

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(json(r).get("ack").asBoolean()).as("ACK — redelivering would not help").isTrue();
        assertThat(lostTheClaim(r)).isTrue();
        assertThat(hits.get()).as("no second call to the subscriber").isZero();
        assertThat(repo.attemptsByJob(id)).as("no attempt row for a delivery we never made").isEmpty();
        assertThat(reload(id).status()).as("and the other delivery's row is left alone")
                .isEqualTo(DispatchJobStatus.PROCESSING);
    }

    private static boolean lostTheClaim(HttpResponse<String> r) {
        JsonNode message = json(r).get("message");
        return message != null && !message.isNull() && "already claimed".equals(message.asText());
    }

    // ── injected repository failures: the three 500 ack:false branches (audit finding, test-gap) ──

    /// A thin decorator over the real [DispatchJobRepository] (via
    /// [ProcessingRepository], the seam `ProcessingApi` was narrowed to for
    /// exactly this purpose): every call delegates except the ONE this test
    /// configures to fail, so a genuine 500/`ack:false` branch is pinned
    /// against a real database instead of only reasoned about from reading
    /// the code — the three prior audit finding was that nothing could make
    /// any of them actually happen.
    private static final class FailingRepo implements ProcessingRepository {
        private final ProcessingRepository delegate;
        boolean failFindById;
        boolean failGroupHeldBefore;
        boolean failReschedule;
        boolean failClaim;

        FailingRepo(ProcessingRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<DispatchJob> findById(String id) {
            if (failFindById) throw new RuntimeException("injected: load failed");
            return delegate.findById(id);
        }

        @Override
        public boolean groupHeldBefore(DispatchJob job) {
            if (failGroupHeldBefore) throw new RuntimeException("injected: groupHeldBefore failed");
            return delegate.groupHeldBefore(job);
        }

        @Override
        public void reschedule(String id, Instant createdAt, Instant scheduledFor) {
            if (failReschedule) throw new RuntimeException("injected: reschedule failed");
            delegate.reschedule(id, createdAt, scheduledFor);
        }

        @Override
        public boolean claimForDelivery(String id, Instant createdAt) {
            if (failClaim) throw new RuntimeException("injected: claim failed");
            return delegate.claimForDelivery(id, createdAt);
        }

        @Override
        public void recordAttempt(String jobId, int attemptNumber, boolean success, Integer responseCode,
                                   String responseBody, String errorMessage, AttemptErrorType errorType,
                                   io.flowcatalyst.platform.dispatchjob.Attempt.RequestInfo requestInfo,
                                   Instant attemptedAt, Instant completedAt, Long durationMillis) {
            delegate.recordAttempt(jobId, attemptNumber, success, responseCode, responseBody, errorMessage,
                    errorType, requestInfo, attemptedAt, completedAt, durationMillis);
        }

        @Override
        public void markCompleted(String id, Instant createdAt, Instant completedAt, Long durationMillis) {
            delegate.markCompleted(id, createdAt, completedAt, durationMillis);
        }

        @Override
        public void scheduleRetry(String id, Instant createdAt, Instant scheduledFor, int attemptCount, String lastError) {
            delegate.scheduleRetry(id, createdAt, scheduledFor, attemptCount, lastError);
        }

        @Override
        public void markFailed(String id, Instant createdAt, String lastError) {
            delegate.markFailed(id, createdAt, lastError);
        }
    }

    private TestHttp httpOver(FailingRepo failing) {
        return TestHttp.routes(routes -> {
            HttpError.install(routes);
            ProcessingApi.register(routes,
                    new ProcessingApi.State(failing, verifier, new SubscriberDelivery(SubscriberDelivery.defaultClient(), ClientCodeResolver.none())));
        });
    }

    @Test
    void loadFailureIsA500ThatNacksWithoutDelivering() {
        var failing = new FailingRepo(repo);
        failing.failFindById = true;
        try (TestHttp failingHttp = httpOver(failing)) {
            String id = seedJob(Seed.of(code("proc-loadfail")));

            var body = "{\"messageId\":\"%s\"}".formatted(id);
            var r = failingHttp.post("/api/dispatch/process", body, "Authorization", "Bearer " + verifier.sign(id));

            assertThat(r.statusCode()).isEqualTo(500);
            assertThat(json(r).get("ack").asBoolean()).isFalse();
            assertThat(hits.get()).as("no delivery attempted").isZero();
        }
    }

    @Test
    void groupHeldBeforeFailureIsA500ThatNacksAndLeavesTheJobUntouched() {
        var failing = new FailingRepo(repo);
        failing.failGroupHeldBefore = true;
        try (TestHttp failingHttp = httpOver(failing)) {
            String group = "grp-checkfail-" + RUN;
            String id = seedJob(Seed.of(code("proc-checkfail")).withMode("BLOCK_ON_ERROR")
                    .withMessageGroup(group).withSequence(1));

            var body = "{\"messageId\":\"%s\"}".formatted(id);
            var r = failingHttp.post("/api/dispatch/process", body, "Authorization", "Bearer " + verifier.sign(id));

            assertThat(r.statusCode()).isEqualTo(500);
            assertThat(json(r).get("ack").asBoolean()).isFalse();
            assertThat(hits.get()).as("no delivery attempted — the check failed before dispatch").isZero();
            assertThat(reload(id).status()).as("job status untouched").isEqualTo(DispatchJobStatus.PENDING);
        }
    }

    @Test
    void rescheduleFailureWhileHeldIsA500ThatNacksAndLeavesTheJobUntouched() {
        var failing = new FailingRepo(repo);
        failing.failReschedule = true;
        try (TestHttp failingHttp = httpOver(failing)) {
            // A backed-off PENDING sibling (not a FAILED one) holds the group for
            // groupHeldBefore's full holding predicate exactly like
            // blockOnErrorJobHeldByAnEarlierBackedOffSiblingIsNeverDelivered above, but —
            // deliberately, unlike a FAILED head — never matches sweepStrandedSiblings'
            // narrower FAILED/ERROR-only predicate. This test intentionally leaves `id`
            // stuck QUEUED (the injected failure IS the point); a FAILED head would leave a
            // row the reaper's own sweep (or any other test's direct sweepStrandedSiblings
            // call in this shared database) could later pick up and reset out from under
            // this assertion — this shape can never be "stranded" from the reaper's view.
            String group = "grp-revertfail-" + RUN;
            String sibling = seedJob(Seed.of(code("proc-revertfail-sib")).withMode("BLOCK_ON_ERROR")
                    .withMessageGroup(group).withSequence(1).withStatus("PENDING"));
            backdate(sibling, Instant.now().plusSeconds(300));
            String id = seedJob(Seed.of(code("proc-revertfail")).withMode("BLOCK_ON_ERROR").withMessageGroup(group)
                    .withSequence(2).withStatus("QUEUED"));

            var body = "{\"messageId\":\"%s\"}".formatted(id);
            var r = failingHttp.post("/api/dispatch/process", body, "Authorization", "Bearer " + verifier.sign(id));

            assertThat(r.statusCode()).isEqualTo(500);
            assertThat(json(r).get("ack").asBoolean()).isFalse();
            assertThat(hits.get()).as("group held — no HTTP call was ever made").isZero();
            // A successful revert would have moved this to PENDING (spec §5, §9's first
            // invariant); the injected failure means the job is left exactly as it was.
            assertThat(reload(id).status()).as("job status untouched — still QUEUED, never reverted")
                    .isEqualTo(DispatchJobStatus.QUEUED);
        }
    }

    /// A claim that THREW leaves ownership unknown, and delivering anyway is
    /// exactly the duplicate the claim exists to prevent — so unlike the
    /// best-effort flip it replaced, a claim failure NACKs and makes no call.
    ///
    /// Mutant: swallow the exception and deliver anyway (what the code did
    /// before) and both the 500 and the zero hit count fail.
    @Test
    void claimFailureIsA500ThatNacksWithoutDelivering() {
        var failing = new FailingRepo(repo);
        failing.failClaim = true;
        try (TestHttp failingHttp = httpOver(failing)) {
            String id = seedJob(Seed.of(code("proc-claimfail")));

            var body = "{\"messageId\":\"%s\"}".formatted(id);
            var r = failingHttp.post("/api/dispatch/process", body, "Authorization", "Bearer " + verifier.sign(id));

            assertThat(r.statusCode()).isEqualTo(500);
            assertThat(json(r).get("ack").asBoolean()).as("NACK so the queue redelivers").isFalse();
            assertThat(hits.get()).as("ownership unknown — no delivery").isZero();
            assertThat(repo.attemptsByJob(id)).as("no attempt row").isEmpty();
            assertThat(reload(id).status()).as("job left exactly where a redelivery can pick it up")
                    .isEqualTo(DispatchJobStatus.PENDING);
        }
    }

    // ── the delivered webhook names its tenant (docs/spec/webhook-client-code.md) ──

    /// T1 — a client-scoped job's non-`dataOnly` envelope carries `clientCode`
    /// (the client's `identifier`) alongside the existing `clientId`.
    /// Mutant: drop the field.
    @Test
    void clientScopedEnvelopeCarriesClientCodeAlongsideClientId() {
        String identifier = "acme-" + RUN;
        String clientId = insertClient(identifier);
        String id = seedJob(Seed.of(code("proc-clientcode")).withClientId(clientId));
        setDataOnly(id, false);
        status.set(200);
        lastBody.set(null);

        var r = process(clientCodeHttp, id);

        assertThat(r.statusCode()).isEqualTo(200);
        var envelope = Json.MAPPER.readTree(lastBody.get());
        assertThat(envelope.get("clientId").asText()).isEqualTo(clientId);
        assertThat(envelope.get("clientCode").asText()).isEqualTo(identifier);
    }

    /// T2 — a platform-scoped job (no `clientId`) has neither key in its
    /// envelope AND no `X-FlowCatalyst-Client` header — the header shape the
    /// envelope alone cannot cover. Mutant: emit the header with an empty half.
    @Test
    void platformScopedJobOmitsBothEnvelopeKeysAndTheHeader() {
        String id = seedJob(Seed.of(code("proc-platformcode")));
        setDataOnly(id, false);
        status.set(200);
        lastBody.set(null);
        lastHeaders.clear();

        var r = process(clientCodeHttp, id);

        assertThat(r.statusCode()).isEqualTo(200);
        var envelope = Json.MAPPER.readTree(lastBody.get());
        assertThat(envelope.has("clientId")).as("no clientId on a platform-scoped job").isFalse();
        assertThat(envelope.has("clientCode")).isFalse();
        assertThat(lastHeaders).doesNotContainKey("x-flowcatalyst-client");
    }

    /// T3 — the header is exactly `{clientId}:{clientCode}`, not the code
    /// alone. Mutant: send the code alone.
    @Test
    void headerIsExactlyClientIdColonClientCode() {
        String identifier = "widget-" + RUN;
        String clientId = insertClient(identifier);
        String id = seedJob(Seed.of(code("proc-headershape")).withClientId(clientId));
        setDataOnly(id, false);
        status.set(200);
        lastHeaders.clear();

        var r = process(clientCodeHttp, id);

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(lastHeaders.get("x-flowcatalyst-client"))
                .as("exact bytes of the header for a sample job")
                .isEqualTo(clientId + ":" + identifier);
    }

    /// T4 — a `dataOnly` delivery (the DB default `msg_dispatch_jobs.data_only
    /// = true` this fixture never overrides here) still carries the header,
    /// and its body is the raw payload byte-for-byte, untouched by
    /// `clientCode`. Mutant: skip the header in `dataOnly` mode.
    @Test
    void dataOnlyDeliveryKeepsTheRawBodyButStillCarriesTheHeader() {
        String identifier = "raw-" + RUN;
        String clientId = insertClient(identifier);
        String payload = "{\"raw\":true,\"n\":42}";
        String id = seedJob(Seed.of(code("proc-dataonly")).withClientId(clientId).withPayload(payload));
        // dataOnly left at the DB default (true) — deliberately not calling setDataOnly.
        status.set(200);
        lastBody.set(null);
        lastHeaders.clear();

        var r = process(clientCodeHttp, id);

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(new String(lastBody.get(), StandardCharsets.UTF_8))
                .as("dataOnly body is the raw payload, unchanged")
                .isEqualTo(payload);
        assertThat(lastHeaders.get("x-flowcatalyst-client")).isEqualTo(clientId + ":" + identifier);
    }

    /// T5 — an unresolvable client (a `clientId` with no matching
    /// `tnt_clients` row): the delivery still happens, with no `clientCode`
    /// and no header. Mutant: fail or block the delivery.
    @Test
    void unresolvableClientStillDeliversWithNeitherCodeNorHeader() {
        String bogusClientId = "clt_bogus_" + RUN;
        String id = seedJob(Seed.of(code("proc-unresolvable")).withClientId(bogusClientId));
        setDataOnly(id, false);
        status.set(200);
        lastBody.set(null);
        lastHeaders.clear();

        var r = process(clientCodeHttp, id);

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(json(r).get("ack").asBoolean()).isTrue();
        assertThat(hits.get()).as("the subscriber was still called").isEqualTo(1);
        var envelope = Json.MAPPER.readTree(lastBody.get());
        assertThat(envelope.has("clientCode")).isFalse();
        assertThat(lastHeaders).doesNotContainKey("x-flowcatalyst-client");
        assertThat(reload(id).status()).isEqualTo(DispatchJobStatus.COMPLETED);
    }

    /// T6 — caching: two deliveries for the same (resolvable) client perform
    /// exactly one repository lookup; a client that missed once still
    /// resolves on a later delivery, proving the miss was not cached.
    /// Mutant: cache negatives for ever.
    @Test
    void oneLookupPerResolvedClientAndAMissDoesNotStickForever() {
        var lookup = new CountingLookup();
        try (TestHttp countingHttp = TestHttp.routes(routes -> {
            HttpError.install(routes);
            ProcessingApi.register(routes, new ProcessingApi.State(repo, verifier,
                    new SubscriberDelivery(SubscriberDelivery.defaultClient(), new ClientCodeResolver(lookup)),
                    DeliveryCredentials.none(), Clock.systemUTC()));
        })) {
            // msg_dispatch_jobs.client_id is varchar(17) — these fake ids (never a
            // real TSID, since CountingLookup ignores the value entirely) must fit.
            String clientId = "cid-hit-" + RUN;
            Client resolved = Client.create("Cache test", ClientIdentifier.parse("cache-" + RUN));
            lookup.client = resolved;

            String id1 = seedJob(Seed.of(code("proc-cache1")).withClientId(clientId));
            setDataOnly(id1, false);
            lastHeaders.clear();
            var r1 = process(countingHttp, id1);
            assertThat(r1.statusCode()).isEqualTo(200);
            assertThat(lastHeaders.get("x-flowcatalyst-client")).isEqualTo(clientId + ":" + resolved.identifier());

            String id2 = seedJob(Seed.of(code("proc-cache2")).withClientId(clientId));
            setDataOnly(id2, false);
            lastHeaders.clear();
            var r2 = process(countingHttp, id2);
            assertThat(r2.statusCode()).isEqualTo(200);
            assertThat(lastHeaders.get("x-flowcatalyst-client")).isEqualTo(clientId + ":" + resolved.identifier());
            assertThat(lookup.calls.get())
                    .as("the second delivery for the same client reused the cached hit — one lookup total")
                    .isEqualTo(1);

            // A different, still-unresolvable client id: the miss must not stick.
            String missingClientId = "cid-miss-" + RUN;
            lookup.client = null;
            String id3 = seedJob(Seed.of(code("proc-miss1")).withClientId(missingClientId));
            setDataOnly(id3, false);
            lastHeaders.clear();
            var r3 = process(countingHttp, id3);
            assertThat(r3.statusCode()).isEqualTo(200);
            assertThat(lastHeaders).as("unresolvable — no header at all").doesNotContainKey("x-flowcatalyst-client");

            Client belated = Client.create("Belated", ClientIdentifier.parse("belated-" + RUN));
            lookup.client = belated;
            String id4 = seedJob(Seed.of(code("proc-miss2")).withClientId(missingClientId));
            setDataOnly(id4, false);
            lastHeaders.clear();
            var r4 = process(countingHttp, id4);
            assertThat(r4.statusCode()).isEqualTo(200);
            assertThat(lastHeaders.get("x-flowcatalyst-client"))
                    .as("a client that missed once resolves on a later delivery")
                    .isEqualTo(missingClientId + ":" + belated.identifier());
        }
    }

    /// T7 — `X-FlowCatalyst-Signature` still verifies over the body alone
    /// even though the `X-FlowCatalyst-Client` header is present. Mutant:
    /// fold the header into the signed material.
    @Test
    void signatureStillVerifiesOverTheBodyAloneWithTheClientHeaderPresent() {
        String secret = "signing-secret-t7-" + RUN;
        DeliveryCredentials creds = job -> new DeliveryCredentials.Resolved(null, secret);
        String identifier = "signed-" + RUN;
        String clientId = insertClient(identifier);
        try (TestHttp signedHttp = TestHttp.routes(routes -> {
            HttpError.install(routes);
            ProcessingApi.register(routes, new ProcessingApi.State(repo, verifier,
                    new SubscriberDelivery(SubscriberDelivery.defaultClient(), new ClientCodeResolver(clientRepo::findById)),
                    creds, Clock.systemUTC()));
        })) {
            String id = seedJob(Seed.of(code("proc-signed-t7")).withClientId(clientId));
            setDataOnly(id, false);
            lastHeaders.clear();
            lastBody.set(null);

            var r = process(signedHttp, id);

            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(lastHeaders.get("x-flowcatalyst-client")).isEqualTo(clientId + ":" + identifier);
            assertThat(lastHeaders).containsKey("x-flowcatalyst-signature");

            String expected = WebhookSigner.sign(secret, lastHeaders.get("x-flowcatalyst-timestamp"), lastBody.get());
            assertThat(lastHeaders.get("x-flowcatalyst-signature"))
                    .as("recomputed over the body alone still matches with the header present")
                    .isEqualTo(expected);
        }
    }

    /// A fake [ClientCodeResolver.Lookup] that counts calls and lets the test
    /// flip whether the client currently "exists" — [#oneLookupPerResolvedClientAndAMissDoesNotStickForever]
    /// needs to observe the repository call count directly, which a real
    /// database cannot cheaply offer.
    private static final class CountingLookup implements ClientCodeResolver.Lookup {
        final AtomicInteger calls = new AtomicInteger();
        volatile Client client;

        @Override
        public Optional<Client> findById(String id) {
            calls.incrementAndGet();
            return Optional.ofNullable(client);
        }
    }
}
