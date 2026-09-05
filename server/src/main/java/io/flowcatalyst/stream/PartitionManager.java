package io.flowcatalyst.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// The partition manager (stream spec §6): a leader-gated tick loop (not a
/// batch [Projector.Step] — there is nothing to claim) that keeps
/// [#PARENTS]' monthly `RANGE` partitions rolling forward and drops ones
/// past their retention window. One pass at start, then every
/// `Config#tickInterval`, leader only; a failing parent is logged and the
/// pass continues (spec: "A failing parent is logged and the pass
/// continues").
public final class PartitionManager implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(PartitionManager.class);

    /// The seven partitioned parents (stream spec §6) — deliberately **not**
    /// `iam_login_attempts` (owner question, `docs/backlog.md`).
    public static final List<String> PARENTS = List.of(
            "msg_events",
            "msg_events_read",
            "msg_dispatch_jobs",
            "msg_dispatch_jobs_read",
            "msg_dispatch_job_attempts",
            "msg_scheduled_job_instances",
            "msg_scheduled_job_instance_logs");

    /// The two parents using [Config#scheduledJobRetentionDays] instead of
    /// [Config#retentionDays] (spec §6).
    private static final Set<String> SCHEDULED_JOB_PARENTS =
            Set.of("msg_scheduled_job_instances", "msg_scheduled_job_instance_logs");

    private static final Pattern MONTH_SUFFIX = Pattern.compile("^(\\d{4})_(\\d{2})$");

    public record Config(boolean enabled, int monthsForward, int retentionDays, int scheduledJobRetentionDays,
                          Duration tickInterval) {

        public static final int DEFAULT_MONTHS_FORWARD = 3;
        public static final int DEFAULT_RETENTION_DAYS = 90;
        public static final int DEFAULT_SCHEDULED_JOB_RETENTION_DAYS = 30;
        public static final Duration DEFAULT_TICK_INTERVAL = Duration.ofHours(24);

        public Config {
            Objects.requireNonNull(tickInterval, "tickInterval");
        }

        /// `enabled` at the stream spec §6 defaults.
        public static Config of(boolean enabled) {
            return new Config(enabled, DEFAULT_MONTHS_FORWARD, DEFAULT_RETENTION_DAYS,
                    DEFAULT_SCHEDULED_JOB_RETENTION_DAYS, DEFAULT_TICK_INTERVAL);
        }
    }

    private final DataSource dataSource;
    private final Config config;
    private final BooleanSupplier leader;
    private final Health health;
    private final Clock clock;

    public PartitionManager(DataSource dataSource, Config config, BooleanSupplier leader, Health health, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.config = Objects.requireNonNull(config, "config");
        this.leader = Objects.requireNonNull(leader, "leader");
        this.health = Objects.requireNonNull(health, "health");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ── Pure functions (Go `TestParsePartitionEnd*`, `TestRetentionFor_*`) ──

    /// The retention window for `parent`: [Config#scheduledJobRetentionDays]
    /// for the two `msg_scheduled_job_instance*` parents, else
    /// [Config#retentionDays] (spec §6).
    static int retentionDaysFor(String parent, Config config) {
        return SCHEDULED_JOB_PARENTS.contains(parent) ? config.scheduledJobRetentionDays() : config.retentionDays();
    }

    /// The first instant of `year`-`month`, UTC.
    static Instant monthStart(int year, int month) {
        return YearMonth.of(year, month).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /// The exclusive end (start of the month after) of a `<parent>_YYYY_MM`
    /// child's range, or empty when `childName` is not exactly that shape
    /// under `parent` — a default partition, a quarterly name, or an
    /// unrelated table is "left alone" (spec §6).
    static Optional<Instant> parsePartitionEnd(String parent, String childName) {
        String prefix = parent + "_";
        if (!childName.startsWith(prefix)) return Optional.empty();
        Matcher m = MONTH_SUFFIX.matcher(childName.substring(prefix.length()));
        if (!m.matches()) return Optional.empty();
        int year = Integer.parseInt(m.group(1));
        int month = Integer.parseInt(m.group(2));
        if (month < 1 || month > 12) return Optional.empty();
        return Optional.of(monthStart(year, month).atZone(ZoneOffset.UTC).plusMonths(1).toInstant());
    }

    // ── One pass ─────────────────────────────────────────────────────────

    /// One full pass over every parent: create-forward then drop-old, each
    /// parent independent so one's failure never stops the rest. Returns the
    /// number of partitions created + dropped (Health's "processed").
    int tick(Instant now) {
        int processed = 0;
        try (Connection conn = dataSource.getConnection()) {
            for (String parent : PARENTS) {
                try {
                    processed += ensureForward(conn, parent, now);
                    processed += dropOld(conn, parent, now);
                } catch (SQLException e) {
                    LOG.warn("partition manager: pass failed for parent={}", parent, e);
                    health.recordError();
                }
            }
        } catch (SQLException e) {
            LOG.warn("partition manager: could not acquire a connection", e);
            health.recordError();
        }
        return processed;
    }

    /// `CREATE TABLE IF NOT EXISTS <parent>_YYYY_MM PARTITION OF <parent> FOR
    /// VALUES FROM (…) TO (…)` for this month and the next
    /// `Config#monthsForward` months. Counts only the ones that did not
    /// already exist.
    int ensureForward(Connection conn, String parent, Instant now) throws SQLException {
        YearMonth base = YearMonth.from(now.atZone(ZoneOffset.UTC));
        int created = 0;
        for (int i = 0; i <= config.monthsForward(); i++) {
            YearMonth ym = base.plusMonths(i);
            String childName = String.format(Locale.ROOT, "%s_%04d_%02d", parent, ym.getYear(), ym.getMonthValue());
            if (tableExists(conn, childName)) {
                continue;
            }
            Instant start = monthStart(ym.getYear(), ym.getMonthValue());
            Instant end = start.atZone(ZoneOffset.UTC).plusMonths(1).toInstant();
            // Postgres's `CREATE TABLE … PARTITION OF … FOR VALUES FROM (…) TO
            // (…)` bound expressions are parsed as constants, not bind
            // parameters ("could not determine data type of parameter"), so the
            // two instants — both computed here, never user input — are
            // rendered as literals rather than bound.
            String sql = "CREATE TABLE IF NOT EXISTS " + childName + " PARTITION OF " + parent
                    + " FOR VALUES FROM ('" + start + "') TO ('" + end + "')";
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(sql);
            }
            created++;
        }
        return created;
    }

    /// `DROP TABLE IF EXISTS` every child of `parent` whose parsed end is at
    /// or before `now - retention` (spec §6: "whose **end** ≤ now −
    /// retention").
    int dropOld(Connection conn, String parent, Instant now) throws SQLException {
        Instant cutoff = now.minus(Duration.ofDays(retentionDaysFor(parent, config)));
        int dropped = 0;
        for (String child : children(conn, parent)) {
            Optional<Instant> end = parsePartitionEnd(parent, child);
            if (end.isEmpty() || end.get().isAfter(cutoff)) {
                continue;
            }
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS " + child);
            }
            dropped++;
        }
        return dropped;
    }

    private static boolean tableExists(Connection conn, String tableName) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT to_regclass(?)")) {
            stmt.setString(1, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() && rs.getString(1) != null;
            }
        }
    }

    private static List<String> children(Connection conn, String parent) throws SQLException {
        String sql = """
                SELECT c.relname FROM pg_inherits i
                JOIN pg_class c ON c.oid = i.inhrelid
                JOIN pg_class p ON p.oid = i.inhparent
                WHERE p.relname = ?
                """;
        List<String> names = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, parent);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        }
        return names;
    }

    // ── Loop ─────────────────────────────────────────────────────────────

    @Override
    public void run() {
        if (!config.enabled()) {
            return;
        }
        health.setRunning(true);
        try {
            while (!Thread.currentThread().isInterrupted()) {
                if (leader.getAsBoolean()) {
                    int n = tick(clock.instant());
                    if (n > 0) health.addProcessed(n);
                }
                if (!sleep(config.tickInterval())) return;
            }
        } finally {
            health.setRunning(false);
            LOG.info("partition manager stopped");
        }
    }

    private static boolean sleep(Duration duration) {
        try {
            Thread.sleep(duration);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
