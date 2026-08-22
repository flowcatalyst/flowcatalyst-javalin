package io.flowcatalyst.platform.shared.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import javax.sql.DataSource;

import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;

import io.flowcatalyst.testpg.TestPg;

/// A database created and migrated by the Go service (goose) is adopted by
/// Flyway: baselined at V1 without running V1, and `goose_db_version` is
/// left untouched.
class GoAdoptionTest {

    @Test
    void goDatabaseIsBaselinedAtV1AndGooseTableIsLeftAlone() throws Exception {
        DataSource ds = TestPg.newDatabase("go_adoption");
        GoSchema.load(ds);
        // go-schema.sql contains the (empty) goose_db_version table; give it the
        // rows goose would have written for the 45 migrations.
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO public.goose_db_version (version_id, is_applied) VALUES (0, true)");
            for (int v = 1; v <= 45; v++) {
                if (v == 23) {
                    continue; // no 023 in flowcatalyst-go/internal/migrate/sql
                }
                st.execute("INSERT INTO public.goose_db_version (version_id, is_applied) VALUES (" + v + ", true)");
            }
        }
        String before = SchemaFingerprint.compute(ds);

        MigrateResult result = Migrator.migrate(ds);
        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isZero();

        MigrationInfo[] applied = Migrator.flyway(ds).info().applied();
        assertThat(applied).hasSize(1);
        assertThat(applied[0].getVersion().getVersion()).isEqualTo("1");
        assertThat(applied[0].getState()).isEqualTo(MigrationState.BASELINE);
        assertThat(Migrator.flyway(ds).info().pending()).isEmpty();

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT count(*), max(version_id) FROM public.goose_db_version")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(45);
                assertThat(rs.getInt(2)).isEqualTo(45);
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT type, version, success FROM public.flyway_schema_history ORDER BY installed_rank")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("BASELINE");
                assertThat(rs.getString(2)).isEqualTo("1");
                assertThat(rs.getBoolean(3)).isTrue();
                assertThat(rs.next()).isFalse();
            }
        }
        // Schema untouched (flyway_schema_history is ignored by the fingerprint).
        assertThat(SchemaFingerprint.compute(ds)).isEqualTo(before);

        // And a second run is still a no-op.
        assertThat(Migrator.migrate(ds).migrationsExecuted).isZero();
    }
}
