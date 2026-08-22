package io.flowcatalyst.sdk.usecase.jdbc;

import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.HasId;

import java.util.Objects;

/// An aggregate to upsert paired with the per-row domain event to emit for it
/// (typically a `Created` or `Updated` event). See `Plan.sync` /
/// [UnitOfWork#commitSync].
public record SyncSave<A extends HasId>(A aggregate, DomainEvent event) {
    public SyncSave {
        Objects.requireNonNull(aggregate, "aggregate");
        Objects.requireNonNull(event, "event");
    }
}
