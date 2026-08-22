package io.flowcatalyst.sdk.usecase.op;

import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.HasId;
import io.flowcatalyst.sdk.usecase.jdbc.Persist;
import io.flowcatalyst.sdk.usecase.jdbc.SyncDelete;
import io.flowcatalyst.sdk.usecase.jdbc.SyncSave;

import java.util.List;
import java.util.Objects;

/// A pending change and the domain event it produces, built during an
/// operation's `Execute` phase but NOT yet committed. `Operation.run` is the
/// only thing that applies a Plan, and it does so inside one transaction —
/// writing the aggregate change, the domain event and the audit log
/// atomically.
///
/// Plan is sealed: the only implementations are the five records below, the
/// only constructors are the factories, and `Execute` receives no unit of
/// work. An operation therefore cannot reach the database except by returning
/// a Plan and letting `run` apply it after `Validate` and `Authorize` — which
/// is what makes "aggregate written ⇒ event + audit written, atomically" hold
/// by construction.
///
/// @param <E> the committed (returned) event type
public sealed interface Plan<E extends DomainEvent> {

    /// Upsert one aggregate with its domain event (create and update operations).
    static <A extends HasId, E extends DomainEvent> Plan<E> save(A aggregate, Persist<A> repository, E event) {
        return new Save<>(aggregate, repository, event);
    }

    /// Delete one aggregate with its domain event.
    static <A extends HasId, E extends DomainEvent> Plan<E> delete(A aggregate, Persist<A> repository, E event) {
        return new Delete<>(aggregate, repository, event);
    }

    /// A domain event with no aggregate change (e.g. `UserLoggedIn`).
    static <E extends DomainEvent> Plan<E> emit(E event) {
        return new Emit<>(event);
    }

    /// Upsert many aggregates of one type with a single summary event — one
    /// logical command that touches many rows.
    static <A extends HasId, E extends DomainEvent> Plan<E> saveAll(List<A> aggregates, Persist<A> repository, E event) {
        return new SaveAll<>(aggregates, repository, event);
    }

    /// A batch of per-row saves and deletes (each with its own event) plus a
    /// rollup event, all in one transaction. For sync / bulk-upsert endpoints
    /// whose consumers project the per-row events. The rollup is the returned
    /// event.
    static <A extends HasId, RE extends DomainEvent> Plan<RE> sync(
            Persist<A> repository, List<SyncSave<A>> saves, List<SyncDelete<A>> deletes, RE rollup) {
        return new Sync<>(repository, saves, deletes, rollup);
    }

    record Save<A extends HasId, E extends DomainEvent>(A aggregate, Persist<A> repository, E event) implements Plan<E> {
        public Save {
            Objects.requireNonNull(aggregate, "aggregate");
            Objects.requireNonNull(repository, "repository");
            Objects.requireNonNull(event, "event");
        }
    }

    record Delete<A extends HasId, E extends DomainEvent>(A aggregate, Persist<A> repository, E event) implements Plan<E> {
        public Delete {
            Objects.requireNonNull(aggregate, "aggregate");
            Objects.requireNonNull(repository, "repository");
            Objects.requireNonNull(event, "event");
        }
    }

    record Emit<E extends DomainEvent>(E event) implements Plan<E> {
        public Emit {
            Objects.requireNonNull(event, "event");
        }
    }

    record SaveAll<A extends HasId, E extends DomainEvent>(List<A> aggregates, Persist<A> repository, E event) implements Plan<E> {
        public SaveAll {
            aggregates = List.copyOf(Objects.requireNonNull(aggregates, "aggregates"));
            Objects.requireNonNull(repository, "repository");
            Objects.requireNonNull(event, "event");
        }
    }

    record Sync<A extends HasId, RE extends DomainEvent>(
            Persist<A> repository, List<SyncSave<A>> saves, List<SyncDelete<A>> deletes, RE rollup) implements Plan<RE> {
        public Sync {
            Objects.requireNonNull(repository, "repository");
            saves = List.copyOf(Objects.requireNonNull(saves, "saves"));
            deletes = List.copyOf(Objects.requireNonNull(deletes, "deletes"));
            Objects.requireNonNull(rollup, "rollup");
        }
    }
}
