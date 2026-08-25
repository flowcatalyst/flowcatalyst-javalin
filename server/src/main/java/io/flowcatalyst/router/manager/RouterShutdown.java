package io.flowcatalyst.router.manager;

import io.flowcatalyst.router.inflight.InFlightTracker;
import io.flowcatalyst.router.pool.Pool;
import io.flowcatalyst.router.queue.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/// Stops the router in an order that loses as little as possible
/// (`docs/spec/router.md` §11).
///
/// ### What shutdown means for messages
///
/// **Nothing is acked on the way out.** Everything delivering, retrying or
/// buffered goes back to the broker — mostly by redelivery once its
/// visibility or ack-wait lapses, and immediately for the paths that nack
/// explicitly. A webhook that was mid-flight may already have reached its
/// target and **will be delivered again**: this is an at-least-once system,
/// and shutdown is one of the moments that shows it.
///
/// ### Why the order is what it is
///
/// Stopping consumers first is the whole trick. Once no queue is feeding the
/// pools, the in-flight set can only shrink, so the drain has a definite end
/// rather than racing new arrivals. Reversing it — draining before stopping
/// the sources — would mean draining against a queue still handing out work,
/// which finishes only when the timeout says so.
public final class RouterShutdown {

    private static final Logger log = LoggerFactory.getLogger(RouterShutdown.class);

    /// How long to let in-flight deliveries finish (spec constant 36).
    public static final Duration DEFAULT_DRAIN_TIMEOUT = Duration.ofSeconds(60);

    /// How often to re-check whether the drain is done (constant 37).
    static final Duration DRAIN_POLL_INTERVAL = Duration.ofMillis(500);

    private final InFlightTracker tracker;
    private final Duration drainTimeout;
    private final Supplier<Long> nanoTime;

    public RouterShutdown(InFlightTracker tracker, Duration drainTimeout) {
        this(tracker, drainTimeout, System::nanoTime);
    }

    RouterShutdown(InFlightTracker tracker, Duration drainTimeout, Supplier<Long> nanoTime) {
        this.tracker = tracker;
        this.drainTimeout = drainTimeout;
        this.nanoTime = nanoTime;
    }

    /// Stops everything, in order, and reports what was still in flight when
    /// the drain ended.
    ///
    /// @param loops     the poll-loop threads to interrupt
    /// @param consumers the queues to close
    /// @param pools     the pools to stop
    public Result shutdown(Collection<Thread> loops, Collection<Consumer> consumers, Collection<Pool> pools) {
        int inFlightAtStart = tracker.size();
        log.info("router shutting down with {} messages in flight", inFlightAtStart);

        // 1. Stop the sources. Interruption unwinds every poll loop and every
        //    blocking point beneath it; from here the in-flight set can only
        //    shrink, which is what makes the drain terminate.
        loops.forEach(Thread::interrupt);
        consumers.forEach(RouterShutdown::closeQuietly);

        // 2. Let what is already delivering finish.
        boolean drained = awaitDrain();

        // 3. Stop the pools, handing back whatever is still buffered. Done
        //    after the drain so a message that would have completed on its
        //    own is not nacked out from under a worker that was about to
        //    succeed — an unnecessary redelivery is a duplicate somebody has
        //    to absorb.
        pools.forEach(Pool::stop);

        int remaining = tracker.size();
        if (!drained) {
            log.warn("drain timed out with {} messages still in flight; they will be redelivered", remaining);
        }
        return new Result(inFlightAtStart, remaining, drained);
    }

    /// Waits for the in-flight set to empty, up to the drain timeout.
    ///
    /// @return whether it emptied
    private boolean awaitDrain() {
        long deadline = nanoTime.get() + drainTimeout.toNanos();
        while (tracker.size() > 0) {
            if (nanoTime.get() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(DRAIN_POLL_INTERVAL);
            } catch (InterruptedException e) {
                // A second signal: stop waiting and let the remaining
                // messages redeliver. Insisting on the full drain here would
                // ignore an operator asking twice.
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    private static void closeQuietly(Consumer consumer) {
        try {
            consumer.close();
        } catch (RuntimeException e) {
            // One uncooperative backend must not stop the others closing.
            log.warn("failed to close consumer {}", consumer.identifier(), e);
        }
    }

    /// @param inFlightAtStart messages owned when shutdown began
    /// @param stillInFlight   messages still owned when it ended; each will be
    ///                        redelivered
    /// @param drained         whether everything finished within the timeout
    public record Result(int inFlightAtStart, int stillInFlight, boolean drained) {

        /// Messages that will arrive at their target a second time. Zero is
        /// the good case; anything else is the at-least-once guarantee being
        /// spent, and worth logging as such.
        public int redeliveries() {
            return stillInFlight;
        }
    }

    /// The pools and consumers of a manager, in the order shutdown wants
    /// them.
    public static List<Consumer> consumersOf(RouterManager manager, Collection<String> queueNames) {
        return queueNames.stream().map(manager::consumer).flatMap(java.util.Optional::stream).toList();
    }
}
