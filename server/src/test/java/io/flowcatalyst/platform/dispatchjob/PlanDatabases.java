package io.flowcatalyst.platform.dispatchjob;

import io.flowcatalyst.platform.shared.database.Migrator;
import io.flowcatalyst.testpg.TestPg;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/// Databases for the plan and concurrency tests: a migrated template with four monthly partitions of
/// `msg_dispatch_jobs` and autovacuum switched off (the statistics state is the test's to control), cloned per
/// scenario, and seeded the way production fills it — a mostly-`COMPLETED` job table and a burst of `PENDING` jobs
/// in the ACTIVE (current month's) partition.
public final class PlanDatabases {

    /// The scheduler pool's two settings, as a libpq `options` string.
    public static final String SETTINGS_OPTIONS = "-c plan_cache_mode=force_custom_plan -c enable_sort=off";

    private static final String TEMPLATE_DB = "dq_plan_template";
    private static boolean templateReady;

    private PlanDatabases() {
    }

    /// First instant of the current month (UTC): the active partition starts here.
    public static java.time.Instant monthStart() {
        return YearMonth.now(ZoneOffset.UTC).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public static void exec(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    public static List<String> lines(Connection c, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    /// A DataSource of plain connections to one database, with the scheduler pool's settings or without, in the
    /// simple query protocol (so a `$n` in a PREPARE reaches the server untouched) or not; records the SQL each
    /// prepared statement was issued with, by the name the test set.
    public static final class Source implements DataSource {
        final String url;
        final boolean settings;
        final boolean simple;
        public final Map<String, String> sqlByName = new LinkedHashMap<>();
        volatile String current = "?";

        public Source(String db, boolean settings, boolean simple) {
            this.url = TestPg.instance().getJdbcUrl("postgres", db);
            this.settings = settings;
            this.simple = simple;
        }

        public void as(String name) {
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
                            synchronized (sqlByName) {
                                sqlByName.merge(current, sql, (a, b) -> a.equals(b) ? a : a + "\n;;\n" + b);
                            }
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

    /// A migrated database with four monthly partitions and autovacuum off everywhere, plus the EXPLAIN helper.
    private static synchronized void template() throws Exception {
        if (templateReady) return;
        DataSource ds = TestPg.newDatabase(TEMPLATE_DB);
        Migrator.migrate(ds);
        try (Connection c = ds.getConnection()) {
            YearMonth now = YearMonth.now(ZoneOffset.UTC);
            for (int i = 0; i <= 3; i++) {
                YearMonth ym = now.minusMonths(i);
                exec(c, "CREATE TABLE IF NOT EXISTS " + partition(ym) + " PARTITION OF msg_dispatch_jobs FOR VALUES FROM ('"
                        + ym.atDay(1) + "') TO ('" + ym.plusMonths(1).atDay(1) + "')");
            }
            for (String t : lines(c, "SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid"
                    + " JOIN pg_class p ON p.oid = i.inhparent WHERE p.relname = 'msg_dispatch_jobs'")) {
                exec(c, "ALTER TABLE " + t + " SET (autovacuum_enabled = false)");
            }
            exec(c, "CREATE FUNCTION explain_generic(q text) RETURNS SETOF text LANGUAGE plpgsql AS $f$ DECLARE r text; BEGIN"
                    + " FOR r IN EXECUTE 'EXPLAIN (GENERIC_PLAN, COSTS OFF) ' || q LOOP RETURN NEXT r; END LOOP; END $f$");
        }
        templateReady = true;
    }

    static String partition(YearMonth ym) {
        return String.format("msg_dispatch_jobs_%04d_%02d", ym.getYear(), ym.getMonthValue());
    }

    /// The partitions the seed populates (the migrated database also has empty ones ahead).
    public static java.util.Set<String> populatedPartitions() {
        var out = new java.util.HashSet<String>();
        YearMonth now = YearMonth.now(ZoneOffset.UTC);
        for (int i = 0; i <= 3; i++) out.add(partition(now.minusMonths(i)));
        return out;
    }

    /// The partitions with no rows: the ones ahead of the current month (and none behind: all four are seeded).
    static List<String> emptyPartitions() throws Exception {
        template();
        var out = new ArrayList<String>();
        try (Connection c = new Source(TEMPLATE_DB, false, false).getConnection()) {
            for (String t : lines(c, "SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid"
                    + " JOIN pg_class p ON p.oid = i.inhparent WHERE p.relname = 'msg_dispatch_jobs'")) {
                if (!populatedPartitions().contains(t)) out.add(t);
            }
        }
        return out;
    }

    public static String clone(String name) throws Exception {
        template();
        try (Connection c = TestPg.instance().getPostgresDatabase().getConnection()) {
            exec(c, "DROP DATABASE IF EXISTS " + name);
            exec(c, "CREATE DATABASE " + name + " TEMPLATE " + TEMPLATE_DB);
        }
        return name;
    }

    /// `n` COMPLETED jobs spread over the last four months (the mostly-completed bulk of the table).
    public static void seedCompleted(Connection c, int n) throws SQLException {
        exec(c, """
                INSERT INTO msg_dispatch_jobs (id, code, target_url, status, message_group, sequence, mode, created_at, updated_at, completed_at)
                SELECT 'F' || lpad(i::text, 12, '0'), 'plan:code', 'https://hook.example/x', 'COMPLETED',
                       'old-' || (i %% 5000), (i / 5000) %% 100, 'IMMEDIATE',
                       (date_trunc('month', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC') - interval '3 months' + (i::double precision / %d) * (interval '3 months' + now() - (date_trunc('month', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC')),
                       now() - interval '2 hours', now() - interval '2 hours'
                  FROM generate_series(1, %d) AS i
                """.formatted(n, n));
    }

    /// A burst of `n` PENDING jobs in the active partition: ids `J000000000000`.., 500 groups (`g0000`..), sequence
    /// `i / 500`, `created_at` = the month's start + `i` seconds, `BLOCK_ON_ERROR`. With `ties`, every job shares one
    /// `created_at` and one `sequence`.
    public static void burst(Connection c, int n, boolean ties) throws SQLException {
        // the UTC month start, whatever the session's time zone (the driver sets the JVM's)
        String month = "(date_trunc('month', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC')";
        String created = ties ? month + " + interval '1 hour'" : month + " + i * interval '1 second'";
        String sequence = ties ? "0" : "i / 500";
        exec(c, """
                INSERT INTO msg_dispatch_jobs (id, code, target_url, status, message_group, sequence, mode,
                        subscription_id, dispatch_pool_id, client_id, created_at, updated_at)
                SELECT 'J' || lpad(i::text, 12, '0'), 'plan:code', 'https://hook.example/x', 'PENDING',
                       'g' || lpad((i %% 500)::text, 4, '0'), %s, 'BLOCK_ON_ERROR',
                       CASE WHEN i %% 7 = 0 THEN 'sub' || (i %% 50) END, 'pool' || (i %% 5), 'cli' || (i %% 20),
                       %s, now() - interval '1 hour'
                  FROM generate_series(0, %d) AS i
                """.formatted(sequence, created, n - 1));
    }
}
