package io.flowcatalyst.platform.scheduler;

import io.flowcatalyst.platform.dispatch.DispatchQueueSettings;
import io.flowcatalyst.router.queue.postgres.PostgresQueueRows;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// [DispatchPublisher] over the built-in Postgres broker — the SAME
/// `queue_messages` table
/// [io.flowcatalyst.router.queue.postgres.PostgresQueue] claims from
/// (`docs/spec/router.md` §7.3), so the scheduler and the router's own
/// Postgres-backed consumers never drift onto two different tables.
///
/// **Routes per (tenant, priority), exactly like [SqsDispatchPublisher]**
/// (`docs/spec/deployed-dispatch.md` §3, unit D part 1). Before this, every
/// job was written to one fixed row-queue named after the database URL
/// (`Server#defaultQueueUri`) — the instant a router consumed the platform's
/// own served router-config document (unit B), which advertises composed
/// names such as `platform-DEFAULT`, that mismatch would have meant every
/// dev dispatch job published where nothing is listening. [#destinations]
/// resolves each job's row-queue name exactly as [SqsDispatchPublisher]
/// does, through the SAME [DispatchDestinationResolver] class — the two
/// publishers cannot independently drift on where a job goes.
///
/// The row write itself is [PostgresQueueRows#insertBatch] — the same
/// helper `PostgresQueue`'s own `POST /messages` publisher uses (§9.1,
/// CONVENTIONS §8 "Shared row/payload builders"), so the two publishers
/// can never drift onto two different column mappings for the one table.
/// `PublishedMessage.jobId()` is `PublishedMessage.message().id()` verbatim
/// (see that record's javadoc), so the row id comes from the message itself.
///
/// One JDBC batch, one statement, regardless of how many distinct
/// destination queues the batch spans (`PostgresQueueRows.Row` carries the
/// per-message queue name) — so the documented all-or-nothing failure
/// semantics (ruling O2's carve-out for this publisher) are unchanged by
/// routing per (tenant, priority): a failure still reports the WHOLE batch
/// unpublished, never a partial one.
public final class PostgresQueuePublisher implements DispatchPublisher {

    private final DataSource dataSource;
    private final DispatchDestinationResolver destinations;

    public PostgresQueuePublisher(DataSource dataSource, DispatchQueueSettings settings) {
        this(dataSource, new DispatchDestinationResolver(
                new PoolCodeResolver(dataSource), new SubscriptionPriorityCache(dataSource), settings));
    }

    PostgresQueuePublisher(DataSource dataSource, DispatchDestinationResolver destinations) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.destinations = Objects.requireNonNull(destinations, "destinations");
    }

    @Override
    public void publish(List<PublishedMessage> batch) throws PublishException {
        if (batch.isEmpty()) return;
        List<PostgresQueueRows.Row> rows = new ArrayList<>(batch.size());
        for (PublishedMessage m : batch) {
            rows.add(new PostgresQueueRows.Row(destinations.destinationFor(m).value(), m.message()));
        }
        try {
            PostgresQueueRows.insertBatch(dataSource, rows);
        } catch (SQLException e) {
            // One statement, one transaction: a failure here never partially
            // applies, so the whole batch is unpublished (ruling O2 — this
            // publisher's contribution to that ruling's "still effectively
            // all-or-nothing in practice" carve-out) — even though the batch
            // may span several distinct destination queues.
            List<String> ids = batch.stream().map(PublishedMessage::jobId).toList();
            throw new PublishException("postgres publish failed for " + ids.size() + " job(s)", e, ids);
        }
    }
}
