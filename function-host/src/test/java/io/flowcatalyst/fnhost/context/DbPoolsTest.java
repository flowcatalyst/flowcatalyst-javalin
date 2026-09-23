package io.flowcatalyst.fnhost.context;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// X7 (`docs/spec/function-context.md` §2, §4): [DbPools] against the
/// embedded Postgres — real connections, a real marker password planted on a
/// throwaway role, real `pg_stat_activity` cross-checks.
class DbPoolsTest {

    private static final String DB_A = "fn_dbpools_x7_a";
    private static final String DB_B = "fn_dbpools_x7_b";
    private static final String MARKER_PASSWORD = "x7-marker-pw-3f9c1a";

    private static DataSource DB;
    private static int PORT;

    @BeforeAll
    static void setUp() {
        DB = TestPg.newDatabase(DB_A);
        TestPg.newDatabase(DB_B);
        PORT = TestPg.instance().getPort();
        createRole("x7_role", MARKER_PASSWORD);
    }

    private static void createRole(String role, String password) {
        try (Connection c = DB.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE ROLE " + role + " LOGIN PASSWORD '" + password + "'");
            st.execute("GRANT CONNECT ON DATABASE " + DB_A + " TO " + role);
            st.execute("GRANT CONNECT ON DATABASE " + DB_B + " TO " + role);
        } catch (SQLException e) {
            throw new IllegalStateException("create role " + role, e);
        }
    }

    private static String dsn(String db) {
        return "postgresql://x7_role:" + MARKER_PASSWORD + "@localhost:" + PORT + "/" + db;
    }

    @Test
    void x7_twoUsersOfOneDsnShareOnePoolSizedToTheLargerPoolSizeAndCloseOnlyWhenTheLastUnloads() throws Exception {
        var appender = attachRootAppender();
        try (DbPools pools = new DbPools(16)) {
            String rawDsn = dsn(DB_A);
            Object tokenSmall = new Object(); // "function A", poolSize 3
            Object tokenLarge = new Object(); // "function B", poolSize 7

            DbPools.Acquired a = pools.acquire(rawDsn, 3, tokenSmall);
            assertThat(pools.poolCountForTest()).as("mutant: one pool per function").isEqualTo(1);
            assertThat(pools.currentMaxPoolSizeForTest(rawDsn)).isEqualTo(3);
            int hikariAfterFirst = pools.hikariIdentityForTest(rawDsn);

            DbPools.Acquired b = pools.acquire(rawDsn, 7, tokenLarge);
            assertThat(pools.poolCountForTest())
                    .as("mutant: one pool per function — a second user of the SAME dsn must join, not open a second pool")
                    .isEqualTo(1);
            assertThat(pools.hikariIdentityForTest(rawDsn))
                    .as("mutant: one pool per function — the SAME Hikari pool object must back both users")
                    .isEqualTo(hikariAfterFirst);
            assertThat(pools.currentMaxPoolSizeForTest(rawDsn))
                    .as("mutant: size = the larger poolSize").isEqualTo(7);

            // Both handed-out DataSources actually work, over the ONE shared pool.
            try (Connection c1 = a.dataSource().getConnection(); Connection c2 = b.dataSource().getConnection()) {
                assertThat(query1(c1)).isEqualTo(1);
                assertThat(query1(c2)).isEqualTo(1);
            }

            pools.release(a.poolIdentity(), tokenSmall);
            assertThat(pools.poolCountForTest())
                    .as("mutant: close on first unload — one user remains, the pool must still be open")
                    .isEqualTo(1);
            assertThat(pools.currentMaxPoolSizeForTest(rawDsn))
                    .as("the smaller user left; the remaining (larger) user's declared size still governs")
                    .isEqualTo(7);
            // The remaining handed-out DataSource for tokenLarge still works.
            try (Connection c2 = b.dataSource().getConnection()) {
                assertThat(query1(c2)).isEqualTo(1);
            }

            pools.release(b.poolIdentity(), tokenLarge);
            assertThat(pools.poolCountForTest())
                    .as("mutant: close on first unload — the LAST user left, the pool must now be closed")
                    .isEqualTo(0);
        } finally {
            detach(appender);
            assertNoPasswordLeaked(appender);
        }
    }

    @Test
    void x7_handedOutDataSourceCannotCloseOrUnwrapTheSharedPool() throws SQLException {
        try (DbPools pools = new DbPools(16)) {
            Object token = new Object();
            DbPools.Acquired a = pools.acquire(dsn(DB_A), 2, token);
            DataSource ds = a.dataSource();

            assertThat(ds).as("mutant: pass Hikari through — no close() reachable at all")
                    .isNotInstanceOf(AutoCloseable.class);
            assertThat(ds.isWrapperFor(DataSource.class)).isFalse();
            assertThatThrownBy(() -> ds.unwrap(DataSource.class)).isInstanceOf(SQLException.class);

            pools.release(a.poolIdentity(), token);
        }
    }

    @Test
    void x7_aBrandNewDsnPastTheLimitIsRefused() {
        try (DbPools pools = new DbPools(1)) {
            Object token1 = new Object();
            pools.acquire(dsn(DB_A), 2, token1);
            // A second, DIFFERENT dsn — not a second user of the first — must be refused, never an
            // eviction of the pool already in use.
            Object token2 = new Object();
            assertThatThrownBy(() -> pools.acquire(dsn(DB_B), 2, token2))
                    .as("mutant: evict instead of refusing").isInstanceOf(DbPools.PoolLimitException.class);
            assertThat(pools.poolCountForTest()).as("the pool already in use must still be open").isEqualTo(1);
        }
    }

    @Test
    void x7_anUnsupportedDsnIsRefused() {
        try (DbPools pools = new DbPools(16)) {
            assertThatThrownBy(() -> pools.acquire("mysql://user:pw@localhost:3306/db", 2, new Object()))
                    .isInstanceOf(Dsn.UnsupportedDsnException.class);
            assertThatThrownBy(() -> pools.acquire("jdbc:mysql://localhost:3306/db", 2, new Object()))
                    .as("a jdbc: URL for a non-PostgreSQL driver must also be refused")
                    .isInstanceOf(Dsn.UnsupportedDsnException.class);
            assertThat(pools.poolCountForTest()).isEqualTo(0);
        }
    }

    @Test
    void x7_undeclaredDataSourceNameThrowsIllegalArgumentException() {
        try (DbPools pools = new DbPools(16)) {
            HostFunctionContext ctx = buildContextWithOneDb(pools, "db1", dsn(DB_A), 2);
            assertThat(ctx.dataSource("db1").getClass()).isNotNull(); // declared: works
            assertThatThrownBy(() -> ctx.dataSource("unknown"))
                    .as("mutant: accept any name").isInstanceOf(IllegalArgumentException.class);
            ctx.close();
        }
    }

    /// A wrong password never appears in a log line or exception message
    /// A real, failing connection attempt — using the CORRECT marker
    /// password against a database that does not exist (embedded Postgres's
    /// default `pg_hba.conf` is `trust` for local connections, so a WRONG
    /// password is not actually rejected by this fixture at all; a bad
    /// database name is the reliable way to force pgjdbc/Hikari to produce a
    /// real error here) — the failure surfaces from [DbPools#acquire] itself
    /// (Hikari fails fast at pool construction), and neither it nor anything
    /// captured on the root logger during the attempt names the password.
    @Test
    void x7_aConnectionFailureNeverLeaksThePasswordInAnyLogLineOrExceptionMessage() {
        var appender = attachRootAppender();
        String badDsn = "postgresql://x7_role:" + MARKER_PASSWORD + "@localhost:" + PORT + "/does_not_exist_db";
        try (DbPools pools = new DbPools(16)) {
            assertThatThrownBy(() -> pools.acquire(badDsn, 2, new Object()))
                    .satisfies(e -> assertThat(rootMessages(e))
                            .as("mutant: scrub Hikari/pgjdbc exception messages")
                            .doesNotContain(MARKER_PASSWORD));
        } finally {
            detach(appender);
            assertNoPasswordLeaked(appender);
        }
    }

    private static String rootMessages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            sb.append(cur.getMessage()).append('\n');
        }
        return sb.toString();
    }

    private static HostFunctionContext buildContextWithOneDb(DbPools pools, String dbName, String rawDsn, int poolSize) {
        var manifestJson = """
                {"runtime":"jvm","entrypoint":"fixture.x7.Fn","limits":{"maxDurationMs":5000,"maxConcurrency":5},
                 "db":[{"name":"%s","secretRef":"the_dsn","poolSize":%d}]}
                """.formatted(dbName, poolSize);
        var manifest = io.flowcatalyst.platform.function.Manifest.readStored(
                io.flowcatalyst.platform.shared.json.Json.MAPPER.readTree(manifestJson));
        var address = io.flowcatalyst.platform.function.FunctionAddress.parse("x7.svc.fn");
        var entry = new io.flowcatalyst.fnhost.reconcile.DesiredDocument.Entry(address, "fnc_1", "v1", 1,
                io.flowcatalyst.fnhost.reconcile.DesiredDocument.Role.LIVE,
                io.flowcatalyst.fnhost.reconcile.DesiredDocument.Mode.WARM,
                io.flowcatalyst.platform.function.Digest.parse(
                        "sha256:0000000000000000000000000000000000000000000000000000000000000000"),
                "file:///dev/null", null, null, manifest, null, null, null, java.util.Map.of(),
                java.util.Map.of("the_dsn", rawDsn), java.util.List.of(), java.util.List.of());
        ContextFactory factory = new ContextFactory(pools, java.net.http.HttpClient.newHttpClient(), java.time.Clock.systemUTC(),
                new io.flowcatalyst.fnhost.reconcile.FakeControlPlane(), "host-1");
        return factory.build(entry, new Object());
    }

    private static int query1(Connection c) throws SQLException {
        try (Statement st = c.createStatement(); var rs = st.executeQuery("SELECT 1")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static ListAppender<ILoggingEvent> attachRootAppender() {
        var root = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        root.addAppender(appender);
        return appender;
    }

    private static void detach(ListAppender<ILoggingEvent> appender) {
        var root = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        root.detachAppender(appender);
        appender.stop();
    }

    private static void assertNoPasswordLeaked(ListAppender<ILoggingEvent> appender) {
        for (ILoggingEvent event : appender.list) {
            assertThat(event.getFormattedMessage()).doesNotContain(MARKER_PASSWORD);
            var t = event.getThrowableProxy();
            while (t != null) {
                assertThat(String.valueOf(t.getMessage())).doesNotContain(MARKER_PASSWORD);
                t = t.getCause();
            }
        }
    }
}
