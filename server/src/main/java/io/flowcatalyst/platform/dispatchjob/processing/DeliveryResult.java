package io.flowcatalyst.platform.dispatchjob.processing;

import io.flowcatalyst.platform.dispatchjob.AttemptErrorType;

import java.util.Objects;

/// The classification of one subscriber-delivery attempt (dispatch-seam spec
/// §5 "Response classification"). Deliberately **simpler** than the router's
/// own [io.flowcatalyst.router.wire.MediationOutcome]: the platform, not the
/// router, owns retry/backoff/terminal-failure for a dispatch job, and every
/// non-2xx/429 status — including every 5xx — is retried identically on the
/// fixed backoff ladder until the job's retry budget is spent (spec §5, open
/// question 1 — no R-57 502/503/504-vs-other-5xx split here, unlike the
/// router's mediator).
public sealed interface DeliveryResult {

    /// A 2xx the subscriber did not defer (no `{"ack":false}` body). Ends
    /// the job's lifecycle: [ProcessingApi] marks it `COMPLETED`.
    record Delivered(int status, String body) implements DeliveryResult {
    }

    /// Cooperative back-pressure — a 2xx body of `{"ack":false[,
    /// "delaySeconds":N]}`, or HTTP 429 (`Retry-After` seconds, or its
    /// default). [ProcessingApi] reschedules to `now + delaySeconds`
    /// spending **no** retry budget (spec §4, §5, §9's second invariant).
    record Deferred(int delaySeconds) implements DeliveryResult {
    }

    /// Any other outcome: 3xx/4xx/5xx from the subscriber, a timeout, or a
    /// transport failure. `status` is `null` when no HTTP response was ever
    /// received (timeout, connection failure). [ProcessingApi] spends one
    /// unit of retry budget and either schedules the next backoff rung or,
    /// once the job's `maxRetries` is exhausted, marks it `FAILED`.
    record Failed(AttemptErrorType errorType, Integer status, String message) implements DeliveryResult {
        public Failed {
            Objects.requireNonNull(errorType, "errorType");
            Objects.requireNonNull(message, "message");
        }
    }
}
