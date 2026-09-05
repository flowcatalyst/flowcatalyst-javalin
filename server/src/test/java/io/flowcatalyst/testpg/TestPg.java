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
/// use, and the `postgres` database is migrated once with [Migrator].
/// Tests get a shared [DataSource] via [#dataSource()].
///
/// Isolation model (same rule as Go): there is NO truncation between tests.
/// Tests must seed their own rows under fresh ids and assert on that subset,
/// never on table-wide counts.
///
/// [#newDatabase(String)] creates an extra, empty database on the same
/// instance for tests that need a pristine schema (migration tests).
public final class TestPg {

    private static final Object LOCK = new Object();
    private static EmbeddedPostgres pg;
    private static DataSource migrated;

    private TestPg() {
    }

    /// The shared, migrated database.
    public static DataSource dataSource() {
        synchronized (LOCK) {
            if (migrated == null) {
                DataSource ds = instance().getPostgresDatabase();
                Migrator.migrate(ds);
                migrated = ds;
            }
            return migrated;
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
                migrated = null;
            }
        }
    }
}
