package io.flowcatalyst.router.pool;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.flowcatalyst.router.observability.Warnings;
import io.flowcatalyst.router.policy.BreakerRegistry;
import io.flowcatalyst.router.policy.CircuitBreaker;
import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.router.wire.MediationOutcome;
import io.flowcatalyst.router.wire.MediationType;
import io.flowcatalyst.router.wire.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/// The status → outcome mapping and the breaker's part in it
/// (`docs/spec/router.md` §6).
class HttpMediatorTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> responseBody = new AtomicReference<>("");
    private final Map<String, String> responseHeaders = new ConcurrentHashMap<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final Map<String, String> lastHeaders = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();

    private final java.util.List<Raised> raised = new CopyOnWriteArrayList<>();

    private BreakerRegistry breakers;
    private HttpMediator mediator;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
        breakers = new BreakerRegistry(CircuitBreaker.Config.DEFAULTS, FIXED);
        mediator = new HttpMediator(HttpMediator.defaultClient(true), Duration.ofSeconds(5), breakers, FIXED,
                (severity, category, text) -> raised.add(new Raised(severity, category, text)));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        calls.incrementAndGet();
        lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        exchange.getRequestHeaders().forEach((k, v) -> lastHeaders.put(k.toLowerCase(), v.getFirst()));
        responseHeaders.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        var body = responseBody.get().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status.get(), body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    // ── Request shape ───────────────────────────────────────────────────

    // ── Operator warnings (§2.7) ────────────────────────────────────────

    @ParameterizedTest(name = "HTTP {0} warns an operator at {1}")
    @CsvSource({
            "400, ERROR,    bad request",
            "401, ERROR,    auth error",
            "403, ERROR,    auth error",
            "404, ERROR,    not found",
            "418, ERROR,    client error",
            "501, CRITICAL, not implemented",
            "301, ERROR,    redirect not followed",
            "500, ERROR,    'server error, rejected for review'",
            "505, ERROR,    'server error, rejected for review'",
    })
    @DisplayName("every permanent ACK-drop tells someone")
    void permanentDropsWarn(int status, Warnings.Severity severity, String detail) throws Exception {
        // These paths delete a message off the broker for good. Each is the
        // right call — none can succeed on a retry — but the warning is the
        // only trace the message leaves, so an unwarned drop is a silent loss.
        this.status.set(status);

        mediator.deliver(message(baseUrl), true);

        assertThat(raised).singleElement().satisfies(warning -> {
            assertThat(warning.severity()).isEqualTo(severity);
            assertThat(warning.category()).isEqualTo("CONFIGURATION");
            assertThat(warning.message()).contains(detail).contains(baseUrl);
        });
    }

    @ParameterizedTest(name = "HTTP {0} does not warn")
    @CsvSource({"200", "204", "429", "502", "503", "504"})
    @DisplayName("a message that is coming back does not raise a warning")
    void retryablesDoNotWarn(int status) throws Exception {
        // A warning store holding a thousand entries is useless, and a target
        // having a bad afternoon would flood it. These outcomes keep the
        // message, so there is nothing an operator must act on to save it.
        this.status.set(status);

        mediator.deliver(message(baseUrl), true);

        assertThat(raised).isEmpty();
    }

    @Test
    @DisplayName("an unparseable target warns rather than dropping in silence")
    void malformedTargetWarns() throws Exception {
        // Never warned before, in either implementation. It ACK-drops every
        // message routed through it, and it is a configuration mistake someone
        // can actually fix — which is exactly the case for telling them.
        var outcome = mediator.deliver(message("http:///no-host"), true);

        assertThat(outcome).isInstanceOf(MediationOutcome.ErrorConfig.class);
        assertThat(raised).singleElement().satisfies(warning -> {
            assertThat(warning.severity()).isEqualTo(Warnings.Severity.ERROR);
            assertThat(warning.message()).contains("invalid mediation target");
        });
    }

    @Test
    @DisplayName("an unsupported mediation type warns rather than dropping in silence")
    void unsupportedTypeWarns() throws Exception {
        var message = new Message("m1", "", null, null, MediationType.parse("SQS"),
                baseUrl, null, false, DispatchMode.NEXT_ON_ERROR);

        var outcome = mediator.deliver(message, true);

        assertThat(outcome).isInstanceOf(MediationOutcome.ErrorConfig.class);
        assertThat(raised).singleElement().satisfies(warning ->
                assertThat(warning.message()).contains("unsupported mediation type"));
    }

    @Test
    @DisplayName("an open circuit is not an operator's problem")
    void circuitOpenDoesNotWarn() throws Exception {
        // The breaker exists to stop shouting about a target that is already
        // known to be down. Warning per suppressed call would defeat it.
        var breaker = breakers.get(baseUrl);
        while (breaker.state() != CircuitBreaker.State.OPEN) {
            breaker.recordFailure();
        }

        mediator.deliver(message(baseUrl), true);

        assertThat(raised).isEmpty();
    }

    @Test
    @DisplayName("ErrorConfig only ever carries UNDELIVERABLE or REJECTED")
    void errorConfigDispositionIsValidated() {
        assertThat(MediationOutcome.ErrorConfig.undeliverable(404, "x").disposition())
                .isEqualTo(MediationOutcome.Disposition.UNDELIVERABLE);
        assertThat(MediationOutcome.ErrorConfig.rejected(500, "x").disposition())
                .isEqualTo(MediationOutcome.Disposition.REJECTED);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new MediationOutcome.ErrorConfig(500, "x", MediationOutcome.Disposition.RETURN_TO_BROKER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private record Raised(Warnings.Severity severity, String category, String message) {
    }

    @Test
    @DisplayName("the body is exactly the one-field object the signature covers")
    void bodyShape() throws Exception {
        mediator.deliver(message("msg_1", null, null), true);

        assertThat(lastBody.get()).isEqualTo("{\"messageId\":\"msg_1\"}");
        assertThat(lastHeaders).containsEntry("content-type", "application/json");
    }

    @Test
    @DisplayName("a signing secret adds a timestamp and a signature over the sent bytes")
    void signingHeaders() throws Exception {
        mediator.deliver(message("msg_TEST123456", null, "test-secret-do-not-use-in-prod"), true);

        // The golden vector: same secret, same fixed timestamp, same body.
        assertThat(lastHeaders).containsEntry("x-flowcatalyst-timestamp", "2026-01-01T00:00:00.000Z");
        assertThat(lastHeaders).containsEntry("x-flowcatalyst-signature",
                "4c53b4f3224c8c870cd4f12e03a6903233153f26777708dd3afab915b2eb3819");
    }

    @Test
    @DisplayName("no signing secret means no signature headers at all")
    void unsignedRequest() throws Exception {
        mediator.deliver(message("msg_1", null, null), true);

        assertThat(lastHeaders).doesNotContainKeys("x-flowcatalyst-signature", "x-flowcatalyst-timestamp");
    }

    @Test
    @DisplayName("an empty auth token still sends the header, because absent means something else")
    void emptyAuthTokenIsSent() throws Exception {
        mediator.deliver(message("msg_1", "", null), true);

        // The observable contract is that the header is PRESENT. HTTP strips
        // trailing whitespace from header values, so "Bearer " reaches the
        // target as "Bearer" — the space is not part of the contract, but the
        // presence of the header is: a receiver can tell "authenticated with
        // an empty token" from "no credentials offered".
        assertThat(lastHeaders).containsKey("authorization");
        assertThat(lastHeaders.get("authorization")).startsWith("Bearer");
    }

    @Test
    @DisplayName("no auth token means no Authorization header")
    void absentAuthTokenSendsNoHeader() throws Exception {
        mediator.deliver(message("msg_1", null, null), true);

        assertThat(lastHeaders).doesNotContainKey("authorization");
    }

    // ── Status mapping ──────────────────────────────────────────────────

    @Test
    @DisplayName("2xx is a success carrying the real status")
    void successCarriesStatus() throws Exception {
        status.set(201);

        assertThat(mediator.deliver(message("msg_1", null, null), true))
                .isEqualTo(MediationOutcome.Success.of(201));
    }

    @Test
    @DisplayName("a 2xx body can defer or flush")
    void bodySteersTheOutcome() throws Exception {
        responseBody.set("{\"ack\":false,\"delaySeconds\":45}");
        assertThat(mediator.deliver(message("msg_1", null, null), true))
                .isEqualTo(new MediationOutcome.Deferred(200, 45, "Target returned ack=false"));

        responseBody.set("{\"flushGroup\":true,\"delaySeconds\":90}");
        assertThat(mediator.deliver(message("msg_1", null, null), true))
                .isEqualTo(MediationOutcome.Success.flushing(200, 90));
    }

    @ParameterizedTest(name = "HTTP {0} maps to a config error saying which")
    @CsvSource({
            "400, HTTP 400: bad request",
            "401, HTTP 401: auth error",
            "403, HTTP 403: auth error",
            "404, HTTP 404: not found",
            "422, HTTP 422: client error",
    })
    void clientErrorsAreConfigErrors(int code, String detail) throws Exception {
        // The detail is operator-facing text, not a log string: it is what the
        // warning carries, and "auth error" and "not found" send someone to
        // different places. A bare "HTTP 401" sends them nowhere.
        status.set(code);

        assertThat(mediator.deliver(message("msg_1", null, null), true))
                .isEqualTo(MediationOutcome.ErrorConfig.undeliverable(code, detail));
    }

    @Test
    @DisplayName("429 reads Retry-After, and falls back when it is unusable")
    void rateLimited() throws Exception {
        status.set(429);
        responseHeaders.put("Retry-After", "120");
        assertThat(mediator.deliver(message("msg_1", null, null), true))
                .isEqualTo(new MediationOutcome.RateLimited(120));

        // An HTTP-date is not parsed: mis-reading one into a huge delay is
        // worse than using the default.
        responseHeaders.put("Retry-After", "Wed, 21 Oct 2026 07:28:00 GMT");
        assertThat(mediator.deliver(message("msg_1", null, null), true))
                .isEqualTo(new MediationOutcome.RateLimited(30));
    }

    @ParameterizedTest(name = "HTTP {0} is retryable — the target, not the message")
    @CsvSource({"502", "503", "504"})
    @DisplayName("502/503/504 are the gateway's answer: unavailable, retried by the broker")
    void gatewayErrorsReturnToBroker(int code) throws Exception {
        status.set(code);

        var outcome = mediator.deliver(message("msg_1", null, null), true);

        assertThat(outcome).isInstanceOf(MediationOutcome.ErrorProcess.class);
        assertThat(outcome.disposition()).isEqualTo(MediationOutcome.Disposition.RETURN_TO_BROKER);
    }

    @ParameterizedTest(name = "HTTP {0} is R-57 rejected — the app ran and answered badly")
    @CsvSource({"500", "505"})
    @DisplayName("every other 5xx is a boundary, not a single status: R-57")
    void nonGatewayServerErrorsAreRejected(int code) throws Exception {
        // The rule is "broader than exactly 500" (`docs/spec/router.md`
        // §6.4): an implementation that special-cases only 500 and retries
        // every OTHER 5xx forever is non-conformant. 505 pins the boundary
        // as a range rather than a single number.
        status.set(code);
        var before = breakers.get(baseUrl).stats();

        var outcome = mediator.deliver(message("msg_1", null, null), true);

        assertThat(outcome).isInstanceOf(MediationOutcome.ErrorConfig.class);
        assertThat(outcome.disposition()).isEqualTo(MediationOutcome.Disposition.REJECTED);
        assertThat(outcome.statusCode()).isEqualTo(code);
        assertThat(((MediationOutcome.ErrorConfig) outcome).message())
                .contains("server error, rejected for review");
        // Breaker SUCCESS, same as any other ErrorConfig: the endpoint
        // answered, so it is healthy — it is the request it rejected.
        var after = breakers.get(baseUrl).stats();
        assertThat(after.successes() - before.successes()).isOne();
        assertThat(after.failures() - before.failures()).isZero();
    }

    @ParameterizedTest(name = "HTTP {0} is a permanent error, ACKed rather than retried")
    @CsvSource({"301", "302", "303", "307", "308"})
    void redirectsArePermanentErrors(int code) throws Exception {
        // Owner ruling: a redirect is a misconfigured target, and retrying
        // reproduces it forever. Following it is not an option either —
        // 301/302/303 downgrade POST to GET and drop the body, so the target
        // would receive nothing and we would record a success.
        status.set(code);
        responseHeaders.put("Location", "http://127.0.0.1:1/elsewhere");

        var outcome = mediator.deliver(message("msg_1", null, null), true);

        assertThat(outcome).isInstanceOf(MediationOutcome.ErrorConfig.class);
        assertThat(outcome.disposition()).isNotEqualTo(MediationOutcome.Disposition.RETURN_TO_BROKER);
        assertThat(((MediationOutcome.ErrorConfig) outcome).message()).contains("misconfigured");
    }

    @Test
    @DisplayName("a redirect keeps the breaker closed — the target answered us")
    void redirectDoesNotOpenTheBreaker() throws Exception {
        status.set(308);

        for (int i = 0; i < 20; i++) {
            mediator.deliver(message("msg_" + i, null, null), true);
        }

        assertThat(breakers.get(baseUrl).state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("an unreachable target is a connection error, not a rejection")
    void unreachableTarget() throws Exception {
        var dead = new Message("msg_1", "", null, null, MediationType.HTTP,
                "http://127.0.0.1:1/hook", null, false, DispatchMode.IMMEDIATE);

        var outcome = mediator.deliver(dead, true);

        assertThat(outcome).isInstanceOf(MediationOutcome.ErrorConnection.class);
        assertThat(outcome.disposition()).isEqualTo(MediationOutcome.Disposition.RETURN_TO_BROKER);
    }

    @Test
    @DisplayName("an unusable target or type is dropped without a call")
    void undeliverableMessages() throws Exception {
        var noHost = new Message("msg_1", "", null, null, MediationType.HTTP,
                "not-a-url", null, false, DispatchMode.IMMEDIATE);
        var wrongType = new Message("msg_1", "", null, null, new MediationType.Unsupported("GRPC"),
                baseUrl, null, false, DispatchMode.IMMEDIATE);

        assertThat(mediator.deliver(noHost, true)).isInstanceOf(MediationOutcome.ErrorConfig.class);
        assertThat(mediator.deliver(wrongType, true)).isInstanceOf(MediationOutcome.ErrorConfig.class);
        assertThat(calls.get()).isZero();
    }

    // ── Breaker ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("a 4xx counts as a breaker SUCCESS — the endpoint is healthy")
    void clientErrorsKeepTheBreakerClosed() throws Exception {
        // Treating a 4xx as a failure would open the circuit for every other
        // message to a target that is working perfectly and telling us so.
        status.set(400);

        for (int i = 0; i < 20; i++) {
            mediator.deliver(message("msg_" + i, null, null), true);
        }

        assertThat(breakers.get(baseUrl).state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("a failure is only recorded at a burst boundary")
    void failuresRecordPerBurst() throws Exception {
        // Q3: three failed attempts inside one burst are ONE breaker failure.
        // Recording each would open every circuit three times faster.
        // 503, not 500: R-57 moved 500 to ErrorConfig, a breaker SUCCESS —
        // 502/503/504 are the statuses that still count as unavailability.
        status.set(503);

        IntStream.range(0, 30).forEach(i -> deliverQuietly("msg_" + i, false));
        assertThat(breakers.get(baseUrl).stats().failures()).isZero();

        IntStream.range(0, 10).forEach(i -> deliverQuietly("msg_b" + i, true));
        assertThat(breakers.get(baseUrl).stats().failures()).isEqualTo(10);
    }

    @Test
    @DisplayName("an open breaker short-circuits without calling the target")
    void openBreakerShortCircuits() throws Exception {
        status.set(503);
        IntStream.range(0, 10).forEach(i -> deliverQuietly("msg_" + i, true));
        assertThat(breakers.get(baseUrl).state()).isEqualTo(CircuitBreaker.State.OPEN);
        int callsBefore = calls.get();

        var outcome = mediator.deliver(message("msg_x", null, null), true);

        assertThat(outcome).isInstanceOf(MediationOutcome.CircuitOpen.class);
        assertThat(((MediationOutcome.CircuitOpen) outcome).delaySeconds()).isEqualTo(5);
        assertThat(calls.get()).isEqualTo(callsBefore);
    }

    @Test
    @DisplayName("the circuit opening raises exactly ONE CIRCUIT_BREAKER warning, not one per failed attempt")
    void circuitOpeningWarnsExactlyOnce() throws Exception {
        // Ten failed bursts trip the breaker (defaults: minCalls 10, rate
        // 0.5) — if the warning fired per attempt this would be ten, not one.
        status.set(503);

        IntStream.range(0, 10).forEach(i -> deliverQuietly("msg_" + i, true));

        assertThat(breakers.get(baseUrl).state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(raised).as("one warning for the transition, not one per failed attempt")
                .hasSize(1);
        var warning = raised.getFirst();
        assertThat(warning.severity()).isEqualTo(Warnings.Severity.WARNING);
        assertThat(warning.category()).isEqualTo("CIRCUIT_BREAKER");
        assertThat(warning.message()).contains(baseUrl).contains("opened").contains("10 failures");
    }

    @Test
    @DisplayName("a success is recorded whatever the burst position")
    void successesAlwaysRecord() throws Exception {
        // A success ends its burst wherever it lands, so withholding it would
        // leave the breaker believing a recovered endpoint is still failing.
        mediator.deliver(message("msg_1", null, null), false);

        assertThat(breakers.get(baseUrl).stats().successes()).isOne();
    }

    private void deliverQuietly(String id, boolean recordFailure) {
        try {
            mediator.deliver(message(id, null, null), recordFailure);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /// A plain HTTP message aimed at `target`.
    private static Message message(String target) {
        return new Message("m1", "", null, null, MediationType.HTTP, target,
                null, false, DispatchMode.IMMEDIATE);
    }

    private Message message(String id, String authToken, String signingSecret) {
        return new Message(id, "", authToken, signingSecret, MediationType.HTTP,
                baseUrl, null, false, DispatchMode.IMMEDIATE);
    }
}
