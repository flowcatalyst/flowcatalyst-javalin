package io.flowcatalyst.router.manager;

import java.time.Duration;

/// Wakes every [ConsumerLoop] parked in [ConsumerLoop#awaitCapacity] the
/// moment some pool's capacity might have changed, replacing the fixed
/// `Thread.sleep(2s)` poll that loop used to fall back on with an event a
/// pool (or a reconfigure) raises on the crossing back under capacity
/// (`docs/spec/router.md` §3.2).
///
/// Defect this replaces: measured on NATS JetStream (fetch ≈ 1 ms), eight
/// pollers sharing one 5,120-slot pool buffer filled it in well under a
/// second, the workers drained it in ~0.7 s, and every poller then sat out
/// the rest of a fixed 2 s pause with the router at 22% CPU instead of
/// pulling more work the workers were ready for. One queue alone hit
/// 7,916 deliveries/s (the workers' own limit, correctly); eight queues
/// together dropped to 1,312/s purely from the pause. Fixed 2026-09-07.
///
/// A monotonic counter under an intrinsic lock, not a bare
/// [java.util.concurrent.locks.Condition]: the counter closes the classic
/// lost-wakeup race a bare condition variable has on its own — a signal
/// raised between a waiter's last capacity check and the moment it actually
/// parks would otherwise be missed forever, since nothing woke it and nothing
/// ever will again. Capturing [#generation] *before* the check, then waiting
/// against that snapshot in [#awaitChangeSince], closes the window: any
/// signal in between has already advanced the counter, so the wait returns
/// at once instead of blocking on a signal that already happened.
final class CapacityGate {

    private final Object lock = new Object();
    private long generation;

    /// The current generation. A caller snapshots this **before** checking
    /// whether it has room, then waits against the snapshot — never against a
    /// generation read after the check — or a signal landing in between is
    /// missed.
    long generation() {
        synchronized (lock) {
            return generation;
        }
    }

    /// Raised whenever some pool's queue crosses back under capacity
    /// ([io.flowcatalyst.router.pool.Pool#onCapacityFreed]), or a reconfigure
    /// adds, removes or evicts a pool — anything that could make
    /// [RouterManager#anyPoolHasCapacity] or [RouterManager#poolsHaveCapacity]
    /// answer differently than it did a moment ago.
    ///
    /// Cheap even under load: one lock, one increment, one `notifyAll` — and
    /// `notifyAll` with nothing parked costs no more than the lock itself.
    void signal() {
        synchronized (lock) {
            generation++;
            lock.notifyAll();
        }
    }

    /// Parks the calling thread, **untimed**, until [#generation] advances
    /// past `since`. If it already has by the time this is called — the
    /// signal landed in the window between the caller's check and this call —
    /// returns immediately without waiting at all.
    void awaitChangeSince(long since) throws InterruptedException {
        synchronized (lock) {
            while (generation == since) {
                lock.wait();
            }
        }
    }

    /// As [#awaitChangeSince(long)], but also returns once `timeout` elapses
    /// — the head-of-line deferral wake-up (owner ruling 2026-09-22,
    /// `docs/spec/router-hol-deferral.md` §1): a loop parked because its
    /// deferral budget is spent must also wake when the earliest deferred
    /// message comes due, not only on a capacity signal, since nothing else
    /// signals "budget is back". This is the ONE wait [ConsumerLoop#awaitCapacity]
    /// arms with a timer to that due time — not a second loop.
    ///
    /// A non-positive `timeout` returns at once without waiting at all,
    /// matching a due time that has already passed.
    void awaitChangeSince(long since, Duration timeout) throws InterruptedException {
        if (timeout.isZero() || timeout.isNegative()) {
            return;
        }
        synchronized (lock) {
            var deadline = System.nanoTime() + timeout.toNanos();
            while (generation == since) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return;
                }
                lock.wait(remaining / 1_000_000, (int) (remaining % 1_000_000));
            }
        }
    }
}
