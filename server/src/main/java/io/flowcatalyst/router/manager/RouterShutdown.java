package io.flowcatalyst.router.manager;

import io.flowcatalyst.router.concurrent.Concurrently;

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
/// Stop polling, drain, hand back, then close — the order Go uses
/// (`internal/router/server.go` `Run`: `StopPolling`, drain, `Manager.Shutdown`).
///
/// **Stopping intake first** is what gives the drain an end. Once no queue is
/// feeding the pools, the in-flight set can only shrink, so the drain has a
/// definite end rather than racing new arrivals.
///
/// **Closing the consumers last** is what lets the drain count. A delivery
/// that finishes during the drain settles through its consumer, and the
/// pools hand their buffered messages back through it too. Closed first, a
/// consumer can do neither: NATS clears its pending map on close, so every
/// ack during the drain found nothing to ack and the message was delivered
/// a second time (review 2026-09-28, the Rust port's H8). Stopping intake is
/// therefore [Consumer#stopPolling], not [Consumer#close].
public final class RouterShutdown {

    private static final Logger log = LoggerFactory.getLogger(RouterShutdown.class);

    /// How long to let in-flight deliveries finish (spec constant 36).
    public static final Duration DEFAULT_DRAIN_TIMEOUT = Duration.ofSeconds(60);

    /// How often to re-check whether the drain is done (constant 37).
    static final Duration DRAIN_POLL_INTERVAL = Duration.ofMillis(500);

    /// Budget for one concurrent step — closing every consumer, stopping
    /// every pool. Bounded so a wedged broker cannot hold the process open.
    static final Duration STEP_TIMEOUT = Duration.ofSeconds(15);

    private final InFlightTracker tracker;
    private final Duration drainTimeout;
    private final Duration stepTimeout;
    private final Supplier<Long> nanoTime;

    public RouterShutdown(InFlightTracker tracker, Duration drainTimeout) {
        this(tracker, drainTimeout, STEP_TIMEOUT, System::nanoTime);
    }

    /// `stepTimeout` is injectable for the same reason `drainTimeout` is: it
    /// is real elapsed time, and a test asserting that a wedged broker is
    /// bounded should not have to wait out the production bound to prove it.
    public RouterShutdown(InFlightTracker tracker, Duration drainTimeout, Duration stepTimeout) {
        this(tracker, drainTimeout, stepTimeout, System::nanoTime);
    }

    RouterShutdown(InFlightTracker tracker, Duration drainTimeout, Duration stepTimeout,
                   Supplier<Long> nanoTime) {
        this.tracker = tracker;
        this.drainTimeout = drainTimeout;
        this.stepTimeout = stepTimeout;
        this.nanoTime = nanoTime;
    }

    /// Stops everything, in order, and reports what was still in flight when
    /// the drain ended.
    ///
    /// @param loops     the poll-loop threads to interrupt
    /// @param consumers the queues to stop, and after the drain close
    /// @param pools     the pools to stop
    /// Terminal: the process is exiting, so pools are **closed** — their
    /// worker executors released along with their buffers.
    public Result shutdown(Collection<Thread> loops, Collection<Consumer> consumers, Collection<Pool> pools) {
        return stop(loops, consumers, pools, Pool::close, "pool close");
    }

    /// Leadership loss: another instance is taking over, so buffered work
    /// goes back to the broker but the pools **survive**.
    ///
    /// Closing them here would be a bug that only shows on failover *back*:
    /// a stopped pool nacks everything for ever, so the router would regain
    /// leadership and quietly refuse every message.
    public Result standDown(Collection<Thread> loops, Collection<Consumer> consumers, Collection<Pool> pools) {
        return stop(loops, consumers, pools, Pool::releaseBuffered, "pool release");
    }

    private Result stop(Collection<Thread> loops, Collection<Consumer> consumers, Collection<Pool> pools,
                        java.util.function.Consumer<Pool> poolAction, String poolActionName) {
        int inFlightAtStart = tracker.size();
        log.atInfo().setMessage("router shutting down")
                .addKeyValue("count", inFlightAtStart)
                .log();

        // 1. Stop intake. Interruption unwinds every poll loop and every
        //    blocking point beneath it, and stopPolling stops a backend that
        //    receives on its own thread (NATS) asking for more; from here the
        //    in-flight set can only shrink, which is what makes the drain
        //    terminate. The consumers stay open: step 2 settles through them.
        //
        //    Interrupting is instant, so it stays sequential. stopPolling may
        //    talk to its broker, so it runs concurrently: one unreachable
        //    broker would otherwise spend the whole budget while every
        //    healthy queue waited behind it.
        loops.forEach(Thread::interrupt);
        Concurrently.forEach(consumers, RouterShutdown::stopPollingQuietly, stepTimeout, "consumer stop polling");

        // 2. Let what is already delivering finish.
        boolean drained = awaitDrain();

        // 3. Stop the pools, handing back whatever is still buffered. Done
        //    after the drain so a message that would have completed on its
        //    own is not nacked out from under a worker that was about to
        //    succeed — an unnecessary redelivery is a duplicate somebody has
        //    to absorb.
        //
        //    Concurrent for the same reason as the closes: stopping a pool
        //    nacks every message it still holds, one broker round-trip each,
        //    and a slow pool must not eat the budget of the others.
        Concurrently.forEach(pools, poolAction, stepTimeout, poolActionName);

        // 4. Only now close the consumers: nothing will settle through them
        //    again. Concurrent for the same reason as step 1.
        Concurrently.forEach(consumers, RouterShutdown::closeQuietly, stepTimeout, "consumer close");

        int remaining = tracker.size();
        if (!drained) {
            log.atWarn().setMessage("drain timed out with messages still in flight; they will be redelivered")
                    .addKeyValue("count", remaining)
                    .log();
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

    private static void stopPollingQuietly(Consumer consumer) {
        try {
            consumer.stopPolling();
        } catch (RuntimeException e) {
            // The contract says it never throws; one backend breaking that
            // must still not stop the others.
            log.atWarn().setMessage("failed to stop consumer polling")
                    .addKeyValue("consumer", consumer.identifier())
                    .setCause(e)
                    .log();
        }
    }

    private static void closeQuietly(Consumer consumer) {
        try {
            consumer.close();
        } catch (RuntimeException e) {
            // One uncooperative backend must not stop the others closing.
            log.atWarn().setMessage("failed to close consumer")
                    .addKeyValue("consumer", consumer.identifier())
                    .setCause(e)
                    .log();
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
    /// them. `queueNames` are config queue names — [RouterManager#activeConsumer],
    /// not the identifier-keyed [RouterManager#consumer] ack/nack resolves.
    public static List<Consumer> consumersOf(RouterManager manager, Collection<String> queueNames) {
        return queueNames.stream().map(manager::activeConsumer).flatMap(java.util.Optional::stream).toList();
    }
}
