package io.flowcatalyst.router.manager;

import io.flowcatalyst.router.inflight.InFlightTracker;
import io.flowcatalyst.router.pool.Broker;
import io.flowcatalyst.router.pool.QueuedMessage;
import io.flowcatalyst.router.queue.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

/// Routes an acknowledgement back to the queue the message came from, and
/// releases the router's ownership of it.
///
/// **The invariant this exists to hold**: a message is only ever acked or
/// nacked on *its own* consumer (`docs/spec/router.md` §3.1). Pools are
/// passive and receive messages from every queue, so the source has to be
/// resolved per message rather than held by the pool. Acking on the wrong
/// consumer would delete an unrelated delivery.
///
/// Two things happen on every acknowledgement, in an order that matters:
/// the **freshest** receipt handle is substituted, and only then is the
/// tracker entry released. A redelivery may have replaced the handle the
/// message was dispatched with, and acknowledging a stale one silently
/// leaves the message to redeliver forever.
public final class QueueBroker implements Broker {

    private static final Logger log = LoggerFactory.getLogger(QueueBroker.class);

    private final Function<String, Consumer> consumers;
    private final InFlightTracker tracker;

    public QueueBroker(Function<String, Consumer> consumers, InFlightTracker tracker) {
        this.consumers = consumers;
        this.tracker = tracker;
    }

    public QueueBroker(Map<String, Consumer> consumers, InFlightTracker tracker) {
        this(consumers::get, tracker);
    }

    @Override
    public void ack(QueuedMessage message) {
        var freshest = withFreshestHandle(message);
        var consumer = consumers.apply(message.queueId());
        // Release ownership first: whatever happens at the broker, this
        // process is done with the message, and holding the entry would block
        // a later redelivery from being taken up.
        tracker.remove(message.id());
        if (consumer == null) {
            // The queue was deregistered by a reconfigure while the message
            // was in flight. There is nothing to ack it on; it will redeliver
            // on whichever consumer replaces it, if any.
            log.warn("ack skipped: queue {} is no longer registered (message {})",
                    message.queueId(), message.id());
            return;
        }
        consumer.ack(freshest);
    }

    @Override
    public void nack(QueuedMessage message, Duration delay) {
        var freshest = withFreshestHandle(message);
        var consumer = consumers.apply(message.queueId());
        tracker.remove(message.id());
        if (consumer == null) {
            log.warn("nack skipped: queue {} is no longer registered (message {})",
                    message.queueId(), message.id());
            return;
        }
        consumer.nack(freshest, delay);
    }

    /// Substitutes the freshest handle the tracker knows, falling back to the
    /// one the message was dispatched with.
    ///
    /// The fallback matters: if the entry was reaped during a long delivery,
    /// the dispatch-time handle is still the best guess, and skipping the
    /// acknowledgement entirely would guarantee a redelivery.
    private QueuedMessage withFreshestHandle(QueuedMessage message) {
        return tracker.freshestHandle(message.id())
                .filter(handle -> !handle.equals(message.receiptHandle()))
                .map(message::withReceiptHandle)
                .orElse(message);
    }
}
