package io.flowcatalyst.outbox;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/// [HttpDispatcher] against a stub HTTP server — every row of spec §6's
/// response table, plus the request shape (path, verbatim body, headers)
/// and the 401 token-invalidation hook.
class HttpDispatcherTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> responseBody = new AtomicReference<>("");
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final Map<String, String> lastHeaders = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        calls.incrementAndGet();
        lastPath.set(exchange.getRequestURI().getPath());
        lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        exchange.getRequestHeaders().forEach((k, v) -> lastHeaders.put(k.toLowerCase(), v.getFirst()));
        byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status.get(), body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    private HttpDispatcher dispatcher() {
        return dispatcher(null, "static-token");
    }

    private HttpDispatcher dispatcher(HttpDispatcher.TokenSource tokenSource, String staticToken) {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        return new HttpDispatcher(client, baseUrl, Duration.ofSeconds(5), tokenSource, staticToken);
    }

    private static OutboxItem item(String id, String payload) {
        return new OutboxItem(id, OutboxItemType.EVENT, null, payload, 0, Instant.now());
    }

    // ── request shape ────────────────────────────────────────────────────────

    @Test
    void requestsTheItemTypesApiPathWithTheBatchEnvelopeAndBearerToken() {
        responseBody.set("{\"results\":[{\"id\":\"e1\",\"status\":\"SUCCESS\"}]}");

        var outcomes = dispatcher().send(OutboxItemType.EVENT, List.of(item("e1", "{\"a\":1}")));

        assertThat(outcomes).hasSize(1);
        assertThat(lastPath.get()).isEqualTo(OutboxItemType.EVENT.apiPath());
        assertThat(lastBody.get()).as("payload passed through verbatim, not re-serialised")
                .isEqualTo("{\"items\":[{\"a\":1}]}");
        assertThat(lastHeaders.get("authorization")).isEqualTo("Bearer static-token");
        assertThat(lastHeaders.get("content-type")).isEqualTo("application/json");
    }

    @Test
    void multipleItemsInABatchArePassedThroughVerbatimInOrder() {
        responseBody.set("""
                {"results":[{"id":"a","status":"SUCCESS"},{"id":"b","status":"SUCCESS"}]}
                """);

        dispatcher().send(OutboxItemType.DISPATCH_JOB,
                List.of(item("a", "{\"z\":1,\"a\":2}"), item("b", "{\"x\":true}")));

        assertThat(lastPath.get()).isEqualTo(OutboxItemType.DISPATCH_JOB.apiPath());
        assertThat(lastBody.get()).isEqualTo("{\"items\":[{\"z\":1,\"a\":2},{\"x\":true}]}");
    }

    @Test
    void noTokenConfiguredOmitsTheAuthorizationHeader() {
        responseBody.set("{\"results\":[{\"id\":\"e1\",\"status\":\"SUCCESS\"}]}");

        dispatcher(null, "").send(OutboxItemType.EVENT, List.of(item("e1", "{}")));

        assertThat(lastHeaders).doesNotContainKey("authorization");
    }

    @Test
    void emptyItemsMakesNoRequestAtAll() {
        var outcomes = dispatcher().send(OutboxItemType.EVENT, List.of());

        assertThat(outcomes).isEmpty();
        assertThat(calls).hasValue(0);
    }

    // ── 2xx success path ─────────────────────────────────────────────────────

    @Test
    void twoXxWithMatchingResultsMapsEachItemsStatus() {
        responseBody.set("""
                {"results":[
                  {"id":"a","status":"SUCCESS"},
                  {"id":"b","status":"SKIPPED"},
                  {"id":"c","status":"BAD_REQUEST","error":"nope"},
                  {"id":"d","status":"UNAUTHORIZED"},
                  {"id":"e","status":"FORBIDDEN"},
                  {"id":"f","status":"GATEWAY_ERROR","error":"upstream down"},
                  {"id":"g","status":"INTERNAL_ERROR"}
                ]}
                """);

        var items = List.of(item("a", "{}"), item("b", "{}"), item("c", "{}"), item("d", "{}"), item("e", "{}"),
                item("f", "{}"), item("g", "{}"));
        var outcomes = dispatcher().send(OutboxItemType.EVENT, items);

        assertThat(outcomes.get(0)).isEqualTo(new HttpDispatcher.ItemOutcome.Success());
        assertThat(outcomes.get(1)).as("SKIPPED reads as SUCCESS").isEqualTo(new HttpDispatcher.ItemOutcome.Success());
        assertThat(outcomes.get(2)).isEqualTo(new HttpDispatcher.ItemOutcome.Failure(OutboxStatus.BAD_REQUEST, "nope"));
        assertThat(outcomes.get(3)).isEqualTo(new HttpDispatcher.ItemOutcome.Failure(OutboxStatus.UNAUTHORIZED, "UNAUTHORIZED"));
        assertThat(outcomes.get(4)).isEqualTo(new HttpDispatcher.ItemOutcome.Failure(OutboxStatus.FORBIDDEN, "FORBIDDEN"));
        assertThat(outcomes.get(5)).isEqualTo(new HttpDispatcher.ItemOutcome.Failure(OutboxStatus.GATEWAY_ERROR, "upstream down"));
        assertThat(outcomes.get(6)).isEqualTo(new HttpDispatcher.ItemOutcome.Failure(OutboxStatus.INTERNAL_ERROR, "INTERNAL_ERROR"));
    }

    @Test
    void unknownWireStatusBecomesInternalErrorWithTheOffendingValue() {
        responseBody.set("{\"results\":[{\"id\":\"a\",\"status\":\"WAT\"}]}");

        var outcomes = dispatcher().send(OutboxItemType.EVENT, List.of(item("a", "{}")));

        assertThat(outcomes).containsExactly(
                new HttpDispatcher.ItemOutcome.Failure(OutboxStatus.INTERNAL_ERROR, "unknown item status: WAT"));
    }

    @Test
    void unparseableTwoXxBodyFailsEveryItemWithAParseMessage() {
        responseBody.set("not json at all {{{");

        var outcomes = dispatcher().send(OutboxItemType.EVENT, List.of(item("a", "{}"), item("b", "{}")));

        assertThat(outcomes).hasSize(2);
        for (var outcome : outcomes) {
            var failure = (HttpDispatcher.ItemOutcome.Failure) outcome;
            assertThat(failure.status()).isEqualTo(OutboxStatus.INTERNAL_ERROR);
            assertThat(failure.message()).startsWith("parse results: not json at all");
        }
    }

    @Test
    void countMismatchFailsEveryItemAsRetryable() {
        responseBody.set("{\"results\":[{\"id\":\"a\",\"status\":\"SUCCESS\"}]}");

        var outcomes = dispatcher().send(OutboxItemType.EVENT, List.of(item("a", "{}"), item("b", "{}")));

        assertThat(outcomes).containsExactly(
                new HttpDispatcher.ItemOutcome.Failure(OutboxStatus.INTERNAL_ERROR,
                        "result count mismatch: got 1 for 2 items"),
                new HttpDispatcher.ItemOutcome.Failure(OutboxStatus.INTERNAL_ERROR,
                        "result count mismatch: got 1 for 2 items"));
        assertThat(outcomes).allSatisfy(o -> assertThat(((HttpDispatcher.ItemOutcome.Failure) o).status().retryable())
                .as("count mismatch must be retryable, not terminal").isTrue());
    }

    // ── non-2xx status table (spec §6) ──────────────────────────────────────

    @Test
    void unauthorizedInvalidatesTheTokenSourceAndIsRetryable() {
        status.set(401);
        var invalidated = new AtomicInteger();
        HttpDispatcher.TokenSource source = new HttpDispatcher.TokenSource() {
            @Override
            public String token() {
                return "t-" + invalidated.get();
            }

            @Override
            public void invalidate() {
                invalidated.incrementAndGet();
            }
        };

        var outcomes = dispatcher(source, null).send(OutboxItemType.EVENT, List.of(item("a", "{}")));

        assertThat(outcomes).containsExactly(new HttpDispatcher.ItemOutcome.Failure(OutboxStatus.UNAUTHORIZED, "401"));
        assertThat(outcomes.getFirst())
                .extracting(o -> ((HttpDispatcher.ItemOutcome.Failure) o).status().retryable())
                .isEqualTo(true);
        assertThat(invalidated).as("a 401 must invalidate the cached token so the next send re-fetches").hasValue(1);
    }

    @Test
    void aStaticTokenSourceIsUntouchedByA401SinceThereIsNothingToInvalidate() {
        status.set(401);

        var outcomes = dispatcher(null, "static").send(OutboxItemType.EVENT, List.of(item("a", "{}")));

        assertThat(outcomes).containsExactly(new HttpDispatcher.ItemOutcome.Failure(OutboxStatus.UNAUTHORIZED, "401"));
    }

    @ParameterizedTest(name = "HTTP {0} -> {1} \"{2}\" retryable={3}")
    @CsvSource({
            "403, FORBIDDEN, 403, false",
            "400, BAD_REQUEST, 400, false",
            "502, GATEWAY_ERROR, 502, true",
            "503, GATEWAY_ERROR, 503, true",
            "504, GATEWAY_ERROR, 504, true",
            "404, INTERNAL_ERROR, 404, true",
            "409, INTERNAL_ERROR, 409, true",
            "422, INTERNAL_ERROR, 422, true",
            "429, INTERNAL_ERROR, 429, true",
            "500, INTERNAL_ERROR, 500, true",
    })
    void statusTableRow(int httpStatus, OutboxStatus expectedStatus, String expectedMessage, boolean retryable) {
        status.set(httpStatus);

        var outcomes = dispatcher().send(OutboxItemType.EVENT, List.of(item("a", "{}")));

        assertThat(outcomes).containsExactly(new HttpDispatcher.ItemOutcome.Failure(expectedStatus, expectedMessage));
        assertThat(expectedStatus.retryable()).isEqualTo(retryable);
    }

    // ── transport / auth / marshal failures ─────────────────────────────────

    @Test
    void transportFailureIsAGatewayErrorNamingTheCause() {
        stopServer(); // nothing is listening any more

        var outcomes = dispatcher().send(OutboxItemType.EVENT, List.of(item("a", "{}")));

        assertThat(outcomes).hasSize(1);
        var failure = (HttpDispatcher.ItemOutcome.Failure) outcomes.getFirst();
        assertThat(failure.status()).isEqualTo(OutboxStatus.GATEWAY_ERROR);
        assertThat(failure.message()).startsWith("request: ");
    }

    @Test
    void tokenFetchFailureIsAGatewayErrorNamingTheCauseAndMakesNoHttpCall() {
        HttpDispatcher.TokenSource failingSource = () -> {
            throw new IllegalStateException("token endpoint down");
        };

        var outcomes = dispatcher(failingSource, null).send(OutboxItemType.EVENT, List.of(item("a", "{}")));

        assertThat(outcomes).containsExactly(
                new HttpDispatcher.ItemOutcome.Failure(OutboxStatus.GATEWAY_ERROR, "auth: token endpoint down"));
        assertThat(calls).as("never reached the HTTP server").hasValue(0);
    }

    @Test
    void aMalformedPayloadFailsMarshalForEveryItemWithoutCallingTheServer() {
        var outcomes = dispatcher().send(OutboxItemType.EVENT,
                List.of(item("a", "not { valid json"), item("b", "{}")));

        assertThat(outcomes).hasSize(2);
        assertThat(outcomes).allSatisfy(o -> {
            var failure = (HttpDispatcher.ItemOutcome.Failure) o;
            assertThat(failure.status()).isEqualTo(OutboxStatus.BAD_REQUEST);
            assertThat(failure.message()).startsWith("marshal: ");
        });
        assertThat(calls).as("a bad payload never reaches the server").hasValue(0);
    }
}
