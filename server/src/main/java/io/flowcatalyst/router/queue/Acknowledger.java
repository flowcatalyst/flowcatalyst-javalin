package io.flowcatalyst.router.queue;

import io.flowcatalyst.router.pool.QueuedMessage;

import java.time.Duration;

/// The acknowledgement half of a queue — what a delivery needs once it has
/// an outcome, and nothing more.
///
/// Extracted from [Consumer] deliberately. A component that acknowledges a
/// message has no business polling one, reading its metrics, or **closing
/// it**: the manager owns a consumer's lifecycle, and closing one from a
/// delivery path would take the whole queue down for every other message in
/// flight on it. Depending on this narrower type makes that mistake
/// unavailable rather than merely discouraged — and stops every tool
/// reasonably asking why an `AutoCloseable` is being used without
/// try-with-resources.
///
/// Both operations are **best-effort and must not throw**: a broker that is
/// briefly unreachable cannot be allowed to fail a delivery that already
/// succeeded, nor to kill the worker holding the message.
public interface Acknowledger {

    /// Stable name of the queue this acknowledges to.
    String identifier();

    /// Permanently removes a delivery.
    ///
    /// @return whether the broker confirmed the removal. `false` means the
    ///         message may still redeliver
    boolean ack(QueuedMessage message);

    /// Makes a delivery visible again after `delay`. Some backends ignore
    /// the delay; none may treat a nack as an ack.
    void nack(QueuedMessage message, Duration delay);

    /// Makes a delivery visible again after `delay` WITHOUT counting it as a
    /// failure — backpressure, not rejection (owner ruling 2026-09-22,
    /// `docs/go-mirror/2026-09-22-router-hol-deferral-handoff.md` §2). Same
    /// ownership contract as [#nack]: the caller has already dropped the
    /// in-flight tracker entry.
    ///
    /// Deliberately **abstract, no default** (CLAUDE.md: every backend has
    /// an opinion): every implementation counts this against its own
    /// `totalDeferred`, never `totalNacked` — the wire mechanism (SQS
    /// `ChangeMessageVisibility`, Postgres `visible_at`, NATS
    /// `nakWithDelay`) is the same call [#nack] makes, but a deferral has
    /// not failed and must not be reported as though it had.
    void defer(QueuedMessage message, Duration delay);

    /// Whether a [#nack]ed message's `delay` is actually honoured by this
    /// backend before it redelivers — R5 (owner ruling 2026-09-17,
    /// `docs/spec/router-deferral-handback.md`, second unit).
    ///
    /// Deliberately **abstract, no default** (CLAUDE.md: every backend must
    /// have an opinion): `SqsQueue` and `PostgresQueue` answer `true` — a
    /// hand-back is a fresh delivery after exactly `delay` (R3/R4). NATS
    /// answers `false`: this stream is one durable WorkQueue consumer with
    /// no per-group subject, so a nack (`AckWait`) never blocks a group's
    /// successors the way R4 blocks Postgres, and — worse — each hand-back
    /// spends one of `max-deliver`'s limited redeliveries, so treating a
    /// deferral as "handed back" here would collapse ten in-memory retries
    /// into ten deliveries and then silent, permanent redelivery loss. A
    /// backend answering `false` keeps R1's pre-ruling behaviour: the
    /// deferral is retried in memory, on the `DEFERRED` curve, within its
    /// own budget.
    boolean honoursDelayedReturn();
}
