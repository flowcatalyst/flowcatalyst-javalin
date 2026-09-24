package io.flowcatalyst.sdk.usecase.jdbc;

import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.HasId;
import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.sql.Connection;
import java.sql.SQLException;
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
            throw writeFailure("PERSIST", "repository persist failed", aggregate, e);
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
            throw writeFailure("DELETE", "repository delete failed", aggregate, e);
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
                throw writeFailure("PERSIST_BATCH", "persist failed at index " + i, aggregates.get(i), e);
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
                throw writeFailure("PERSIST_BATCH", "sync save failed at index " + i, save.aggregate(), e);
            }
            writeEvent(save.event(), "per-row save event write failed at index " + i);
            writeAudit(save.event(), command, "per-row save audit write failed at index " + i);
        }
        for (int i = 0; i < deletes.size(); i++) {
            SyncDelete<A> delete = deletes.get(i);
            try {
                repository.delete(delete.aggregate(), tx);
            } catch (Exception e) {
                throw writeFailure("DELETE_BATCH", "sync delete failed at index " + i, delete.aggregate(), e);
            }
            writeEvent(delete.event(), "per-row delete event write failed at index " + i);
            writeAudit(delete.event(), command, "per-row delete audit write failed at index " + i);
        }
        writeEvent(rollup, "rollup event write failed");
        writeAudit(rollup, command, "rollup audit write failed");
        return rollup;
    }

    /// SQLSTATE `23505`, unique_violation (PostgreSQL and H2 alike).
    private static final String UNIQUE_VIOLATION = "23505";

    /// A repository write that failed. A unique violation anywhere in the
    /// cause chain is a conflict, not an internal error: the operation's
    /// validate phase checked for the duplicate, and a concurrent writer got
    /// there between that check and this persist — the caller should be told
    /// 409, as if the check had seen it. Anything else stays internal, and
    /// names which aggregate failed.
    private static UseCaseException writeFailure(String code, String what, HasId aggregate, Exception e) {
        String subject = aggregate.getClass().getSimpleName() + " " + aggregate.id();
        if (hasSqlState(e, UNIQUE_VIOLATION)) {
            return UseCaseException.conflict("DUPLICATE_KEY",
                    subject + " conflicts with an existing row on a unique key");
        }
        return UseCaseException.internal(code, what + " for " + subject, e);
    }

    private static boolean hasSqlState(Throwable t, String state) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof SQLException sql && state.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private void writeEvent(DomainEvent event, String failureMessage) {
        try {
            sink.writeEvent(tx, event);
        } catch (Exception e) {
            throw UseCaseException.internal("EVENT_WRITE", failureMessage + " (" + event.getClass().getSimpleName() + ")", e);
        }
    }

    private void writeAudit(DomainEvent event, Object command, String failureMessage) {
        try {
            sink.writeAudit(tx, event, command);
        } catch (Exception e) {
            throw UseCaseException.internal("AUDIT_WRITE", failureMessage + " (" + event.getClass().getSimpleName() + ")", e);
        }
    }
}
