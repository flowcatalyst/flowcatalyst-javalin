package io.flowcatalyst.router.queue.postgres;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.router.pool.QueuedMessage;
import io.flowcatalyst.router.queue.Consumer;
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
/// Unlike the Go consumer, which owns a dedicated `pgxpool` per instance,
/// this class borrows connections from the platform's shared HikariCP
/// [DataSource] and never closes it — the composition root owns the pool's
/// lifecycle, not the queue.
///
/// ### Claim algorithm
/// One SQL statement (a `WITH … FOR UPDATE SKIP LOCKED` claim CTE feeding an
/// `UPDATE … RETURNING`) both selects and claims a batch, so two consumers
/// racing the same table never claim the same row. Eligibility is
/// `visible_at <= now` and "earliest visible row in its group
/// (`COALESCE(message_group_id, id)`)" — a claimed (invisible) head does not
/// block its successors on a *later* poll, so cross-poll group ordering is
/// not enforced by the broker (§7.3 "Ordering consequence").
public final class PostgresQueue implements Consumer {

    private static final Logger log = LoggerFactory.getLogger(PostgresQueue.class);

    private static final Duration DEFAULT_VISIBILITY = Duration.ofSeconds(30);

    private static final String CLAIM_SQL = """
            WITH claimed AS (
              SELECT m.id
                FROM queue_messages m
               WHERE m.queue_name = ?
                 AND m.visible_at <= ?
                 AND m.error_at IS NULL
                 AND NOT EXISTS (
                       SELECT 1 FROM queue_messages e
                        WHERE e.queue_name = m.queue_name
                          AND COALESCE(e.message_group_id, e.id) = COALESCE(m.message_group_id, m.id)
                          AND e.visible_at <= ?
                          AND e.error_at IS NULL
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

    private static final String MARK_ERRORED_SQL = """
            UPDATE queue_messages
               SET error_at       = ?,
                   error_message  = ?,
                   receipt_handle = NULL
             WHERE queue_name = ?
               AND id = ?
            """;

    private final DataSource dataSource;
    private final String queueName;
    private final Duration visibility;

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
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.queueName = Objects.requireNonNull(queueName, "queueName");
        this.visibility = (visibilityTimeout == null || visibilityTimeout.isZero() || visibilityTimeout.isNegative())
                ? DEFAULT_VISIBILITY
                : visibilityTimeout;
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
        // Additive, so a rollback to Go is unaffected: its CREATE TABLE IF
        // NOT EXISTS leaves these alone and its claim query simply ignores
        // them — which does mean Go would re-claim an errored row and poison
        // itself again, the behaviour Java is fixing.
        final String addErrorColumns = """
                ALTER TABLE queue_messages
                    ADD COLUMN IF NOT EXISTS error_at      BIGINT,
                    ADD COLUMN IF NOT EXISTS error_message TEXT
                """;
        final String createIndex = """
                CREATE INDEX IF NOT EXISTS idx_queue_visible
                    ON queue_messages (queue_name, visible_at, message_group_id)
                """;
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute(createTable);
            st.execute(addErrorColumns);
            st.execute(createIndex);
        } catch (SQLException e) {
            throw new PostgresQueueException("failed to initialise queue_messages schema", e);
        }
    }

    /// Records a row as unusable and takes it out of circulation, without
    /// deleting it.
    ///
    /// The payload is kept deliberately: it is the only evidence of *why* the
    /// message was malformed, and a queue that silently discards what it
    /// cannot parse gives an operator nothing to work from. The receipt
    /// handle is cleared so the row does not look claimed, and `error_at`
    /// keeps it out of every future claim.
    ///
    /// Best-effort by design. If the marking itself fails, the row keeps its
    /// pushed-out visibility and will be retried later — the same message
    /// arriving twice is far better than a poll that dies and takes the whole
    /// batch with it.
    private void markErrored(String id, Exception cause) {
        log.error("queue {}: message {} has a malformed payload; marking it errored and skipping it",
                queueName, id, cause);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(MARK_ERRORED_SQL)) {
            ps.setLong(1, Instant.now().getEpochSecond());
            ps.setString(2, truncate(cause.getMessage()));
            ps.setString(3, queueName);
            ps.setString(4, id);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("queue {}: could not mark message {} as errored", queueName, id, e);
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
                    } catch (JsonProcessingException e) {
                        // Q17 RULED (owner, 2026-08-25): mark the row as errored
                        // and carry on with the rest of the batch.
                        //
                        // Go instead fails the whole poll, and the row is already
                        // claimed at this point — so it re-claims and re-fails every
                        // time its visibility lapses, and every message behind it is
                        // never delivered. One bad row stops the queue permanently.
                        //
                        // Marking beats deleting (SQS acks a malformed message away,
                        // NATS terms it): the payload stays for inspection, which is
                        // the only way to find out why it was malformed.
                        markErrored(id, e);
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
    public void ack(QueuedMessage message) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM queue_messages WHERE receipt_handle = ? AND queue_name = ?")) {
            ps.setString(1, message.receiptHandle());
            ps.setString(2, queueName);
            int rows = ps.executeUpdate();
            if (rows == 0) {
                log.warn("ack: receipt handle not found on queue {}: {}", queueName, message.receiptHandle());
                return;
            }
            acked.incrementAndGet();
        } catch (Exception e) {
            log.warn("ack failed on queue {} for receipt {}", queueName, message.receiptHandle(), e);
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

    /// Terminal. Does not touch the shared [DataSource] — it is owned by the
    /// composition root, not by this consumer.
    @Override
    public void close() {
        stopped.set(true);
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
