package io.flowcatalyst.platform.dispatchjob;

import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository.ClaimRow;
import io.flowcatalyst.platform.shared.database.Migrator;
import io.flowcatalyst.platform.shared.database.Pools;
import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// The plans of the scheduler's claim (S1 = the ordered read, S2 = the delete that is the claim) and of the
/// restore, on a real database with the scheduler pool's two server settings applied
/// (`plan_cache_mode = force_custom_plan`, `enable_sort = off`; dispatch-queue spec step 3b §5-6), in six
/// statistics states at about 5,000 and 100,000 queue rows:
///
///  1. never analysed;
///  2. analysed while brand-new and empty, then filled;
///  3. DRAINED, then analysed with its pages still allocated (the planner believes the table has one row), then
///     a burst;
///  4. freshly analysed;
///  5. analysed when every row present was scheduled for the future, then every row made due;
///  6. the cached-plan case: both statements prepared and executed several times on an empty, vacuumed queue on
///     ONE connection, then the burst, then the plan that same connection would use.
///
/// The statements are captured as the lifecycle really issues them (a recording DataSource), prepared on a
/// connection with the same settings and planned with `EXPLAIN EXECUTE`, so the plan is the one a (cached or
/// custom) prepared statement would run. Asserted: SHAPE, never time — S1 has no Sort and no Seq Scan; S2 is an
/// index scan (the primary key; in the drained state the order index is also acceptable) and never a Seq Scan;
/// the restore reads `msg_dispatch_jobs` by primary key. The negative control repeats state 6 WITHOUT the settings.
/// The report (plans, timings) is written to `target/dispatch-queue-plans.txt`.
class DispatchQueuePlanTest {

    private static final String TEMPLATE_DB = "dq_plan_template";
    private static final String SETTINGS_OPTIONS = "-c plan_cache_mode=force_custom_plan -c enable_sort=off";

    private enum State { NEVER_ANALYSED, ANALYSED_EMPTY, DRAINED_THEN_BURST, FRESHLY_ANALYSED, ALL_FUTURE_THEN_DUE, CACHED_PLAN }

    /// A DataSource of plain connections to one database, with the scheduler pool's settings (or without).
    private static final class Source implements DataSource {
        final String url;
        final boolean settings;
        final boolean simple;
        final Map<String, String> sqlByName = new LinkedHashMap<>();
        volatile String current = "?";

        Source(String db, boolean settings, boolean simple) {
            this.url = TestPg.instance().getJdbcUrl("postgres", db);
            this.settings = settings;
            this.simple = simple;
        }

        void as(String name) {
            current = name;
        }

        @Override
        public Connection getConnection() throws SQLException {
            Properties p = new Properties();
            p.setProperty("user", "postgres");
            if (settings) p.setProperty("options", SETTINGS_OPTIONS);
            if (simple) p.setProperty("preferQueryMode", "simple");
            Connection c = DriverManager.getConnection(url, p);
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

        @Override public Connection getConnection(String u, String pw) throws SQLException { return getConnection(); }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("no"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static List<String> lines(Connection c, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    // ── databases ──────────────────────────────────────────────────────────

    private static boolean templateReady;

    /// A migrated database with four monthly partitions and autovacuum off everywhere (the statistics state is ours).
    private static synchronized void template() throws Exception {
        if (templateReady) return;
        DataSource ds = TestPg.newDatabase(TEMPLATE_DB);
        Migrator.migrate(ds);
        try (Connection c = ds.getConnection()) {
            java.time.YearMonth now = java.time.YearMonth.now(java.time.ZoneOffset.UTC);
            for (int i = 0; i <= 3; i++) {
                java.time.YearMonth ym = now.minusMonths(i);
                exec(c, "CREATE TABLE IF NOT EXISTS " + String.format("msg_dispatch_jobs_%04d_%02d", ym.getYear(), ym.getMonthValue())
                        + " PARTITION OF msg_dispatch_jobs FOR VALUES FROM ('" + ym.atDay(1) + "') TO ('" + ym.plusMonths(1).atDay(1) + "')");
            }
            for (String t : lines(c, "SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid"
                    + " JOIN pg_class p ON p.oid = i.inhparent WHERE p.relname = 'msg_dispatch_jobs'")) {
                exec(c, "ALTER TABLE " + t + " SET (autovacuum_enabled = false)");
            }
            exec(c, "ALTER TABLE msg_dispatch_queue SET (autovacuum_enabled = false)");
        }
        templateReady = true;
    }

    private static String clone(String name) throws Exception {
        template();
        try (Connection c = TestPg.instance().getPostgresDatabase().getConnection()) {
            exec(c, "DROP DATABASE IF EXISTS " + name);
            exec(c, "CREATE DATABASE " + name + " TEMPLATE " + TEMPLATE_DB);
        }
        return name;
    }

    /// `n` PENDING jobs spread over the four partitions, in 500 groups.
    private static void seedJobs(Connection c, int n) throws SQLException {
        exec(c, """
                INSERT INTO msg_dispatch_jobs (id, code, target_url, status, message_group, sequence, mode,
                        subscription_id, dispatch_pool_id, client_id, created_at, updated_at)
                SELECT 'J' || lpad(i::text, 12, '0'), 'plan:code', 'https://hook.example/x', 'PENDING',
                       CASE WHEN i %% 10 = 0 THEN NULL ELSE 'grp-' || (i %% 500) END, (i / 500) %% 100,
                       CASE WHEN i %% 3 = 0 THEN 'BLOCK_ON_ERROR' ELSE 'IMMEDIATE' END,
                       CASE WHEN i %% 7 = 0 THEN 'sub' || (i %% 50) END, 'pool' || (i %% 5), 'cli' || (i %% 20),
                       now() - interval '85 days' + (i::double precision / %d) * interval '84 days', now() - interval '1 hour'
                  FROM generate_series(1, %d) AS i
                """.formatted(n, n));
    }

    private static final String QUEUE_FROM_JOBS = "INSERT INTO msg_dispatch_queue (job_id, job_created_at, message_group, sequence,"
            + " scheduled_for, subscription_id, dispatch_pool_id, client_id, mode, queue, version)"
            + " SELECT id, created_at, message_group, sequence, %s, subscription_id, dispatch_pool_id, client_id, mode, queue,"
            + " updated_at FROM msg_dispatch_jobs WHERE status = 'PENDING'";

    // ── the statements ─────────────────────────────────────────────────────

    private static String generic(String sql) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (char ch : sql.toCharArray()) {
            if (ch == '?') sb.append('$').append(++n);
            else sb.append(ch);
        }
        return sb.toString();
    }

    private record Statements(String s1, String s2, String restore, String holdBack, String heldBefore) {
    }

    /// The SQL of the claim's two statements and of the restore, as the lifecycle issues them.
    private static Statements capture(String db) throws Exception {
        var rec = new Source(db, true, false);
        var lifecycle = new DispatchJobLifecycle(rec);
        Instant created = Instant.now().minus(Duration.ofDays(30));
        rec.as("claim");
        lifecycle.claimPending(3, Set.of("sub1"), Set.of("grp-1"));
        // S1 may have returned nothing, in which case S2 was not issued: run S2 on its own
        try (Connection c = rec.getConnection()) {
            rec.as("claim");
            DispatchJobLifecycle.takeRows(c, List.of("J000000000001"));
        }
        rec.as("restore");
        lifecycle.restore(List.of(new ClaimRow("J000000000001", null, "grp-1", null, null, null, created, 1, null, created)));
        rec.as("holdback");
        var repo = new DispatchJobRepository(rec);
        repo.heldBeforeIds(List.of(new ClaimRow("J000000000001", null, "grp-1", io.flowcatalyst.platform.shared.dispatch.DispatchMode.BLOCK_ON_ERROR,
                null, null, created, 1, null, created)));
        rec.as("heldbefore");
        repo.groupHeldBefore("grp-1", 5, created, "J000000000001");
        String[] claim = rec.sqlByName.get("claim").split("\n;;\n");
        String s1 = null, s2 = null;
        for (String q : claim) {
            if (q.trim().startsWith("SELECT job_id")) s1 = q;
            else if (q.trim().startsWith("DELETE FROM msg_dispatch_queue")) s2 = q;
        }
        assertThat(s1).isNotNull();
        assertThat(s2).isNotNull();
        return new Statements(s1, s2, rec.sqlByName.get("restore"), rec.sqlByName.get("holdback"), rec.sqlByName.get("heldbefore"));
    }

    private static String arrayLiteral(List<String> ids) {
        return "'{" + String.join(",", ids) + "}'::text[]";
    }

    /// Prepares the statements on `c` (simple-protocol connection: the `$n` markers reach the server untouched).
    private static void prepare(Connection c, Statements st) throws SQLException {
        exec(c, "DEALLOCATE ALL");
        exec(c, "PREPARE dq_s1(text[], text[], int) AS " + generic(st.s1()));
        exec(c, "PREPARE dq_s2(text[]) AS " + generic(st.s2()));
        exec(c, "PREPARE dq_restore(text[], text[]) AS " + generic(st.restore()));
        exec(c, "PREPARE dq_hold(text[], text[], text[], int[], text[], text[]) AS " + generic(st.holdBack()));
        exec(c, "PREPARE dq_before(text[], text, int, timestamptz, text, text, int, timestamptz, text) AS " + generic(st.heldBefore()));
    }

    private static String explain(Connection c, String executeCall) throws SQLException {
        return String.join("\n", lines(c, "EXPLAIN (COSTS OFF) EXECUTE " + executeCall));
    }

    private static final String S1_ARGS = "'{sub1,sub2}'::text[], '{grp-1,grp-2}'::text[], 500";

    /// The restore's arguments for `ids`, with their real creation times from the jobs table.
    private static String restoreArgs(Connection c, List<String> ids) throws SQLException {
        Map<String, String> created = new java.util.HashMap<>();
        try (Statement stmt = c.createStatement(); ResultSet rs = stmt.executeQuery(
                "SELECT id, created_at::text FROM msg_dispatch_jobs WHERE id = ANY(" + arrayLiteral(ids) + ")")) {
            while (rs.next()) created.put(rs.getString(1), rs.getString(2));
        }
        List<String> ordered = new ArrayList<>();
        for (String id : ids) ordered.add("\"" + created.get(id) + "\"");
        return arrayLiteral(ids) + ", " + arrayLiteral(ordered);
    }

    private static boolean seqScansQueue(String plan) {
        return plan.lines().anyMatch(l -> l.contains("Seq Scan on msg_dispatch_queue"));
    }

    private static final Set<String> POPULATED = new java.util.HashSet<>();

    static {
        java.time.YearMonth now = java.time.YearMonth.now(java.time.ZoneOffset.UTC);
        for (int i = 0; i <= 3; i++) {
            java.time.YearMonth ym = now.minusMonths(i);
            POPULATED.add(String.format("msg_dispatch_jobs_%04d_%02d", ym.getYear(), ym.getMonthValue()));
        }
    }

    private static boolean seqScansPopulatedJobs(String plan) {
        var m = java.util.regex.Pattern.compile("Seq Scan on (msg_dispatch_jobs_\\d{4}_\\d{2})").matcher(plan);
        while (m.find()) if (POPULATED.contains(m.group(1))) return true;
        return false;
    }

    // ── one scenario ───────────────────────────────────────────────────────

    /// Builds the state, then returns the three plans (S1, S2 over the ids S1 returns, restore of those ids) and timings.
    private record Result(String s1Plan, String s2Plan, String restorePlan, double s1Ms, double s2Ms, double restoreMs,
                          String holdPlan, double holdMs, String beforePlan) {
    }

    private static Result scenario(State state, int n, boolean settings, String db, Statements st) throws Exception {
        try (Connection admin = new Source(db, false, false).getConnection()) {
            switch (state) {
                case NEVER_ANALYSED, FRESHLY_ANALYSED -> {
                    seedJobs(admin, n);
                    exec(admin, QUEUE_FROM_JOBS.formatted("NULL"));
                    if (state == State.FRESHLY_ANALYSED) {
                        exec(admin, "ANALYZE msg_dispatch_jobs");
                        exec(admin, "ANALYZE msg_dispatch_queue");
                    }
                }
                case ANALYSED_EMPTY -> {
                    exec(admin, "ANALYZE msg_dispatch_jobs");
                    exec(admin, "ANALYZE msg_dispatch_queue");
                    seedJobs(admin, n);
                    exec(admin, QUEUE_FROM_JOBS.formatted("NULL"));
                }
                case DRAINED_THEN_BURST -> {
                    seedJobs(admin, n);
                    exec(admin, "ANALYZE msg_dispatch_jobs");
                    exec(admin, QUEUE_FROM_JOBS.formatted("NULL"));
                    exec(admin, "DELETE FROM msg_dispatch_queue"); // drained: pages stay allocated
                    exec(admin, "ANALYZE msg_dispatch_queue");       // the planner now believes it has about one row
                    exec(admin, QUEUE_FROM_JOBS.formatted("NULL"));  // the burst
                }
                case ALL_FUTURE_THEN_DUE -> {
                    seedJobs(admin, n);
                    exec(admin, "ANALYZE msg_dispatch_jobs");
                    exec(admin, QUEUE_FROM_JOBS.formatted("now() + interval '1 hour'"));
                    exec(admin, "ANALYZE msg_dispatch_queue");
                    exec(admin, "UPDATE msg_dispatch_queue SET scheduled_for = NULL"); // every row due at once
                }
                case CACHED_PLAN -> {
                    seedJobs(admin, n);
                    exec(admin, "ANALYZE msg_dispatch_jobs");
                    exec(admin, "VACUUM ANALYZE msg_dispatch_queue"); // empty and vacuumed
                }
            }
        }
        try (Connection c = new Source(db, settings, true).getConnection()) {
            prepare(c, st);
            if (state == State.CACHED_PLAN) {
                // the statements run several times against the EMPTY queue on this connection: a cached plan is born
                for (int i = 0; i < 8; i++) {
                    exec(c, "EXECUTE dq_s1(" + S1_ARGS + ")");
                    exec(c, "EXECUTE dq_s2('{}'::text[])");
                }
                try (Connection admin = new Source(db, false, false).getConnection()) {
                    exec(admin, QUEUE_FROM_JOBS.formatted("NULL")); // the burst, no analyze
                }
            }
            String s1Plan = explain(c, "dq_s1(" + S1_ARGS + ")");
            long t = System.nanoTime();
            List<String> ids = lines(c, "EXECUTE dq_s1(" + S1_ARGS + ")");
            double s1Ms = (System.nanoTime() - t) / 1e6;
            String s2Plan = explain(c, "dq_s2(" + arrayLiteral(ids) + ")");
            t = System.nanoTime();
            exec(c, "EXECUTE dq_s2(" + arrayLiteral(ids) + ")");
            double s2Ms = (System.nanoTime() - t) / 1e6;
            String restoreArgs = restoreArgs(c, ids);
            String restorePlan = explain(c, "dq_restore(" + restoreArgs + ")");
            t = System.nanoTime();
            exec(c, "EXECUTE dq_restore(" + restoreArgs + ")");
            double restoreMs = (System.nanoTime() - t) / 1e6;
            // the claim-time hold-back for 500 candidate groups (the scheduler's connection)
            List<String> groups = new ArrayList<>();
            for (int g = 1; g <= 500; g++) groups.add("grp-" + g);
            // each group's last candidate: a position near the head of the group: the claim has just deleted the rows before it
            List<String> seqs = new ArrayList<>(), creates = new ArrayList<>(), cids = new ArrayList<>();
            for (int g = 1; g <= 500; g++) {
                seqs.add("1");
                creates.add("\"2100-01-01T00:00:00Z\"");
                cids.add("Jzzzzzzzzzzzz");
            }
            String holdArgs = "'{FAILED,ERROR}'::text[], " + arrayLiteral(groups) + ", " + arrayLiteral(groups) + ", '{"
                    + String.join(",", seqs) + "}'::int[], " + arrayLiteral(creates) + ", " + arrayLiteral(cids);
            String holdPlan = explain(c, "dq_hold(" + holdArgs + ")");
            t = System.nanoTime();
            exec(c, "EXECUTE dq_hold(" + holdArgs + ")");
            double holdMs = (System.nanoTime() - t) / 1e6;
            // the delivery-time gate runs on a pool WITHOUT the settings
            String beforePlan;
            try (Connection plain = new Source(db, false, true).getConnection()) {
                prepare(plain, st);
                beforePlan = explain(plain, "dq_before('{FAILED,ERROR}'::text[], 'grp-1', 50, '2000-01-01'::timestamptz, 'J000000000001',"
                        + " 'grp-1', 50, '2000-01-01'::timestamptz, 'J000000000001')");
            }
            return new Result(s1Plan, s2Plan, restorePlan, s1Ms, s2Ms, restoreMs, holdPlan, holdMs, beforePlan);
        }
    }

    // ── tests ──────────────────────────────────────────────────────────────

    @Test
    void theClaimAndTheRestoreAreWellPlannedInEveryStateWithTheSchedulerSettings() throws Exception {
        var report = new StringBuilder();
        List<String> failures = new ArrayList<>();
        String base = clone("dq_plan_capture");
        Statements st = capture(base);
        report.append("S1: ").append(st.s1().replaceAll("\\s+", " ")).append("\nS2: ").append(st.s2()).append("\nrestore: ")
                .append(st.restore()).append("\n");
        report.append(String.format("%n%-22s %7s %-9s %9s %9s %9s%n", "state", "rows", "", "S1 ms", "S2 ms", "restore ms"));
        for (int n : new int[] {5_000, 100_000}) {
            for (State state : State.values()) {
                String db = clone("dq_plan_" + state.name().toLowerCase(java.util.Locale.ROOT) + "_" + n);
                Result r = scenario(state, n, true, db, st);
                String where = state + " @" + n;
                report.append(String.format("%-22s %7d %-9s %9.1f %9.1f %9.1f%n", state, n, "", r.s1Ms(), r.s2Ms(), r.restoreMs()));
                report.append(String.format("   hold-back (500 groups): %.1f ms%n", r.holdMs()));
                report.append("--- ").append(where).append(" S1\n").append(r.s1Plan()).append("\n--- S2\n").append(r.s2Plan())
                        .append("\n--- restore\n").append(r.restorePlan()).append("\n--- hold-back\n").append(r.holdPlan())
                        .append("\n--- delivery-time hold-back (no settings)\n").append(r.beforePlan()).append("\n");
                if (r.s1Plan().contains("Sort")) failures.add(where + ": S1 has a Sort node");
                if (seqScansQueue(r.s1Plan())) failures.add(where + ": S1 seq-scans the queue");
                if (!r.s1Plan().contains("idx_dispatch_queue_order")) failures.add(where + ": S1 does not use the order index");
                // at 5,000 rows a seq scan of the queue (or of the jobs) is the cheap and right answer for a tiny
                // table; the shape that matters, and is asserted, is at 100,000
                if (n >= 100_000) {
                    boolean s2Index = r.s2Plan().contains("msg_dispatch_queue_pkey")
                            || (state == State.DRAINED_THEN_BURST && r.s2Plan().contains("idx_dispatch_queue_order"));
                    if (!s2Index) failures.add(where + ": S2 is not an index scan on the primary key");
                    if (seqScansQueue(r.s2Plan())) failures.add(where + ": S2 seq-scans the queue");
                    if (seqScansPopulatedJobs(r.restorePlan())) failures.add(where + ": restore seq-scans msg_dispatch_jobs");
                    if (!r.restorePlan().contains("_pkey")) failures.add(where + ": restore does not read msg_dispatch_jobs by primary key");
                    if (r.restorePlan().contains("status_message_group_sequence_cre")) failures.add(where + ": restore walks the status index per job");
                    if (seqScansQueue(r.holdPlan())) failures.add(where + ": the claim-time hold-back seq-scans the queue");
                    if (seqScansQueue(r.beforePlan())) failures.add(where + ": the delivery-time hold-back seq-scans the queue");
                }
            }
        }
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/dispatch-queue-plans.txt"), report.toString());
        System.out.println("dispatch queue plans written to target/dispatch-queue-plans.txt");
        assertThat(failures).isEmpty();
    }

    /// The negative control: the cached-plan case WITHOUT the two settings. A plan cached while the queue was
    /// empty is reused after the burst; the settings are what prevent it, so without them the plan is bad
    /// (a seq scan of the queue, or a sort).
    @Test
    void withoutTheSettingsTheCachedPlanAfterABurstIsBad() throws Exception {
        String base = clone("dq_plan_capture2");
        Statements st = capture(base);
        String db = clone("dq_plan_negative");
        Result r = scenario(State.CACHED_PLAN, 100_000, false, db, st);
        String report = "NEGATIVE CONTROL (no settings), cached plan after a burst of 100,000 rows\n--- S1\n" + r.s1Plan()
                + "\n--- S2\n" + r.s2Plan() + String.format("%nS1 %.1f ms, S2 %.1f ms%n", r.s1Ms(), r.s2Ms());
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/dispatch-queue-plans-negative.txt"), report);
        System.out.println(report);
        boolean bad = r.s1Plan().contains("Sort") || seqScansQueue(r.s1Plan()) || seqScansQueue(r.s2Plan());
        assertThat(bad).as("the cached plan without the settings is a seq scan or a sort:%n%s", report).isTrue();
    }

    /// The settings, as the scheduler's pool applies them, are the ones this test runs with.
    @Test
    void thePlanTestUsesTheSchedulerPoolsSettings() {
        assertThat(Pools.SCHEDULER_SERVER_SETTINGS).containsEntry("plan_cache_mode", "force_custom_plan")
                .containsEntry("enable_sort", "off").hasSize(2);
        assertThat(SETTINGS_OPTIONS).contains("plan_cache_mode=force_custom_plan").contains("enable_sort=off");
    }
}
