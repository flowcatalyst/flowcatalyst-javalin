package io.flowcatalyst.router.queue.postgres;

import tools.jackson.core.JacksonException;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.router.pool.QueuedMessage;
import io.flowcatalyst.router.queue.Consumer;
import io.flowcatalyst.router.queue.Publisher;
import io.flowcatalyst.router.queue.QueueMetrics;
import io.flowcatalyst.router.wire.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/// Postgres-backed [Consumer] — wire- and schema-compatible with the
/// pre-existing `queue_messages` table (`docs/spec/router.md` §7.3), so this
/// and the Go consumer can drain the same table.
///
/// Connects the same way Go's consumer does: when
/// [io.flowcatalyst.router.queue.QueueFactory] builds this from a queue URI
/// that carries its own connection, that instance owns a dedicated pool
/// (Go's per-instance `pgxpool`) and closes it in [#close]. When the URI
/// carries none — or names the platform's own database — this instance
/// instead borrows the platform's shared HikariCP [DataSource] and never
/// closes it; the composition root owns that pool's lifecycle, not the
/// queue. See [#ownedPool].
///
/// ### Claim algorithm
/// One SQL statement (a `WITH … FOR UPDATE SKIP LOCKED` claim CTE feeding an
/// `UPDATE … RETURNING`) both selects and claims a batch, so two consumers
/// racing the same table never claim the same row. Eligibility is
/// `visible_at <= now` and "earliest visible row in its group
/// (`COALESCE(message_group_id, id)`)" — a claimed (invisible) head does not
/// block its successors on a *later* poll, so cross-poll group ordering is
/// not enforced by the broker (§7.3 "Ordering consequence").
public final class PostgresQueue implements Consumer, Publisher {

    private static final Logger log = LoggerFactory.getLogger(PostgresQueue.class);

    private static final Duration DEFAULT_VISIBILITY = Duration.ofSeconds(30);

    private static final String CLAIM_SQL = """
            WITH claimed AS (
              SELECT m.id
                FROM queue_messages m
               WHERE m.queue_name = ?
                 AND m.visible_at <= ?
                 AND NOT EXISTS (
                       SELECT 1 FROM queue_messages e
                        WHERE e.queue_name = m.queue_name
                          AND COALESCE(e.message_group_id, e.id) = COALESCE(m.message_group_id, m.id)
                          AND e.visible_at <= ?
                          AND (e.created_at < m.created_at
                               OR (e.created_at = m.created_at AND e.id < m.id))
                     )
               ORDER BY m.created_at, m.id
               LIMIT ?
               FOR UPDATE SKIP LOCKED
            )
            UPDATE queue_messages t
               SET receipt_handle = ? || ':' || t.id,
                   visible_at     = ?,
                   receive_count  = t.receive_count + 1
              FROM claimed
             WHERE t.queue_name = ?
               AND t.id = claimed.id
             RETURNING t.id, t.payload
            """;

    /// Moves one unusable row out of the live table in a single statement,
    /// so it can never be half-moved: either it is gone from `queue_messages`
    /// and recorded in `queue_messages_failed`, or nothing happened.
    private static final String MOVE_TO_FAILED_SQL = """
            WITH moved AS (
                DELETE FROM queue_messages
                 WHERE queue_name = ?
                   AND id = ?
             RETURNING id, queue_name, message_group_id, payload, created_at, receive_count
            )
            INSERT INTO queue_messages_failed
                (id, queue_name, message_group_id, payload, created_at, receive_count, failed_at, error_message)
            SELECT id, queue_name, message_group_id, payload, created_at, receive_count, ?, ?
              FROM moved
            ON CONFLICT (queue_name, id) DO UPDATE
                SET payload       = EXCLUDED.payload,
                    failed_at     = EXCLUDED.failed_at,
                    error_message = EXCLUDED.error_message,
                    receive_count = EXCLUDED.receive_count
            """;

    private final DataSource dataSource;
    private final String queueName;
    private final Duration visibility;

    /// The pool this consumer exclusively owns — built by
    /// [io.flowcatalyst.router.queue.QueueFactory] from this queue's own URI
    /// (`docs/spec/router.md` §7.3) rather than borrowed from the platform —
    /// and therefore closed alongside this consumer in [#close]. `null` when
    /// [#dataSource] is the shared platform pool, which outlives this
    /// consumer and is owned by the composition root instead.
    private final AutoCloseable ownedPool;

    /// Set by [#close]; checked at the top of every [#poll] (CONVENTIONS §5:
    /// an explicit stop signal, never a silently-closed resource).
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    // Process-local counters (`queue.Metrics` contract, §2.9) — no round-trip.
    private final AtomicLong polled = new AtomicLong();
    private final AtomicLong acked = new AtomicLong();
    private final AtomicLong nacked = new AtomicLong();

    /// @param visibilityTimeout how long a claimed message stays invisible;
    ///                          `null`, zero or negative falls back to 30 s,
    ///                          matching the Go `cfg seconds, ≤0 → 30 s` rule
    public PostgresQueue(DataSource dataSource, String queueName, Duration visibilityTimeout) {
        this(dataSource, queueName, visibilityTimeout, null);
    }

    /// @param ownedPool see [#ownedPool]; `null` when `dataSource` is shared
    public PostgresQueue(DataSource dataSource, String queueName, Duration visibilityTimeout, AutoCloseable ownedPool) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.queueName = Objects.requireNonNull(queueName, "queueName");
        this.visibility = (visibilityTimeout == null || visibilityTimeout.isZero() || visibilityTimeout.isNegative())
                ? DEFAULT_VISIBILITY
                : visibilityTimeout;
        this.ownedPool = ownedPool;
    }

    /// See [#ownedPool] — a testing seam so [io.flowcatalyst.router.queue.QueueFactory]'s
    /// per-queue pool sizing (`docs/spec/router.md` §7.3, Go `pgxpool.New`
    /// parity) can be asserted against the actual pool this consumer runs
    /// on, not a value recomputed alongside the code under test.
    public AutoCloseable ownedPool() {
        return ownedPool;
    }

    /// Creates the `queue_messages` table and its index if absent. Matches
    /// the pre-existing production layout exactly (§7.3 DDL) — idempotent,
    /// safe to call against a database the existing Go system already
    /// provisioned.
    public static void initSchema(DataSource dataSource) {
        final String createTable = """
                CREATE TABLE IF NOT EXISTS queue_messages (
                    id               TEXT NOT NULL,
                    queue_name       TEXT NOT NULL,
                    message_group_id TEXT,
                    receipt_handle   TEXT,
                    visible_at       BIGINT NOT NULL,
                    payload          TEXT NOT NULL,
                    created_at       BIGINT NOT NULL,
                    receive_count    INTEGER DEFAULT 0,
                    PRIMARY KEY (queue_name, id)
                )
                """;
        // Unusable messages are MOVED here rather than flagged in place, so
        // the live table stays purely live work: no errored rows in its heap
        // to vacuum around, and no error predicate in the claim query.
        //
        // It also makes a rollback to Go safe rather than merely tolerable.
        // Go's claim has no notion of an error flag, so a flagged row left in
        // queue_messages would be re-claimed and poison it again; a row that
        // has been moved out is simply not there.
        //
        // The primary key is (queue_name, id) with an upsert on conflict, so
        // an id that fails, is re-queued, and fails again records its latest
        // failure rather than accumulating a row per attempt.
        final String createFailedTable = """
                CREATE TABLE IF NOT EXISTS queue_messages_failed (
                    id               TEXT NOT NULL,
                    queue_name       TEXT NOT NULL,
                    message_group_id TEXT,
                    payload          TEXT NOT NULL,
                    created_at       BIGINT NOT NULL,
                    receive_count    INTEGER,
                    failed_at        BIGINT NOT NULL,
                    error_message    TEXT,
                    PRIMARY KEY (queue_name, id)
                )
                """;
        final String createIndex = """
                CREATE INDEX IF NOT EXISTS idx_queue_visible
                    ON queue_messages (queue_name, visible_at, message_group_id)
                """;
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute(createTable);
            st.execute(createFailedTable);
            st.execute(createIndex);
        } catch (SQLException e) {
            throw new PostgresQueueException("failed to initialise queue_messages schema", e);
        }
    }

    /// Moves an unusable row out of the live queue and into the failed table.
    ///
    /// The payload travels with it deliberately: it is the only evidence of
    /// *why* the message was malformed, and a queue that silently discards
    /// what it cannot parse leaves an operator nothing to work from.
    ///
    /// Best-effort by design. If the move fails, the row keeps its pushed-out
    /// visibility and is retried later — the same message arriving twice is
    /// far better than a poll that dies and takes the whole batch with it.
    private void moveToFailed(String id, Exception cause) {
        log.error("queue {}: message {} has a malformed payload; moving it to queue_messages_failed",
                queueName, id, cause);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(MOVE_TO_FAILED_SQL)) {
            ps.setString(1, queueName);
            ps.setString(2, id);
            ps.setLong(3, Instant.now().getEpochSecond());
            ps.setString(4, truncate(cause.getMessage()));
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("queue {}: could not move message {} to the failed table", queueName, id, e);
        }
    }

    /// Keeps a parser's message from becoming an unbounded column value.
    private static String truncate(String message) {
        if (message == null) {
            return "malformed payload";
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    @Override
    public String identifier() {
        return queueName;
    }

    @Override
    public PollResult poll(int max) throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException("interrupted before polling queue " + queueName);
        }
        if (stopped.get()) {
            return PollResult.STOPPED;
        }

        long now = Instant.now().getEpochSecond();
        long newVisibleAt = now + visibility.toSeconds();
        String receipt = UUID.randomUUID().toString();

        List<QueuedMessage> messages = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(CLAIM_SQL)) {
            ps.setString(1, queueName);
            ps.setLong(2, now);
            ps.setLong(3, now);
            ps.setInt(4, max);
            ps.setString(5, receipt);
            ps.setLong(6, newVisibleAt);
            ps.setString(7, queueName);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString("id");
                    String payload = rs.getString("payload");
                    Message message;
                    try {
                        message = Json.read(payload, Message.class);
                    } catch (JacksonException e) {
                        // Q17 RULED (owner, 2026-08-25): mark the row as errored
                        // and carry on with the rest of the batch.
                        //
                        // Go instead fails the whole poll, and the row is already
                        // claimed at this point — so it re-claims and re-fails every
                        // time its visibility lapses, and every message behind it is
                        // never delivered. One bad row stops the queue permanently.
                        //
                        // Moving beats deleting (SQS acks a malformed message away,
                        // NATS terms it): the payload stays for inspection, which is
                        // the only way to find out why it was malformed. It also
                        // keeps the live table free of rows that will never run.
                        moveToFailed(id, e);
                        continue;
                    }
                    messages.add(QueuedMessage.of(message, id, receipt + ":" + id, queueName));
                }
            }
        } catch (SQLException e) {
            if (isInterruption(e)) {
                throw new InterruptedException("interrupted while polling queue " + queueName);
            }
            throw new PostgresQueueException("poll failed for queue " + queueName, e);
        }

        polled.addAndGet(messages.size());
        return PollResult.of(messages);
    }

    /// Deletes the delivery permanently. Best-effort: a failure — including
    /// an unknown receipt handle — is logged, never thrown (the [Consumer]
    /// contract).
    @Override
    public boolean ack(QueuedMessage message) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM queue_messages WHERE receipt_handle = ? AND queue_name = ?")) {
            ps.setString(1, message.receiptHandle());
            ps.setString(2, queueName);
            int rows = ps.executeUpdate();
            if (rows == 0) {
                log.warn("ack: receipt handle not found on queue {}: {}", queueName, message.receiptHandle());
                return false;
            }
            acked.incrementAndGet();
            return true;
        } catch (Exception e) {
            log.warn("ack failed on queue {} for receipt {}", queueName, message.receiptHandle(), e);
            return false;
        }
    }

    /// Clears the receipt handle and makes the row visible again after
    /// `delay` (matching Go, an unknown receipt handle is a silent no-op —
    /// the `UPDATE` simply affects zero rows). Best-effort: never throws.
    @Override
    public void nack(QueuedMessage message, Duration delay) {
        long delaySeconds = (delay == null || delay.isNegative()) ? 0 : delay.toSeconds();
        long newVisibleAt = Instant.now().getEpochSecond() + delaySeconds;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE queue_messages SET receipt_handle = NULL, visible_at = ? "
                             + "WHERE receipt_handle = ? AND queue_name = ?")) {
            ps.setLong(1, newVisibleAt);
            ps.setString(2, message.receiptHandle());
            ps.setString(3, queueName);
            ps.executeUpdate();
            nacked.incrementAndGet();
        } catch (Exception e) {
            log.warn("nack failed on queue {} for receipt {}", queueName, message.receiptHandle(), e);
        }
    }

    /// Inserts one row (§7.3 "Publish"). `message.id()` is the returned
    /// broker id even when a row with that id already existed — the
    /// `ON CONFLICT DO NOTHING` in [PostgresQueueRows] makes a republish of
    /// an id already on the queue a no-op, not an error.
    @Override
    public String publish(Message message) throws PublishException {
        try {
            PostgresQueueRows.insertBatch(dataSource, queueName, List.of(message));
        } catch (SQLException e) {
            throw new PublishException("postgres publish failed for queue " + queueName, e);
        }
        return message.id();
    }

    /// One JDBC batch for every message (§7.3 "PublishBatch": "loop of
    /// Publish; error aborts and returns nil ids") — a batch statement
    /// failure throws and publishes nothing, rather than reporting which
    /// messages happened to insert before the failure.
    @Override
    public List<String> publishBatch(List<Message> messages) throws PublishException {
        if (messages.isEmpty()) {
            return List.of();
        }
        try {
            PostgresQueueRows.insertBatch(dataSource, queueName, messages);
        } catch (SQLException e) {
            throw new PublishException("postgres publishBatch failed for queue " + queueName, e);
        }
        return messages.stream().map(Message::id).toList();
    }

    /// Pending = not claimed and visible now; in-flight = claimed (an
    /// expired claim still counts as in-flight, a delayed nack counts as
    /// neither — §7.3 "Metrics"). A query failure is tolerated: [Consumer]
    /// documents metrics as always allowed to come back empty.
    @Override
    public Optional<QueueMetrics> metrics() {
        long now = Instant.now().getEpochSecond();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT
                       COUNT(*) FILTER (WHERE receipt_handle IS NULL AND visible_at <= ?),
                       COUNT(*) FILTER (WHERE receipt_handle IS NOT NULL)
                     FROM queue_messages WHERE queue_name = ?
                     """)) {
            ps.setLong(1, now);
            ps.setString(2, queueName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                long pending = rs.getLong(1);
                long inFlight = rs.getLong(2);
                return Optional.of(new QueueMetrics(pending, inFlight, polled.get(), acked.get(), nacked.get()));
            }
        } catch (SQLException e) {
            log.warn("metrics query failed for queue {}", queueName, e);
            return Optional.empty();
        }
    }

    /// Terminal. Never touches a shared [DataSource] — that pool is owned by
    /// the composition root, not by this consumer — but closes [#ownedPool]
    /// when this consumer opened one of its own. Best-effort: a failure here
    /// is logged, not thrown — this consumer is stopping either way.
    @Override
    public void close() {
        stopped.set(true);
        if (ownedPool != null) {
            try {
                ownedPool.close();
            } catch (Exception e) {
                log.warn("closing queue {}'s own pool failed", queueName, e);
            }
        }
    }

    /// Whether `e` (or the interrupt flag) signals that a blocking JDBC call
    /// was cut short by [Thread#interrupt] — e.g. HikariCP aborting a
    /// connection-acquisition wait. CONVENTIONS §5/§8: cancellation is
    /// interruption, restored and exited at every blocking point.
    private static boolean isInterruption(SQLException e) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException) {
                return true;
            }
        }
        return false;
    }
}
