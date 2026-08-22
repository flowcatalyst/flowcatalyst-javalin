package io.flowcatalyst.sdk.usecase.jdbc;

import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.HasId;
import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.sql.Connection;
import java.util.List;

/// A unit of work bound to one externally orchestrated transaction opened by
/// [UnitOfWork#inTransaction]. Every commit performed against it joins that
/// transaction; it never commits or rolls back itself — the surrounding
/// `inTransaction` does.
///
/// Use [#dbTx()] / [#connection()] for ad-hoc writes that must be atomic
/// with the events this unit of work produces (a non-aggregate row, raw SQL
/// on a join table).
public final class TxScopedUnitOfWork {

    private final Connection connection;
    private final Sink sink;
    private final DbTx tx;

    TxScopedUnitOfWork(Connection connection, Sink sink) {
        this.connection = connection;
        this.sink = sink;
        this.tx = new DbTx(connection);
    }

    /// The open transaction, as repositories see it.
    public DbTx dbTx() {
        return tx;
    }

    /// The open transaction's connection, for raw SQL.
    public Connection connection() {
        return connection;
    }

    public Sink sink() {
        return sink;
    }

    /// Aggregate upsert + event + audit on the open transaction (no commit).
    public <A extends HasId, E extends DomainEvent> E commit(A aggregate, Persist<A> repository, E event, Object command) {
        try {
            repository.persist(aggregate, tx);
        } catch (Exception e) {
            throw UseCaseException.internal("PERSIST", "repository persist failed", e);
        }
        writeEvent(event, "could not write domain event");
        writeAudit(event, command, "could not write audit log");
        return event;
    }

    /// Aggregate delete + event + audit on the open transaction (no commit).
    public <A extends HasId, E extends DomainEvent> E commitDelete(A aggregate, Persist<A> repository, E event, Object command) {
        try {
            repository.delete(aggregate, tx);
        } catch (Exception e) {
            throw UseCaseException.internal("DELETE", "repository delete failed", e);
        }
        writeEvent(event, "could not write domain event");
        writeAudit(event, command, "could not write audit log");
        return event;
    }

    /// Event + audit on the open transaction, no aggregate change.
    public <E extends DomainEvent> E emitEvent(E event, Object command) {
        writeEvent(event, "could not write domain event");
        writeAudit(event, command, "could not write audit log");
        return event;
    }

    <A extends HasId, E extends DomainEvent> E commitAll(List<A> aggregates, Persist<A> repository, E event, Object command) {
        for (int i = 0; i < aggregates.size(); i++) {
            try {
                repository.persist(aggregates.get(i), tx);
            } catch (Exception e) {
                throw UseCaseException.internal("PERSIST_BATCH", "persist failed at index " + i, e);
            }
        }
        writeEvent(event, "could not write domain event");
        writeAudit(event, command, "could not write audit log");
        return event;
    }

    <A extends HasId, RE extends DomainEvent> RE commitSync(
            Persist<A> repository, List<SyncSave<A>> saves, List<SyncDelete<A>> deletes, RE rollup, Object command) {
        for (int i = 0; i < saves.size(); i++) {
            SyncSave<A> save = saves.get(i);
            try {
                repository.persist(save.aggregate(), tx);
            } catch (Exception e) {
                throw UseCaseException.internal("PERSIST_BATCH", "sync save failed at index " + i, e);
            }
            writeEvent(save.event(), "per-row save event write failed at index " + i);
            writeAudit(save.event(), command, "per-row save audit write failed at index " + i);
        }
        for (int i = 0; i < deletes.size(); i++) {
            SyncDelete<A> delete = deletes.get(i);
            try {
                repository.delete(delete.aggregate(), tx);
            } catch (Exception e) {
                throw UseCaseException.internal("DELETE_BATCH", "sync delete failed at index " + i, e);
            }
            writeEvent(delete.event(), "per-row delete event write failed at index " + i);
            writeAudit(delete.event(), command, "per-row delete audit write failed at index " + i);
        }
        writeEvent(rollup, "rollup event write failed");
        writeAudit(rollup, command, "rollup audit write failed");
        return rollup;
    }

    private void writeEvent(DomainEvent event, String failureMessage) {
        try {
            sink.writeEvent(tx, event);
        } catch (Exception e) {
            throw UseCaseException.internal("EVENT_WRITE", failureMessage, e);
        }
    }

    private void writeAudit(DomainEvent event, Object command, String failureMessage) {
        try {
            sink.writeAudit(tx, event, command);
        } catch (Exception e) {
            throw UseCaseException.internal("AUDIT_WRITE", failureMessage, e);
        }
    }
}
