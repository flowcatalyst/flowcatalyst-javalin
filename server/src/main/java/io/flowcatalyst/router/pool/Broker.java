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

    /// As [#ack(QueuedMessage)], recording **who decided**.
    ///
    /// The reason never changes what happens — it is carried onto the
    /// flight-recorder event, where it is the difference between an ack that
    /// followed a 200 and one that followed an open circuit. Defaulted so an
    /// implementation that does not record is not made to care.
    default void ack(QueuedMessage message, String reason) {
        ack(message);
    }

    /// Returns the message to the queue, asking for `delay` before it is
    /// redelivered. Some backends ignore the delay; none may treat a nack as
    /// an ack.
    void nack(QueuedMessage message, Duration delay);

    /// As [#nack(QueuedMessage, Duration)], recording who decided.
    default void nack(QueuedMessage message, Duration delay, String reason) {
        nack(message, delay);
    }

    /// Records that the message is being retried **in place**, so the
    /// in-flight entry's attempt count advances.
    ///
    /// Not cosmetic. Two guards read that count and both are unreachable
    /// while it stays at zero: the stall detector treats a legitimately
    /// retrying message as stalled, and with force-nack enabled it hands the
    /// message back to the queue while a worker is still retrying it — two
    /// deliveries of the same message, from one broker.
    ///
    /// Defaulted, unlike [#disposition]-style members, because it carries no
    /// decision: it is a notification, and an implementation with no tracker
    /// has nothing to say. Only the real broker has state to advance.
    default void retrying(QueuedMessage message) {
    }

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

    /// Whether **this** broker copy still owns the pipeline for `message` —
    /// the process-time backstop, layer 2 of the three duplicate-suppression
    /// layers (`docs/spec/router.md` §2.1 `EnsureTracked`).
    ///
    /// Catches the case route-time registration cannot: the tracker entry
    /// was reaped (stall/reap housekeeping) while the message sat buffered,
    /// and a *different* broker copy has since claimed the same application
    /// id. A caller finding this false must ACK its own copy as a duplicate
    /// and abandon delivery rather than deliver it — a different copy now
    /// owns the pipeline.
    ///
    /// Defaulted true — always owning — so an implementation with no tracker
    /// (nothing to dedup against) is not made to care, the same shape as
    /// [#retrying(QueuedMessage)].
    default boolean owns(QueuedMessage message) {
        return true;
    }
}
