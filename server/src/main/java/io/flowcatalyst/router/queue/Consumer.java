package io.flowcatalyst.router.queue;

import io.flowcatalyst.router.pool.QueuedMessage;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/// One queue the router polls (`docs/spec/router.md` §7.1).
///
/// Implementations are the only place backend differences live: the router
/// above this line does not know whether it is talking to SQS, Postgres or
/// NATS.
///
/// ### Contracts every backend must honour
///
/// - [#poll] returns messages **owned by this consumer** for one visibility
///   window. It may block; interruption is how it is cancelled.
/// - A **malformed payload must never be returned**. It cannot be delivered
///   and would otherwise redeliver forever, so it is disposed of by whatever
///   the backend calls permanent removal.
/// - [#ack] and [#nack] are **best-effort and must not throw**. A broker
///   that is briefly unreachable cannot be allowed to fail a delivery that
///   already succeeded, nor to kill the worker holding the message.
/// - After [#close] every [#poll] answers [PollResult.Stopped].
public interface Consumer extends AutoCloseable {

    /// Stable name for this queue, used as `QueueIdentifier` on every polled
    /// message, as the key that routes an acknowledgement back here, and as a
    /// metrics label. Must not change over the consumer's life.
    String identifier();

    /// Takes up to `max` messages, blocking if the backend does.
    ///
    /// @throws InterruptedException if interrupted while waiting — the caller
    ///         treats this as shutdown, never as a queue failure
    PollResult poll(int max) throws InterruptedException;

    /// Permanently removes a delivery. Failure is logged, never thrown.
    void ack(QueuedMessage message);

    /// Makes a delivery visible again after `delay`.
    ///
    /// **Advisory.** Some backends ignore the delay entirely — SQS lets the
    /// visibility timeout lapse instead, and NATS takes its ack-wait from the
    /// connection URI. No backend may treat a nack as an ack.
    void nack(QueuedMessage message, Duration delay);

    /// Broker-side depth, when the backend can report it cheaply enough to
    /// ask on a schedule. Empty is always acceptable.
    Optional<QueueMetrics> metrics();

    /// Terminal. Subsequent polls answer [PollResult.Stopped].
    @Override
    void close();

    /// The result of a poll — an expected outcome, so a sealed type rather
    /// than a sentinel or an exception (CONVENTIONS §8).
    sealed interface PollResult {

        /// Messages now owned by this consumer. May be empty: an empty poll
        /// is a normal answer, not an error.
        record Delivered(List<QueuedMessage> messages) implements PollResult {

            public Delivered {
                messages = List.copyOf(messages);
            }
        }

        /// The consumer is closed and will never deliver again. The caller
        /// must stop polling rather than retrying.
        record Stopped() implements PollResult {
        }

        PollResult STOPPED = new Stopped();

        static PollResult of(List<QueuedMessage> messages) {
            return new Delivered(messages);
        }

        static PollResult empty() {
            return new Delivered(List.of());
        }
    }
}
