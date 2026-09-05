package io.flowcatalyst.stream;

import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// [PartitionManager] (stream spec §6): the pure `parsePartitionEnd` /
/// `monthStart` / `retentionDaysFor` functions (Go's `TestParsePartitionEnd*`
/// / `TestRetentionFor_*`), then `ensureForward`/`dropOld` against a
/// dedicated, run-unique partitioned table on the shared embedded Postgres
/// (CONVENTIONS §6: never touch or assume about another test's rows) so the
/// retention-cutoff edge and the "leave the default partition alone" rule
/// are pinned against real DDL, not just the parser.
class PartitionManagerTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8)
            .toLowerCase(Locale.ROOT);
    private static final PartitionManager.Config CONFIG =
            new PartitionManager.Config(true, 3, 90, 30, Duration.ofHours(24));

    private static PartitionManager manager() {
        return new PartitionManager(DS, CONFIG, () -> true, new Health("partition-test"), Clock.systemUTC());
    }

    // ── PARENTS ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the seven partitioned parents, and never iam_login_attempts")
    void parentsListIsExactlyTheSeven() {
        assertThat(PartitionManager.PARENTS).containsExactlyInAnyOrder(
                "msg_events", "msg_events_read", "msg_dispatch_jobs", "msg_dispatch_jobs_read",
                "msg_dispatch_job_attempts", "msg_scheduled_job_instances", "msg_scheduled_job_instance_logs");
        assertThat(PartitionManager.PARENTS).doesNotContain("iam_login_attempts");
    }

    // ── retentionDaysFor ─────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} -> {1} days")
    @CsvSource({
            "msg_scheduled_job_instances,      30",
            "msg_scheduled_job_instance_logs,  30",
            "msg_events,                       90",
            "msg_dispatch_jobs,                90",
            "msg_dispatch_job_attempts,        90",
    })
    void retentionDaysForScheduledJobTables(String parent, int expectedDays) {
        assertThat(PartitionManager.retentionDaysFor(parent, CONFIG)).isEqualTo(expectedDays);
    }

    // ── monthStart / parsePartitionEnd (pure) ───────────────────────────────

    @Test
    @DisplayName("monthStart is the first instant of the month, UTC")
    void monthStartIsFirstInstantOfMonthUtc() {
        assertThat(PartitionManager.monthStart(2026, 3)).isEqualTo(Instant.parse("2026-03-01T00:00:00Z"));
        assertThat(PartitionManager.monthStart(2026, 12)).isEqualTo(Instant.parse("2026-12-01T00:00:00Z"));
    }

    @ParameterizedTest(name = "{0} under parent {1} -> {2}")
    @CsvSource({
            "msg_events_2026_03,   msg_events,       2026-04-01T00:00:00Z",
            "msg_events_2026_12,   msg_events,       2027-01-01T00:00:00Z",
            "msg_events_2026_1,    msg_events,       ''",             // not two digits
            "msg_events_2026_13,   msg_events,       ''",             // not a real month
            "msg_events_default,   msg_events,       ''",             // the default partition
            "msg_events_q1_2026,   msg_events,       ''",             // a quarterly name
            "other_table_2026_03,  msg_events,       ''",             // not this parent's child at all
    })
    void parsePartitionEndParsesOrRejects(String child, String parent, String expected) {
        Optional<Instant> end = PartitionManager.parsePartitionEnd(parent, child);
        if (expected == null || expected.isEmpty()) {
            assertThat(end).isEmpty();
        } else {
            assertThat(end).contains(Instant.parse(expected));
        }
    }

    // ── ensureForward: real DDL ──────────────────────────────────────────────

    @Test
    @DisplayName("ensureForward creates this month and the next monthsForward months, idempotently")
    void ensureForwardCreatesMonthsZeroThroughForward() throws SQLException {
        String parent = "pm_ensure_" + RUN;
        Instant now = Instant.parse("2030-06-15T00:00:00Z");
        try (Connection conn = DS.getConnection()) {
            createPartitionedParent(conn, parent);

            int created = manager().ensureForward(conn, parent, now);

            assertThat(created).isEqualTo(4); // months 0..3 inclusive
            assertThat(tableExists(conn, parent + "_2030_06")).isTrue();
            assertThat(tableExists(conn, parent + "_2030_07")).isTrue();
            assertThat(tableExists(conn, parent + "_2030_08")).isTrue();
            assertThat(tableExists(conn, parent + "_2030_09")).isTrue();
            assertThat(tableExists(conn, parent + "_2030_10")).isFalse();

            // Idempotent: a second pass creates nothing new.
            int createdAgain = manager().ensureForward(conn, parent, now);
            assertThat(createdAgain).isZero();
        }
    }

    // ── dropOld: the retention-cutoff edge ───────────────────────────────────

    @Test
    @DisplayName("a partition ending exactly at the cutoff is dropped; one ending a day later is kept")
    void dropOldRespectsTheCutoffEdge() throws SQLException {
        String parent = "pm_drop_" + RUN;
        try (Connection conn = DS.getConnection()) {
            createPartitionedParent(conn, parent);
            // Covers February 2026: its end is 2026-03-01T00:00:00Z.
            createMonthPartition(conn, parent, 2026, 2);
            Instant end = Instant.parse("2026-03-01T00:00:00Z");

            // now such that now - retentionDays(90) == end exactly -> dropped.
            Instant nowAtCutoff = end.plus(90, ChronoUnit.DAYS);
            int droppedAtCutoff = manager().dropOld(conn, parent, nowAtCutoff);
            assertThat(droppedAtCutoff).isEqualTo(1);
            assertThat(tableExists(conn, parent + "_2026_02")).isFalse();

            // Recreate the same partition; now one day earlier -> the
            // partition's end is one day AFTER the cutoff -> kept.
            createMonthPartition(conn, parent, 2026, 2);
            Instant nowOneDayEarlier = nowAtCutoff.minus(1, ChronoUnit.DAYS);
            int droppedOneDayLater = manager().dropOld(conn, parent, nowOneDayEarlier);
            assertThat(droppedOneDayLater).isZero();
            assertThat(tableExists(conn, parent + "_2026_02")).isTrue();
        }
    }

    @Test
    @DisplayName("the default partition is never dropped, however old the cutoff")
    void defaultPartitionIsNeverDropped() throws SQLException {
        String parent = "pm_default_" + RUN;
        try (Connection conn = DS.getConnection()) {
            createPartitionedParent(conn, parent);
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE " + parent + "_default PARTITION OF " + parent + " DEFAULT");
            }

            int dropped = manager().dropOld(conn, parent, Instant.parse("2099-01-01T00:00:00Z"));

            assertThat(dropped).isZero();
            assertThat(tableExists(conn, parent + "_default")).isTrue();
        }
    }

    private static void createPartitionedParent(Connection conn, String name) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE " + name + " (id text, created_at timestamptz not null) "
                    + "PARTITION BY RANGE (created_at)");
        }
    }

    private static void createMonthPartition(Connection conn, String parent, int year, int month) throws SQLException {
        String child = String.format(Locale.ROOT, "%s_%04d_%02d", parent, year, month);
        Instant start = PartitionManager.monthStart(year, month);
        Instant end = start.atZone(ZoneOffset.UTC).plusMonths(1).toInstant();
        // Partition bound expressions are constants, not bind parameters (see
        // PartitionManager#ensureForward) — literals here too.
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE " + child + " PARTITION OF " + parent
                    + " FOR VALUES FROM ('" + start + "') TO ('" + end + "')");
        }
    }

    private static boolean tableExists(Connection conn, String tableName) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT to_regclass(?)")) {
            stmt.setString(1, tableName);
            try (var rs = stmt.executeQuery()) {
                return rs.next() && rs.getString(1) != null;
            }
        }
    }
}
