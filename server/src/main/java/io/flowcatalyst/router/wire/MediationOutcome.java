package io.flowcatalyst.router.wire;

/// The result of one attempt to deliver a message — an **expected outcome**,
/// so a sealed result type rather than an exception (CONVENTIONS §8). The
/// pool switches over it exhaustively to decide ACK, retry or defer;
/// genuinely exceptional conditions stay exceptions.
///
/// Resolution of each kind is tabled in `docs/spec/router.md` §6.5.
public sealed interface MediationOutcome {

    /// Seconds the caller asked us to wait. Zero when none was requested.
    /// Its meaning differs by kind — a retry floor, a suppression window —
    /// which is why it is read through the specific record, never generically.
    int delaySeconds();

    /// Whether the target could not be reached, or was reachable but not
    /// ready to serve — as opposed to having taken the message and failed on
    /// it.
    ///
    /// The distinction decides what happens to an ordered group
    /// (`docs/spec/router.md` §2.6) and is a **deliberate deviation from
    /// Go**, which classifies every 5xx identically. Unavailability says
    /// nothing is wrong with the message, so it is returned to the broker and
    /// retried for as long as the broker keeps it. A rejection says the
    /// target ran this message and it failed, so it is retried a bounded
    /// number of times and then handed to the platform.
    default boolean targetUnavailable() {
        return false;
    }

    /// 2xx. ACK and release.
    ///
    /// [#flushGroup] is set when the body carried `{"flushGroup": true}`: the
    /// message was delivered, and the target is asking for the rest of its
    /// group to be suppressed for [#delaySeconds]. See `router.md` §2.11.
    ///
    /// Deviation from Go, deliberate: Go hard-codes 200 here even for a 201
    /// or 204, and its flush branch does not copy the real status
    /// (`router.md` §13 Q51). Java carries the true status. The value is
    /// internal — it reaches logs and metrics, never the wire — so this
    /// cannot change what a target observes.
    record Success(int statusCode, boolean flushGroup, int delaySeconds) implements MediationOutcome {

        public static Success of(int statusCode) {
            return new Success(statusCode, false, 0);
        }

        public static Success flushing(int statusCode, int delaySeconds) {
            return new Success(statusCode, true, delaySeconds);
        }
    }

    /// 2xx carrying `{"ack": false}`: healthy, but asking us to come back.
    /// Requeued on the deferred backoff curve floored at [#delaySeconds];
    /// no in-pipeline retry, and no circuit-breaker impact.
    record Deferred(int statusCode, int delaySeconds, String reason) implements MediationOutcome {
    }

    /// 4xx other than 429, an unsupported mediation type, or an unusable
    /// target URL. ACK — dropping the message — to prevent an infinite retry
    /// of something that cannot succeed. Records a breaker **success**: the
    /// destination answered.
    record ErrorConfig(int statusCode, String message) implements MediationOutcome {

        @Override
        public int delaySeconds() {
            return 0;
        }
    }

    /// 5xx, or a status below 200. Retryable; counts as a breaker failure.
    record ErrorProcess(int statusCode, int delaySeconds, String message) implements MediationOutcome {

        /// 502, 503 and 504 are the gateway's answer, not the application's:
        /// a proxy could not reach the app, or the app said it was not ready.
        /// A status the router could not make sense of (`0`) is treated the
        /// same way — we cannot claim the message was rejected when we do not
        /// know that it was seen.
        @Override
        public boolean targetUnavailable() {
            return statusCode == 0 || statusCode == 502 || statusCode == 503 || statusCode == 504;
        }
    }

    /// Transport failure — timeout, DNS, refused, TLS, redirect loop.
    /// Retryable; counts as a breaker failure. Always unavailability: we
    /// never got far enough to learn anything about the message.
    record ErrorConnection(int delaySeconds, String message) implements MediationOutcome {

        @Override
        public boolean targetUnavailable() {
            return true;
        }
    }

    /// HTTP 429. Retryable, floored at the target's `Retry-After`, but
    /// **not** a breaker failure: the destination is healthy and throttling.
    record RateLimited(int delaySeconds) implements MediationOutcome {
    }

    /// The per-endpoint breaker was open, so no HTTP call was made. A defer,
    /// not a failure — it records nothing on the breaker it came from.
    record CircuitOpen(int delaySeconds) implements MediationOutcome {
    }
}
