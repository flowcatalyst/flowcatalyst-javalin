package io.flowcatalyst.router.queue;

/// The outcome of building a [Consumer] for one configured queue
/// (`docs/spec/router.md` §7.1/§7.2, §8.2 —
/// [io.flowcatalyst.router.manager.RouterManager.ConsumerFactory]).
///
/// A third outcome alongside "built" and "failed" (owner ruling 2026-09-11,
/// `docs/spec/router.md` §7.2, `docs/backlog.md` "a queue that does not
/// exist yet…"): Integral's control plane lists every subscription's SQS
/// queue, but creates each queue lazily on its first send, so a queue the
/// configuration names that the broker does not have **yet** is normal, not
/// a failure. It must never be counted as [Failed] and must never raise the
/// warning a genuine build failure raises (CONVENTIONS §8: expected outcomes
/// are sealed results the caller switches on, not sentinels folded into an
/// existing case).
public sealed interface ConsumerBuild {

    /// The consumer was built and is ready to poll.
    record Built(Consumer consumer) implements ConsumerBuild {
    }

    /// Could not be built for a reason worth surfacing to an operator: an
    /// unknown URI scheme, an unreachable broker, a malformed URI, or — for
    /// SQS — an existence check that failed for a reason other than "the
    /// queue does not exist" (network, throttling, auth). That last case is
    /// deliberately `Failed`, not [Missing]: a transient AWS error must never
    /// silently stop consumption, so the ordinary build (and its own
    /// poll-failure handling once started) applies instead.
    record Failed() implements ConsumerBuild {
    }

    /// The queue does not exist on the broker yet. Not a failure: no
    /// consumer is started for it, no [Failed]-style warning is raised, and
    /// it is simply retried — silently, past the first sighting — on every
    /// later config sync until it appears.
    record Missing() implements ConsumerBuild {
    }

    ConsumerBuild FAILED = new Failed();
    ConsumerBuild MISSING = new Missing();

    static ConsumerBuild of(Consumer consumer) {
        return new Built(consumer);
    }
}
