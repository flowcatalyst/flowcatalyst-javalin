package io.flowcatalyst.platform.scheduler;

import io.flowcatalyst.router.queue.postgres.PostgresQueueRows;

import javax.sql.DataSource;
import java.sql.SQLException;
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
/// The row write itself is [PostgresQueueRows#insertBatch] — the same
/// helper `PostgresQueue`'s own `POST /messages` publisher uses (§9.1,
/// CONVENTIONS §8 "Shared row/payload builders"), so the two publishers
/// can never drift onto two different column mappings for the one table.
/// `PublishedMessage.jobId()` is `PublishedMessage.message().id()` verbatim
/// (see that record's javadoc), so the row id comes from the message itself.
public final class PostgresQueuePublisher implements DispatchPublisher {

    private final DataSource dataSource;
    private final String queueName;

    public PostgresQueuePublisher(DataSource dataSource, String queueName) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.queueName = Objects.requireNonNull(queueName, "queueName");
    }

    @Override
    public void publish(List<PublishedMessage> batch) throws PublishException {
        if (batch.isEmpty()) return;
        try {
            PostgresQueueRows.insertBatch(dataSource, queueName, batch.stream().map(PublishedMessage::message).toList());
        } catch (SQLException e) {
            // One statement, one transaction: a failure here never partially
            // applies, so the whole batch is unpublished (ruling O2 — this
            // publisher's contribution to that ruling's "still effectively
            // all-or-nothing in practice" carve-out).
            List<String> ids = batch.stream().map(PublishedMessage::jobId).toList();
            throw new PublishException("postgres publish failed for queue " + queueName, e, ids);
        }
    }
}
