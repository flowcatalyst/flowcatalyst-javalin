package io.flowcatalyst.sdk.usecase.op;

import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.HasId;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;

/// The single consumer of a [Plan]: maps each planned change onto the unit of
/// work's atomic commit paths. Package-private — only `Operation.run` calls it.
final class PlanApplier {

    private PlanApplier() {}

    static <E extends DomainEvent> E apply(Plan<E> plan, UnitOfWork uow, Object command) {
        return switch (plan) {
            case Plan.Save<?, E> p -> save(p, uow, command);
            case Plan.Delete<?, E> p -> delete(p, uow, command);
            case Plan.Emit<E> p -> uow.emitEvent(p.event(), command);
            case Plan.SaveAll<?, E> p -> saveAll(p, uow, command);
            case Plan.Sync<?, E> p -> sync(p, uow, command);
        };
    }

    // The helpers below exist to capture the aggregate type parameter once per
    // plan, so aggregate and repository are known to agree on A.

    private static <A extends HasId, E extends DomainEvent> E save(Plan.Save<A, E> p, UnitOfWork uow, Object command) {
        return uow.commit(p.aggregate(), p.repository(), p.event(), command);
    }

    private static <A extends HasId, E extends DomainEvent> E delete(Plan.Delete<A, E> p, UnitOfWork uow, Object command) {
        return uow.commitDelete(p.aggregate(), p.repository(), p.event(), command);
    }

    private static <A extends HasId, E extends DomainEvent> E saveAll(Plan.SaveAll<A, E> p, UnitOfWork uow, Object command) {
        return uow.commitAll(p.aggregates(), p.repository(), p.event(), command);
    }

    private static <A extends HasId, RE extends DomainEvent> RE sync(Plan.Sync<A, RE> p, UnitOfWork uow, Object command) {
        return uow.commitSync(p.repository(), p.saves(), p.deletes(), p.rollup(), command);
    }
}
