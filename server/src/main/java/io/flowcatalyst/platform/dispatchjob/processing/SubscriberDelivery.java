package io.flowcatalyst.platform.dispatchjob.processing;

import io.flowcatalyst.platform.shared.Failures;
import io.flowcatalyst.platform.dispatchjob.Attempt;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
    private final ClientCodeResolver clientCodes;

    /// `clientCodes` is deliberately required, with no convenience overload
    /// defaulting it to [ClientCodeResolver#none]: a delivery that silently
    /// drops `clientCode` and the `X-FlowCatalyst-Client` header looks
    /// identical to a correct one from in here, so the only way a caller can
    /// lose the tenant is by saying so at the construction site
    /// (`docs/spec/webhook-client-code.md` R2).
    public SubscriberDelivery(HttpClient client, ClientCodeResolver clientCodes) {
        this.client = client;
        this.clientCodes = Objects.requireNonNull(clientCodes, "clientCodes");
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

    /// `Authorization`'s masked wire value (`docs/spec/catch-up-2026-09-22.md`
    /// slice C3): the `sign` action shows that credentials WOULD be sent,
    /// never the bearer itself.
    private static final String MASKED_BEARER = "Bearer ••••••";

    /// Builds the delivery exactly as [#deliver] would — same header and
    /// signature logic, the same [Attempt.RequestInfo] — but never sends it
    /// (`docs/spec/catch-up-2026-09-22.md` slice C3, the `sign` action): an
    /// operator can see exactly what a delivery would look like, with a REAL
    /// signature verifiable against the returned timestamp and body, without
    /// spending a real attempt against the subscriber. `headers` carries
    /// every header NAME **and value** — unlike [Attempt.RequestInfo], which
    /// is names only — except `Authorization`, which is masked
    /// ([#MASKED_BEARER]): this is the one place in the codebase that is
    /// allowed to show header values at all, and it still never shows the
    /// bearer (mutant: return the raw bearer).
    public Plan plan(DispatchJob job, DeliveryCredentials.Resolved credentials, Instant at) {
        String clientCode = clientCodes.identifierFor(job.clientId());
        byte[] body = DeliveryPayload.build(job, clientCode);
        BuiltRequest built = buildRequest(job, body, credentials, at, clientCode);
        return new Plan(built.info(), maskedHeaders(built.request()), new String(body, StandardCharsets.UTF_8));
    }

    /// `{request, headers, body}` (lockfile `DeliveryPlan`) — never sent.
    ///
    /// @param request the same [Attempt.RequestInfo] a real attempt would record
    /// @param headers every header actually built, `Authorization` masked
    /// @param body    the request body, decoded as UTF-8 (it is always JSON)
    public record Plan(Attempt.RequestInfo request, java.util.Map<String, String> headers, String body) {
    }

    private static java.util.Map<String, String> maskedHeaders(HttpRequest request) {
        var headers = new java.util.LinkedHashMap<String, String>();
        request.headers().map().forEach((name, values) -> headers.put(name,
                "Authorization".equalsIgnoreCase(name) ? MASKED_BEARER
                        : values.isEmpty() ? "" : values.getFirst()));
        return headers;
    }

    /// Delivers `job`'s payload to its `target_url` and classifies the
    /// result. `at` is the clock reading used for the signature timestamp
    /// (spec §5) — passed in so tests can pin it.
    public DeliveryResult deliver(DispatchJob job, DeliveryCredentials.Resolved credentials, Instant at) {
        // Resolved once per delivery and threaded into both the body (non-dataOnly
        // envelope) and the header below, so the two can never disagree on whether
        // this job's client resolved (webhook-client-code spec R1/R2).
        String clientCode = clientCodes.identifierFor(job.clientId());
        byte[] body = DeliveryPayload.build(job, clientCode);
        BuiltRequest built;
        try {
            built = buildRequest(job, body, credentials, at, clientCode);
        } catch (RuntimeException e) {
            return new DeliveryResult.Failed(AttemptErrorType.CONNECTION, null, "could not build request: " + e.getMessage(),
                    null, null);
        }
        try {
            var response = client.send(built.request(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] cappedBody = readCapped(response.body());
            return withRequestInfo(classify(response, cappedBody), built.info());
        } catch (HttpTimeoutException e) {
            return withRequestInfo(new DeliveryResult.Failed(AttemptErrorType.TIMEOUT, null, "request timeout", null, null),
                    built.info());
        } catch (IOException e) {
            // DNS, refused, TLS, reset — nothing was learned about the
            // message, so this is unavailability, not a rejection.
            return withRequestInfo(new DeliveryResult.Failed(AttemptErrorType.CONNECTION, null,
                    "request failed: " + Failures.describe(e), null, null), built.info());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return withRequestInfo(new DeliveryResult.Failed(AttemptErrorType.CONNECTION, null, "interrupted", null, null),
                    built.info());
        }
    }

    /// Stamps `info` on `result` and — on a [DeliveryResult.Failed] only,
    /// never [DeliveryResult.Delivered]/[DeliveryResult.Deferred] — appends
    /// `" (delivered unsigned: <reason>)"` to the error message when the
    /// delivery went out bare (hand-off 2026-09-22: "so the job page says
    /// why the 401 happened instead of just that it happened").
    private static DeliveryResult withRequestInfo(DeliveryResult result, Attempt.RequestInfo info) {
        return switch (result) {
            case DeliveryResult.Delivered d -> new DeliveryResult.Delivered(d.status(), d.body(), info);
            case DeliveryResult.Deferred d -> new DeliveryResult.Deferred(d.status(), d.delaySeconds(), info);
            case DeliveryResult.Failed f -> new DeliveryResult.Failed(f.errorType(), f.status(),
                    appendUnsignedSuffix(f.message(), info), f.body(), info);
        };
    }

    private static String appendUnsignedSuffix(String message, Attempt.RequestInfo info) {
        if (info != null && info.unsignedReason() != null && !info.unsignedReason().isEmpty()) {
            return message + " (delivered unsigned: " + info.unsignedReason() + ")";
        }
        return message;
    }

    private record BuiltRequest(HttpRequest request, Attempt.RequestInfo info) {
    }

    private BuiltRequest buildRequest(DispatchJob job, byte[] body, DeliveryCredentials.Resolved credentials,
                                       Instant at, String clientCode) {
        var builder = HttpRequest.newBuilder(URI.create(job.targetUrl()))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(attemptTimeout(job.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("X-Dispatch-Job-Id", job.id())
                .header("X-Event-Type", job.code());
        List<String> headerNames = new ArrayList<>(List.of("Content-Type", "X-Dispatch-Job-Id", "X-Event-Type"));

        if (job.clientId() != null && clientCode != null) {
            // Sent for dataOnly deliveries too — that is the case the envelope body
            // cannot cover, and the reason this is a header, not just an envelope
            // field (webhook-client-code spec R2). Never a half pair: a platform-scoped
            // job (no clientId) or an unresolved client omits the header entirely.
            builder.header("X-FlowCatalyst-Client", job.clientId() + ":" + clientCode);
            headerNames.add("X-FlowCatalyst-Client");
        }

        boolean bearerSent = false;
        boolean signatureSent = false;
        String timestamp = null;
        if (credentials.bearerToken() != null && !credentials.bearerToken().isEmpty()) {
            builder.header("Authorization", "Bearer " + credentials.bearerToken());
            headerNames.add("Authorization");
            bearerSent = true;
        }
        if (credentials.signingSecret() != null && !credentials.signingSecret().isEmpty()) {
            // Byte-identical to the router's own outbound signing (spec §4.1
            // of the router spec, reused verbatim here via WebhookSigner).
            timestamp = WebhookSigner.timestamp(at);
            builder.header("X-FlowCatalyst-Timestamp", timestamp);
            builder.header("X-FlowCatalyst-Signature", WebhookSigner.sign(credentials.signingSecret(), timestamp, body));
            headerNames.add("X-FlowCatalyst-Timestamp");
            headerNames.add("X-FlowCatalyst-Signature");
            signatureSent = true;
        }
        // unsignedReason only when genuinely bare (neither header pair sent) — never
        // when only ONE of the two is configured (S5's "exactly one header" case is
        // not "unsigned").
        String unsignedReason = (!bearerSent && !signatureSent) ? blankToNull(credentials.reason()) : null;
        var info = new Attempt.RequestInfo(credentials.signedBy(), signatureSent, bearerSent, timestamp,
                headerNames.stream().sorted().toList(), unsignedReason, job.targetUrl());
        return new BuiltRequest(builder.build(), info);
    }

    private static String blankToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
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
                return new DeliveryResult.Deferred(status, deferralDelay.get(), null);
            }
            return new DeliveryResult.Delivered(status, bodyStr, null);
        }
        if (status == 429) {
            return new DeliveryResult.Deferred(status, retryAfterSeconds(response), null);
        }
        // 3xx / 4xx / 5xx: a delivery failure, retried on the fixed ladder
        // (spec §5) until the job's own retry budget is spent. The response
        // body is kept — 2026-09-22: the subscriber's stated reason, not just
        // the status that earned it.
        return new DeliveryResult.Failed(AttemptErrorType.HTTP_ERROR, status, "HTTP " + status, bodyStr, null);
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
