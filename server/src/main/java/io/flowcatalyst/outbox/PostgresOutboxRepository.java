package io.flowcatalyst.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Postgres-backed [OutboxRepository] (spec §3) — plain JDBC text blocks,
/// not jOOQ's typed DSL: `outbox_messages` is the SDK's table
/// (`sdk/migrations`), owned by the consumer application's own schema rather
/// than the platform's generated jOOQ model, so there is no generated table
/// to build a typed query against. CONVENTIONS §1 carves out "plain-SQL text
/// blocks only for `SKIP LOCKED` claims and partition DDL" — every statement
/// here falls into that carve-out, not just the claim.
public final class PostgresOutboxRepository implements OutboxRepository {

    private static final Logger LOG = LoggerFactory.getLogger(PostgresOutboxRepository.class);

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS outbox_messages (
                id VARCHAR(26) PRIMARY KEY,
                type VARCHAR(20) NOT NULL,
                message_group VARCHAR(255),
                payload TEXT NOT NULL,
                status SMALLINT NOT NULL DEFAULT 0,
                retry_count SMALLINT NOT NULL DEFAULT 0,
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                error_message TEXT,
                client_id VARCHAR(26),
                payload_size INTEGER,
                headers JSONB
            )
            """;

    private static final String CREATE_PENDING_INDEX_SQL = """
            CREATE INDEX IF NOT EXISTS idx_outbox_messages_pending
                ON outbox_messages (status, message_group, created_at)
                WHERE status = 0
            """;

    private static final String CREATE_STUCK_INDEX_SQL = """
            CREATE INDEX IF NOT EXISTS idx_outbox_messages_stuck
                ON outbox_messages (status, created_at)
                WHERE status = 9
            """;

    /// The claim query (spec §3). The lock and the order are both
    /// load-bearing: `FOR UPDATE SKIP LOCKED` is what makes two concurrent
    /// claimers exclusive (`PostgresOutboxRepositoryTest`'s barrier test),
    /// and `ORDER BY message_group, created_at` is the order grouped
    /// delivery depends on. Selects more than the spec's bare `id` — the
    /// extra columns are exactly what an [OutboxItem] needs — but the
    /// `WHERE`/`ORDER BY`/`LIMIT`/lock clause is verbatim.
    private static final String CLAIM_SQL = """
            SELECT id, type, message_group, payload, retry_count, created_at
              FROM outbox_messages
             WHERE status = 0
             ORDER BY message_group, created_at
             LIMIT ?
               FOR UPDATE SKIP LOCKED
            """;

    private static final String MARK_IN_PROGRESS_SQL =
            "UPDATE outbox_messages SET status = 9, updated_at = NOW() WHERE id = ANY(?)";

    private static final String MARK_SUCCESS_SQL =
            "DELETE FROM outbox_messages WHERE id = ANY(?)";

    private static final String MARK_FAILED_SQL = """
            UPDATE outbox_messages
               SET status = ?, error_message = ?, retry_count = retry_count + 1, updated_at = NOW()
             WHERE id = ANY(?)
            """;

    /// Only a still-`IN_PROGRESS` (9) row is released — one claimed but
    /// never attempted because its group was inactive. A row anything else
    /// has already moved on (dispatched, or a concurrent recovery/retry
    /// already reset it) and must not be reset out from under it (spec §3).
    private static final String RELEASE_SQL =
            "UPDATE outbox_messages SET status = 0 WHERE id = ANY(?) AND status = 9";

    private static final String REQUEUE_SQL =
            "UPDATE outbox_messages SET status = 0, retry_count = 0, error_message = NULL WHERE id = ANY(?)";

    private static final String RECOVER_STUCK_SQL =
            "UPDATE outbox_messages SET status = 0 WHERE status = 9 AND updated_at < ?";

    private final DataSource dataSource;

    public PostgresOutboxRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void initSchema() {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute(CREATE_TABLE_SQL);
            st.execute(CREATE_PENDING_INDEX_SQL);
            st.execute(CREATE_STUCK_INDEX_SQL);
        } catch (SQLException e) {
            throw new OutboxSqlException("initSchema failed", e);
        }
    }

    @Override
    public List<OutboxItem> claimPending(int n) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                List<OutboxItem> claimed = claimInTx(conn, n);
                conn.commit();
                return claimed;
            } catch (RuntimeException e) {
                rollbackQuietly(conn);
                throw e;
            } catch (SQLException e) {
                rollbackQuietly(conn);
                throw new OutboxSqlException("claimPending failed", e);
            }
        } catch (SQLException e) {
            throw new OutboxSqlException("claimPending failed", e);
        }
    }

    /// The claim's two statements on a caller-owned connection, **not** committed —
    /// package-private so the exclusivity test can hold one claim open while a
    /// second claimer runs against it.
    List<OutboxItem> claimInTx(Connection conn, int n) throws SQLException {
        List<OutboxItem> items = new ArrayList<>();
        try (PreparedStatement select = conn.prepareStatement(CLAIM_SQL)) {
            select.setInt(1, n);
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString("id");
                    items.add(new OutboxItem(
                            id,
                            parseType(id, rs.getString("type")),
                            rs.getString("message_group"),
                            rs.getString("payload"),
                            rs.getInt("retry_count"),
                            rs.getTimestamp("created_at").toInstant()));
                }
            }
        }
        if (items.isEmpty()) {
            return items;
        }
        List<String> ids = items.stream().map(OutboxItem::id).toList();
        try (PreparedStatement update = conn.prepareStatement(MARK_IN_PROGRESS_SQL)) {
            update.setArray(1, toSqlArray(conn, ids));
            update.executeUpdate();
        }
        return items;
    }

    @Override
    public void markSuccess(List<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        execute(MARK_SUCCESS_SQL, (conn, ps) -> ps.setArray(1, toSqlArray(conn, ids)));
    }

    @Override
    public void markFailed(List<String> ids, OutboxStatus status, String message, boolean requeue) {
        if (ids.isEmpty()) {
            return;
        }
        int code = requeue ? OutboxStatus.PENDING.code() : status.code();
        execute(MARK_FAILED_SQL, (conn, ps) -> {
            ps.setInt(1, code);
            ps.setString(2, message);
            ps.setArray(3, toSqlArray(conn, ids));
        });
    }

    @Override
    public void release(List<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        execute(RELEASE_SQL, (conn, ps) -> ps.setArray(1, toSqlArray(conn, ids)));
    }

    @Override
    public void requeue(List<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        execute(REQUEUE_SQL, (conn, ps) -> ps.setArray(1, toSqlArray(conn, ids)));
    }

    @Override
    public int recoverStuck(Duration olderThan) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(RECOVER_STUCK_SQL)) {
            ps.setTimestamp(1, Timestamp.from(Instant.now().minus(olderThan)));
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new OutboxSqlException("recoverStuck failed", e);
        }
    }

    @Override
    public boolean healthy() {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("SELECT 1");
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    @FunctionalInterface
    private interface Binder {
        void bind(Connection conn, PreparedStatement ps) throws SQLException;
    }

    private void execute(String sql, Binder binder) {
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(conn, ps);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new OutboxSqlException("outbox write failed", e);
        }
    }

    private static Array toSqlArray(Connection conn, List<String> ids) throws SQLException {
        return conn.createArrayOf("varchar", ids.toArray());
    }

    /// [OutboxItemType]'s three constants are the only values the SDK ever
    /// writes; a row with anything else is corrupt data, reported loudly
    /// with the offending row's id rather than silently coerced (mirrors
    /// [io.flowcatalyst.platform.dispatchjob.DispatchJobRepository]'s
    /// `status(rowId, stored)` helper).
    private static OutboxItemType parseType(String rowId, String stored) {
        try {
            return OutboxItemType.valueOf(stored);
        } catch (IllegalArgumentException e) {
            throw new OutboxSqlException("outbox_messages row " + rowId + " has an unrecognised type: " + stored, e);
        }
    }

    private static void rollbackQuietly(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException e) {
            LOG.warn("outbox claim rollback failed", e);
        }
    }

    /// Wraps a JDBC failure from any statement in this repository.
    public static final class OutboxSqlException extends RuntimeException {
        public OutboxSqlException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
