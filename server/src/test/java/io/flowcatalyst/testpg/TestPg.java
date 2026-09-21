package io.flowcatalyst.testpg;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import io.flowcatalyst.platform.shared.database.Migrator;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/// Shared embedded-PostgreSQL fixture for integration tests — the Java
/// counterpart of Go's `internal/testpg`.
///
/// ONE embedded Postgres (zonky, PG 18) is started per JVM, lazily on first
/// use. A template database is migrated once with [Migrator]; **every test
/// class then gets its own copy of it** (`CREATE DATABASE … TEMPLATE`, tens
/// of milliseconds), dropped when the class finishes.
///
/// [#dataSource()] is a *router*, not a database: each `getConnection()`
/// goes to the database of the test class running at that moment
/// ([TestPgPerClass] tracks it). That is what lets the static fixtures
/// (`OutboxFixture.DS`, `DispatchJobFixture.DS`, …) stay `static final` and
/// still be per-class — the alternative, keying by caller, would hand a test
/// and the fixture it uses two different databases.
///
/// Why per class: the old model — one database for the whole module, "seed
/// under fresh ids, never assert table-wide" — held for rows a test reads by
/// id and failed for everything that scans: claim queries, pollers, seeders,
/// uniqueness on well-known codes, a corrupt row another class planted. It
/// made the suite order-dependent (`-Dsurefire.runOrder=random` failed six
/// classes) and let a poller leaked by one class claim another's rows.
/// Within a class the old rule still applies: methods share a database.
///
/// [#newDatabase(String)] creates an extra, EMPTY database on the same
/// instance for tests that need a pristine schema (migration tests).
public final class TestPg {

    private static final Object LOCK = new Object();
    private static final String TEMPLATE = "fc_test_template";
    /// Connections made outside any test class (a static initialiser run at
    /// discovery, a thread that outlives its class) land here.
    private static final String UNOWNED = "fc_test_unowned";
    private static final DataSource ROUTER = new RoutingDataSource();

    private static EmbeddedPostgres pg;
    private static boolean templateReady;
    /// The running top-level test class → its database; set by [TestPgPerClass].
    private static volatile String currentOwner;
    private static final java.util.Map<String, String> DATABASES = new java.util.HashMap<>();
    private static int sequence;

    private TestPg() {
    }

    /// The migrated database of the test class running now — see the class doc.
    public static DataSource dataSource() {
        return ROUTER;
    }

    static void enter(String testClass) {
        currentOwner = testClass;
    }

    /// Drops the class's database. `WITH (FORCE)`: a pool or a poller the
    /// class leaked must not keep the database — or the next class's rows —
    /// alive; its next statement fails in its own thread, which is the
    /// honest outcome for a thread nobody stopped.
    static void leave(String testClass) {
        String database;
        synchronized (LOCK) {
            if (testClass.equals(currentOwner)) currentOwner = null;
            database = DATABASES.remove(testClass);
        }
        if (database == null) return;
        try (Connection c = instance().getPostgresDatabase().getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        } catch (SQLException e) {
            throw new IllegalStateException("drop database " + database, e);
        }
    }

    private static DataSource current() {
        String owner = currentOwner;
        String key = owner == null ? UNOWNED : owner;
        synchronized (LOCK) {
            String database = DATABASES.get(key);
            if (database == null) {
                database = owner == null ? UNOWNED : "fc_test_" + (++sequence);
                cloneTemplate(database);
                DATABASES.put(key, database);
            }
            return instance().getDatabase("postgres", database);
        }
    }

    private static void cloneTemplate(String database) {
        EmbeddedPostgres instance = instance();
        try (Connection c = instance.getPostgresDatabase().getConnection(); Statement st = c.createStatement()) {
            if (!templateReady) {
                st.execute("DROP DATABASE IF EXISTS " + TEMPLATE);
                st.execute("CREATE DATABASE " + TEMPLATE);
                // A simple (unpooled) DataSource: every connection Flyway opens is closed
                // again, which CREATE DATABASE … TEMPLATE requires of its source.
                Migrator.migrate(instance.getDatabase("postgres", TEMPLATE));
                templateReady = true;
            }
            st.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            st.execute("CREATE DATABASE " + database + " TEMPLATE " + TEMPLATE);
        } catch (SQLException e) {
            throw new IllegalStateException("create database " + database, e);
        }
    }

    /// Routes every connection to [#current()]; nothing else about it is a database.
    private static final class RoutingDataSource implements DataSource {
        @Override
        public Connection getConnection() throws SQLException {
            return current().getConnection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return current().getConnection(username, password);
        }

        @Override
        public java.io.PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(java.io.PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getLogger("io.flowcatalyst.testpg");
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return current().unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return current().isWrapperFor(iface);
        }
    }

    /// Creates a fresh, empty database named `name` on the shared instance
    /// (dropping any previous one of that name) and returns a DataSource for it.
    public static DataSource newDatabase(String name) {
        if (!name.matches("[a-z_][a-z0-9_]*")) {
            throw new IllegalArgumentException("bad database name: " + name);
        }
        EmbeddedPostgres instance = instance();
        try (Connection c = instance.getPostgresDatabase().getConnection();
             Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + name);
            st.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("create database " + name, e);
        }
        return instance.getDatabase("postgres", name);
    }

    /// The underlying embedded instance (started on first call).
    public static EmbeddedPostgres instance() {
        synchronized (LOCK) {
            if (pg == null) {
                try {
                    pg = EmbeddedPostgres.builder().start();
                } catch (IOException e) {
                    throw new UncheckedIOException("start embedded postgres", e);
                }
                Runtime.getRuntime().addShutdownHook(new Thread(TestPg::stop, "testpg-shutdown"));
            }
            return pg;
        }
    }

    /// Runs `fn` with the named CHECK constraint on `table` temporarily
    /// removed, then restores it (even if `fn` throws). This is how an X-06
    /// "corrupt row fails loudly at read time" test seeds the corrupt row in
    /// the first place: since migrations 051/052, the enum columns these
    /// tests target are guarded by a CHECK constraint at the write boundary,
    /// so a plain `INSERT` of a bad value is rejected before the
    /// read-boundary code under test ever sees it. Dropping the constraint
    /// for the duration of the seed insert honestly simulates the scenario
    /// the read-boundary check exists for: a row written before the
    /// constraint existed, or one that arrives via direct DBA action —
    /// legacy or out-of-band corruption, not a new write through the app.
    ///
    /// Ported from Go's `internal/testpg.WithConstraintDropped`. Not safe to
    /// run concurrently with another test against the same table: the
    /// constraint is genuinely off table-wide for the window between the
    /// drop and the restore.
    public static void withConstraintDropped(DataSource ds, String table, String constraint, Runnable fn) {
        String definition;
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             var rs = st.executeQuery(
                     "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = '" + constraint + "'")) {
            if (!rs.next()) {
                throw new IllegalStateException("no such constraint: " + constraint);
            }
            definition = rs.getString(1);
        } catch (SQLException e) {
            throw new IllegalStateException("look up " + constraint, e);
        }
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("ALTER TABLE " + table + " DROP CONSTRAINT " + constraint);
        } catch (SQLException e) {
            throw new IllegalStateException("drop " + constraint, e);
        }
        try {
            fn.run();
        } finally {
            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                st.execute("ALTER TABLE " + table + " ADD CONSTRAINT " + constraint + " " + definition);
            } catch (SQLException e) {
                throw new IllegalStateException("restore " + constraint, e);
            }
        }
    }

    private static void stop() {
        synchronized (LOCK) {
            if (pg != null) {
                try {
                    pg.close();
                } catch (IOException _) {
                    // best effort at JVM exit
                }
                pg = null;
                templateReady = false;
                DATABASES.clear();
            }
        }
    }
}
