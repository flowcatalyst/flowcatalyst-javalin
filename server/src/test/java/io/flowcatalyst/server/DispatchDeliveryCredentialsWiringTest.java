package io.flowcatalyst.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.flowcatalyst.db.generated.Tables;
import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ApplicationType;
import io.flowcatalyst.platform.dispatchjob.DispatchJobFixture;
import io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.Seed;
import io.flowcatalyst.platform.dispatchjob.settled.HmacTokenVerifier;
import io.flowcatalyst.platform.shared.database.Pools;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.router.wire.WebhookSigner;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import io.flowcatalyst.testpg.TestPg;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/// S8 (`docs/spec/dispatch-delivery-credentials.md` §5): the composition
/// root, not a hand-built `ProcessingApi.State`, is what is under test — a
/// real [Server] booted on [TestPg], run one job through
/// `POST /api/dispatch/process`, receives a signed request. Every other
/// load-bearing behaviour (S1–S7) is pinned against `ProcessingApi.State`
/// directly in `ProcessingApiTest`/`DeliveryCredentialsTest`; this class
/// exists ONLY to prove `Platform` wires the real
/// `DeliveryCredentials.resolve` resolver instead of leaving
/// `DeliveryCredentials.none()` in place (the exact regression the spec's
/// defect section describes).
///
/// Mutant: revert `Platform`'s `ProcessingApi.State` construction to the
/// 3-arg convenience constructor (`DeliveryCredentials.none()` default) —
/// the subscriber would receive neither `Authorization` nor
/// `X-FlowCatalyst-Signature`, failing this test's header assertions.
@SuppressWarnings("deprecation") // JsonNode#asText() — see DispatchJobRepository's own class doc
class DispatchDeliveryCredentialsWiringTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);

    private static final List<String> insertedApplications = new ArrayList<>();
    private static final List<String> insertedServiceAccounts = new ArrayList<>();
    private static final List<String> insertedJobs = new ArrayList<>();

    private static Server.Running running;
    private static String appKey;
    private static final HttpClient http = HttpClient.newHttpClient();

    private static HttpServer subscriber;
    private static String subscriberUrl;
    private static final AtomicInteger hits = new AtomicInteger();
    private static final AtomicReference<byte[]> lastBody = new AtomicReference<>();
    private static final Map<String, String> lastHeaders = new ConcurrentHashMap<>();

    @BeforeAll
    static void start() throws IOException {
        appKey = Encryption.generateKey();
        Env env = Env.load(Map.of(
                "FC_API_PORT", "0",
                "FC_METRICS_PORT", "0",
                "FC_PLATFORM_ENABLED", "true",
                "FLOWCATALYST_APP_KEY", appKey,
                // T10 (catch-up-2026-09-22.md slice C3): lets #signSharesTheSameResolverProcessUses
                // call `/api/dispatch-jobs/{id}/sign` as an authenticated anchor without minting a
                // real JWT — the dev-only `X-FC-Test-*` bypass every other Platform wiring test uses.
                "FC_AUTH_ALLOW_TEST_HEADERS", "true"));
        running = new Server(env, new Server.Mode.Platform(Pools.ofSingle(DS)), Server.Spa.none(),
                new PrometheusRegistry()).start();

        subscriber = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        subscriber.createContext("/hook", DispatchDeliveryCredentialsWiringTest::handle);
        subscriber.start();
        subscriberUrl = "http://127.0.0.1:" + subscriber.getAddress().getPort() + "/hook";
    }

    @AfterAll
    static void stop() {
        running.stop();
        subscriber.stop(0);
        if (!insertedJobs.isEmpty()) {
            DB.deleteFrom(Tables.MSG_DISPATCH_JOBS).where(Tables.MSG_DISPATCH_JOBS.ID.in(insertedJobs)).execute();
        }
        if (!insertedServiceAccounts.isEmpty()) {
            DB.deleteFrom(Tables.IAM_SERVICE_ACCOUNTS).where(Tables.IAM_SERVICE_ACCOUNTS.ID.in(insertedServiceAccounts)).execute();
        }
        if (!insertedApplications.isEmpty()) {
            DB.deleteFrom(Tables.APP_APPLICATIONS).where(Tables.APP_APPLICATIONS.ID.in(insertedApplications)).execute();
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        hits.incrementAndGet();
        lastBody.set(exchange.getRequestBody().readAllBytes());
        exchange.getRequestHeaders().forEach((k, v) -> lastHeaders.put(k.toLowerCase(Locale.ROOT), v.getFirst()));
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    private static String persistApplication(String code) {
        Application app = Application.create(ApplicationType.APPLICATION, code, code);
        try (Connection conn = DS.getConnection()) {
            conn.setAutoCommit(false);
            new ApplicationRepository(DS).persist(app, DbTx.wrapForBootstrap(conn));
            conn.commit();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        insertedApplications.add(app.id());
        return app.id();
    }

    private static void activeServiceAccount(String applicationId, String token, String signingSecret) {
        String id = EntityType.SERVICE_ACCOUNT.generate();
        DB.insertInto(Tables.IAM_SERVICE_ACCOUNTS)
                .set(Tables.IAM_SERVICE_ACCOUNTS.ID, id)
                // Suffixed with the account's own id, not just RUN: two test methods in one JVM
                // run share RUN, and a bare "wiring-svc-" + RUN collided on the unique code
                // constraint the moment a second test called this (T10 companion).
                .set(Tables.IAM_SERVICE_ACCOUNTS.CODE, "wiring-svc-" + RUN + "-" + id)
                .set(Tables.IAM_SERVICE_ACCOUNTS.NAME, "wiring test service account")
                .set(Tables.IAM_SERVICE_ACCOUNTS.APPLICATION_ID, applicationId)
                .set(Tables.IAM_SERVICE_ACCOUNTS.ACTIVE, true)
                .set(Tables.IAM_SERVICE_ACCOUNTS.WH_AUTH_TYPE, "BEARER_TOKEN")
                .set(Tables.IAM_SERVICE_ACCOUNTS.WH_AUTH_TOKEN_REF, token)
                .set(Tables.IAM_SERVICE_ACCOUNTS.WH_SIGNING_SECRET_REF, signingSecret)
                .execute();
        insertedServiceAccounts.add(id);
    }

    private static JsonNode json(HttpResponse<String> r) {
        try {
            return Json.MAPPER.readTree(r.body());
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + r.body(), e);
        }
    }

    @Test
    void aJobDeliveredThroughTheRealComposedServerIsSignedWithItsApplicationsCredentials() throws Exception {
        // Direct job (no subscription): the job's own code names the
        // application (spec §2 step 2) — exercises the same resolver
        // Platform wires, through the real HTTP listener.
        String appCode = "wiring-app-" + RUN;
        String token = "wiring-token-" + RUN;
        String secret = "wiring-secret-" + RUN;
        String appId = persistApplication(appCode);
        activeServiceAccount(appId, token, secret);

        String jobId = DispatchJobFixture.seedWriteRow(
                Seed.of(appCode + ":orders:order:created"));
        insertedJobs.add(jobId);
        DB.update(Tables.MSG_DISPATCH_JOBS)
                .set(Tables.MSG_DISPATCH_JOBS.TARGET_URL, subscriberUrl)
                .where(Tables.MSG_DISPATCH_JOBS.ID.eq(jobId))
                .execute();

        String authToken = HmacTokenVerifier.fromAppKey(appKey).sign(jobId);
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + running.apiPort() + "/api/dispatch/process"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"messageId\":\"" + jobId + "\"}"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + authToken)
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(json(response).get("ack").asBoolean()).isTrue();
        assertThat(hits.get()).as("the real composed server actually delivered to the subscriber").isEqualTo(1);

        assertThat(lastHeaders.get("authorization"))
                .as("the composition root resolved the REAL service account's bearer token, not none()")
                .isEqualTo("Bearer " + token);
        assertThat(lastHeaders).containsKey("x-flowcatalyst-signature");
        assertThat(lastHeaders).containsKey("x-flowcatalyst-timestamp");

        String expected = WebhookSigner.sign(secret, lastHeaders.get("x-flowcatalyst-timestamp"), lastBody.get());
        assertThat(lastHeaders.get("x-flowcatalyst-signature"))
                .as("signed with the resolved application's own secret, over the body actually delivered")
                .isEqualTo(expected);
    }

    /// T10 companion (catch-up-2026-09-22.md slice C3): `sign`, reached
    /// through the SAME real composed server, resolves the SAME application's
    /// REAL service account — the `sign` counterpart of this class's own
    /// mutant (reverting `Platform`'s wiring to `DeliveryCredentials.none()`)
    /// applied to `SignState` instead of `ProcessingApi.State`. A second,
    /// independently-built `DeliveryCredentials.resolve(...)` for `sign`
    /// (rather than reusing the one instance) answers identically here —
    /// both are pure, deterministic reads over the same fresh DB rows — so
    /// that specific sharing is a code-review property (`Platform`'s own
    /// comment), not one this test can tell apart from two correct copies.
    @Test
    void signResolvesTheSameApplicationsRealCredentialsThroughTheComposedServer() throws Exception {
        String appCode = "wiring-sign-app-" + RUN;
        String token = "wiring-sign-token-" + RUN;
        String secret = "wiring-sign-secret-" + RUN;
        String appId = persistApplication(appCode);
        activeServiceAccount(appId, token, secret);

        String jobId = DispatchJobFixture.seedWriteRow(Seed.of(appCode + ":orders:order:created"));
        insertedJobs.add(jobId);
        DB.update(Tables.MSG_DISPATCH_JOBS)
                .set(Tables.MSG_DISPATCH_JOBS.TARGET_URL, subscriberUrl)
                .where(Tables.MSG_DISPATCH_JOBS.ID.eq(jobId))
                .execute();

        var request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + running.apiPort() + "/api/dispatch-jobs/" + jobId + "/sign"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .header(io.flowcatalyst.platform.shared.auth.Authenticator.TEST_PRINCIPAL,
                        io.flowcatalyst.platform.shared.tsid.EntityType.PRINCIPAL.generate())
                .header(io.flowcatalyst.platform.shared.auth.Authenticator.TEST_SCOPE, "ANCHOR")
                .header(io.flowcatalyst.platform.shared.auth.Authenticator.TEST_PERMISSIONS, "platform:*:*:*")
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(hits.get()).as("sign never reaches the subscriber").isZero();
        JsonNode plan = json(response);

        assertThat(plan.get("headers").get("Authorization").asText())
                .as("the real bearer is never on the wire, even in the plan")
                .isEqualTo("Bearer ••••••")
                .doesNotContain(token);
        String timestamp = plan.get("headers").get("X-FlowCatalyst-Timestamp").asText();
        String expected = WebhookSigner.sign(secret, timestamp,
                plan.get("body").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(plan.get("headers").get("X-FlowCatalyst-Signature").asText())
                .as("signed with the SAME real application's secret /api/dispatch/process would use")
                .isEqualTo(expected);
    }
}
