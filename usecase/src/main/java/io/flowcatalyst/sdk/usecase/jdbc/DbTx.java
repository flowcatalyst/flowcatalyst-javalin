package io.flowcatalyst.sdk.usecase.jdbc;

import java.sql.Connection;
import java.util.Objects;

/// The opaque write handle passed to repository `persist` / `delete` methods:
/// a JDBC `Connection` that is inside an open transaction owned by the unit
/// of work. Repositories execute SQL on it (plain JDBC, or
/// `DSL.using(tx.connection())` for jOOQ) and must never commit, roll back or
/// close it.
///
/// Only the unit of work constructs one, so a `DbTx` in hand is proof of
/// being inside a use-case transaction — with the single, loud exception of
/// [#wrapForBootstrap].
public final class DbTx {

    private final Connection connection;

    DbTx(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /// The underlying connection, already in a transaction.
    public Connection connection() {
        return connection;
    }

    /// A `DbTx` around an externally managed transaction, for
    /// infrastructure-bootstrap callers ONLY (init commands, seeders, admin
    /// tools). Those paths run outside the use-case envelope: no executing
    /// principal, no domain events. Production code paths must go through
    /// `Operation.run` — if you are reaching for this from one, you want a
    /// real use case instead.
    public static DbTx wrapForBootstrap(Connection connection) {
        return new DbTx(connection);
    }
}
