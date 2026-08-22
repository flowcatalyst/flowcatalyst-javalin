package io.flowcatalyst.sdk.usecase.jdbc;

import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.HasId;

import java.util.Objects;

/// An aggregate to delete paired with the per-row domain event to emit for
/// it. See `Plan.sync` / [UnitOfWork#commitSync].
public record SyncDelete<A extends HasId>(A aggregate, DomainEvent event) {
    public SyncDelete {
        Objects.requireNonNull(aggregate, "aggregate");
        Objects.requireNonNull(event, "event");
    }
}
