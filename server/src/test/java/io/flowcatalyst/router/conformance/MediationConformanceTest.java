package io.flowcatalyst.router.conformance;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.flowcatalyst.router.policy.BreakerRegistry;
import io.flowcatalyst.router.policy.CircuitBreaker;
import io.flowcatalyst.router.pool.HttpMediator;
import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.router.wire.MediationOutcome;
import io.flowcatalyst.router.wire.MediationType;
import io.flowcatalyst.router.wire.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/// Runs `conformance/mediation-outcomes.json` against the real mediator.
///
/// The corpus is deliberately **not** a Java artefact: every case is stated as
/// an HTTP response or a named precondition, so the same file is the contract
/// for the Go implementation too (`conformance/README.md`). While both exist,
/// a row that passes here and fails there is the only evidence that actually
/// bears on "drop-in replacement" — two suites written separately against the
/// same prose agree only by luck.
///
/// It also converts the spec's §6 table from prose into something that fails.
/// That table was the source of truth for a dozen decisions and nothing
/// checked the code still matched it.
@SuppressWarnings("deprecation")
class MediationConformanceTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private final AtomicReference<String> responseBody = new AtomicReference<>("");
    private final Map<String, String> responseHeaders = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();

    private final List<String> raised = new java.util.concurrent.CopyOnWriteArrayList<>();

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
                (severity, category, text) -> raised.add(severity + "/" + category));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        calls.incrementAndGet();
        exchange.getRequestBody().readAllBytes();
        responseHeaders.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(responseStatus.get(), body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    @org.junit.jupiter.api.Test
    @DisplayName("every divergence says which side is right, and why")
    void divergencesAreArgued() throws IOException {
        // The rule that keeps this corpus from decaying into a
        // Go-compatibility harness. A row that differs between the two
        // implementations and does not say WHICH IS CORRECT has, in practice,
        // picked Go — because Go is the one that already exists. Requiring the
        // reasoning in `basis` is what forces the question to be asked at all.
        var root = JSON.readTree(Files.readString(corpusFile()));
        var unargued = new ArrayList<String>();
        var ids = new ArrayList<String>();
        root.get("cases").forEach(testCase -> {
            ids.add(testCase.get("id").asText());
            var divergence = testCase.get("divergence");
            if (divergence == null) {
                return;
            }
            if (!divergence.hasNonNull("correct") || !divergence.hasNonNull("basis")) {
                unargued.add(testCase.get("id").asText());
            } else if (!List.of("java", "go", "both").contains(divergence.get("correct").asText())) {
                unargued.add(testCase.get("id").asText() + " (bad `correct` value)");
            }
        });

        assertThat(unargued)
                .as("divergences missing `correct` and/or `basis`")
                .isEmpty();
        assertThat(ids).as("case ids must be unique").doesNotHaveDuplicates();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    @DisplayName("the mediation outcome table, as it is written")
    void conforms(String id, JsonNode testCase) throws Exception {
        var given = testCase.get("given");
        var expect = testCase.get("expect");
        String target = baseUrl;
        var mediationType = MediationType.HTTP;

        switch (given.get("kind").asText()) {
            case "response" -> {
                responseStatus.set(given.get("status").asInt());
                responseBody.set(given.path("body").asText(""));
                given.path("headers").properties()
                        .forEach(h -> responseHeaders.put(h.getKey(), h.getValue().asText()));
            }
            // A port nothing is listening on. Bound and released so the OS is
            // the one guaranteeing it is free, rather than a hopeful constant.
            case "unreachableTarget" -> target = "http://127.0.0.1:" + freePort() + "/hook";
            case "malformedTargetUrl" -> target = "http:///no-host";
            case "unsupportedMediationType" -> mediationType = MediationType.parse("SQS");
            case "breakerOpen" -> {
                var breaker = breakers.get(baseUrl);
                while (breaker.state() != CircuitBreaker.State.OPEN) {
                    breaker.recordFailure();
                }
            }
            default -> throw new IllegalArgumentException("unknown given kind in case " + id);
        }

        var before = breakers.get(target).stats();
        var outcome = mediator.deliver(message(target, mediationType), true);
        var after = breakers.get(target).stats();

        assertThat(outcome.getClass().getSimpleName())
                .as("%s: outcome", id).isEqualTo(expect.get("outcome").asText());
        assertThat(outcome.statusCode())
                .as("%s: statusCode", id).isEqualTo(expect.get("statusCode").asInt());
        assertThat(outcome.disposition().name())
                .as("%s: disposition", id).isEqualTo(expect.get("disposition").asText());

        if (expect.has("delaySeconds")) {
            assertThat(delayOf(outcome))
                    .as("%s: delaySeconds", id).isEqualTo(expect.get("delaySeconds").asInt());
        }
        if (expect.has("flushGroup")) {
            assertThat(outcome)
                    .as("%s: flushGroup", id)
                    .isInstanceOfSatisfying(MediationOutcome.Success.class,
                            success -> assertThat(success.flushGroup())
                                    .isEqualTo(expect.get("flushGroup").asBoolean()));
        }
        if (expect.has("httpCallMade")) {
            assertThat(calls.get() > 0)
                    .as("%s: httpCallMade", id).isEqualTo(expect.get("httpCallMade").asBoolean());
        }
        assertBreaker(id, expect.get("breaker").asText(), before, after);
        assertWarning(id, expect.get("warning").asText());
        assertMetric(id, expect.get("metric").asText(), outcome);
    }

    /// Maps the corpus's wire spelling (`rateLimited`, camelCase) onto
    /// [Pool.Metric] and asserts [Pool#metricFor] agrees — the runner never
    /// read this column before, so a wrong arm in that exhaustive switch had
    /// nothing to catch it.
    private static void assertMetric(String id, String expected, MediationOutcome outcome) {
        var actual = io.flowcatalyst.router.pool.Pool.metricFor(outcome);
        var wanted = switch (expected.toLowerCase(java.util.Locale.ROOT)) {
            case "success" -> io.flowcatalyst.router.pool.Pool.Metric.SUCCESS;
            case "failure" -> io.flowcatalyst.router.pool.Pool.Metric.FAILURE;
            case "transient" -> io.flowcatalyst.router.pool.Pool.Metric.TRANSIENT;
            case "ratelimited" -> io.flowcatalyst.router.pool.Pool.Metric.RATE_LIMITED;
            case "none" -> io.flowcatalyst.router.pool.Pool.Metric.NONE;
            default -> throw new IllegalArgumentException("unknown metric expectation: " + expected);
        };
        assertThat(actual).as("%s: metric", id).isEqualTo(wanted);
    }

    /// The breaker column is the one most often got wrong, because the
    /// intuitive answer is wrong: a 404 is a breaker **success**. The endpoint
    /// answered, so it is healthy — it is the message that is not.
    private static void assertBreaker(String id, String expected,
                                      CircuitBreaker.Stats before, CircuitBreaker.Stats after) {
        long successes = after.successes() - before.successes();
        long failures = after.failures() - before.failures();
        switch (expected) {
            case "success" -> assertThat(List.of(successes, failures))
                    .as("%s: breaker should record one success", id).containsExactly(1L, 0L);
            case "failure" -> assertThat(List.of(successes, failures))
                    .as("%s: breaker should record one failure", id).containsExactly(0L, 1L);
            case "neither", "none" -> assertThat(List.of(successes, failures))
                    .as("%s: breaker should record nothing", id).containsExactly(0L, 0L);
            default -> throw new IllegalArgumentException("unknown breaker expectation: " + expected);
        }
    }

    /// A permanent ACK-drop deletes the message; the warning is the only
    /// trace it leaves. A retryable outcome must NOT warn — the message is
    /// coming back, and a target having a bad afternoon would flood the store.
    private void assertWarning(String id, String expected) {
        if ("none".equals(expected)) {
            assertThat(raised).as("%s: must not warn", id).isEmpty();
        } else {
            assertThat(raised).as("%s: must warn an operator", id)
                    .containsExactly(expected + "/CONFIGURATION");
        }
    }

    private static int delayOf(MediationOutcome outcome) {
        return switch (outcome) {
            case MediationOutcome.Success success -> success.delaySeconds();
            case MediationOutcome.Deferred deferred -> deferred.delaySeconds();
            case MediationOutcome.ErrorProcess process -> process.delaySeconds();
            case MediationOutcome.ErrorConnection connection -> connection.delaySeconds();
            case MediationOutcome.RateLimited rateLimited -> rateLimited.delaySeconds();
            case MediationOutcome.CircuitOpen circuitOpen -> circuitOpen.delaySeconds();
            case MediationOutcome.ErrorConfig ignored -> 0;
        };
    }

    private static Message message(String target, MediationType type) {
        return new Message("conformance-1", "", null, null, type, target,
                null, false, DispatchMode.NEXT_ON_ERROR);
    }

    private static int freePort() throws IOException {
        try (var socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    static List<org.junit.jupiter.params.provider.Arguments> corpus() throws IOException {
        var root = JSON.readTree(Files.readString(corpusFile()));
        var cases = new ArrayList<org.junit.jupiter.params.provider.Arguments>();
        root.get("cases").forEach(testCase ->
                cases.add(org.junit.jupiter.params.provider.Arguments.of(testCase.get("id").asText(), testCase)));
        // A corpus that silently shrinks to nothing would pass, loudly.
        assertThat(cases).as("conformance corpus").isNotEmpty();
        return cases;
    }

    /// Walks up from the module directory, so the corpus is found whether the
    /// build runs from the module or the reactor root.
    private static Path corpusFile() {
        var dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4 && dir != null; i++, dir = dir.getParent()) {
            var candidate = dir.resolve("conformance/mediation-outcomes.json");
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("conformance/mediation-outcomes.json not found from " + Path.of("").toAbsolutePath());
    }
}
