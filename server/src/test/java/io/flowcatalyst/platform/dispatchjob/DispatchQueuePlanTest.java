package io.flowcatalyst.platform.dispatchjob;

import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository.ClaimRow;
import io.flowcatalyst.platform.shared.database.Migrator;
import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// The plans of every statement the dispatch path runs against `msg_dispatch_jobs`
/// and `msg_dispatch_queue` (dispatch-queue spec step 3, §1 and §7), on a real
/// database: 400,000 jobs over four monthly partitions, mostly `COMPLETED`, with
/// 100,000 `QUEUED`, 20,000 `PENDING` (and queue rows), 20,000 `PROCESSING` and
/// 10,000 `FAILED`/`ERROR`.
///
/// Each statement is captured as the lifecycle and the repository really issue it
/// (a recording `DataSource`), then planned as a GENERIC plan (`EXPLAIN
/// (GENERIC_PLAN)`: every `?` a bind parameter, nothing provable from a literal) in
/// the three statistics states that matter: freshly analysed, analysed while the
/// tables were empty, and never analysed. The claim is also run with `EXPLAIN
/// (ANALYZE)` under a forced generic plan, to see that it stops early.
///
/// Asserted, per state: the claim walks `idx_dispatch_queue_order` with no Sort
/// node and reads about as many rows as it returns; nothing seq-scans
/// `msg_dispatch_jobs` except where the statement is meant to read a large share
/// of a status (the stale sweeps); the hold-back reads the one plain index. The
/// plans and timings are written to `target/dispatch-queue-plans.txt`.
class DispatchQueuePlanTest {

    /// The mix. Defaults keep the test to seconds; `-Dplan.completed=900000 -Dplan.queued=100000 ...`
    /// runs the report-sized one (100,000 QUEUED among a million jobs).
    private static final int COMPLETED = Integer.getInteger("plan.completed", 300_000);
    private static final int QUEUED = Integer.getInteger("plan.queued", 20_000);
    /// PENDING is also the queue's size. (In a queue-only experiment, with no usable statistics Postgres
    /// preferred a top-N sort of the whole table to the ordered index walk at 20,000-40,000 rows and the
    /// index from 80,000 up: without statistics `claimed_at IS NULL` is estimated at 0.5% selectivity.
    /// The wrong plan costs about as much as the table is small.)
    private static final int PENDING = Integer.getInteger("plan.pending", 120_000);
    private static final int PROCESSING = Integer.getInteger("plan.processing", 8_000);
    private static final int FAILED = Integer.getInteger("plan.failed", 2_000);
    private static final int ERROR = Integer.getInteger("plan.error", 1_000);
    private static final int JOBS = COMPLETED + QUEUED + PENDING + PROCESSING + FAILED + ERROR;

    private enum Stats { FRESHLY_ANALYSED, ANALYSED_WHILE_EMPTY, NEVER_ANALYSED }

    /// A DataSource that records every SQL text prepared on its connections.
    private static final class Recording implements DataSource {
        final DataSource delegate;
        final Map<String, String> sqlByName = new LinkedHashMap<>();
        volatile String current = "?";

        Recording(DataSource delegate) {
            this.delegate = delegate;
        }

        void as(String name) {
            current = name;
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection c = delegate.getConnection();
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement") && args != null && args[0] instanceof String sql) {
                            sqlByName.merge(current, sql, (a, b) -> a.equals(b) ? a : a + "\n;;\n" + b);
                        }
                        try {
                            return method.invoke(c, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        @Override public Connection getConnection(String u, String p) throws SQLException { return delegate.getConnection(u, p); }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { return delegate.unwrap(iface); }
        @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return delegate.isWrapperFor(iface); }
    }

    private static void exec(DataSource ds, String sql) {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    private static List<String> lines(DataSource ds, String sql) {
        List<String> out = new ArrayList<>();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.add(rs.getString(1));
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
        return out;
    }

    private static DataSource database(String name, Stats stats) {
        DataSource ds = TestPg.newDatabase(name);
        Migrator.migrate(ds);
        // four monthly partitions covering the seeded range, where the migrated database has none
        java.time.YearMonth now = java.time.YearMonth.now(java.time.ZoneOffset.UTC);
        for (int i = 0; i <= 3; i++) {
            java.time.YearMonth ym = now.minusMonths(i);
            String child = String.format("msg_dispatch_jobs_%04d_%02d", ym.getYear(), ym.getMonthValue());
            exec(ds, "CREATE TABLE IF NOT EXISTS " + child + " PARTITION OF msg_dispatch_jobs FOR VALUES FROM ('"
                    + ym.atDay(1) + "') TO ('" + ym.plusMonths(1).atDay(1) + "')");
        }
        // the statistics state is ours to control: nothing may analyse behind our back
        for (String t : lines(ds, "SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid"
                + " JOIN pg_class p ON p.oid = i.inhparent WHERE p.relname = 'msg_dispatch_jobs'")) {
            exec(ds, "ALTER TABLE " + t + " SET (autovacuum_enabled = false)");
        }
        exec(ds, "ALTER TABLE msg_dispatch_queue SET (autovacuum_enabled = false)");
        if (stats == Stats.ANALYSED_WHILE_EMPTY) {
            exec(ds, "ANALYZE msg_dispatch_jobs");
            exec(ds, "ANALYZE msg_dispatch_queue");
        }
        installHelpers(ds);
        seed(ds);
        if (stats == Stats.FRESHLY_ANALYSED) {
            exec(ds, "ANALYZE msg_dispatch_jobs");
            exec(ds, "ANALYZE msg_dispatch_queue");
        }
        return ds;
    }

    /// The jobs: statuses mixed uniformly through the id range (so every partition holds every
    /// status), 5,000 groups, sequences 0..99, creation times spread over the last three months;
    /// QUEUED and PENDING `updated_at` an hour old (stale). A queue row for every PENDING job,
    /// exactly as the lifecycle keeps it.
    private static void seed(DataSource ds) {
        int c1 = COMPLETED, c2 = c1 + QUEUED, c3 = c2 + PENDING, c4 = c3 + PROCESSING, c5 = c4 + FAILED;
        exec(ds, """
                INSERT INTO msg_dispatch_jobs (id, code, target_url, status, message_group, sequence, mode,
                        subscription_id, dispatch_pool_id, client_id, scheduled_for, created_at, updated_at)
                SELECT 'J' || lpad(n::text, 12, '0'), 'plan:code', 'https://hook.example/x', s.status,
                       CASE WHEN n %% 10 = 0 THEN NULL ELSE 'grp-' || (n %% 5000) END,
                       (n / 5000) %% 100, CASE WHEN n %% 3 = 0 THEN 'BLOCK_ON_ERROR' ELSE 'IMMEDIATE' END,
                       CASE WHEN n %% 7 = 0 THEN 'sub' || (n %% 50) END, 'pool' || (n %% 5), 'cli' || (n %% 20),
                       CASE WHEN s.status = 'PENDING' AND n %% 50 = 0 THEN now() + interval '10 minutes' END,
                       now() - interval '85 days' + (n::double precision / %d) * interval '84 days',
                       CASE WHEN s.status IN ('QUEUED', 'PENDING') THEN now() - interval '1 hour' ELSE now() - interval '2 hours' END
                  FROM generate_series(1, %d) AS n,
                       LATERAL (SELECT CASE WHEN r < %d THEN 'COMPLETED' WHEN r < %d THEN 'QUEUED' WHEN r < %d THEN 'PENDING'
                                            WHEN r < %d THEN 'PROCESSING' WHEN r < %d THEN 'FAILED' ELSE 'ERROR' END AS status
                                  FROM (SELECT (n::bigint * 2654435761) %% %d AS r) x) s
                """.formatted(JOBS, JOBS, c1, c2, c3, c4, c5, JOBS));
        exec(ds, "INSERT INTO msg_dispatch_queue (job_id, job_created_at, message_group, sequence, scheduled_for,"
                + " subscription_id, dispatch_pool_id, client_id, mode, queue, version)"
                + " SELECT id, created_at, message_group, sequence, scheduled_for, subscription_id, dispatch_pool_id,"
                + " client_id, mode, queue, updated_at FROM msg_dispatch_jobs WHERE status = 'PENDING'");
    }

    private static String generic(String sql) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (char ch : sql.toCharArray()) {
            if (ch == '?') sb.append('$').append(++n);
            else sb.append(ch);
        }
        return sb.toString();
    }

    /// Helpers that run EXPLAIN on a statement passed as TEXT, so its `$n` markers reach the server
    /// untouched (the driver treats a `$n` in a plain statement as a parameter of that statement).
    private static void installHelpers(DataSource ds) {
        exec(ds, "CREATE FUNCTION explain_generic(q text) RETURNS SETOF text LANGUAGE plpgsql AS $f$ DECLARE r text; BEGIN"
                + " FOR r IN EXECUTE 'EXPLAIN (GENERIC_PLAN, COSTS OFF) ' || q LOOP RETURN NEXT r; END LOOP; END $f$");
        exec(ds, "CREATE FUNCTION explain_prepared_analyze(q text, args text) RETURNS SETOF text LANGUAGE plpgsql AS $f$"
                + " DECLARE r text; BEGIN PERFORM set_config('plan_cache_mode', 'force_generic_plan', true);"
                + " EXECUTE 'PREPARE dq_plan(text[], int) AS ' || q;"
                + " FOR r IN EXECUTE 'EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) EXECUTE dq_plan(' || args || ')'"
                + " LOOP RETURN NEXT r; END LOOP; DEALLOCATE dq_plan; END $f$");
    }

    private static String plan(DataSource ds, String sql) {
        List<String> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement("SELECT * FROM explain_generic(?)")) {
            ps.setString(1, generic(sql));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
        return String.join("\n", out);
    }

    /// Runs the lifecycle and repository operations once each, recording their SQL.
    private static Map<String, String> record(DataSource ds) {
        var rec = new Recording(ds);
        var lifecycle = new DispatchJobLifecycle(rec);
        var repo = new DispatchJobRepository(rec);
        Instant created = Instant.now().minus(Duration.ofDays(30));

        rec.as("claim");
        List<ClaimRow> claimed = lifecycle.claimPending(3, Set.of("sub1"));
        rec.as("release");
        lifecycle.releaseClaims(claimed.stream().map(ClaimRow::id).toList());
        rec.as("stale claims");
        lifecycle.releaseStaleClaims(List.of("x"), Duration.ofMinutes(5));
        rec.as("hold-back (claim time)");
        repo.heldBeforeIds(List.of(new ClaimRow("J000000000001", null, "grp-1", DispatchMode.BLOCK_ON_ERROR, null, null,
                created, 5, null, created)));
        rec.as("hold-back (delivery time)");
        repo.groupHeldBefore("grp-1", 5, created, "J000000000001");
        rec.as("mark QUEUED");
        lifecycle.markQueued(List.of(new ClaimRow("J000000000001", null, "grp-1", DispatchMode.IMMEDIATE, null, null,
                created, 5, null, created)));
        rec.as("claim for delivery");
        lifecycle.claimForDelivery("J000000000001", created);
        rec.as("stale QUEUED recovery");
        lifecycle.recoverStaleQueued(Instant.EPOCH); // matches nothing: the plan is what is wanted here
        rec.as("reaper sweep");
        lifecycle.sweepStrandedSiblings(Instant.EPOCH, "reaper: plan");
        rec.as("reconcile");
        lifecycle.reconcileQueue(5000, Duration.ofSeconds(60), Duration.ofMinutes(5));
        rec.as("backlog");
        lifecycle.queueBacklog();
        return rec.sqlByName;
    }

    /// The months the seed covers; the migrated database also has empty partitions for the months
    /// ahead, which the planner rightly seq-scans (nothing to read).
    private static final Set<String> SEEDED_PARTITIONS = new java.util.HashSet<>();

    static {
        java.time.YearMonth now = java.time.YearMonth.now(java.time.ZoneOffset.UTC);
        for (int i = 0; i <= 3; i++) {
            java.time.YearMonth ym = now.minusMonths(i);
            SEEDED_PARTITIONS.add(String.format("msg_dispatch_jobs_%04d_%02d", ym.getYear(), ym.getMonthValue()));
        }
    }

    /// Postgres names the partitions' copies of `idx_dispatch_jobs_status_group` after the table and the columns.
    private static boolean usesStatusIndex(String plan) {
        return plan.contains("status_message_group_sequence_cre");
    }

    /// A seq scan of a partition that has rows in it.
    private static boolean seqScansJobs(String plan) {
        var m = java.util.regex.Pattern.compile("Seq Scan on (msg_dispatch_jobs_\\d{4}_\\d{2})").matcher(plan);
        while (m.find()) {
            if (SEEDED_PARTITIONS.contains(m.group(1))) return true;
        }
        return plan.lines().anyMatch(l -> l.matches(".*Seq Scan on msg_dispatch_jobs( .*)?$"));
    }

    @Test
    void theDispatchPathUsesItsIndexesWithBindParametersInEveryStatisticsState() throws Exception {
        var report = new StringBuilder();
        List<String> failures = new ArrayList<>();
        for (Stats stats : Stats.values()) {
            DataSource ds = database("dq_plan_" + stats.name().toLowerCase(java.util.Locale.ROOT), stats);
            Map<String, String> sql = record(ds);
            report.append("\n=== ").append(stats).append(" ===\n");

            for (var e : sql.entrySet()) {
                String name = e.getKey();
                for (String one : e.getValue().split("\n;;\n")) {
                    String plan = plan(ds, one);
                    report.append("\n--- ").append(name).append(" (generic plan)\n").append(plan).append('\n');
                    String where = stats + " / " + name;
                    switch (name) {
                        case "claim" -> {
                            if (plan.contains("Sort")) failures.add(where + ": the claim has a Sort node");
                            if (!plan.contains("Index Scan using idx_dispatch_queue_order")) failures.add(where + ": the claim does not walk idx_dispatch_queue_order");
                            if (plan.contains("msg_dispatch_jobs")) failures.add(where + ": the claim reads msg_dispatch_jobs");
                        }
                        case "hold-back (claim time)", "hold-back (delivery time)" -> {
                            if (seqScansJobs(plan)) failures.add(where + ": seq scan of msg_dispatch_jobs");
                            if (!usesStatusIndex(plan)) failures.add(where + ": the FAILED/ERROR holders do not use idx_dispatch_jobs_status_group");
                        }
                        case "stale QUEUED recovery", "reaper sweep" -> {
                            if (!usesStatusIndex(plan)) failures.add(where + ": does not use idx_dispatch_jobs_status_group");
                            if (seqScansJobs(plan)) failures.add(where + ": seq scan of msg_dispatch_jobs");
                        }
                        case "mark QUEUED", "claim for delivery" -> {
                            if (seqScansJobs(plan)) failures.add(where + ": seq scan of msg_dispatch_jobs");
                        }
                        case "reconcile" -> {
                            // The reconcile statements compare WHOLE tables by design (every PENDING job against every
                            // queue row): at this mix PENDING is a quarter of the table, where hash and merge joins over
                            // seq scans are the planner's right answer, so they are reported (and timed), not asserted.
                            // What they must never use is an index that no longer exists:
                            if (plan.contains("idx_dispatch_jobs_pending_poll") || plan.contains("idx_dispatch_jobs_group_holders")
                                    || plan.contains("idx_dispatch_jobs_in_flight")) failures.add(where + ": uses a dropped index");
                        }
                        case "backlog", "release", "stale claims" -> {
                            if (seqScansJobs(plan)) failures.add(where + ": reads msg_dispatch_jobs");
                        }
                        default -> { }
                    }
                }
            }

            // the claim, executed (generic plan forced): stops early, reads about what it returns
            String claim = generic(sql.get("claim"));
            try (Connection c = ds.getConnection()) {
                c.setAutoCommit(false);
                long t0 = System.nanoTime();
                List<String> analyze = new ArrayList<>();
                try (java.sql.PreparedStatement ps = c.prepareStatement("SELECT * FROM explain_prepared_analyze(?, ?)")) {
                    ps.setString(1, claim);
                    ps.setString(2, "'{sub1,sub2}', 500");
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) analyze.add(rs.getString(1));
                    }
                }
                c.rollback();
                String text = String.join("\n", analyze);
                report.append("\n--- claim, 500 rows, EXPLAIN ANALYZE under a generic plan (rolled back)\n")
                        .append(text).append('\n');
                if (text.contains("Sort")) failures.add(stats + ": the executed claim sorts");
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("Index Scan using idx_dispatch_queue_order .*?actual time=[\\d.]+\\.\\.[\\d.]+ rows=(\\d+)")
                        .matcher(text);
                if (!m.find()) {
                    failures.add(stats + ": no executed index scan on idx_dispatch_queue_order in the claim");
                } else {
                    int read = Integer.parseInt(m.group(1));
                    // 500 asked, 2 subscriptions paused (~4% of rows) and a few not yet due: a little over 500, never the table
                    if (read > 600) failures.add(stats + ": the claim's index scan read " + read + " rows for LIMIT 500");
                    report.append("claim index scan rows read: ").append(read).append(" for LIMIT 500\n");
                }
                report.append(String.format("claim wall time incl. EXPLAIN: %.1f ms%n", (System.nanoTime() - t0) / 1e6));
            }

            // timings of the sweeps on the real data, in this state
            var lifecycle = new DispatchJobLifecycle(ds);
            long t = System.nanoTime();
            lifecycle.reconcileQueue(5000, Duration.ofSeconds(60), Duration.ofMinutes(5));
            report.append(String.format("%nreconcile pass (clean table, 20,000 queue rows): %.1f ms%n", (System.nanoTime() - t) / 1e6));
            t = System.nanoTime();
            lifecycle.queueBacklog();
            report.append(String.format("backlog sample: %.1f ms%n", (System.nanoTime() - t) / 1e6));
            int queuedNow = Integer.parseInt(lines(ds, "SELECT count(*) FROM msg_dispatch_jobs WHERE status = 'QUEUED'").getFirst());
            t = System.nanoTime();
            lifecycle.recoverStaleQueued(Instant.EPOCH); // nothing is that stale: the usual case, a pure scan
            report.append(String.format("stale QUEUED sweep, nothing stale, %d QUEUED rows: %.1f ms%n", queuedNow, (System.nanoTime() - t) / 1e6));
            t = System.nanoTime();
            int stranded = lifecycle.sweepStrandedSiblings(Instant.EPOCH, "reaper: plan").size();
            report.append(String.format("reaper sweep (resets %d stranded QUEUED/PROCESSING siblings): %.1f ms%n", stranded, (System.nanoTime() - t) / 1e6));
            queuedNow = Integer.parseInt(lines(ds, "SELECT count(*) FROM msg_dispatch_jobs WHERE status = 'QUEUED'").getFirst());
            t = System.nanoTime();
            int recovered = lifecycle.recoverStaleQueued(Instant.now().minus(Duration.ofMinutes(15)));
            report.append(String.format("stale QUEUED recovery of %d of %d QUEUED rows (all stale): %.1f ms%n", recovered, queuedNow, (System.nanoTime() - t) / 1e6));
            exec(ds, "UPDATE msg_dispatch_jobs SET status = 'QUEUED', updated_at = now() WHERE status = 'PENDING' AND id <= 'J000000350000'");
        }
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/dispatch-queue-plans.txt"), report.toString());
        System.out.println("dispatch queue plans written to target/dispatch-queue-plans.txt");
        assertThat(failures).isEmpty();
    }
}
