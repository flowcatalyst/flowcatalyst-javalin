package io.flowcatalyst.router.pool;

import java.time.Duration;

/// Acknowledgement back to whichever queue delivered a message.
///
/// Both operations are **best-effort and must not throw**: a broker that is
/// unreachable cannot be allowed to fail a delivery that already succeeded,
/// or to kill the worker that is holding the message. Implementations log
/// and return.
public interface Broker {

    /// Removes the message from the queue permanently.
    ///
    /// Called with the freshest receipt handle known for the delivery — a
    /// redelivery may have replaced the one the message was dispatched with,
    /// and acknowledging a stale handle silently leaves the message to
    /// redeliver forever.
    void ack(QueuedMessage message);

    /// Returns the message to the queue, asking for `delay` before it is
    /// redelivered. Some backends ignore the delay; none may treat a nack as
    /// an ack.
    void nack(QueuedMessage message, Duration delay);

    /// Gives up ownership **without touching the broker**.
    ///
    /// The third thing that can happen to a message, and the one that is easy
    /// to forget: this process is done with it, but has neither delivered it
    /// nor returned it. Shutdown mid-backoff is the case — the message was
    /// never acknowledged, so the broker's own redelivery brings it back, and
    /// nacking would race that redelivery with our own.
    ///
    /// Ownership still has to go. Holding it means the redelivery we are
    /// relying on is classified as a duplicate and dropped, and the message
    /// waits for the reaper instead.
    void release(QueuedMessage message);
}
