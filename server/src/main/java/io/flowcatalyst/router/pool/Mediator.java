package io.flowcatalyst.router.pool;

import io.flowcatalyst.router.wire.MediationOutcome;
import io.flowcatalyst.router.wire.Message;

/// Delivers one message to its target and classifies what happened.
///
/// The pool depends on this rather than on an HTTP client so the delivery
/// decisions — ordering, backoff, acknowledgement — are testable without a
/// socket, and so the breaker and host-pool machinery stay behind one seam.
///
/// Implementations own the per-endpoint circuit breaker: an open breaker is
/// reported as [MediationOutcome.CircuitOpen] **without** attempting a call.
@FunctionalInterface
public interface Mediator {

    /// @throws InterruptedException if the calling thread is interrupted;
    ///         the pool treats this as shutdown, never as a delivery failure
    MediationOutcome deliver(Message message) throws InterruptedException;
}
