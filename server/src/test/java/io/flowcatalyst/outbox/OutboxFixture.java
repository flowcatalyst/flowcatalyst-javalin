package io.flowcatalyst.outbox;

import io.flowcatalyst.testpg.TestPg;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/// Shared fixture for outbox tests: the one embedded-Postgres
/// `outbox_messages` table ([PostgresOutboxRepository#initSchema] is
/// idempotent, so every test class calling it in its own static initializer
/// is safe), a run-unique id prefix (CONVENTIONS §6 — no truncation between
/// tests, so every seeded id must be unique per run), and a raw-row
/// seeder/reader for shapes the repository's own write paths never produce
/// (an arbitrary starting status, retry_count or created_at).
final class OutboxFixture {

    static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    static final DataSource DS = TestPg.dataSource();

    static {
        // Per-class databases (TestPg): a static block alone would create the tables
        // in the first class's database only.
        TestPg.forEveryDatabase(ds -> new PostgresOutboxRepository(ds).initSchema());
    }

    private OutboxFixture() {
    }

    /// A run-unique id: distinct outbox rows never collide with another test
    /// class's leftovers (no truncation between tests, CONVENTIONS §6).
    /// `outbox_messages.id` is `VARCHAR(26)` — `RUN` (6) + `-` (1) leaves 19
    /// characters for `label`, the longest one any test in this package uses.
    static String id(String label) {
        String candidate = RUN + "-" + label;
        if (candidate.length() > 26) {
            throw new IllegalArgumentException(
                    "outbox id " + candidate + " (" + candidate.length() + " chars) exceeds VARCHAR(26): " + label);
        }
        return candidate;
    }

    static final String VALID_PAYLOAD = "{\"hello\":\"world\"}";

    static void seedRow(String id, OutboxItemType type, String messageGroup, String payload, int status,
                         int retryCount, Instant createdAt) {
        String sql = """
                INSERT INTO outbox_messages (id, type, message_group, payload, status, retry_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = DS.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, type.name());
            ps.setString(3, messageGroup);
            ps.setString(4, payload);
            ps.setInt(5, status);
            ps.setInt(6, retryCount);
            ps.setTimestamp(7, Timestamp.from(createdAt));
            ps.setTimestamp(8, Timestamp.from(createdAt));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("seed outbox row " + id, e);
        }
    }

    static void seedPending(String id, OutboxItemType type, String messageGroup) {
        seedRow(id, type, messageGroup, VALID_PAYLOAD, 0, 0, Instant.now());
    }

    /// The row's live `status`/`retry_count`/`error_message`, or `null` if
    /// the row no longer exists (a `SUCCESS` outcome deletes it).
    record Row(int status, int retryCount, String errorMessage) {
    }

    static Row row(String id) {
        String sql = "SELECT status, retry_count, error_message FROM outbox_messages WHERE id = ?";
        try (Connection conn = DS.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? new Row(rs.getInt(1), rs.getInt(2), rs.getString(3)) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("read outbox row " + id, e);
        }
    }

    static boolean exists(String id) {
        return row(id) != null;
    }
}
