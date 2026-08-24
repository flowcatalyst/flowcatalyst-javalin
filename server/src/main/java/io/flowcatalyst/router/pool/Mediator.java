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

    /// Delivers `message` once.
    ///
    /// @param recordFailure whether a failure should count against the
    ///        endpoint's circuit breaker. The breaker still *gates* every
    ///        attempt — an open circuit short-circuits all of them — but it
    ///        is only *told about* failures at a burst boundary, so a burst
    ///        of three failed attempts is one breaker failure. Go achieves
    ///        the same by recording once per `Mediate` call after its in-call
    ///        retries; flattening that schedule (Q3) moves the decision here.
    ///        Successes are always recorded: a success ends its burst wherever
    ///        it lands.
    /// @throws InterruptedException if the calling thread is interrupted;
    ///         the pool treats this as shutdown, never as a delivery failure
    MediationOutcome deliver(Message message, boolean recordFailure) throws InterruptedException;
}
