package io.flowcatalyst.platform.scheduler;

import io.flowcatalyst.platform.shared.json.Json;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/// [DispatchPublisher] over the built-in Postgres broker — the SAME
/// `queue_messages` table
/// [io.flowcatalyst.router.queue.postgres.PostgresQueue] claims from
/// (`docs/spec/router.md` §7.3), so the scheduler and the default-broker
/// router never drift onto two different queues. `queueName` MUST be the
/// exact string the router's default-broker consumer was configured with
/// (`Server#defaultQueueUri`) — the two composition roots derive it
/// identically from `FC_DATABASE_URL`, deliberately duplicated rather than
/// shared across a new dependency edge between them.
///
/// One `INSERT ... ON CONFLICT (queue_name, id) DO NOTHING` per message,
/// matching Go's `Queue.Publish` (`internal/queue/postgres/postgres.go:342-355`)
/// exactly: `visible_at` and `created_at` are both "now" — a freshly
/// published message is immediately eligible — and the `ON CONFLICT DO
/// NOTHING` makes a re-publish of an id already on the queue (the harmless
/// duplicate [DispatchPublisher] documents) a no-op rather than an error.
public final class PostgresQueuePublisher implements DispatchPublisher {

    private static final String INSERT_SQL = """
            INSERT INTO queue_messages (id, queue_name, message_group_id, visible_at, payload, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (queue_name, id) DO NOTHING
            """;

    private final DataSource dataSource;
    private final String queueName;

    public PostgresQueuePublisher(DataSource dataSource, String queueName) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.queueName = Objects.requireNonNull(queueName, "queueName");
    }

    @Override
    public void publish(List<PublishedMessage> batch) throws PublishException {
        if (batch.isEmpty()) return;
        long now = Instant.now().getEpochSecond();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
            for (PublishedMessage m : batch) {
                ps.setString(1, m.jobId());
                ps.setString(2, queueName);
                ps.setString(3, m.message().messageGroupId());
                ps.setLong(4, now);
                ps.setString(5, Json.write(m.message()));
                ps.setLong(6, now);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new PublishException("postgres publish failed for queue " + queueName, e);
        }
    }
}
