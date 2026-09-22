package io.flowcatalyst.router.queue;

/// What a queue can say about itself.
///
/// @param pending    messages waiting to be delivered, as the broker sees it
/// @param inFlight   messages delivered and not yet acknowledged
/// @param polled     messages this consumer has taken, process-local
/// @param acked      messages this consumer has removed, process-local
/// @param nacked     messages this consumer has returned as failed, process-local
/// @param deferred   messages this consumer has returned for capacity, not
///                    failure — [io.flowcatalyst.router.queue.Acknowledger#defer]
///                    (owner ruling 2026-09-22), process-local. Counted apart
///                    from [#nacked] on purpose: the same wire call, a
///                    different verdict on the message.
///
/// `pending` and `inFlight` may cost a round-trip and are therefore sampled
/// on a schedule rather than read per message; the four counters are local
/// and free.
public record QueueMetrics(long pending, long inFlight, long polled, long acked, long nacked, long deferred) {

    public static final QueueMetrics EMPTY = new QueueMetrics(0, 0, 0, 0, 0, 0);
}
