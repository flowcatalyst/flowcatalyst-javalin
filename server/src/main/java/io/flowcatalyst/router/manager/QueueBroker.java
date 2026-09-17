package io.flowcatalyst.router.manager;

import io.flowcatalyst.router.inflight.InFlightTracker;
import io.flowcatalyst.router.pool.Broker;
import io.flowcatalyst.router.pool.QueuedMessage;
import io.flowcatalyst.router.queue.Acknowledger;
import io.flowcatalyst.router.observability.jfr.MessageSettledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
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

    /// Looks up the acknowledger for a queue id.
    ///
    /// Deliberately [Acknowledger] and not `Consumer`: the manager owns a
    /// consumer's lifecycle, and closing one from here would take the queue
    /// down for every other message in flight on it. Narrowing the type
    /// makes that unavailable rather than merely discouraged.
    private final Function<String, Acknowledger> consumers;
    private final InFlightTracker tracker;
    private final Clock clock;

    /// A tracker entry rebuilt by [#owns] carries no batch context — this
    /// broker is re-asserting ownership at delivery time, not routing —
    /// see [RouterManager#inFlight].
    private static final String OWNERSHIP_CHECK_BATCH_ID = "owns-check";

    public QueueBroker(Function<String, Acknowledger> consumers, InFlightTracker tracker) {
        this(consumers, tracker, Clock.systemUTC());
    }

    public QueueBroker(Map<String, ? extends Acknowledger> consumers, InFlightTracker tracker) {
        this(consumers::get, tracker);
    }

    public QueueBroker(Function<String, Acknowledger> consumers, InFlightTracker tracker, Clock clock) {
        this.consumers = consumers;
        this.tracker = tracker;
        this.clock = clock;
    }

    public QueueBroker(Map<String, ? extends Acknowledger> consumers, InFlightTracker tracker, Clock clock) {
        this(consumers::get, tracker, clock);
    }

    @Override
    public void ack(QueuedMessage message) {
        ack(message, "settled");
    }

    /// @param reason who decided, recorded on the flight-recorder event.
    ///               An ack is correct after a 200 and catastrophic after an
    ///               open circuit, and the action alone cannot tell them apart.
    public void ack(QueuedMessage message, String reason) {
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
            log.atWarn().setMessage("ack skipped: queue is no longer registered")
                    .addKeyValue("queue", message.queueId())
                    .addKeyValue("message_id", message.id())
                    .log();
            settled(message, "ack", reason, Duration.ZERO, false);
            return;
        }
        var confirmed = consumer.ack(freshest);
        settled(message, "ack", reason, Duration.ZERO, confirmed);
        if (!confirmed) {
            // Not fatal — the message is finished with here either way — but
            // it means the broker may redeliver it, and a silent redelivery
            // is harder to explain later than a logged one.
            log.atWarn().setMessage("broker did not confirm ack; it may redeliver")
                    .addKeyValue("message_id", message.id())
                    .addKeyValue("queue", message.queueId())
                    .log();
        }
    }

    @Override
    public void nack(QueuedMessage message, Duration delay) {
        nack(message, delay, "returned");
    }

    public void nack(QueuedMessage message, Duration delay, String reason) {
        var freshest = withFreshestHandle(message);
        var consumer = consumers.apply(message.queueId());
        tracker.remove(message.id());
        if (consumer == null) {
            log.atWarn().setMessage("nack skipped: queue is no longer registered")
                    .addKeyValue("queue", message.queueId())
                    .addKeyValue("message_id", message.id())
                    .log();
            settled(message, "nack", reason, delay, false);
            return;
        }
        consumer.nack(freshest, delay);
        settled(message, "nack", reason, delay, true);
    }

    @Override
    public void retrying(QueuedMessage message) {
        tracker.markRetrying(message.id());
    }

    /// Answers from `message`'s own consumer (R5, owner ruling 2026-09-17,
    /// `docs/spec/router-deferral-handback.md`).
    ///
    /// An unregistered queue (deregistered by a reconfigure) answers `false`
    /// — keep the message in memory rather than nack it into a hand-back
    /// nothing is there to honour.
    @Override
    public boolean honoursDelayedReturn(QueuedMessage message) {
        var consumer = consumers.apply(message.queueId());
        return consumer != null && consumer.honoursDelayedReturn();
    }

    @Override
    public boolean owns(QueuedMessage message) {
        // Layer 2, the process-time backstop (`docs/spec/router.md` §2.1
        // EnsureTracked): re-asserts the tracker entry, restoring one the
        // reaper pruned while this message sat buffered. False means a
        // different broker copy has since claimed the id — this attempt
        // must ACK its own copy as a duplicate rather than deliver it.
        return tracker.ensureTracked(RouterManager.inFlight(message, OWNERSHIP_CHECK_BATCH_ID, clock.instant()));
    }

    @Override
    public void release(QueuedMessage message) {
        // No broker call at all — just ownership.
        tracker.remove(message.id());
        settled(message, "release", "abandoned", Duration.ZERO, false);
    }

    /// Records the message leaving, if anyone is recording.
    ///
    /// `shouldCommit()` first so a disabled recording costs one virtual call
    /// and no field writes — this sits on the delivery path of every message.
    private static void settled(QueuedMessage message, String action, String reason,
                                Duration requestedDelay, boolean brokerConfirmed) {
        var event = new MessageSettledEvent();
        if (!event.shouldCommit()) {
            return;
        }
        event.messageId = message.id();
        event.queue = message.queueId();
        event.action = action;
        event.reason = reason;
        event.requestedDelay = requestedDelay.toSeconds();
        event.brokerConfirmed = brokerConfirmed;
        event.commit();
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
