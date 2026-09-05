package io.flowcatalyst.stream;

import io.flowcatalyst.sdk.usecase.jdbc.DbTx;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// `dispatch_job_projection` (stream spec §5): claims `msg_dispatch_jobs`
/// rows that are unprojected OR have changed since their last projection
/// (`projected_at IS NULL OR updated_at > projected_at`, so every status
/// change re-projects) and upserts their read shape into
/// `msg_dispatch_jobs_read`. One [Projector.Step] call is one
/// claim-project-mark transaction ([StreamTx]).
public final class DispatchJobProjection implements Projector.Step {

    private static final String CLAIM_SQL = """
            SELECT id, created_at FROM msg_dispatch_jobs
            WHERE projected_at IS NULL OR updated_at > projected_at
            ORDER BY created_at
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """;

    /// Bounded by the claimed batch's own `created_at` range (partition
    /// pruning, spec §5) in addition to `id = ANY(?)`, which is what
    /// actually scopes the upsert to exactly the claimed rows.
    /// `is_completed`/`is_terminal` and `application`/`subdomain`/`aggregate`
    /// are derived exactly as spec §5 states; on conflict, every column that
    /// can change after first insertion is refreshed from `EXCLUDED`.
    private static final String UPSERT_SQL = """
            INSERT INTO msg_dispatch_jobs_read (
                id, external_id, source, kind, code, subject, event_id, correlation_id, target_url, protocol,
                service_account_id, client_id, subscription_id, dispatch_pool_id, mode, message_group, sequence,
                timeout_seconds, status, max_retries, retry_strategy, scheduled_for, expires_at, attempt_count,
                last_attempt_at, completed_at, duration_millis, last_error, idempotency_key, is_completed,
                is_terminal, application, subdomain, aggregate, updated_at, projected_at, created_at)
            SELECT id, external_id, source, kind, code, subject, event_id, correlation_id, target_url, protocol,
                service_account_id, client_id, subscription_id, dispatch_pool_id, mode, message_group, sequence,
                timeout_seconds, status, max_retries, retry_strategy, scheduled_for, expires_at, attempt_count,
                last_attempt_at, completed_at, duration_millis, last_error, idempotency_key,
                (status = 'COMPLETED'),
                (status IN ('COMPLETED', 'FAILED', 'CANCELLED', 'EXPIRED')),
                split_part(code, ':', 1),
                NULLIF(split_part(code, ':', 2), ''),
                NULLIF(split_part(code, ':', 3), ''),
                updated_at, now(), created_at
            FROM msg_dispatch_jobs
            WHERE id = ANY(?) AND created_at BETWEEN ? AND ?
            ON CONFLICT (id, created_at) DO UPDATE SET
                status = EXCLUDED.status,
                attempt_count = EXCLUDED.attempt_count,
                last_attempt_at = EXCLUDED.last_attempt_at,
                completed_at = EXCLUDED.completed_at,
                duration_millis = EXCLUDED.duration_millis,
                last_error = EXCLUDED.last_error,
                is_completed = EXCLUDED.is_completed,
                is_terminal = EXCLUDED.is_terminal,
                updated_at = EXCLUDED.updated_at,
                projected_at = EXCLUDED.projected_at
            """;

    private static final String MARK_PROJECTED_SQL =
            "UPDATE msg_dispatch_jobs SET projected_at = now() WHERE id = ANY(?)";

    private final DataSource dataSource;

    public DispatchJobProjection(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public int step(int batchSize) {
        return StreamTx.run(dataSource, tx -> projectInTx(tx, batchSize));
    }

    private static int projectInTx(DbTx tx, int batchSize) {
        Connection conn = tx.connection();
        Claim claim = claim(conn, batchSize);
        if (claim.ids().isEmpty()) return 0;
        try {
            try (PreparedStatement upsert = conn.prepareStatement(UPSERT_SQL)) {
                upsert.setArray(1, conn.createArrayOf("varchar", claim.ids().toArray()));
                upsert.setTimestamp(2, Timestamp.from(claim.minCreatedAt()));
                upsert.setTimestamp(3, Timestamp.from(claim.maxCreatedAt()));
                upsert.executeUpdate();
            }
            try (PreparedStatement mark = conn.prepareStatement(MARK_PROJECTED_SQL)) {
                mark.setArray(1, conn.createArrayOf("varchar", claim.ids().toArray()));
                mark.executeUpdate();
            }
        } catch (SQLException e) {
            throw new StreamTx.StreamStepException(e);
        }
        return claim.ids().size();
    }

    private static Claim claim(Connection conn, int batchSize) {
        List<String> ids = new ArrayList<>();
        Instant min = null;
        Instant max = null;
        try (PreparedStatement stmt = conn.prepareStatement(CLAIM_SQL)) {
            stmt.setInt(1, batchSize);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getString("id"));
                    Instant createdAt = rs.getTimestamp("created_at").toInstant();
                    if (min == null || createdAt.isBefore(min)) min = createdAt;
                    if (max == null || createdAt.isAfter(max)) max = createdAt;
                }
            }
        } catch (SQLException e) {
            throw new StreamTx.StreamStepException(e);
        }
        return new Claim(ids, min, max);
    }

    private record Claim(List<String> ids, Instant minCreatedAt, Instant maxCreatedAt) {
    }
}
