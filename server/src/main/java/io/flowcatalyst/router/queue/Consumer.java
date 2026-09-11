package io.flowcatalyst.router.queue;

import io.flowcatalyst.router.pool.QueuedMessage;

import java.time.Duration;
import java.time.Instant;
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
public interface Consumer extends Acknowledger, AutoCloseable {

    /// Stable name for this queue, used as `QueueIdentifier` on every polled
    /// message, as the key that routes an acknowledgement back here, and as a
    /// metrics label. Must not change over the consumer's life.
    @Override
    String identifier();

    /// Takes up to `max` messages, blocking if the backend does.
    ///
    /// @throws InterruptedException if interrupted while waiting — the caller
    ///         treats this as shutdown, never as a queue failure
    PollResult poll(int max) throws InterruptedException;

    /// Permanently removes a delivery.
    ///
    /// Never throws — a broker blip must not fail a delivery that already
    /// succeeded, nor kill the worker holding the message.
    ///
    /// @return whether the broker confirmed the removal. `false` means the
    ///         message may still redeliver. Most callers ignore this — the
    ///         message is finished with either way — but an **operator
    ///         force-acking a stuck message is entitled to know**, and the
    ///         only alternative is telling them something untrue.
    @Override
    boolean ack(QueuedMessage message);

    /// Makes a delivery visible again after `delay`.
    ///
    /// **Advisory.** Some backends ignore the delay entirely — SQS lets the
    /// visibility timeout lapse instead, and NATS takes its ack-wait from the
    /// connection URI. No backend may treat a nack as an ack.
    @Override
    void nack(QueuedMessage message, Duration delay);

    /// Broker-side depth, when the backend can report it cheaply enough to
    /// ask on a schedule. Empty is always acceptable.
    Optional<QueueMetrics> metrics();

    /// The last time this consumer had independent evidence the broker is
    /// alive — a message delivered, or (where observable) an idle
    /// heartbeat — distinct from [#poll] returning at all
    /// (`docs/spec/router.md` §3.2, §5 row 47).
    ///
    /// Empty is the default and is correct for a request/response backend
    /// (Postgres, SQS): [#poll] itself returns within a bounded time on
    /// those, so a poll that has not returned in a while already IS the
    /// staleness signal, and there is nothing this method could report that
    /// [io.flowcatalyst.router.manager.ConsumerLoop#lastPoll] does not
    /// already cover.
    ///
    /// A backend whose [#poll] instead blocks **untimed** waiting on the
    /// broker (NATS's continuous subscription, `NatsQueue`) must override
    /// this: without it, a consumer idling on a genuinely quiet queue is
    /// indistinguishable from one whose poll has hung, and both look
    /// "stalled" to [io.flowcatalyst.router.manager.ConsumerSupervisor]
    /// after the same threshold — restarting a healthy, merely-idle
    /// consumer, over and over, which is exactly the throughput collapse
    /// this method exists to prevent.
    default Optional<Instant> lastBrokerActivity() {
        return Optional.empty();
    }

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

        /// The queue itself no longer exists on the broker (owner ruling
        /// 2026-09-11, `docs/spec/router-env.md`): Integral's control plane
        /// creates an SQS queue lazily on its first send, so a queue it still
        /// lists can be deleted and reappear later under the same name.
        /// Distinct from [Stopped] — the *consumer* was not asked to stop,
        /// the *queue* is gone — and distinct from an ordinary poll failure:
        /// this is an expected outcome, not a broker error, so it must never
        /// raise the CONNECTION warning a genuine connection failure does.
        /// [io.flowcatalyst.router.manager.ConsumerLoop] ends its loop and
        /// detaches the consumer so the next config sync rechecks the queue.
        record QueueMissing() implements PollResult {
        }

        PollResult STOPPED = new Stopped();
        PollResult QUEUE_MISSING = new QueueMissing();

        static PollResult of(List<QueuedMessage> messages) {
            return new Delivered(messages);
        }

        static PollResult empty() {
            return new Delivered(List.of());
        }
    }
}
