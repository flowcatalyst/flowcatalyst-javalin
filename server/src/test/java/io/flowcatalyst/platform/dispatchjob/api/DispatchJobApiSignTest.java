package io.flowcatalyst.platform.dispatchjob.api;

import io.flowcatalyst.platform.serviceaccount.SigningAccounts;
import io.flowcatalyst.platform.dispatchjob.processing.DeliverySigningGuard;
import io.flowcatalyst.platform.subscription.SubscriptionRepository;
import io.flowcatalyst.platform.connection.ConnectionRepository;
import com.sun.net.httpserver.HttpServer;
import io.flowcatalyst.db.generated.Tables;
import io.flowcatalyst.platform.dispatchjob.DispatchJobFixture;
import io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.Seed;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository;
import io.flowcatalyst.platform.dispatchjob.processing.ClientCodeResolver;
import io.flowcatalyst.platform.dispatchjob.processing.DeliveryCredentials;
import io.flowcatalyst.platform.dispatchjob.processing.SubscriberDelivery;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.platform.shared.auth.ClaimsResolver;
import io.flowcatalyst.platform.shared.auth.JwtVerifier;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.router.wire.WebhookSigner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.RUN;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.code;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.seedWriteRow;
import static org.assertj.core.api.Assertions.assertThat;

/// `POST /api/dispatch-jobs/{id}/sign` (`docs/spec/catch-up-2026-09-22.md`
/// slice C3, test T10): builds the delivery exactly as `/api/dispatch/process`
/// would — a REAL, independently-verifiable signature — but never sends it,
/// masks `Authorization`, and is gated on `dispatch-job:view-raw` specifically
/// (not the plain `view` permission every other id-addressed route but this
/// one and `{id}/raw` accepts).
///
/// The "nothing was sent" half is pinned against a REAL loopback target that
/// records hits — a mutant that swaps [SubscriberDelivery#plan] for
/// [SubscriberDelivery#deliver] would make this call the target and fail the
/// `hits` assertion, not just leave an assertion that would pass either way.
@SuppressWarnings("deprecation") // JsonNode#asText() — see DispatchJobRepository's own class doc
class DispatchJobApiSignTest {

    private static final String CLIENT_A = "cli_sgn" + RUN;
    private static final String CODE = code("sign");
    private static final String SECRET = "sign-test-secret-value";

    private static final String[] RAW_VIEWER_A = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, CLIENT_A,
            Authenticator.TEST_PERMISSIONS, "platform:messaging:dispatch-job:view-raw"};
    private static final String[] VIEWER_A = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, CLIENT_A,
            Authenticator.TEST_PERMISSIONS, "platform:messaging:dispatch-job:view"};

    private static TestHttp http;
    private static HttpServer target;
    private static final AtomicInteger hits = new AtomicInteger();
    private static String jobId;

    @BeforeAll
    static void start() throws IOException {
        target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        target.createContext("/hook", ex -> {
            hits.incrementAndGet();
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        target.start();
        String targetUrl = "http://127.0.0.1:" + target.getAddress().getPort() + "/hook";

        jobId = seedWriteRow(Seed.of(CODE).withClientId(CLIENT_A));
        DispatchJobFixture.DB.update(Tables.MSG_DISPATCH_JOBS)
                .set(Tables.MSG_DISPATCH_JOBS.TARGET_URL, targetUrl)
                .where(Tables.MSG_DISPATCH_JOBS.ID.eq(jobId))
                .execute();

        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080",
                new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        // A fixed secret, distinct from anything a real resolver would produce, so the
        // recomputed signature can only match if the handler actually used THIS
        // credentials instance — the same-resolver-instance requirement (spec).
        DeliveryCredentials credentials = job -> new DeliveryCredentials.Resolved("bearer-secret-value", SECRET,
                "", "sign-sa-code");
        var state = new DispatchJobApi.SignState(new DispatchJobRepository(DispatchJobFixture.DS),
                new SubscriberDelivery(SubscriberDelivery.defaultClient(), ClientCodeResolver.none()),
                credentials, Clock.systemUTC(),
                new DeliverySigningGuard(new SubscriptionRepository(DispatchJobFixture.DS)::findById,
                        new ConnectionRepository(DispatchJobFixture.DS)::findById, SigningAccounts.reach(DispatchJobFixture.DS)));
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before("/api/*", auth);
            DispatchJobApi.registerSign(routes, "/api/dispatch-jobs", state);
        });
    }

    @AfterAll
    static void stop() {
        http.close();
        target.stop(0);
    }

    @BeforeEach
    void resetHits() {
        hits.set(0);
    }

    @Test
    void t10SignsTheRealDeliveryWithoutSendingIt() {
        var r = http.post("/api/dispatch-jobs/" + jobId + "/sign", "", RAW_VIEWER_A);
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode body = json(r);

        // The target genuinely never saw a request — this is what would fail
        // if the handler called #deliver instead of #plan.
        assertThat(hits.get()).as("mutant: send it").isZero();

        JsonNode req = body.get("request");
        assertThat(req.get("bearer").asBoolean()).isTrue();
        assertThat(req.get("signature").asBoolean()).isTrue();
        assertThat(req.get("signedBy").asText()).isEqualTo("sign-sa-code");
        String timestamp = req.get("timestamp").asText();
        assertThat(timestamp).isNotBlank();

        String bearerHeader = body.get("headers").get("Authorization").asText();
        assertThat(bearerHeader).as("mutant: return the raw bearer")
                .isEqualTo("Bearer ••••••")
                .doesNotContain("bearer-secret-value");

        String bodyStr = body.get("body").asText();
        String expectedSignature = WebhookSigner.sign(SECRET, timestamp, bodyStr.getBytes(StandardCharsets.UTF_8));
        assertThat(body.get("headers").get("X-FlowCatalyst-Signature").asText())
                .as("the signature verifies against the RETURNED timestamp + body with the connection's secret")
                .isEqualTo(expectedSignature);
        assertThat(body.get("headers").get("X-FlowCatalyst-Timestamp").asText()).isEqualTo(timestamp);
    }

    @Test
    void t10GatedOnViewRawNotPlainView() {
        // The same permission `{id}/raw` needs, not the coarser `view` every
        // other id-addressed route but this one and `{id}/raw` accepts.
        var r = http.post("/api/dispatch-jobs/" + jobId + "/sign", "", VIEWER_A);
        assertThat(r.statusCode()).as("mutant: gate dropped").isEqualTo(403);
        assertThat(hits.get()).isZero();
    }

    /// security-fixes-2026-09-24 S3.2: the signed plan IS a signature over the
    /// job's body, so it is refused when the identity that would sign is out
    /// of the caller's reach — here an operator's anchor-tier account, named
    /// by the caller's own client's subscription. Before, any visible job was
    /// signed, which made this route a signing oracle.
    @Test
    void signRefusesAJobWhoseSignerIsOutOfTheCallersReach() {
        String operatorAccount = SigningAccounts.seed(DispatchJobFixture.DS, java.util.List.of(), null);
        String subscriptionId = SigningAccounts.subscription(DispatchJobFixture.DS, CLIENT_A, operatorAccount, null);
        String outOfReach = seedWriteRow(Seed.of(code("sign-oor")).withClientId(CLIENT_A));
        DispatchJobFixture.DB.update(Tables.MSG_DISPATCH_JOBS)
                .set(Tables.MSG_DISPATCH_JOBS.SUBSCRIPTION_ID, subscriptionId)
                .where(Tables.MSG_DISPATCH_JOBS.ID.eq(outOfReach))
                .execute();

        var r = http.post("/api/dispatch-jobs/" + outOfReach + "/sign", "", RAW_VIEWER_A);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(403);
        assertThat(r.body()).doesNotContain("X-FlowCatalyst-Signature");
        assertThat(hits.get()).isZero();
    }

    @Test
    void t10RequiresAuthentication() {
        var r = http.post("/api/dispatch-jobs/" + jobId + "/sign", "");
        assertThat(r.statusCode()).isEqualTo(403);
    }

    private static JsonNode json(HttpResponse<String> r) {
        try {
            return Json.MAPPER.readTree(r.body());
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + r.body(), e);
        }
    }
}
