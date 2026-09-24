package io.flowcatalyst.fnhost.wasm;

import io.flowcatalyst.fnhost.context.InvocationDeadline;
import io.flowcatalyst.platform.shared.LogThrottle;
import io.flowcatalyst.fnhost.wasm.DbFailure.BadRequest;
import io.flowcatalyst.fnhost.wasm.DbFailure.NoTimeLeft;
import io.flowcatalyst.fnhost.wasm.DbFailure.NotDeclared;
import io.flowcatalyst.fnhost.wasm.DbFailure.Sql;
import io.flowcatalyst.fnhost.wasm.DbFailure.TxUnknown;
import io.flowcatalyst.sdk.result.Result;
import io.flowcatalyst.sdk.result.Result.Err;
import io.flowcatalyst.sdk.result.Result.Ok;
import org.postgresql.jdbc.PgStatement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// The database state of ONE Wasm invocation (`docs/spec/function-wasm-db.md`):
/// the manifest's `db[]` pools — the very [DataSource]s a JVM function gets
/// from [io.flowcatalyst.function.FunctionContext#dataSource], the same
/// [io.flowcatalyst.fnhost.context.DbPools] caps — and the transactions this
/// call has open.
///
/// - **Scoped to the call.** [WasmFunction#handle] makes one per call and binds
///   it as [#CURRENT] around the guest; the `fc_db_*` host functions reach it
///   only through that binding. A transaction id therefore resolves only in the
///   call that opened it — the registry is this object's own field, and no
///   other call can reach this object.
/// - **Force-release.** [#close] runs in [WasmFunction#handle]'s `finally` —
///   normal return, guest error, trap, deadline interrupt, host exception — and
///   rolls back and returns every connection still held.
/// - **Without `tx`**, a statement borrows a connection in autocommit and returns
///   it before the host function answers.
/// - **Deadline.** Every statement's timeout is the time left before the
///   invocation deadline ([InvocationDeadline]), to the millisecond; with less
///   than 1 ms left the statement is not sent ([NoTimeLeft]).
///
/// Confined to the invocation's thread (Endive runs host functions on the
/// thread that called the guest), so it needs no lock.
final class DbSession implements AutoCloseable {

    /// Bound by [WasmFunction#handle] for the duration of one guest call.
    static final ScopedValue<DbSession> CURRENT = ScopedValue.newInstance();

    private static final SecureRandom TX_IDS = new SecureRandom();
    private static final Logger LOG = LoggerFactory.getLogger(DbSession.class);
    private static final LogThrottle UNAVAILABLE_LOG = new LogThrottle(Duration.ofSeconds(10));

    private final Map<String, DataSource> declared;
    private final Clock clock;
    /// This call's open transactions by id. Owned by this call alone.
    private final Map<String, OpenTx> transactions = new LinkedHashMap<>();
    private boolean closed;

    private record OpenTx(String db, Connection connection) {
    }

    /// @param declared the manifest's `db[]` entries by name
    DbSession(Map<String, DataSource> declared, Clock clock) {
        this.declared = Map.copyOf(declared);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ── the five operations ───────────────────────────────────────────────

    /// `fc_db_query` — the answer JSON bytes.
    Result<byte[], DbFailure> query(JsonNode in) {
        return statement(in, true);
    }

    /// `fc_db_execute` — the answer JSON bytes.
    Result<byte[], DbFailure> execute(JsonNode in) {
        return statement(in, false);
    }

    /// `fc_db_begin` — borrows a connection for this call and opens a
    /// transaction on it.
    Result<String, DbFailure> begin(JsonNode in) {
        DataSource ds;
        switch (dataSource(in)) {
            case Ok<DataSource, DbFailure>(DataSource declaredDs) -> ds = declaredDs;
            case Err<DataSource, DbFailure>(DbFailure failure) -> {
                return Result.err(failure);
            }
        }
        if (closed) {
            return Result.err(new DbFailure.NoInvocation());
        }
        Connection connection;
        try {
            // The wait at the pool's gate is untimed; the deadline interrupt ends it (57014).
            connection = ds.getConnection();
        } catch (SQLException e) {
            return Result.err(sqlFailure(in.path("db").asString(), e));
        }
        try {
            connection.setAutoCommit(false);
        } catch (SQLException e) {
            closeQuietly(connection);
            return Result.err(sqlFailure(in.path("db").asString(), e));
        }
        String id = newTxId();
        transactions.put(id, new OpenTx(in.path("db").asString(), connection));
        return Result.ok(id);
    }

    /// `fc_db_commit` — the transaction is over whatever the outcome: on a
    /// failed commit it is rolled back, and its connection is returned either way.
    Result<Ended, DbFailure> commit(JsonNode in) {
        return finish(in, true);
    }

    /// `fc_db_rollback`.
    Result<Ended, DbFailure> rollback(JsonNode in) {
        return finish(in, false);
    }

    /// How a transaction [#commit] or [#rollback] ended it.
    enum Ended {
        COMMITTED, ROLLED_BACK
    }

    // ── end of call ───────────────────────────────────────────────────────

    /// Rolls back and returns every connection this call still holds. Never
    /// throws; idempotent. A rollback that cannot reach the server (the
    /// deadline interrupt closed the socket) still closes the connection — the
    /// pool evicts it, and the server rolls back a transaction whose session is
    /// gone.
    @Override
    public void close() {
        closed = true;
        List<OpenTx> open = new ArrayList<>(transactions.values());
        transactions.clear();
        for (OpenTx tx : open) {
            try {
                tx.connection().rollback();
            } catch (SQLException | RuntimeException e) {
                // the connection is closed below regardless
            } finally {
                closeQuietly(tx.connection());
            }
        }
    }

    /// Transactions this call holds open — a diagnostic for tests.
    int openTransactions() {
        return transactions.size();
    }

    // ── internals ─────────────────────────────────────────────────────────

    private Result<byte[], DbFailure> statement(JsonNode in, boolean query) {
        DataSource ds;
        switch (dataSource(in)) {
            case Ok<DataSource, DbFailure>(DataSource declaredDs) -> ds = declaredDs;
            case Err<DataSource, DbFailure>(DbFailure failure) -> {
                return Result.err(failure);
            }
        }
        JsonNode sqlNode = in.path("sql");
        if (!sqlNode.isString() || sqlNode.asString().isBlank()) {
            return Result.err(new BadRequest("sql is required and must be a non-empty string"));
        }
        JsonNode params = in.path("params");
        if (!params.isMissingNode() && !params.isNull() && !params.isArray()) {
            return Result.err(new BadRequest("params must be an array"));
        }
        for (int i = 0; i < params.size(); i++) {
            JsonNode p = params.get(i);
            if (!(p.isNull() || p.isBoolean() || p.isNumber() || p.isString())) {
                return Result.err(new BadRequest("params[" + i + "] must be a string, number, boolean or null"));
            }
        }
        JsonNode txNode = in.path("tx");
        if (!txNode.isMissingNode() && !txNode.isNull()) {
            if (!txNode.isString()) {
                return Result.err(new BadRequest("tx must be a string"));
            }
            OpenTx tx = transactions.get(txNode.asString());
            if (tx == null || !tx.db().equals(in.path("db").asString())) {
                return Result.err(new TxUnknown());
            }
            return run(tx.db(), tx.connection(), sqlNode.asString(), params, query);
        }
        if (closed) {
            return Result.err(new DbFailure.NoInvocation());
        }
        if (timeoutMillis() < 1) {
            return Result.err(new NoTimeLeft()); // not even worth a borrow
        }
        String db = in.path("db").asString();
        try (Connection connection = ds.getConnection()) {
            return run(db, connection, sqlNode.asString(), params, query);
        } catch (SQLException e) {
            return Result.err(sqlFailure(db, e));
        }
    }

    private Result<byte[], DbFailure> run(String db, Connection connection, String sql, JsonNode params,
                                          boolean query) {
        long timeout = timeoutMillis();
        if (timeout < 1) {
            return Result.err(new NoTimeLeft());
        }
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            // Milliseconds, not setQueryTimeout's whole seconds: rounding down would turn
            // a sub-second remainder into "no timeout", rounding up would let the statement
            // outlive the call. The driver cancels the statement server-side when it fires.
            ps.unwrap(PgStatement.class).setQueryTimeoutMs(timeout);
            bind(ps, params);
            if (query) {
                // The server stops after one row past the cap, so the driver never holds
                // more than that; the extra row only says "there was more".
                ps.setMaxRows(RowJson.MAX_ROWS + 1);
                if (!ps.execute()) {
                    return Result.ok(RowJson.empty());
                }
                try (ResultSet rs = ps.getResultSet()) {
                    return Result.ok(RowJson.write(rs, RowJson.MAX_ROWS, RowJson.MAX_ROW_BYTES));
                }
            }
            long updated = ps.executeLargeUpdate();
            return Result.ok(("{\"updated\":" + updated + "}").getBytes(StandardCharsets.UTF_8));
        } catch (SQLException e) {
            return Result.err(sqlFailure(db, e));
        }
    }

    /// Positional, never interpolated. A string is sent with no declared type,
    /// so the server reads it as whatever the statement needs there (text,
    /// uuid, timestamptz, jsonb, …); integers as `int8`, other numbers as exact
    /// `numeric`.
    private static void bind(PreparedStatement ps, JsonNode params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            JsonNode p = params.get(i);
            int index = i + 1;
            if (p.isNull()) {
                ps.setNull(index, Types.NULL);
            } else if (p.isBoolean()) {
                ps.setBoolean(index, p.booleanValue());
            } else if (p.isIntegralNumber() && p.canConvertToLong()) {
                ps.setLong(index, p.longValue());
            } else if (p.isNumber()) {
                ps.setBigDecimal(index, p.decimalValue());
            } else {
                ps.setObject(index, p.asString(), Types.OTHER);
            }
        }
    }

    private Result<Ended, DbFailure> finish(JsonNode in, boolean commit) {
        JsonNode txNode = in.path("tx");
        if (!txNode.isString()) {
            return Result.err(new BadRequest("tx is required and must be a string"));
        }
        OpenTx tx = transactions.remove(txNode.asString());
        if (tx == null) {
            return Result.err(new TxUnknown());
        }
        Connection connection = tx.connection();
        try {
            if (commit) {
                connection.commit();
            } else {
                connection.rollback();
            }
            return Result.ok(commit ? Ended.COMMITTED : Ended.ROLLED_BACK);
        } catch (SQLException e) {
            if (commit) {
                try {
                    connection.rollback();
                } catch (SQLException | RuntimeException ignored) {
                    // closed below regardless
                }
            }
            return Result.err(sqlFailure(tx.db(), e));
        } finally {
            closeQuietly(connection);
        }
    }

    /// The guest gets the failure as a value either way. An unavailable
    /// database (connection lost, pool or server out of connections) is also the
    /// operator's problem, which the guest may never report: logged host-side —
    /// the database's name, the SQLSTATE and the driver's exception, never the
    /// SQL text or a parameter value — at most once per interval per session
    /// kind (it can fire on every statement).
    private static Sql sqlFailure(String db, SQLException e) {
        Sql failure = Sql.of(e);
        if (failure.sqlClass() == DbFailure.SqlClass.DB_UNAVAILABLE) {
            UNAVAILABLE_LOG.admit().ifPresent(suppressed -> LOG.atWarn()
                    .setMessage("a Wasm function's database is unavailable")
                    .addKeyValue("db", db)
                    .addKeyValue("sql_state", e.getSQLState())
                    .addKeyValue("suppressed_since_last", suppressed)
                    .setCause(e)
                    .log());
        }
        return failure;
    }

    private Result<DataSource, DbFailure> dataSource(JsonNode in) {
        if (!in.isObject()) {
            return Result.err(new BadRequest("the input must be a JSON object"));
        }
        JsonNode db = in.path("db");
        if (!db.isString()) {
            return Result.err(new BadRequest("db is required and must be a string"));
        }
        DataSource ds = declared.get(db.asString());
        return ds == null ? Result.err(new NotDeclared(db.asString())) : Result.ok(ds);
    }

    /// Whole milliseconds left before the deadline, rounded down.
    private long timeoutMillis() {
        Duration left = InvocationDeadline.remaining(clock);
        return left.toMillis();
    }

    private static String newTxId() {
        byte[] bytes = new byte[16];
        TX_IDS.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException | RuntimeException ignored) {
            // the pool's own problem now; the permit is released by the close itself
        }
    }
}
