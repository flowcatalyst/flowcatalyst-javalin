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
    private final PoolMetrics metrics;

    public HttpMediator(HttpClient client, Duration requestTimeout, BreakerRegistry breakers, Clock clock) {
        this(client, requestTimeout, breakers, clock, Warnings.NO_OP, PoolMetrics.NO_OP);
    }

    public HttpMediator(HttpClient client, Duration requestTimeout, BreakerRegistry breakers,
                        Clock clock, Warnings warnings) {
        this(client, requestTimeout, breakers, clock, warnings, PoolMetrics.NO_OP);
    }

    /// `metrics` is where the negotiated HTTP version of every successful
    /// send is recorded (`docs/spec/router-h2.md` §3) — router-wide, not
    /// per-pool: this mediator is one shared instance built once in
    /// `Router.start` and handed to every pool's factory closure before any
    /// per-pool [PoolMetrics] exists, so there is no single pool's metrics
    /// object to reuse here.
    public HttpMediator(HttpClient client, Duration requestTimeout, BreakerRegistry breakers,
                        Clock clock, Warnings warnings, PoolMetrics metrics) {
        this.client = client;
        this.requestTimeout = requestTimeout;
        this.breakers = breakers;
        this.clock = clock;
        this.warnings = warnings;
        this.metrics = metrics;
    }

    /// `devMode`: dev pins HTTP/1.1 so a hung/misbehaving local target is
    /// obvious immediately; deployed prefers HTTP/2 so the router's
    /// concurrency does not need an unbounded number of HTTP/1.1 connections
    /// (`docs/spec/router-h2.md` §1/§3). Falls back to 1.1 when a deployed
    /// target cannot negotiate h2 — the JDK client's own behaviour, not
    /// something this method arranges.
    public static HttpClient defaultClient(boolean devMode) {
        return HttpClient.newBuilder()
                .version(devMode ? HttpClient.Version.HTTP_1_1 : HttpClient.Version.HTTP_2)
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

        // Resolved once: the registry keys by origin + path (R-12), and the
        // warning on a state change names that same key.
        var breakerKey = BreakerRegistry.keyFor(message.mediationTarget());
        var breaker = breakers.get(breakerKey);
        if (breaker.allow() instanceof CircuitBreaker.Admission.Rejected rejected) {
            // No call was made, so there is nothing to record: the breaker
            // already knows what it thinks of this endpoint.
            return new MediationOutcome.CircuitOpen((int) rejected.retryAfter().toSeconds());
        }

        var outcome = attempt(message, target.get());
        recordOnBreaker(breaker, outcome, recordFailure, breakerKey);
        return outcome;
    }

    private MediationOutcome attempt(Message message, URI target) throws InterruptedException {
        var body = message.deliveryBody();
        HttpRequest request;
        try {
            request = buildRequest(message, target, body);
        } catch (RuntimeException e) {
            return MediationOutcome.ErrorConfig.undeliverable(0, "could not build request: " + e.getMessage());
        }
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            // Recorded for every successful send regardless of status code —
            // this is about which HTTP version the target actually spoke,
            // not whether the delivery succeeded (`docs/spec/router-h2.md`
            // §3).
            metrics.recordHttpVersion(response.version());
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

    /// The warning category for a breaker state change (§7.3).
    private static final String CIRCUIT_BREAKER = "CIRCUIT_BREAKER";

    /// Maps a response to a permanent, undeliverable outcome (§6.5).
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
        return MediationOutcome.ErrorConfig.undeliverable(status, detail);
    }

    /// R-57: a 5xx other than 502/503/504. The app ran and answered, badly —
    /// rejected into the platform's review flow after a single attempt, with
    /// the warning as the deleted message's only trace.
    private MediationOutcome rejected(Message message, int status, String detail) {
        warnings.raise(Warnings.Severity.ERROR, CONFIGURATION,
                detail + " (target " + message.mediationTarget() + ")");
        return MediationOutcome.ErrorConfig.rejected(status, detail);
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
        if (status == 502 || status == 503 || status == 504) {
            // The gateway's answer, not the application's: a proxy could not
            // reach the app, or the app said it was not ready. Unavailable,
            // not rejected — the whole group goes back to the broker.
            return new MediationOutcome.ErrorProcess(status, SERVER_ERROR_DELAY_SECONDS, "HTTP " + status);
        }
        if (status >= 500) {
            // R-57: every other 5xx (500, 505, 599, …) means the app ran and
            // answered, badly — not that the target is unready. Broader than
            // "only 500 rejects": special-casing a single status and
            // retrying every other 5xx forever is exactly the defect this
            // boundary exists to close.
            return rejected(message, status, "HTTP " + status + ": server error, rejected for review");
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
    private void recordOnBreaker(CircuitBreaker breaker, MediationOutcome outcome, boolean recordFailure,
                                 String breakerKey) {
        switch (outcome) {
            case MediationOutcome.Success ignored -> onTransition(breaker.recordSuccess(), breakerKey);
            case MediationOutcome.ErrorConfig ignored -> onTransition(breaker.recordSuccess(), breakerKey);
            case MediationOutcome.ErrorProcess ignored -> {
                if (recordFailure) {
                    onTransition(breaker.recordFailure(), breakerKey);
                }
            }
            case MediationOutcome.ErrorConnection ignored -> {
                if (recordFailure) {
                    onTransition(breaker.recordFailure(), breakerKey);
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

    /// Raises the CIRCUIT_BREAKER warning a breaker state change earns —
    /// once per transition, never per attempt. [CircuitBreaker#recordFailure]
    /// and [CircuitBreaker#recordSuccess] already collapse "did this call
    /// change the breaker's state" to one small result, so there is nothing
    /// left to de-duplicate here: a burst of failing bursts each report
    /// [CircuitBreaker.Transition.None] once the breaker is already open.
    private void onTransition(CircuitBreaker.Transition transition, String breakerKey) {
        switch (transition) {
            case CircuitBreaker.Transition.Opened opened -> warnings.raise(Warnings.Severity.WARNING,
                    CIRCUIT_BREAKER, "circuit opened for " + breakerKey + " after " + opened.failures() + " failures");
            case CircuitBreaker.Transition.Closed ignored -> warnings.raise(Warnings.Severity.INFO,
                    CIRCUIT_BREAKER, "circuit closed for " + breakerKey);
            case CircuitBreaker.Transition.None ignored -> {
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
