package io.flowcatalyst.fnhost.reconcile;

import io.flowcatalyst.fnhost.GuardedLog;
import io.flowcatalyst.fnhost.load.JvmFunctionLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/// Runs [Reconciler#reconcileOnce] on one virtual thread, forever, until
/// [#close] (`docs/spec/function-host-reconciler.md` §1.3). Interruption is
/// the stop signal (`CONVENTIONS.md` §5): [#close] interrupts the loop's
/// thread and joins it, bounded.
public final class ReconcileLoop implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ReconcileLoop.class);

    /// Between the END of one run and the START of the next (spec §1.3) —
    /// not "every 15s on the clock": a slow run never causes back-to-back
    /// runs to catch up.
    static final Duration INTERVAL = Duration.ofSeconds(15);

    private static final Duration CLOSE_JOIN_TIMEOUT = Duration.ofSeconds(5);

    /// `function-host-process.md` §3 item 2: a precomputed fallback line —
    /// built once, ahead of time, no per-failure string concatenation — for
    /// [GuardedLog#logThrowableSafely] if the ordinary log call for a caught
    /// metaspace `OutOfMemoryError` itself throws.
    private static final byte[] METASPACE_OOM_LOG_FALLBACK =
            ("WARN reconcile run hit a metaspace OutOfMemoryError; continuing" + System.lineSeparator())
                    .getBytes(StandardCharsets.UTF_8);

    private final Reconciler reconciler;
    private final Clock clock;
    private final Duration interval;
    private final Thread thread;

    /// Guards `triggered` and is the wait/notify monitor for both the
    /// idle-interval wait and a trigger arriving during it.
    private final Object lock = new Object();

    /// Coalescing (spec §1.3, R10): any number of [#trigger] calls between
    /// the start of one run and the start of the next collapse to exactly
    /// one extra run — this is a flag, not a counter.
    private boolean triggered;

    private volatile boolean closed;

    public ReconcileLoop(Reconciler reconciler) {
        this(reconciler, Clock.systemUTC());
    }

    /// @param clock injectable so a test can hand [Reconciler#reconcileOnce]
    ///              a fixed `now` without coupling the loop's own timing to it
    public ReconcileLoop(Reconciler reconciler, Clock clock) {
        this(reconciler, clock, INTERVAL);
    }

    /// Package-private: [#INTERVAL] is a constant, not a knob
    /// (`feedback_no_tuning.md`) — this overload exists only so R10's own
    /// tests are not 15s each; production code never calls it.
    ReconcileLoop(Reconciler reconciler, Clock clock, Duration interval) {
        this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.interval = Objects.requireNonNull(interval, "interval");
        this.thread = Thread.ofVirtual().name("fn-host-reconcile-loop").unstarted(this::run);
    }

    /// Starts the loop's virtual thread. Not idempotent — call once.
    public void start() {
        thread.start();
    }

    /// Wakes a run early. Any number of calls while a run is in progress, or
    /// while the loop is waiting out the interval, coalesce to exactly one
    /// more run (spec §1.3).
    public void trigger() {
        synchronized (lock) {
            triggered = true;
            lock.notifyAll();
        }
    }

    /// Marks the reconciler draining — every heartbeat from now on reports
    /// `DRAINING` ([Reconciler#drain]).
    public void drain() {
        reconciler.drain();
    }

    private void run() {
        while (!closed) {
            try {
                reconciler.reconcileOnce(clock.instant());
            } catch (RuntimeException e) {
                // Spec §1.3: "logged and the loop continues".
                LOG.atWarn().setMessage("reconcile run failed; continuing").setCause(e).log();
            } catch (Error e) {
                // §1.3 amended by `function-host-process.md` §3 item 2: a metaspace-family
                // OutOfMemoryError — reusing the SAME cause-chain walker JvmFunctionLoader's
                // own per-load fence uses, since it does not always arrive as a bare
                // OutOfMemoryError (see that method's own doc) — is caught here too and the
                // loop continues. Item 1's per-load guard already prevents most of these from
                // ever reaching this far, but a failure OUTSIDE any one function's own
                // try/catch (parsing a control-plane response, say) can still hit the same
                // wall. Any OTHER Error is a real emergency: rethrown here, which ends the
                // loop — `Readiness#RECONCILER_DOWN` (item 3) is what makes that visible to
                // an operator instead of a silently-stopped loop.
                OutOfMemoryError metaspaceOom = JvmFunctionLoader.findMetaspaceOom(e);
                if (metaspaceOom == null) {
                    throw e;
                }
                GuardedLog.logThrowableSafely(LOG, "reconcile run hit a metaspace OutOfMemoryError; continuing",
                        e, METASPACE_OOM_LOG_FALLBACK);
            }
            if (!awaitNextRunOrTrigger()) {
                return;
            }
        }
    }

    /// `function-host-process.md` §3 item 3: `/ready`/`/health` read this
    /// live — the loop's own thread dying (any `Error` OTHER than a
    /// metaspace-family one, per [#run]'s own catch) must make the process
    /// report unhealthy rather than silently stop reconciling forever.
    public boolean isAlive() {
        return thread.isAlive();
    }

    /// Waits up to [#INTERVAL], woken early and consuming exactly one
    /// pending trigger if there is one.
    ///
    /// @return `false` when the loop should stop (closed, or interrupted)
    private boolean awaitNextRunOrTrigger() {
        synchronized (lock) {
            long deadlineNanos = System.nanoTime() + interval.toNanos();
            while (!triggered && !closed) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    break;
                }
                try {
                    lock.wait(remainingNanos / 1_000_000L, (int) (remainingNanos % 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            triggered = false;
            return !closed;
        }
    }

    /// Interrupts the loop's thread (unblocking a run stuck in the control
    /// plane, R10) and joins it, bounded — [#CLOSE_JOIN_TIMEOUT].
    @Override
    public void close() {
        closed = true;
        thread.interrupt();
        synchronized (lock) {
            lock.notifyAll();
        }
        try {
            thread.join(CLOSE_JOIN_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
