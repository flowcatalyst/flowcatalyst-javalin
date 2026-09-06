package io.flowcatalyst.platform.dispatchjob.processing;

import io.flowcatalyst.platform.dispatchjob.AttemptErrorType;
import io.flowcatalyst.platform.dispatchjob.DispatchJob;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.router.wire.WebhookSigner;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/// The processing endpoint's actual webhook client: one POST to a dispatch
/// job's `target_url`, classified into a [DeliveryResult] (dispatch-seam
/// spec §5). Makes exactly one attempt per call — the platform's own
/// `scheduled_for`-driven backoff (spec §4) owns retries, not this client.
public final class SubscriberDelivery {

    /// Applies when a job carries no `timeout_seconds` (spec §3's timing table).
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /// Bounds a single delivery attempt regardless of the job's own timeout
    /// (spec §3's timing table): "Outer HTTP client ceiling".
    public static final Duration MAX_TIMEOUT = Duration.ofMinutes(2);

    /// Caps how much of a subscriber response is recorded on the attempt
    /// row — a hostile or chatty endpoint must not balloon the row (spec §3
    /// `maxResponseBody`).
    static final int MAX_RESPONSE_BODY = 64 * 1024;

    private static final int DEFAULT_DEFERRAL_DELAY_SECONDS = 30;
    private static final int DEFAULT_RETRY_AFTER_SECONDS = 30;

    private final HttpClient client;

    public SubscriberDelivery(HttpClient client) {
        this.client = client;
    }

    /// Redirects are NEVER followed (spec §5: `CheckRedirect` /
    /// `http.ErrUseLastResponse`) — a 3xx is classified as a permanent
    /// failure by [#classify], never chased. The 30s connect timeout is the
    /// missing bound found in the 2026-09-06 review
    /// (`docs/spec/router-h2.md` §3) — without it a target that accepts a
    /// TCP connection but never completes the handshake could hang past
    /// this client's per-attempt request timeout, which only bounds time
    /// after a connection exists.
    public static HttpClient defaultClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /// Delivers `job`'s payload to its `target_url` and classifies the
    /// result. `at` is the clock reading used for the signature timestamp
    /// (spec §5) — passed in so tests can pin it.
    public DeliveryResult deliver(DispatchJob job, DeliveryCredentials.Resolved credentials, Instant at) {
        byte[] body = DeliveryPayload.build(job);
        HttpRequest request;
        try {
            request = buildRequest(job, body, credentials, at);
        } catch (RuntimeException e) {
            return new DeliveryResult.Failed(AttemptErrorType.CONNECTION, null, "could not build request: " + e.getMessage());
        }
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] cappedBody = readCapped(response.body());
            return classify(response, cappedBody);
        } catch (HttpTimeoutException e) {
            return new DeliveryResult.Failed(AttemptErrorType.TIMEOUT, null, "request timeout");
        } catch (IOException e) {
            // DNS, refused, TLS, reset — nothing was learned about the
            // message, so this is unavailability, not a rejection.
            return new DeliveryResult.Failed(AttemptErrorType.CONNECTION, null, "request failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new DeliveryResult.Failed(AttemptErrorType.CONNECTION, null, "interrupted");
        }
    }

    private HttpRequest buildRequest(DispatchJob job, byte[] body, DeliveryCredentials.Resolved credentials, Instant at) {
        var builder = HttpRequest.newBuilder(URI.create(job.targetUrl()))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(attemptTimeout(job.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("X-Dispatch-Job-Id", job.id())
                .header("X-Event-Type", job.code());

        if (credentials.bearerToken() != null && !credentials.bearerToken().isEmpty()) {
            builder.header("Authorization", "Bearer " + credentials.bearerToken());
        }
        if (credentials.signingSecret() != null && !credentials.signingSecret().isEmpty()) {
            // Byte-identical to the router's own outbound signing (spec §4.1
            // of the router spec, reused verbatim here via WebhookSigner).
            String timestamp = WebhookSigner.timestamp(at);
            builder.header("X-FlowCatalyst-Timestamp", timestamp);
            builder.header("X-FlowCatalyst-Signature", WebhookSigner.sign(credentials.signingSecret(), timestamp, body));
        }
        return builder.build();
    }

    /// The per-attempt timeout (spec §3's timing table): the job's own
    /// `timeout_seconds` when positive, else [#DEFAULT_TIMEOUT] (30s),
    /// clamped to [#MAX_TIMEOUT] (2 minutes) regardless — a job cannot ask
    /// for a longer single attempt than the outer client ceiling allows.
    /// A pure function, package-private for the test to pin the clamp
    /// directly rather than driving a real (and possibly hanging) HTTP call.
    static Duration attemptTimeout(int timeoutSeconds) {
        Duration timeout = timeoutSeconds > 0 ? Duration.ofSeconds(timeoutSeconds) : DEFAULT_TIMEOUT;
        return timeout.compareTo(MAX_TIMEOUT) > 0 ? MAX_TIMEOUT : timeout;
    }

    /// The response → outcome table (spec §5's "Response classification"):
    /// 2xx with `{"ack":false}` → [DeliveryResult.Deferred]; 2xx otherwise →
    /// [DeliveryResult.Delivered]; 429 → [DeliveryResult.Deferred] on
    /// `Retry-After`; anything else → [DeliveryResult.Failed] `HTTP_ERROR`.
    /// Deliberately uniform across 3xx/4xx/5xx — no R-57 split (spec §5,
    /// open question 1). `cappedBody` is already bounded to
    /// [#MAX_RESPONSE_BODY] by [#readCapped] — the network read itself is
    /// capped, not just the stored value, so a chatty or hostile subscriber
    /// cannot balloon this process's memory before the truncation runs.
    private DeliveryResult classify(HttpResponse<InputStream> response, byte[] cappedBody) {
        int status = response.statusCode();
        String bodyStr = new String(cappedBody, StandardCharsets.UTF_8);

        if (status >= 200 && status < 300) {
            Optional<Integer> deferralDelay = parseDeferral(cappedBody);
            if (deferralDelay.isPresent()) {
                return new DeliveryResult.Deferred(deferralDelay.get());
            }
            return new DeliveryResult.Delivered(status, bodyStr);
        }
        if (status == 429) {
            return new DeliveryResult.Deferred(retryAfterSeconds(response));
        }
        // 3xx / 4xx / 5xx: a delivery failure, retried on the fixed ladder
        // (spec §5) until the job's own retry budget is spent.
        return new DeliveryResult.Failed(AttemptErrorType.HTTP_ERROR, status, "HTTP " + status);
    }

    /// `{"ack": false[, "delaySeconds": N]}` on a 2xx body — the delay
    /// defaults to 30s when absent (spec §5 `parseDeferral`). `{"ack":true}`,
    /// `{}` and a non-JSON/non-object body are not deferrals.
    private static Optional<Integer> parseDeferral(byte[] body) {
        if (body.length == 0) {
            return Optional.empty();
        }
        JsonNode node;
        try {
            node = Json.MAPPER.readTree(body);
        } catch (JacksonException e) {
            return Optional.empty();
        }
        if (node == null || !node.isObject()) {
            return Optional.empty();
        }
        JsonNode ack = node.get("ack");
        if (ack == null || !ack.isBoolean() || ack.booleanValue()) {
            return Optional.empty();
        }
        JsonNode delay = node.get("delaySeconds");
        int seconds = delay != null && delay.isNumber() && delay.asInt() >= 0
                ? delay.asInt()
                : DEFAULT_DEFERRAL_DELAY_SECONDS;
        return Optional.of(seconds);
    }

    /// `Retry-After` in seconds, or the default when absent, negative or not
    /// an integer (the HTTP-date form is not honoured — silently mis-parsing
    /// a date into a huge delay is worse than the default).
    private static int retryAfterSeconds(HttpResponse<InputStream> response) {
        return response.headers().firstValue("Retry-After")
                .map(String::trim)
                .flatMap(SubscriberDelivery::parsePositiveInt)
                .orElse(DEFAULT_RETRY_AFTER_SECONDS);
    }

    private static Optional<Integer> parsePositiveInt(String raw) {
        try {
            int value = Integer.parseInt(raw);
            return value >= 0 ? Optional.of(value) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /// Reads at most [#MAX_RESPONSE_BODY] bytes off `in`, then discards
    /// (never buffers) whatever the subscriber sent beyond that and closes
    /// the stream — the network read is bounded, not just the value this
    /// process keeps, so a 1 GiB response body costs this process 64 KiB of
    /// heap, not 1 GiB (audit finding: `BodyHandlers.ofByteArray()` used to
    /// materialise the whole body before the old `cap` truncated it).
    private static byte[] readCapped(InputStream in) throws IOException {
        try (in) {
            byte[] captured = in.readNBytes(MAX_RESPONSE_BODY);
            in.transferTo(OutputStream.nullOutputStream());
            return captured;
        }
    }
}
