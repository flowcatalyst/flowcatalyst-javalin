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

    /// Inserts one row per message, in one JDBC batch. Throws (nothing
    /// inserted from the caller's point of view — see [Publisher#publishBatch])
    /// if the batch statement fails; a no-op for an empty list.
    public static void insertBatch(DataSource dataSource, String queueName, List<Message> messages) throws SQLException {
        if (messages.isEmpty()) {
            return;
        }
        long now = Instant.now().getEpochSecond();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
            for (Message m : messages) {
                ps.setString(1, m.id());
                ps.setString(2, queueName);
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
