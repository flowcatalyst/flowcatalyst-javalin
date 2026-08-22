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
