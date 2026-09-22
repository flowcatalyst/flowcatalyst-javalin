package io.flowcatalyst.platform.dispatchjob.processing;

import io.flowcatalyst.platform.dispatchjob.Attempt;
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
///
/// Every variant carries [Attempt.RequestInfo] — what was SENT, recorded on
/// `msg_dispatch_job_attempts.request_info` regardless of outcome
/// (`docs/go-mirror/2026-09-22-delivery-credentials-handoff.md` addendum).
public sealed interface DeliveryResult {

    /// A 2xx the subscriber did not defer (no `{"ack":false}` body). Ends
    /// the job's lifecycle: [ProcessingApi] marks it `COMPLETED`.
    record Delivered(int status, String body, Attempt.RequestInfo request) implements DeliveryResult {
    }

    /// Cooperative back-pressure — a 2xx body of `{"ack":false[,
    /// "delaySeconds":N]}`, or HTTP 429 (`Retry-After` seconds, or its
    /// default). [ProcessingApi] reschedules to `now + delaySeconds`
    /// spending **no** retry budget (spec §4, §5, §9's second invariant).
    ///
    /// `status` is the real HTTP status the subscriber returned (2xx for the
    /// `ack:false` case, 429 for the rate-limit case) — recorded on the
    /// attempt row exactly like [Delivered] and [Failed] already record
    /// theirs, so a deferral is distinguishable on the wire from a transport
    /// failure, which never received a response at all.
    record Deferred(int status, int delaySeconds, Attempt.RequestInfo request) implements DeliveryResult {
    }

    /// Any other outcome: 3xx/4xx/5xx from the subscriber, a timeout, or a
    /// transport failure. `status` is `null` when no HTTP response was ever
    /// received (timeout, connection failure). `body` is the subscriber's
    /// (capped) response body, `null` on a transport failure that never got
    /// one — kept here since 2026-09-22 (`response_body` on failure too, the
    /// hand-off's addendum): before, only a success stored the body, so
    /// every 401 recorded `HTTP 401 Unauthorized` and discarded the SDK's
    /// stated reason. [ProcessingApi] spends one unit of retry budget and
    /// either schedules the next backoff rung or, once the job's
    /// `maxRetries` is exhausted, marks it `FAILED` — except a 401/403,
    /// which is `FAILED` on the very first attempt (the addendum's fail-fast
    /// rule: a retry sends the identical credentials).
    record Failed(AttemptErrorType errorType, Integer status, String message, String body, Attempt.RequestInfo request)
            implements DeliveryResult {
        public Failed {
            Objects.requireNonNull(errorType, "errorType");
            Objects.requireNonNull(message, "message");
        }
    }
}
