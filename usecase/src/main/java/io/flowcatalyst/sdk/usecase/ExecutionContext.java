package io.flowcatalyst.sdk.usecase;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/// Threads trace identifiers and the acting principal through one use-case
/// invocation. Built by the transport layer (HTTP handler, scheduler tick,
/// event consumer) and handed to `Operation.run(...)`; every event the
/// operation emits copies these ids into its [EventMetadata].
///
/// @param principalId   acting user or service account; `null` when unauthenticated
/// @param correlationId propagated from the inbound request, or seeded from the execution id
/// @param causationId   the event that caused this invocation, or `null` for top-level actions
/// @param executionId   unique per invocation
/// @param initiatedAt   when the context was created
public record ExecutionContext(
        String principalId,
        String correlationId,
        String causationId,
        String executionId,
        Instant initiatedAt) {

    public ExecutionContext {
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(initiatedAt, "initiatedAt");
    }

    /// A fresh top-level context: new execution id, correlation id seeded
    /// from it. Use [#withCorrelation] when the inbound request already
    /// carries a correlation id.
    public static ExecutionContext of(String principalId) {
        String executionId = UUID.randomUUID().toString();
        return new ExecutionContext(principalId, executionId, null, executionId, Instant.now());
    }

    /// A context continuing an upstream trace.
    public static ExecutionContext withCorrelation(String principalId, String correlationId) {
        return new ExecutionContext(principalId, correlationId, null, UUID.randomUUID().toString(), Instant.now());
    }

    /// A context derived from a parent event: same correlation chain, parent
    /// recorded as the causation.
    public static ExecutionContext fromParentEvent(DomainEvent parent, String principalId) {
        return new ExecutionContext(principalId, parent.correlationId(), parent.eventId(),
                UUID.randomUUID().toString(), Instant.now());
    }

    /// Same context with the causation id set.
    public ExecutionContext withCausation(String causationId) {
        return new ExecutionContext(principalId, correlationId, causationId, executionId, initiatedAt);
    }
}
