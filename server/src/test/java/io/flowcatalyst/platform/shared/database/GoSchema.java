package io.flowcatalyst.platform.shared.database;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Collectors;

import javax.sql.DataSource;

/// Loads the captured Go schema (`db/go-schema.sql`, a `pg_dump
/// --schema-only` of a database migrated by the Go service) into a database,
/// exactly as `psql -f` would: only the psql meta-command lines (the
/// backslash `restrict` / `unrestrict` lines pg_dump 18 emits) are stripped.
public final class GoSchema {

    public static final String RESOURCE = "/db/go-schema.sql";

    private GoSchema() {
    }

    public static String sql() {
        try (InputStream in = GoSchema.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource " + RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(l -> !l.startsWith("\\"))
                    .collect(Collectors.joining("\n"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// Applies the Go schema to `ds` (which must be an empty database).
    public static void load(DataSource ds) throws SQLException {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql());
        }
    }
}
