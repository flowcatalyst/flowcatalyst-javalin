package io.flowcatalyst.sdk.usecase.jdbc;

import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.HasId;
import io.flowcatalyst.sdk.usecase.UseCaseException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/// The JDBC-backed unit of work. Each top-level `commit` / `commitDelete` /
/// `commitAll` / `commitSync` / `emitEvent` opens its own transaction and
/// commits or rolls back on exit. For orchestrations that span several
/// aggregates (or mix aggregate writes with ad-hoc SQL) use
/// [#inTransaction], which hands the body a [TxScopedUnitOfWork] bound to one
/// open transaction.
///
/// Construct once with a `DataSource` and the [Sink] that decides where
/// domain events and audit logs land (platform tables, or the consumer-app
/// outbox), and pass the instance to every `Operation.run`.
///
/// Failure vocabulary (all surface as internal [UseCaseException]s):
/// `TX_BEGIN`, `PERSIST`, `PERSIST_BATCH`, `DELETE`, `DELETE_BATCH`,
/// `EVENT_WRITE`, `AUDIT_WRITE`, `TX_COMMIT`.
public final class UnitOfWork {

    private final DataSource dataSource;
    private final Sink sink;

    public UnitOfWork(DataSource dataSource, Sink sink) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    /// The underlying data source, for read-only queries outside a use case
    /// (handlers loading data for GET endpoints). Writes must still go
    /// through the commit paths.
    public DataSource dataSource() {
        return dataSource;
    }

    public Sink sink() {
        return sink;
    }

    /// Upserts the aggregate via its repository, then writes the domain event
    /// and audit log via the sink — all in one transaction. Returns the
    /// committed event.
    public <A extends HasId, E extends DomainEvent> E commit(A aggregate, Persist<A> repository, E event, Object command) {
        return transact(Messages.SINGLE, scoped -> scoped.commit(aggregate, repository, event, command));
    }

    /// Deletes the aggregate via its repository and emits the deletion event
    /// + audit log atomically.
    public <A extends HasId, E extends DomainEvent> E commitDelete(A aggregate, Persist<A> repository, E event, Object command) {
        return transact(Messages.SINGLE, scoped -> scoped.commitDelete(aggregate, repository, event, command));
    }

    /// Writes a domain event + audit log with no aggregate change (e.g.
    /// `UserLoggedIn`).
    public <E extends DomainEvent> E emitEvent(E event, Object command) {
        return transact(Messages.SINGLE, scoped -> scoped.emitEvent(event, command));
    }

    /// Upserts a batch of aggregates of one type via one repository and emits
    /// a single summary event + audit log.
    public <A extends HasId, E extends DomainEvent> E commitAll(List<A> aggregates, Persist<A> repository, E event, Object command) {
        return transact(Messages.SINGLE, scoped -> scoped.commitAll(aggregates, repository, event, command));
    }

    /// Persists a batch of saves and deletes, writes one domain event + audit
    /// row per touched aggregate, then a rollup event + audit row — ALL in
    /// one transaction. Write order: saves (in order) → deletes (in order) →
    /// rollup, so downstream readers see per-row events before the rollup.
    /// All audit rows record the outer `command`. Returns the rollup.
    public <A extends HasId, RE extends DomainEvent> RE commitSync(
            Persist<A> repository, List<SyncSave<A>> saves, List<SyncDelete<A>> deletes, RE rollup, Object command) {
        return transact(Messages.SINGLE, scoped -> scoped.commitSync(repository, saves, deletes, rollup, command));
    }

    /// Opens one transaction, hands the body a [TxScopedUnitOfWork] bound to
    /// it, commits when the body returns and rolls back if it throws (the
    /// exception propagates unchanged). Every aggregate change inside the body
    /// must still go through the scoped commit helpers so it is written with
    /// its event + audit in this same transaction.
    public <R> R inTransaction(Function<TxScopedUnitOfWork, R> body) {
        return transact(Messages.ORCHESTRATION, body);
    }

    private <R> R transact(Messages messages, Function<TxScopedUnitOfWork, R> body) {
        Connection connection;
        try {
            connection = dataSource.getConnection();
        } catch (SQLException e) {
            throw UseCaseException.internal("TX_BEGIN", messages.begin(), e);
        }
        try {
            try {
                connection.setAutoCommit(false);
            } catch (SQLException e) {
                throw UseCaseException.internal("TX_BEGIN", messages.begin(), e);
            }
            R result;
            try {
                result = body.apply(new TxScopedUnitOfWork(connection, sink));
            } catch (RuntimeException | Error e) {
                rollbackQuietly(connection, e);
                throw e;
            }
            try {
                connection.commit();
            } catch (SQLException e) {
                rollbackQuietly(connection, e);
                throw UseCaseException.internal("TX_COMMIT", messages.commit(), e);
            }
            return result;
        } finally {
            closeQuietly(connection);
        }
    }

    private static void rollbackQuietly(Connection connection, Throwable primary) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            primary.addSuppressed(e);
        }
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException _) {
            // best effort: pools reset this on return anyway
        }
        try {
            connection.close();
        } catch (SQLException _) {
            // nothing useful to do: the work is already committed or rolled back
        }
    }

    private record Messages(String begin, String commit) {
        static final Messages SINGLE = new Messages(
                "could not open transaction", "could not commit transaction");
        static final Messages ORCHESTRATION = new Messages(
                "could not open orchestration transaction", "could not commit orchestration tx");
    }
}
