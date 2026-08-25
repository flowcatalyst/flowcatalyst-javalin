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
}
