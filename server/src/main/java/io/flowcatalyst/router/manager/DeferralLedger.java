package io.flowcatalyst.router.manager;

import java.time.Instant;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.concurrent.locks.ReentrantLock;

/// One consumer's record of when its deferred messages are due back (owner
/// ruling 2026-09-22, `docs/go-mirror/2026-09-22-router-hol-deferral-handoff.md`
/// §1, `docs/spec/router-hol-deferral.md` §1) — a min-heap of return times.
/// Additions arrive in roughly reservation order but a clamped-and-jittered
/// one does not, and pruning only ever needs the minimum, so a heap beats a
/// sorted structure that would have to re-sort on every add.
///
/// One per consumer, owned by [RouterManager] and keyed by
/// [io.flowcatalyst.router.queue.Consumer#identifier] — see
/// [RouterManager#deferralLedger].
final class DeferralLedger {

    private final ReentrantLock lock = new ReentrantLock();
    private final PriorityQueue<Instant> times = new PriorityQueue<>();

    /// Records a deferred message due back at `at`.
    void add(Instant at) {
        lock.lock();
        try {
            times.add(at);
        } finally {
            lock.unlock();
        }
    }

    /// Drops every entry due at or before `now` and returns how many remain
    /// — the number of deferred messages the broker is still holding for
    /// this queue, which [ConsumerLoop#hasRoom]'s budget clause is judged
    /// against.
    int outstanding(Instant now) {
        lock.lock();
        try {
            while (!times.isEmpty() && !times.peek().isAfter(now)) {
                times.poll();
            }
            return times.size();
        } finally {
            lock.unlock();
        }
    }

    /// When the next deferred message is due, if any are out — what
    /// [ConsumerLoop#awaitCapacity] arms its wake-up timer to.
    Optional<Instant> earliest() {
        lock.lock();
        try {
            return Optional.ofNullable(times.peek());
        } finally {
            lock.unlock();
        }
    }
}
