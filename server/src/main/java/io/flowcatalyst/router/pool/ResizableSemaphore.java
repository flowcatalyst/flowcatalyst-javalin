package io.flowcatalyst.router.pool;

import java.io.Serial;
import java.util.concurrent.Semaphore;

/// A semaphore whose permit count can be changed **in place**.
///
/// Swapping in a fresh `Semaphore` looks equivalent and is not. A worker that
/// is already parked in `acquire()` is parked on the *instance it read*, and
/// can only be woken by a `release()` on that same instance. After a swap it
/// therefore keeps waiting against the OLD limit for ever, while messages
/// submitted afterwards get the new one.
///
/// The consequences run in both directions, and both are the opposite of what
/// the operator asked for:
///
/// - **Raising** concurrency to clear a backlog does nothing for the backlog.
///   The queued messages continue at the old limit while newer arrivals run at
///   the new one — so the work you were trying to drain is the work that gets
///   starved.
/// - **Lowering** it to protect a fragile target does not protect it: the
///   backlog carries on at the old, higher limit.
///
/// Worse, which messages are affected depends on a race between the swap and
/// worker start-up, so the same resize does different things run to run.
///
/// Resizing in place has none of that: there is one instance, so a waiter is
/// always waiting against the current limit. Shrinking below the number
/// in-flight drives the permit count negative, which is correct — releases pay
/// the debt down before anybody new acquires, so it settles to `n` without
/// evicting work already running.
final class ResizableSemaphore extends Semaphore {

    @Serial
    private static final long serialVersionUID = 1L;

    /// The current limit. Guarded by `this` so two concurrent resizes cannot
    /// compute their deltas against the same stale value and both apply.
    private int limit;

    ResizableSemaphore(int permits) {
        super(permits);
        this.limit = permits;
    }

    /// Moves the limit to `n`, blocking nobody and evicting nothing.
    synchronized void resize(int n) {
        int delta = n - limit;
        limit = n;
        if (delta > 0) {
            release(delta);
        } else if (delta < 0) {
            reducePermits(-delta);
        }
    }

    /// The configured limit — not `availablePermits()`, which is what is left
    /// of it right now.
    synchronized int limit() {
        return limit;
    }
}
