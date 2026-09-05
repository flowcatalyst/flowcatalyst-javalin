package io.flowcatalyst.parity;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

/// One embedded PostgreSQL instance for the whole harness run
/// (parity-harness spec §1) — a thin, parity-module copy of
/// `io.flowcatalyst.testpg.TestPg` (that class is test-scope in `server`;
/// `ParityMain` is a main-class entry point, so it needs this at runtime,
/// not just in tests). No migration on start: `seed`, `parity_go` and
/// `parity_java` are created and migrated explicitly by [Seed].
public final class EmbeddedPg implements AutoCloseable {

    private final EmbeddedPostgres pg;

    private EmbeddedPg(EmbeddedPostgres pg) {
        this.pg = pg;
    }

    public static EmbeddedPg start() {
        try {
            return new EmbeddedPg(EmbeddedPostgres.builder().start());
        } catch (IOException e) {
            throw new UncheckedIOException("start embedded postgres", e);
        }
    }

    public int port() {
        return pg.getPort();
    }

    /// A libpq-style URL Go's `pgx` and Java's [io.flowcatalyst.platform.shared.database.Database]
    /// both read (`FC_DATABASE_URL`, parity-harness spec §2).
    public String url(String database) {
        return "postgresql://postgres@127.0.0.1:" + port() + "/" + requireSafeName(database) + "?sslmode=disable";
    }

    public void createDatabase(String name) {
        execute("CREATE DATABASE " + requireSafeName(name));
    }

    public void createDatabaseFromTemplate(String name, String template) {
        execute("CREATE DATABASE " + requireSafeName(name) + " TEMPLATE " + requireSafeName(template));
    }

    /// zonky's own `DataSource` for `database` — used only for the one-off
    /// jOOQ reads against `seed` before the clones are made ([Seed]); both
    /// sides' actual traffic goes through their own Hikari pool / Go's own
    /// pgx pool, never this.
    public DataSource dataSource(String database) {
        return pg.getDatabase("postgres", requireSafeName(database));
    }

    private void execute(String sql) {
        try (Connection c = pg.getPostgresDatabase().getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    private static String requireSafeName(String name) {
        if (!name.matches("[a-z_][a-z0-9_]*")) {
            throw new IllegalArgumentException("unsafe database name: " + name);
        }
        return name;
    }

    @Override
    public void close() {
        try {
            pg.close();
        } catch (IOException e) {
            // best effort at shutdown
        }
    }
}
