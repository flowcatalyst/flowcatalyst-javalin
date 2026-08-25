package io.flowcatalyst.router.pool;

import io.flowcatalyst.router.observability.Warnings;

import io.flowcatalyst.router.policy.BreakerRegistry;
import io.flowcatalyst.router.policy.CircuitBreaker;
import io.flowcatalyst.router.wire.MediationOutcome;
import io.flowcatalyst.router.wire.MediationType;
import io.flowcatalyst.router.wire.Message;
import io.flowcatalyst.router.wire.WebhookSigner;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/// Delivers a message over HTTP and classifies the result
/// (`docs/spec/router.md` §6).
///
/// Makes **one** attempt per call. Go retries up to three times inside a
/// single `Mediate` and then lets the pool back off again; the Q3 ruling
/// collapsed those two layers into one schedule, which now lives in
/// `RetryPolicy` and is applied by [Pool]. What stays here is the mapping
/// from a response to an outcome, and the circuit breaker.
public final class HttpMediator implements Mediator {

    /// Per-request budget in production (spec constant 22). Long because a
    /// target legitimately doing slow work should not be abandoned; the
    /// concurrency limit, not the timeout, is what bounds the damage.
    public static final Duration PRODUCTION_TIMEOUT = Duration.ofMinutes(15);

    /// Dev keeps a short leash so a hung target is obvious immediately
    /// (constant 26).
    public static final Duration DEV_TIMEOUT = Duration.ofSeconds(30);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final int DEFAULT_RETRY_AFTER_SECONDS = 30;
    private static final int SERVER_ERROR_DELAY_SECONDS = 30;

    private final HttpClient client;
    private final Duration requestTimeout;
    private final BreakerRegistry breakers;
    private final Clock clock;
    private final Warnings warnings;

    public HttpMediator(HttpClient client, Duration requestTimeout, BreakerRegistry breakers, Clock clock) {
        this(client, requestTimeout, breakers, clock, Warnings.NO_OP);
    }

    public HttpMediator(HttpClient client, Duration requestTimeout, BreakerRegistry breakers,
                        Clock clock, Warnings warnings) {
        this.client = client;
        this.requestTimeout = requestTimeout;
        this.breakers = breakers;
        this.clock = clock;
        this.warnings = warnings;
    }

    public static HttpClient defaultClient() {
        return HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                // Redirects are NOT followed: 301/302/303 downgrade POST to
                // GET and drop the body, which would deliver nothing and
                // report success (§13 Q5).
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public MediationOutcome deliver(Message message, boolean recordFailure) throws InterruptedException {
        if (!(message.mediationType() instanceof MediationType.Http)) {
            // Nothing else is deliverable. Dropped rather than retried: no
            // amount of waiting turns an unsupported type into HTTP.
            return undeliverable(message, Warnings.Severity.ERROR, 0,
                    "unsupported mediation type: " + message.mediationType().wireValue());
        }
        var target = parseTarget(message.mediationTarget());
        if (target.isEmpty()) {
            return undeliverable(message, Warnings.Severity.ERROR, 0,
                    "invalid mediation target: " + message.mediationTarget());
        }

        var breaker = breakers.get(message.mediationTarget());
        if (breaker.allow() instanceof CircuitBreaker.Admission.Rejected rejected) {
            // No call was made, so there is nothing to record: the breaker
            // already knows what it thinks of this endpoint.
            return new MediationOutcome.CircuitOpen((int) rejected.retryAfter().toSeconds());
        }

        var outcome = attempt(message, target.get());
        recordOnBreaker(breaker, outcome, recordFailure);
        return outcome;
    }

    private MediationOutcome attempt(Message message, URI target) throws InterruptedException {
        var body = message.deliveryBody();
        HttpRequest request;
        try {
            request = buildRequest(message, target, body);
        } catch (RuntimeException e) {
            return new MediationOutcome.ErrorConfig(0, "could not build request: " + e.getMessage());
        }
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return classify(message, response);
        } catch (HttpTimeoutException e) {
            return new MediationOutcome.ErrorConnection(SERVER_ERROR_DELAY_SECONDS, "request timeout");
        } catch (IOException e) {
            // DNS, refused, TLS, reset — we never learned anything about the
            // message, so this is unavailability.
            return new MediationOutcome.ErrorConnection(SERVER_ERROR_DELAY_SECONDS,
                    "request failed: " + e.getMessage());
        }
    }

    private HttpRequest buildRequest(Message message, URI target, byte[] body) {
        var builder = HttpRequest.newBuilder(target)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");

        if (message.authToken() != null) {
            // Present-but-empty is meaningful and distinct from absent.
            builder.header("Authorization", "Bearer " + message.authToken());
        }
        if (message.signingSecret() != null) {
            var timestamp = WebhookSigner.timestamp(clock.instant());
            builder.header("X-FlowCatalyst-Timestamp", timestamp);
            builder.header("X-FlowCatalyst-Signature",
                    WebhookSigner.sign(message.signingSecret(), timestamp, body));
        }
        return builder.build();
    }

    /// The one 5xx that is permanent rather than transient.
    private static final int NOT_IMPLEMENTED = 501;

    /// The warning category every one of these belongs to (§2.7).
    private static final String CONFIGURATION = "CONFIGURATION";

    /// Maps a response to an outcome (§6.5).
    /// Builds an `ErrorConfig` **and tells an operator**.
    ///
    /// Every caller of this is a path that ACKs a message off the broker
    /// permanently. That is the right call — none of them can succeed on a
    /// retry — but it also means the message is gone, and an ACK-drop nobody
    /// is told about is a silent loss. The warning is not decoration here; it
    /// is the only trace the message leaves.
    ///
    /// Deliberately covers the two pre-flight rejections as well (unsupported
    /// mediation type, unparseable target). Both are configuration mistakes an
    /// operator can fix, both delete every message routed through them, and
    /// both said nothing at all before.
    private MediationOutcome undeliverable(Message message, Warnings.Severity severity,
                                           int status, String detail) {
        warnings.raise(severity, CONFIGURATION,
                detail + " (target " + message.mediationTarget() + ")");
        return new MediationOutcome.ErrorConfig(status, detail);
    }

    /// Go distinguishes these in the operator-facing text, and it is worth
    /// keeping: "auth error" and "not found" send someone to different places.
    private static String clientErrorDetail(int status) {
        return switch (status) {
            case 400 -> "HTTP 400: bad request";
            case 401, 403 -> "HTTP " + status + ": auth error";
            case 404 -> "HTTP 404: not found";
            default -> "HTTP " + status + ": client error";
        };
    }

    private MediationOutcome classify(Message message, HttpResponse<byte[]> response) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            // The body may steer us: defer, or flush the group.
            return io.flowcatalyst.router.wire.MediationResponse.resolve(status, response.body());
        }
        if (status == 429) {
            return new MediationOutcome.RateLimited(retryAfterSeconds(response));
        }
        if (status >= 400 && status < 500) {
            // The request is wrong and will stay wrong. ACK-dropped, and
            // recorded as a breaker SUCCESS: the endpoint answered us
            // correctly, so it is healthy.
            return undeliverable(message, Warnings.Severity.ERROR, status, clientErrorDetail(status));
        }
        if (status == NOT_IMPLEMENTED) {
            // 501 is a 5xx that behaves like a 4xx, which is why it has to be
            // tested before the generic 5xx branch rather than after it: the
            // target is telling us it does not implement this hook, and no
            // number of retries will make it. Permanent, and a breaker
            // SUCCESS for the same reason a 404 is — the endpoint answered.
            //
            // CRITICAL, not ERROR: a 4xx is usually one bad message, but a
            // target that does not implement the hook at all rejects every
            // message routed to it. That is a deployment or routing mistake,
            // and it degrades health until someone acknowledges it.
            return undeliverable(message, Warnings.Severity.CRITICAL, status, "HTTP 501: not implemented");
        }
        if (status >= 500) {
            return new MediationOutcome.ErrorProcess(status, SERVER_ERROR_DELAY_SECONDS, "HTTP " + status);
        }
        if (status >= 300) {
            // A redirect we will not follow is a **permanent** error: the
            // target is misconfigured, and retrying reproduces it forever.
            // Owner ruling 2026-08-24 — log it loudly and ACK, rather than
            // retrying a message that can never be delivered as addressed.
            //
            // Following it instead is not an option: 301/302/303 downgrade
            // POST to GET and drop the body, so the target would receive
            // nothing and we would record a success (§13 Q5).
            //
            return undeliverable(message, Warnings.Severity.ERROR, status,
                    "HTTP " + status + ": redirect not followed — target misconfigured");
        }
        // A 1xx as a final status is not something we can interpret. Status 0
        // is "we could not tell", which keeps it retryable: we must not claim
        // a message was rejected when we do not know it was seen.
        return new MediationOutcome.ErrorProcess(0, SERVER_ERROR_DELAY_SECONDS, "unexpected status " + status);
    }

    /// `Retry-After` in seconds, or the default when it is absent, negative
    /// or not an integer. The HTTP-date form is not honoured — Go does not
    /// parse it either, and silently mis-parsing a date into a huge delay is
    /// worse than using the default.
    private int retryAfterSeconds(HttpResponse<byte[]> response) {
        return response.headers().firstValue("Retry-After")
                .map(String::trim)
                .flatMap(HttpMediator::parsePositiveInt)
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

    /// Tells the breaker what happened, subject to the burst rule.
    ///
    /// A 4xx counts as a **success**: the endpoint is healthy and told us our
    /// request was wrong. Treating it as a failure would open the circuit for
    /// every other message to a target that is working perfectly.
    private void recordOnBreaker(CircuitBreaker breaker, MediationOutcome outcome, boolean recordFailure) {
        switch (outcome) {
            case MediationOutcome.Success ignored -> breaker.recordSuccess();
            case MediationOutcome.ErrorConfig ignored -> breaker.recordSuccess();
            case MediationOutcome.ErrorProcess ignored -> {
                if (recordFailure) {
                    breaker.recordFailure();
                }
            }
            case MediationOutcome.ErrorConnection ignored -> {
                if (recordFailure) {
                    breaker.recordFailure();
                }
            }
            // Throttling and deferral are healthy answers from a working
            // target, and an open circuit records nothing about itself.
            case MediationOutcome.RateLimited ignored -> {
            }
            case MediationOutcome.Deferred ignored -> {
            }
            case MediationOutcome.CircuitOpen ignored -> {
            }
        }
    }

    private static Optional<URI> parseTarget(String target) {
        try {
            var uri = new URI(target);
            if (uri.getHost() == null || uri.getScheme() == null) {
                return Optional.empty();
            }
            return Optional.of(uri);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }
}
