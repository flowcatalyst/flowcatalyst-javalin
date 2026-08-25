package io.flowcatalyst.router.traffic;

import java.time.Instant;
import java.util.Optional;

/// Whether this instance is taking HTTP traffic from a load balancer
/// (`docs/spec/router.md` §10.3).
///
/// **Failing to manage traffic never stops the router.** Registration is
/// about the API and dashboard; message delivery comes from queues and does
/// not depend on it. An instance that cannot reach the load balancer should
/// keep draining its queues rather than refuse to run — so every operation
/// here records its failure and returns.
public interface Traffic {

    /// Start taking traffic. Idempotent.
    void register();

    /// Stop taking traffic, then wait for the balancer to finish draining
    /// existing connections. Bounded — see the implementation's delay.
    void deregister();

    Status status();

    /// @param enabled    whether traffic management is configured at all
    /// @param registered whether this instance is currently in the target
    ///                   group as far as we know
    /// @param lastChange when that last changed
    /// @param lastError  why the last attempt failed, if it did. Kept
    ///                   separately from `registered` because a failed
    ///                   deregister leaves us *believing* we are out while
    ///                   the balancer still sends traffic — the two facts
    ///                   disagree and an operator needs both
    record Status(boolean enabled, boolean registered, Optional<Instant> lastChange,
                  Optional<String> lastError) {

        public static Status disabled() {
            return new Status(false, false, Optional.empty(), Optional.empty());
        }
    }

    /// Traffic management switched off — the single-instance and
    /// no-load-balancer cases. Every operation is a no-op.
    Traffic DISABLED = new Traffic() {
        @Override
        public void register() {
        }

        @Override
        public void deregister() {
        }

        @Override
        public Status status() {
            return Status.disabled();
        }
    };
}
