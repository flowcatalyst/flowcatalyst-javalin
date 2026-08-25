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

    /// What this outcome means for the message's **group**.
    ///
    /// Deliberately **not** a default method. Every record must answer, so
    /// adding an outcome is a compile error until someone decides what it
    /// does to a group — which is the whole point of a sealed hierarchy. An
    /// earlier version defaulted to [Disposition#REJECTED], and three
    /// outcomes silently inherited it: an open circuit (where no call was
    /// made at all), a 429 (where the target is healthy and throttling), and
    /// an explicit "come back later". Each of those ACK-deleted an entire
    /// ordered group after three attempts. A default on a sealed type opts
    /// out of the exhaustiveness the type exists to provide.
    Disposition disposition();

    /// The HTTP status the target answered with, or **0 when no call was
    /// made** — an open circuit, a connection that never established, our own
    /// limiter deferring the attempt.
    ///
    /// Abstract rather than defaulted, for the same reason [#disposition()]
    /// is: four of these carry a real status as a component and three must
    /// say so explicitly. A default here would let a future outcome that does
    /// have a status silently report that it had none.
    int statusCode();

    /// What should happen to an ordered group when its head produces an
    /// outcome (`docs/spec/router.md` §2.6).
    enum Disposition {

        /// Nothing failed. Not asked about on a failure path.
        DELIVERED,

        /// Retry the head where it is, keeping its place at the front of the
        /// group. The target is healthy and has told us something specific —
        /// come back later, or slow down — so the message is fine and the
        /// group should not move without it.
        RETRY_IN_PLACE,

        /// Hand the head **and its buffered siblings** back to the broker.
        /// Nothing is wrong with these messages; we could not reach a working
        /// target, or were told not to try. The broker owns the retry for as
        /// long as it keeps them, and nothing waits in memory on an outage of
        /// unknown length.
        RETURN_TO_BROKER,

        /// The target took the message and failed on it. Retry a bounded
        /// number of times, then give up on it: ACK it away and let the
        /// platform surface it for review.
        REJECTED,

        /// The request itself is wrong and will stay wrong. Drop it; no
        /// amount of retrying or returning changes anything.
        UNDELIVERABLE
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

        @Override
        public Disposition disposition() {
            return Disposition.DELIVERED;
        }
    }

    /// 2xx carrying `{"ack": false}`: healthy, but asking us to come back.
    /// Requeued on the deferred backoff curve floored at [#delaySeconds];
    /// no in-pipeline retry, and no circuit-breaker impact.
    record Deferred(int statusCode, int delaySeconds, String reason) implements MediationOutcome {

        /// The target is healthy and asked for more time. Its group waits
        /// with it — advancing past a message the target has explicitly
        /// deferred would deliver its successors out of order, which is the
        /// one thing an ordered group promises not to do.
        @Override
        public Disposition disposition() {
            return Disposition.RETRY_IN_PLACE;
        }
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

        @Override
        public Disposition disposition() {
            return Disposition.UNDELIVERABLE;
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
        public Disposition disposition() {
            return statusCode == 0 || statusCode == 502 || statusCode == 503 || statusCode == 504
                    ? Disposition.RETURN_TO_BROKER
                    : Disposition.REJECTED;
        }
    }

    /// Transport failure — timeout, DNS, refused, TLS, redirect loop.
    /// Retryable; counts as a breaker failure. Always unavailability: we
    /// never got far enough to learn anything about the message.
    record ErrorConnection(int delaySeconds, String message) implements MediationOutcome {

        @Override
        public Disposition disposition() {
            return Disposition.RETURN_TO_BROKER;
        }

        /// The connection never established, so the target never answered.
        @Override
        public int statusCode() {
            return 0;
        }
    }

    /// HTTP 429. Retryable, floored at the target's `Retry-After`, but
    /// **not** a breaker failure: the destination is healthy and throttling.
    record RateLimited(int delaySeconds) implements MediationOutcome {

        /// A 429 is a healthy target asking us to slow down, not a rejection.
        /// The message is fine and keeps its place; honouring `Retry-After`
        /// is the whole of the response.
        @Override
        public Disposition disposition() {
            return Disposition.RETRY_IN_PLACE;
        }

        /// Our own limiter deferred it; the target never heard of the message.
        @Override
        public int statusCode() {
            return 0;
        }
    }

    /// The per-endpoint breaker was open, so no HTTP call was made. A defer,
    /// not a failure — it records nothing on the breaker it came from.
    record CircuitOpen(int delaySeconds) implements MediationOutcome {

        /// **No call was made.** Nothing has been learned about this message,
        /// so it cannot have been rejected — and the breaker being open means
        /// the target is failing for everyone, which is exactly when holding
        /// a group in memory is worst. Back to the broker.
        @Override
        public Disposition disposition() {
            return Disposition.RETURN_TO_BROKER;
        }

        /// The breaker refused the call before it was made.
        @Override
        public int statusCode() {
            return 0;
        }
    }
}
