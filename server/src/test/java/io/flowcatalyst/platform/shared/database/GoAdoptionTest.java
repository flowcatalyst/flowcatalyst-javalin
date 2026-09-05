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
        // rows goose would have written for the migrations up to 052 (a Go
        // database at HEAD). There is no 023 or 050 in
        // flowcatalyst-go/internal/migrate/sql.
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO public.goose_db_version (version_id, is_applied) VALUES (0, true)");
            for (int v = 1; v <= 52; v++) {
                if (v == 23 || v == 50) {
                    continue;
                }
                st.execute("INSERT INTO public.goose_db_version (version_id, is_applied) VALUES (" + v + ", true)");
            }
        }
        String before = SchemaFingerprint.compute(ds);

        MigrateResult result = Migrator.migrate(ds);
        assertThat(result.success).isTrue();
        // Flyway baselines at V1 (not executed) then MUST apply V2..V7 even
        // though the Go database already has their effect: each one is
        // idempotent (IF NOT EXISTS / pg_constraint guards) and a no-op here.
        assertThat(result.migrationsExecuted).isEqualTo(6);
        assertThat(result.migrations).extracting(m -> m.version)
                .containsExactly("2", "3", "4", "5", "6", "7");

        MigrationInfo[] applied = Migrator.flyway(ds).info().applied();
        assertThat(applied).hasSize(7);
        assertThat(applied[0].getVersion().getVersion()).isEqualTo("1");
        assertThat(applied[0].getState()).isEqualTo(MigrationState.BASELINE);
        for (int i = 1; i < applied.length; i++) {
            assertThat(applied[i].getState()).isEqualTo(MigrationState.SUCCESS);
        }
        assertThat(Migrator.flyway(ds).info().pending()).isEmpty();

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT count(*), max(version_id) FROM public.goose_db_version")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(51);
                assertThat(rs.getInt(2)).isEqualTo(52);
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT type, version, success FROM public.flyway_schema_history ORDER BY installed_rank")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("BASELINE");
                assertThat(rs.getString(2)).isEqualTo("1");
                assertThat(rs.getBoolean(3)).isTrue();
                for (int v = 2; v <= 7; v++) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(2)).isEqualTo(String.valueOf(v));
                    assertThat(rs.getBoolean(3)).isTrue();
                }
                assertThat(rs.next()).isFalse();
            }
            // V2..V7 are no-ops on a Go-HEAD database: the schema they add is
            // already there exactly once, not duplicated or altered.
            try (ResultSet rs = st.executeQuery("""
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'oauth_clients'
                      AND column_name = 'previous_secret_ref'""")) {
                rs.next();
                assertThat(rs.getInt(1)).as("previous_secret_ref exists exactly once").isEqualTo(1);
            }
            try (ResultSet rs = st.executeQuery("""
                    SELECT EXISTS (
                        SELECT 1 FROM pg_partitioned_table pt
                        JOIN pg_class c ON c.oid = pt.partrelid
                        WHERE c.relname = 'iam_login_attempts')""")) {
                rs.next();
                assertThat(rs.getBoolean(1)).as("iam_login_attempts is still partitioned").isTrue();
            }
            // Partitioned parents (iam_login_attempts, msg_dispatch_jobs) also carry
            // an inherited copy of the constraint under the same name on every
            // partition, so these are checked on the PARENT relation specifically
            // (conrelid), not by name alone across the whole catalog.
            try (ResultSet rs = st.executeQuery("""
                    SELECT
                        (SELECT count(*) FROM pg_constraint
                          WHERE conname = 'chk_iam_login_attempts_outcome'
                            AND conrelid = 'public.iam_login_attempts'::regclass),
                        (SELECT count(*) FROM pg_constraint
                          WHERE conname = 'chk_oauth_clients_client_type'
                            AND conrelid = 'public.oauth_clients'::regclass),
                        (SELECT count(*) FROM pg_constraint
                          WHERE conname = 'chk_msg_dispatch_jobs_status'
                            AND conrelid = 'public.msg_dispatch_jobs'::regclass),
                        (SELECT count(*) FROM pg_constraint
                          WHERE conname = 'chk_msg_dispatch_jobs_kind'
                            AND conrelid = 'public.msg_dispatch_jobs'::regclass)""")) {
                rs.next();
                assertThat(rs.getInt(1)).as("chk_iam_login_attempts_outcome on the parent").isEqualTo(1);
                assertThat(rs.getInt(2)).as("chk_oauth_clients_client_type").isEqualTo(1);
                assertThat(rs.getInt(3)).as("chk_msg_dispatch_jobs_status on the parent").isEqualTo(1);
                assertThat(rs.getInt(4)).as("chk_msg_dispatch_jobs_kind on the parent").isEqualTo(1);
            }
        }
        // Schema untouched (flyway_schema_history is ignored by the fingerprint).
        assertThat(SchemaFingerprint.compute(ds)).isEqualTo(before);

        // And a second run is still a no-op.
        assertThat(Migrator.migrate(ds).migrationsExecuted).isZero();
    }
}
