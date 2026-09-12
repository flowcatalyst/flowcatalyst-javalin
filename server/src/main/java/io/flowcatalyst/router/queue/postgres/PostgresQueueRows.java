package io.flowcatalyst.router.queue.postgres;

import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.router.queue.Publisher;
import io.flowcatalyst.router.wire.Message;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/// The one place `queue_messages` rows are written from a [Message] —
/// shared by [PostgresQueue] (the router's own `POST /messages` publisher,
/// `docs/spec/router.md` §7.3) and
/// [io.flowcatalyst.platform.scheduler.PostgresQueuePublisher] (the
/// dispatch-seam's publisher onto the same table). One `INSERT ... ON
/// CONFLICT (queue_name, id) DO NOTHING` per message, batched: `visible_at`
/// and `created_at` are both "now" (unix seconds) — a freshly published
/// message is immediately eligible — and the `ON CONFLICT DO NOTHING` makes
/// a re-publish of an id already on the queue a no-op rather than an error.
///
/// CONVENTIONS §8 "Shared row/payload builders live in `SinkSupport`": this
/// is that rule's queue-backend counterpart — two publishers writing the
/// same table must write it identically, so the SQL and the column mapping
/// live in exactly one place.
///
/// Public (rather than package-private) because
/// `io.flowcatalyst.platform.scheduler.PostgresQueuePublisher` — a
/// different package, deliberately not moved here — is the other caller.
public final class PostgresQueueRows {

    private static final String INSERT_SQL = """
            INSERT INTO queue_messages (id, queue_name, message_group_id, visible_at, payload, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (queue_name, id) DO NOTHING
            """;

    private PostgresQueueRows() {
    }

    /// One message bound for one named queue — the unit [#insertBatch(DataSource,List)]
    /// writes, so a single JDBC batch can span several distinct destination
    /// queues in one statement
    /// ([io.flowcatalyst.platform.scheduler.PostgresQueuePublisher] routes
    /// per (tenant, priority), `docs/spec/deployed-dispatch.md` §3 unit D
    /// part 1).
    public record Row(String queueName, Message message) {
        public Row {
            Objects.requireNonNull(queueName, "queueName");
            Objects.requireNonNull(message, "message");
        }
    }

    /// Inserts one row per message, all bound for the SAME `queueName`, in
    /// one JDBC batch — [PostgresQueue]'s own `POST /messages` publisher,
    /// which always addresses one queue at a time. Throws (nothing inserted
    /// from the caller's point of view — see [Publisher#publishBatch]) if the
    /// batch statement fails; a no-op for an empty list.
    public static void insertBatch(DataSource dataSource, String queueName, List<Message> messages) throws SQLException {
        insertBatch(dataSource, messages.stream().map(m -> new Row(queueName, m)).toList());
    }

    /// Inserts one row per [Row], in one JDBC batch — each row may name a
    /// DIFFERENT destination queue, which is what lets
    /// [io.flowcatalyst.platform.scheduler.PostgresQueuePublisher] publish a
    /// heterogeneous batch (several tenants/priorities) as a single
    /// statement, keeping its documented all-or-nothing failure contract
    /// (ruling O2's carve-out) even though the batch spans several queues.
    /// Throws if the batch statement fails; a no-op for an empty list.
    public static void insertBatch(DataSource dataSource, List<Row> rows) throws SQLException {
        if (rows.isEmpty()) {
            return;
        }
        long now = Instant.now().getEpochSecond();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
            for (Row row : rows) {
                Message m = row.message();
                ps.setString(1, m.id());
                ps.setString(2, row.queueName());
                ps.setString(3, m.messageGroupId());
                ps.setLong(4, now);
                ps.setString(5, Json.write(m));
                ps.setLong(6, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}
