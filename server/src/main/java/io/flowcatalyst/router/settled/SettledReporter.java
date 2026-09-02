package io.flowcatalyst.router.settled;

/// Reports a settled `BLOCK_ON_ERROR` group to the platform.
///
/// [#report] **MUST NOT block the caller.** It is invoked from inside a
/// pool's drainer, immediately after the group's head and its untried
/// siblings have already been ACKed off the broker — see
/// [io.flowcatalyst.router.pool.Pool] — so a call that waits on the network
/// would hold that worker's virtual thread (and, transitively, the
/// concurrency slot it freed on the way out) for no reason: the ACKs already
/// happened, and a slow or dead platform must never make the router pay for
/// it. [HttpSettledReporter] satisfies this by returning at once and doing
/// the actual work on its own virtual thread; a fake used in tests may run
/// synchronously.
public interface SettledReporter {

    void report(SettledReport report);
}
