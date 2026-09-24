package io.flowcatalyst.fnhost.wasm;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.Objects;

/// Why an `fc_db_*` host function answered `{"error":{code,message}}` instead of
/// a result (`docs/spec/function-wasm-db.md`) — always a value the guest reads,
/// never a trap. Every case states its own [#code] and [#message]; none is
/// defaulted.
///
/// None of these carries SQL text or parameter values, and none is ever logged:
/// the guest is the only reader.
sealed interface DbFailure permits DbFailure.NotDeclared, DbFailure.BadRequest, DbFailure.TxUnknown,
        DbFailure.NoTimeLeft, DbFailure.NoInvocation, DbFailure.Sql {

    /// The wire code the guest switches on.
    String code();

    /// A human-readable reason for the guest.
    String message();

    /// `db` names no manifest `db[]` entry.
    record NotDeclared(String db) implements DbFailure {
        @Override
        public String code() {
            return "DB_NOT_DECLARED";
        }

        @Override
        public String message() {
            return "no database named '" + db + "' is declared by this function's manifest";
        }
    }

    /// The input JSON is not the function's shape (`detail` names the field,
    /// never its value).
    record BadRequest(String detail) implements DbFailure {
        public BadRequest {
            Objects.requireNonNull(detail, "detail");
        }

        @Override
        public String code() {
            return "DB_BAD_REQUEST";
        }

        @Override
        public String message() {
            return detail;
        }
    }

    /// `tx` is not a transaction this call opened on this `db` and still holds
    /// open — another call's id, one already committed or rolled back, or a
    /// made-up one all read the same.
    record TxUnknown() implements DbFailure {
        @Override
        public String code() {
            return "DB_TX_UNKNOWN";
        }

        @Override
        public String message() {
            return "no open transaction with this id in this call";
        }
    }

    /// The invocation deadline has already passed (or less than a millisecond
    /// is left): the statement is not sent at all.
    record NoTimeLeft() implements DbFailure {
        @Override
        public String code() {
            return "DB_TIMEOUT";
        }

        @Override
        public String message() {
            return "no time left before the invocation deadline";
        }
    }

    /// A host function ran outside a call (a guest's start function at
    /// instantiation) — there is no invocation for a connection to belong to.
    record NoInvocation() implements DbFailure {
        @Override
        public String code() {
            return "DB_ERROR";
        }

        @Override
        public String message() {
            return "database access is only available during a call";
        }
    }

    /// The driver or the server refused. `message` is the driver's own.
    record Sql(SqlClass sqlClass, String message) implements DbFailure {
        public Sql {
            Objects.requireNonNull(sqlClass, "sqlClass");
            Objects.requireNonNull(message, "message");
        }

        static Sql of(SQLException e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new Sql(SqlClass.of(e), message);
        }

        @Override
        public String code() {
            return sqlClass.name();
        }
    }

    /// The SQLSTATE class a [Sql] failure falls in — its constant name is the
    /// wire code (a pinned table in `DbFailureTest`).
    enum SqlClass {
        /// Class `23` — integrity constraint violation.
        DB_CONSTRAINT,
        /// Class `42` — syntax error or access rule violation.
        DB_SYNTAX,
        /// `57014` query_canceled — the statement timeout (the time left before
        /// the invocation deadline) cancelled it, or the deadline interrupted a
        /// wait for a connection ([io.flowcatalyst.platform.shared.database.GatedDataSource]
        /// reports that as `57014` too).
        DB_TIMEOUT,
        /// Class `08` connection exception, the rest of class `57` (operator
        /// intervention: shutdown, cannot connect now), class `53`
        /// (insufficient resources: too many connections, out of memory) — and
        /// a connection-kind exception with no SQLSTATE.
        DB_UNAVAILABLE,
        /// Everything else (data exceptions, serialization failures, deadlocks, …).
        DB_ERROR;

        static SqlClass of(SQLException e) {
            String state = e.getSQLState();
            if (state == null || state.length() < 2) {
                if (e instanceof SQLTimeoutException) {
                    return DB_TIMEOUT;
                }
                if (e instanceof SQLTransientConnectionException || e instanceof SQLNonTransientConnectionException) {
                    return DB_UNAVAILABLE;
                }
                return DB_ERROR;
            }
            return ofState(state);
        }

        static SqlClass ofState(String state) {
            if ("57014".equals(state)) {
                return DB_TIMEOUT;
            }
            return switch (state.substring(0, 2)) {
                case "23" -> DB_CONSTRAINT;
                case "42" -> DB_SYNTAX;
                case "08", "57", "53" -> DB_UNAVAILABLE;
                default -> DB_ERROR;
            };
        }
    }
}
