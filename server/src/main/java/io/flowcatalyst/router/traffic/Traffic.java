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
public interface Traffic extends AutoCloseable {

    /// Start taking traffic. Idempotent.
    void register();

    /// Stop taking traffic, then wait for the balancer to finish draining
    /// existing connections. Bounded — see the implementation's delay.
    void deregister();

    Status status();

    /// Releases whatever the implementation holds — an SDK client and its
    /// connection pool, for the ELBv2 one.
    ///
    /// Defaulted because most implementations hold nothing, and narrowed to
    /// throw nothing: a shutdown step that can throw a checked exception ends
    /// up wrapped in a try/catch at every call site, and the first person to
    /// find that tedious deletes the call rather than the catch.
    @Override
    default void close() {
    }

    /// What [Status#mode] reports when nothing is managing traffic.
    String MODE_DISABLED = "disabled";

    /// @param enabled        whether traffic management is configured at all
    /// @param mode           which mechanism is managing traffic — [#MODE_DISABLED]
    ///                       when none is. A separate field rather than
    ///                       something the reader derives from `enabled`:
    ///                       there is exactly one mechanism today, and an
    ///                       operator reading the status should learn which
    ///                       one from the status rather than from the
    ///                       deployment they assume they are looking at
    /// @param targetGroupArn which group this instance registers with, empty
    ///                       when traffic is unmanaged. It is the first thing
    ///                       to check when registration "works" and the
    ///                       balancer still sends nothing — usually the ARN
    ///                       names a group in another region or another stack
    /// @param registered     whether this instance is currently in the target
    ///                       group as far as we know
    /// @param lastChange     when that last changed
    /// @param lastError      why the last attempt failed, if it did. Kept
    ///                       separately from `registered` because a failed
    ///                       deregister leaves us *believing* we are out while
    ///                       the balancer still sends traffic — the two facts
    ///                       disagree and an operator needs both
    record Status(boolean enabled, String mode, Optional<String> targetGroupArn, boolean registered,
                  Optional<Instant> lastChange, Optional<String> lastError) {

        public static Status disabled() {
            return new Status(false, MODE_DISABLED, Optional.empty(), false,
                    Optional.empty(), Optional.empty());
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
