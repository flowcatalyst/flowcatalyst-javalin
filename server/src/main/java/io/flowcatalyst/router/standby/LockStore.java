package io.flowcatalyst.router.standby;

import java.time.Duration;

/// The three operations leader election needs from a shared store
/// (`docs/spec/router.md` §10.1).
///
/// An interface because the *election* is the part worth reasoning about and
/// testing, and it should be assertable without a Redis server. It is also
/// the seam that keeps the compare-and-act semantics explicit: two of these
/// three operations must be atomic against a concurrent holder, and saying so
/// here is more durable than hoping an implementation remembers.
public interface LockStore {

    /// `SET key value NX EX ttl` — take the lock only if nobody holds it.
    ///
    /// @return whether this caller now holds it
    boolean acquire(String key, String value, Duration ttl);

    /// Extend the lock **only if we still hold it**.
    ///
    /// Must be atomic: a read-then-extend could renew a lock another instance
    /// has already taken over, which is precisely how two routers end up
    /// believing they are both leader.
    ///
    /// @return whether the lock was still ours and has been extended
    boolean refresh(String key, String value, Duration ttl);

    /// Release the lock **only if we still hold it**. Atomic for the same
    /// reason: releasing unconditionally would hand away a lock that has
    /// already moved on.
    void release(String key, String value);

    /// Fail fast if the store is unreachable.
    ///
    /// @throws RuntimeException when it is
    void ping();
}
