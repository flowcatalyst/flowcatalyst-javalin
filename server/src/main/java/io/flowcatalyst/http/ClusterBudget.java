package io.flowcatalyst.http;

import java.util.Optional;

/// Tier 3 (`docs/spec/admission.md` §3): a group budget enforced across every
/// node. Opt-in; only the interface and its always-granting default exist
/// today. An implementation **never waits** — an empty answer is the caller's
/// `503` with `Retry-After` — and is asked *before* any local permit is
/// taken, so a node never holds a pool connection while waiting on the
/// cluster.
public interface ClusterBudget {
    /// A cluster permit; closing releases it.
    interface Permit extends AutoCloseable {
        @Override
        void close();
    }

    /// A permit for `group` if the cluster has one to give, else empty.
    Optional<Permit> tryAcquire(Group group);

    /// The default: always grants.
    ClusterBudget NOOP = group -> Optional.of(() -> { });
}
