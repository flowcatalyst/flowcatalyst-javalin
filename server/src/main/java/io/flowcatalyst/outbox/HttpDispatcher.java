package io.flowcatalyst.outbox;

import io.flowcatalyst.platform.shared.Failures;
import io.flowcatalyst.platform.shared.json.Json;
import tools.jackson.core.JacksonException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/// Sends claimed items to a platform ingest batch route and classifies the
/// response into one [ItemOutcome] per item (spec §6). One attempt per
/// call — no retry loop here; [OutboxProcessor] decides retry/requeue from
/// the returned outcomes and the item's own `retryCount`.
public final class HttpDispatcher {

    private static final int MAX_PARSE_ERROR_SNIPPET = 200;

    /// Supplies the bearer token for outbound requests — client-credentials,
    /// cached, with an optional [#invalidate()] hook a 401 response calls so
    /// the next send re-fetches (spec §6). Nothing in this port wires one
    /// today (only the static `FC_OUTBOX_PLATFORM_AUTH_TOKEN` exists) — this
    /// is the seam the spec names for when one is.
    @FunctionalInterface
    public interface TokenSource {
        String token() throws Exception;

        default void invalidate() {
        }
    }

    /// One item's outcome (spec §6). Never defaulted: every response branch
    /// below builds one explicitly, and `SKIPPED` (audit-only) reads as
    /// [Success] — a skipped row is deleted exactly like a delivered one.
    public sealed interface ItemOutcome {
        record Success() implements ItemOutcome {
        }

        record Failure(OutboxStatus status, String message) implements ItemOutcome {
        }
    }

    private final HttpClient client;
    private final String platformBaseUrl;
    private final Duration requestTimeout;
    private final TokenSource tokenSource;
    private final String staticAuthToken;

    /// @param tokenSource    nullable — when absent, every send falls back
    ///                       to `staticAuthToken` (spec §6)
    /// @param staticAuthToken nullable/blank — `FC_OUTBOX_PLATFORM_AUTH_TOKEN`;
    ///                        an absent token omits the `Authorization` header
    public HttpDispatcher(HttpClient client, String platformBaseUrl, Duration requestTimeout,
                           TokenSource tokenSource, String staticAuthToken) {
        this.client = Objects.requireNonNull(client, "client");
        this.platformBaseUrl = stripTrailingSlash(Objects.requireNonNull(platformBaseUrl, "platformBaseUrl"));
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.tokenSource = tokenSource;
        this.staticAuthToken = staticAuthToken;
    }

    /// The client this dispatcher should use in production: no redirects
    /// (a 301/302/303 would downgrade the POST and drop the body — same
    /// reasoning as [io.flowcatalyst.router.pool.HttpMediator#defaultClient]).
    public static HttpClient defaultClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /// Sends every item of `items` (all the same [OutboxItemType]) as one
    /// batch — `{"items": [<payload>, …]}`, payloads passed through
    /// verbatim — and returns one [ItemOutcome] per item, in the same
    /// order: `results[]` correlates to the request "in input order"
    /// (`docs/spec/sdk-ingest.md` §2). Never returns a list shorter than
    /// `items` when `items` is non-empty — every failure branch fills one
    /// outcome per input item.
    public List<ItemOutcome> send(OutboxItemType type, List<OutboxItem> items) {
        if (items.isEmpty()) {
            return List.of();
        }
        String body;
        try {
            body = buildBody(items);
        } catch (RuntimeException e) {
            return sameFailureForAll(items.size(), OutboxStatus.BAD_REQUEST, "marshal: " + e.getMessage());
        }
        String token;
        try {
            token = tokenSource != null ? tokenSource.token() : staticAuthToken;
        } catch (Exception e) {
            return sameFailureForAll(items.size(), OutboxStatus.GATEWAY_ERROR, "auth: " + e.getMessage());
        }
        HttpRequest request = buildRequest(type, body, token);
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            return sameFailureForAll(items.size(), OutboxStatus.GATEWAY_ERROR, "request: " + Failures.describe(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return sameFailureForAll(items.size(), OutboxStatus.GATEWAY_ERROR, "request: interrupted");
        }
        return classify(response, items.size());
    }

    /// `{"items":[p1,p2,…]}` built by concatenation, never by re-serialising
    /// `payload` — the spec requires the payload pass through verbatim,
    /// which a round trip through a JSON tree would not guarantee (key
    /// order, numeric formatting). Each payload is parsed once, discarded,
    /// purely to validate it is well-formed JSON before it is spliced in.
    private String buildBody(List<OutboxItem> items) {
        var sb = new StringBuilder(32 + items.size() * 48);
        sb.append("{\"items\":[");
        for (int i = 0; i < items.size(); i++) {
            OutboxItem item = items.get(i);
            try {
                Json.MAPPER.readTree(item.payload());
            } catch (JacksonException e) {
                throw new IllegalArgumentException(
                        "item " + item.id() + " payload is not valid JSON: " + e.getMessage(), e);
            }
            if (i > 0) {
                sb.append(',');
            }
            sb.append(item.payload());
        }
        sb.append("]}");
        return sb.toString();
    }

    private HttpRequest buildRequest(OutboxItemType type, String body, String token) {
        var builder = HttpRequest.newBuilder(URI.create(platformBaseUrl + type.apiPath()))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json");
        if (token != null && !token.isEmpty()) {
            // Present-but-empty would be meaningful and distinct from
            // absent; an outbox token is never intentionally empty, so
            // blank is treated as absent.
            builder.header("Authorization", "Bearer " + token);
        }
        return builder.build();
    }

    private List<ItemOutcome> classify(HttpResponse<String> response, int expectedCount) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return classifySuccess(response.body(), expectedCount);
        }
        if (status == 401) {
            if (tokenSource != null) {
                tokenSource.invalidate();
            }
            return sameFailureForAll(expectedCount, OutboxStatus.UNAUTHORIZED, "401");
        }
        if (status == 403) {
            return sameFailureForAll(expectedCount, OutboxStatus.FORBIDDEN, "403");
        }
        if (status == 400) {
            return sameFailureForAll(expectedCount, OutboxStatus.BAD_REQUEST, "400");
        }
        if (status == 502 || status == 503 || status == 504) {
            return sameFailureForAll(expectedCount, OutboxStatus.GATEWAY_ERROR, String.valueOf(status));
        }
        // 404, 409, 422, 429, and any other 5xx: a transient rejection —
        // retryable, so a 429 burst or a flaky route never blocks a group
        // (spec §6, `TestSend_Transient4xxIsRetryable`).
        return sameFailureForAll(expectedCount, OutboxStatus.INTERNAL_ERROR, String.valueOf(status));
    }

    private List<ItemOutcome> classifySuccess(String body, int expectedCount) {
        BatchResponse parsed;
        try {
            parsed = Json.MAPPER.readValue(body, BatchResponse.class);
        } catch (JacksonException e) {
            String snippet = body.length() > MAX_PARSE_ERROR_SNIPPET ? body.substring(0, MAX_PARSE_ERROR_SNIPPET) : body;
            return sameFailureForAll(expectedCount, OutboxStatus.INTERNAL_ERROR, "parse results: " + snippet);
        }
        List<ItemResult> results = parsed == null ? null : parsed.results();
        if (results == null || results.size() != expectedCount) {
            int got = results == null ? 0 : results.size();
            return sameFailureForAll(expectedCount, OutboxStatus.INTERNAL_ERROR,
                    "result count mismatch: got " + got + " for " + expectedCount + " items");
        }
        return results.stream().<ItemOutcome>map(HttpDispatcher::toOutcome).toList();
    }

    private static ItemOutcome toOutcome(ItemResult r) {
        var status = OutboxStatus.parse(r.status());
        if (status.isEmpty()) {
            return new ItemOutcome.Failure(OutboxStatus.INTERNAL_ERROR, "unknown item status: " + r.status());
        }
        if (status.get() == OutboxStatus.SUCCESS) {
            return new ItemOutcome.Success();
        }
        String message = r.error() != null ? r.error() : r.status();
        return new ItemOutcome.Failure(status.get(), message);
    }

    private static List<ItemOutcome> sameFailureForAll(int count, OutboxStatus status, String message) {
        return Collections.<ItemOutcome>nCopies(count, new ItemOutcome.Failure(status, message));
    }

    private record BatchResponse(List<ItemResult> results) {
    }

    private record ItemResult(String id, String status, String error) {
    }
}
