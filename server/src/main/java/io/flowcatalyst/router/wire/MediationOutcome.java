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
    /// The one status a [RateLimited] can come from.
    int TOO_MANY_REQUESTS = 429;

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

        /// The target ran the message and answered with an application-level
        /// failure — R-57's "every other 5xx" (500, 505, … — anything that
        /// is not 502/503/504). Terminal on the **first** attempt: no
        /// bounded retry. Retrying teaches nothing a second attempt would
        /// not — the app already ran and answered — so the message goes
        /// straight to the platform's review flow, ACKed away.
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

    /// 4xx other than 429, an unfollowed 3xx, an unsupported mediation type,
    /// an unusable target URL, or (per R-57) a 5xx other than 502/503/504.
    /// ACK — dropping the message — to prevent an infinite retry of
    /// something that cannot succeed as addressed, or hand it to the
    /// platform's review flow when the app itself is what failed.
    ///
    /// [#disposition] is a required component, not derived from
    /// [#statusCode]: the two dispositions this outcome may carry —
    /// [Disposition#UNDELIVERABLE] and [Disposition#REJECTED] — mean
    /// different things to an ordered group (§3.5 of the router spec), and
    /// the compact constructor rejects every other value, so a construction
    /// site cannot silently pick the wrong one.
    record ErrorConfig(int statusCode, String message, Disposition disposition) implements MediationOutcome {

        public ErrorConfig {
            if (disposition != Disposition.UNDELIVERABLE && disposition != Disposition.REJECTED) {
                throw new IllegalArgumentException(
                        "ErrorConfig disposition must be UNDELIVERABLE or REJECTED, was " + disposition);
            }
        }

        /// The request itself is wrong and will stay wrong — a 4xx, an
        /// unfollowed redirect, an unsupported mediation type, an unusable
        /// target URL. No amount of retrying changes anything.
        public static ErrorConfig undeliverable(int statusCode, String message) {
            return new ErrorConfig(statusCode, message, Disposition.UNDELIVERABLE);
        }

        /// R-57: a 5xx other than 502/503/504. The app ran and answered,
        /// badly — a single attempt, then the platform's review flow.
        public static ErrorConfig rejected(int statusCode, String message) {
            return new ErrorConfig(statusCode, message, Disposition.REJECTED);
        }

        @Override
        public int delaySeconds() {
            return 0;
        }
    }

    /// 502/503/504, or a status the client could not interpret as final
    /// (an unexpected/pre-200 status, carried as `0`). Retryable; counts as
    /// a breaker failure. Always unavailability — the target is not ready,
    /// not that the message is bad — so the whole group goes back to the
    /// broker rather than being dropped.
    ///
    /// The 5xx boundary [R-57] lives in the classifier
    /// ([HttpMediator#classify]), not here: that method only ever
    /// constructs this outcome for 502/503/504 or a status it cannot
    /// interpret. Every other 5xx becomes [ErrorConfig#rejected], not this
    /// record — so `disposition()` is a constant rather than a second place
    /// the boundary could drift from the first.
    record ErrorProcess(int statusCode, int delaySeconds, String message) implements MediationOutcome {

        @Override
        public Disposition disposition() {
            return Disposition.RETURN_TO_BROKER;
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

        /// Always 429. This outcome exists for exactly one response status,
        /// so the status is a property of the type rather than of the
        /// instance — there is no such thing as a `RateLimited` that came
        /// from anything else. A component would only invite one.
        @Override
        public int statusCode() {
            return TOO_MANY_REQUESTS;
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
