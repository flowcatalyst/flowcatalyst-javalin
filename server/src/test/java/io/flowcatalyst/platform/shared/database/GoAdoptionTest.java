package io.flowcatalyst.platform.shared.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

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
        // rows goose would have written for the migrations up to 057 (a Go
        // database at HEAD, including 054_dispatch_job_queue.sql — the same
        // column V10 adds — 056_connection_application_scope.sql — the
        // same schema change V12 adds — and
        // 057_dispatch_job_descriptor_and_read_metadata.sql — the same
        // schema change V14 adds). There is no 023 or 050 in
        // flowcatalyst-go/internal/migrate/sql.
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO public.goose_db_version (version_id, is_applied) VALUES (0, true)");
            for (int v = 1; v <= 57; v++) {
                if (v == 23 || v == 50) {
                    continue;
                }
                st.execute("INSERT INTO public.goose_db_version (version_id, is_applied) VALUES (" + v + ", true)");
            }
        }
        String before = SchemaFingerprint.compute(ds);

        MigrateResult result = Migrator.migrate(ds);
        assertThat(result.success).isTrue();
        // Flyway baselines at V1 (not executed) then MUST apply V2..V14 even
        // though the Go database already has V2..V7, V9's effect (053
        // portal_apps, spec `portal-apps.md`) AND V10's effect (054
        // dispatch_job_queue, spec `dispatch-job-priority.md`): each of those
        // is idempotent (IF NOT EXISTS / pg_constraint guards) and a no-op
        // here. Two are genuine additions, created here for the first time:
        // V8 (`mail_outbox`, spec `mail-outbox.md`, Go mirror item G9) and V13
        // (the ten `fn_` function-registry tables, spec
        // `function-registry.md` §2 — there is no Go for the function service
        // at all, spec §0).
        // V11 (Go 055, seeded schema versions v1 -> 1.0) is data-only and
        // changes no schema; V12 (Go 056, connection application scope, spec
        // `code-first-connections.md`) and V14 (Go 057, dispatch-job
        // descriptor/read-metadata/request_info, catch-up-2026-09-22.md) are
        // each idempotent and a no-op on this goose-57 database.
        assertThat(result.migrationsExecuted).isEqualTo(13);
        assertThat(result.migrations).extracting(m -> m.version)
                .containsExactly("2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14");

        MigrationInfo[] applied = Migrator.flyway(ds).info().applied();
        assertThat(applied).hasSize(14);
        assertThat(applied[0].getVersion().getVersion()).isEqualTo("1");
        assertThat(applied[0].getState()).isEqualTo(MigrationState.BASELINE);
        for (int i = 1; i < applied.length; i++) {
            assertThat(applied[i].getState()).isEqualTo(MigrationState.SUCCESS);
        }
        assertThat(Migrator.flyway(ds).info().pending()).isEmpty();

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT count(*), max(version_id) FROM public.goose_db_version")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(56);
                assertThat(rs.getInt(2)).isEqualTo(57);
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT type, version, success FROM public.flyway_schema_history ORDER BY installed_rank")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("BASELINE");
                assertThat(rs.getString(2)).isEqualTo("1");
                assertThat(rs.getBoolean(3)).isTrue();
                for (int v = 2; v <= 14; v++) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(2)).isEqualTo(String.valueOf(v));
                    assertThat(rs.getBoolean(3)).isTrue();
                }
                assertThat(rs.next()).isFalse();
            }
            // V8 (`mail_outbox`) is genuinely new on a Go-HEAD database (Go mirror
            // item G9): unlike V2..V7 it is not a no-op, it creates the table.
            try (ResultSet rs = st.executeQuery("""
                    SELECT count(*) FROM information_schema.tables
                    WHERE table_schema = 'public' AND table_name = 'mail_outbox'""")) {
                rs.next();
                assertThat(rs.getInt(1)).as("mail_outbox created exactly once").isEqualTo(1);
            }
            // V13's ten fn_ tables are genuinely new here too (Java-only,
            // spec `function-registry.md` §0/§2 and `function-invocation.md`
            // §3/§4: there is no Go for the function service at all).
            try (ResultSet rs = st.executeQuery("""
                    SELECT table_name FROM information_schema.tables
                    WHERE table_schema = 'public' AND table_name LIKE 'fn\\_%' ESCAPE '\\'
                    ORDER BY table_name""")) {
                List<String> fnTables = new java.util.ArrayList<>();
                while (rs.next()) {
                    fnTables.add(rs.getString(1));
                }
                assertThat(fnTables).as("V13 creates each fn_ table exactly once").containsExactly(
                        "fn_aliases", "fn_client_policies", "fn_config", "fn_domains", "fn_functions", "fn_hosts",
                        "fn_routes", "fn_secrets", "fn_trigger_objects", "fn_versions");
            }
            // V2..V7, V9 and V10 are no-ops on a Go-HEAD database: the schema they
            // add is already there exactly once, not duplicated or altered.
            try (ResultSet rs = st.executeQuery("""
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'oauth_clients'
                      AND column_name = 'previous_secret_ref'""")) {
                rs.next();
                assertThat(rs.getInt(1)).as("previous_secret_ref exists exactly once").isEqualTo(1);
            }
            try (ResultSet rs = st.executeQuery("""
                    SELECT count(*) FROM information_schema.tables
                    WHERE table_schema = 'public' AND table_name = 'portal_apps'""")) {
                rs.next();
                assertThat(rs.getInt(1)).as("V9 (portal_apps) is a no-op: the table already exists exactly once").isEqualTo(1);
            }
            try (ResultSet rs = st.executeQuery("""
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'oauth_clients'
                      AND column_name = 'portal_app_id'""")) {
                rs.next();
                assertThat(rs.getInt(1)).as("oauth_clients.portal_app_id exists exactly once").isEqualTo(1);
            }
            try (ResultSet rs = st.executeQuery("""
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'msg_dispatch_jobs'
                      AND column_name = 'queue'""")) {
                rs.next();
                assertThat(rs.getInt(1)).as("V10 (msg_dispatch_jobs.queue) is a no-op: the column already exists exactly once").isEqualTo(1);
            }
            try (ResultSet rs = st.executeQuery("""
                    SELECT
                        (SELECT count(*) FROM information_schema.columns
                          WHERE table_schema = 'public' AND table_name = 'msg_connections' AND column_name = 'application_code'),
                        (SELECT count(*) FROM information_schema.columns
                          WHERE table_schema = 'public' AND table_name = 'msg_connections' AND column_name = 'source'),
                        (SELECT count(*) FROM pg_constraint
                          WHERE conname = 'chk_msg_connections_source' AND conrelid = 'public.msg_connections'::regclass),
                        (SELECT count(*) FROM pg_indexes
                          WHERE schemaname = 'public' AND tablename = 'msg_connections' AND indexname = 'uq_msg_connections_app_client_code'),
                        (SELECT count(*) FROM pg_indexes
                          WHERE schemaname = 'public' AND tablename = 'msg_subscriptions' AND indexname = 'uq_msg_subscriptions_app_client_code'),
                        (SELECT count(*) FROM pg_indexes
                          WHERE schemaname = 'public' AND tablename = 'msg_connections' AND indexname = 'idx_msg_connections_code_client'),
                        (SELECT count(*) FROM pg_indexes
                          WHERE schemaname = 'public' AND tablename = 'msg_subscriptions' AND indexname = 'idx_msg_subscriptions_code_client')""")) {
                rs.next();
                assertThat(rs.getInt(1)).as("V12 (msg_connections.application_code) is a no-op: the column already exists exactly once").isEqualTo(1);
                assertThat(rs.getInt(2)).as("V12 (msg_connections.source) is a no-op: the column already exists exactly once").isEqualTo(1);
                assertThat(rs.getInt(3)).as("chk_msg_connections_source exists exactly once").isEqualTo(1);
                assertThat(rs.getInt(4)).as("uq_msg_connections_app_client_code exists exactly once").isEqualTo(1);
                assertThat(rs.getInt(5)).as("uq_msg_subscriptions_app_client_code exists exactly once").isEqualTo(1);
                assertThat(rs.getInt(6)).as("the old idx_msg_connections_code_client index stays gone").isEqualTo(0);
                assertThat(rs.getInt(7)).as("the old idx_msg_subscriptions_code_client index stays gone").isEqualTo(0);
            }
            try (ResultSet rs = st.executeQuery("""
                    SELECT
                        (SELECT count(*) FROM information_schema.columns
                          WHERE table_schema = 'public' AND table_name = 'msg_dispatch_jobs' AND column_name = 'descriptor'),
                        (SELECT count(*) FROM information_schema.columns
                          WHERE table_schema = 'public' AND table_name = 'msg_dispatch_jobs_read' AND column_name = 'descriptor'),
                        (SELECT count(*) FROM information_schema.columns
                          WHERE table_schema = 'public' AND table_name = 'msg_dispatch_jobs_read' AND column_name = 'metadata'),
                        (SELECT count(*) FROM information_schema.columns
                          WHERE table_schema = 'public' AND table_name = 'msg_dispatch_job_attempts' AND column_name = 'request_info')""")) {
                rs.next();
                assertThat(rs.getInt(1)).as("V14 (msg_dispatch_jobs.descriptor) is a no-op: the column already exists exactly once").isEqualTo(1);
                assertThat(rs.getInt(2)).as("V14 (msg_dispatch_jobs_read.descriptor) is a no-op: the column already exists exactly once").isEqualTo(1);
                assertThat(rs.getInt(3)).as("V14 (msg_dispatch_jobs_read.metadata) is a no-op: the column already exists exactly once").isEqualTo(1);
                assertThat(rs.getInt(4)).as("V14 (msg_dispatch_job_attempts.request_info) is a no-op: the column already exists exactly once").isEqualTo(1);
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
        // V2..V7, V9, V10, V12 and V14 still change nothing (flyway_schema_history is
        // ignored by the fingerprint); V8 (`mail_outbox`) and V13 (the eight
        // fn_ tables) are the genuine additions — assert the only lines the
        // fingerprint gained are theirs, plus V13's one named, exact widening
        // of the Go-shared chk_msg_subscriptions_source constraint
        // (function-invocation.md §4.1) — asserted to the exact definition,
        // not blanket-ignored, and excluded from the "nothing else changed" check.
        java.util.Set<String> javaOnlyTables = java.util.Set.of(
                "mail_outbox", "fn_functions", "fn_versions", "fn_aliases", "fn_hosts", "fn_client_policies",
                "fn_domains", "fn_routes", "fn_trigger_objects", "fn_config", "fn_secrets");
        List<String> afterLines = SchemaFingerprint.compute(ds).lines().toList();
        List<String> javaOnlyTableLines = afterLines.stream()
                .filter(l -> l.split("\t", -1).length > 1 && javaOnlyTables.contains(l.split("\t", -1)[1]))
                .toList();

        List<String> afterDivergent = afterLines.stream()
                .filter(SchemaFingerprintTest::isDivergentConstraintLine).toList();
        assertThat(afterDivergent).as("exactly one divergent-constraint line").hasSize(1);
        assertThat(afterDivergent.getFirst())
                .as("V13 widens chk_msg_subscriptions_source to exactly the Java definition, nothing else")
                .endsWith(SchemaFingerprintTest.DIVERGENT_CONSTRAINT_JAVA_DEF);
        List<String> beforeDivergent = before.lines()
                .filter(SchemaFingerprintTest::isDivergentConstraintLine).toList();
        assertThat(beforeDivergent).as("Go's own (un-widened) definition, present before V13 runs").hasSize(1);
        assertThat(beforeDivergent.getFirst()).isNotEqualTo(afterDivergent.getFirst());

        List<String> afterWithoutNewLines = afterLines.stream()
                .filter(l -> !javaOnlyTableLines.contains(l))
                .filter(l -> !SchemaFingerprintTest.isDivergentConstraintLine(l))
                .toList();
        List<String> beforeWithoutDivergent = before.lines()
                .filter(l -> !SchemaFingerprintTest.isDivergentConstraintLine(l))
                .toList();
        assertThat(afterWithoutNewLines)
                .as("V2..V7, V9, V10, V12 and V14 change nothing beyond V8's/V13's new Java-only tables and the one named divergent constraint")
                .containsExactlyInAnyOrderElementsOf(beforeWithoutDivergent);
        assertThat(javaOnlyTableLines).as("V8 adds mail_outbox and V13 adds the fn_ tables").isNotEmpty();

        // And a second run is still a no-op.
        assertThat(Migrator.migrate(ds).migrationsExecuted).isZero();
    }

    /// A Go database at goose 55 (one migration behind 056) has genuine work
    /// for V12: the fixture (`go-schema.sql`) is captured at goose 56, so this
    /// test synthesises 55 by undoing 056's Up exactly as its own Down section
    /// specifies (Go mirror: `internal/migrate/sql/056_connection_application_scope.sql`)
    /// before baselining — spec `code-first-connections.md` §1, C3.
    @Test
    void goDatabaseAtGoose55CompletesV12() throws Exception {
        DataSource ds = TestPg.newDatabase("go_adoption_55");
        GoSchema.load(ds);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP INDEX IF EXISTS uq_msg_subscriptions_app_client_code");
            st.execute("DROP INDEX IF EXISTS uq_msg_connections_app_client_code");
            st.execute("CREATE UNIQUE INDEX idx_msg_subscriptions_code_client ON msg_subscriptions (code, client_id)");
            st.execute("CREATE UNIQUE INDEX idx_msg_connections_code_client ON msg_connections (code, client_id)");
            st.execute("ALTER TABLE msg_connections DROP CONSTRAINT IF EXISTS chk_msg_connections_source");
            st.execute("ALTER TABLE msg_connections DROP COLUMN IF EXISTS source");
            st.execute("ALTER TABLE msg_connections DROP COLUMN IF EXISTS application_code");

            st.execute("INSERT INTO public.goose_db_version (version_id, is_applied) VALUES (0, true)");
            for (int v = 1; v <= 55; v++) {
                if (v == 23 || v == 50) {
                    continue;
                }
                st.execute("INSERT INTO public.goose_db_version (version_id, is_applied) VALUES (" + v + ", true)");
            }
        }

        MigrateResult result = Migrator.migrate(ds);
        assertThat(result.success).isTrue();
        assertThat(result.migrations).extracting(m -> m.version)
                .as("V12 is among the applied migrations")
                .contains("12");

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("""
                    SELECT
                        (SELECT count(*) FROM information_schema.columns
                          WHERE table_schema = 'public' AND table_name = 'msg_connections' AND column_name = 'application_code'),
                        (SELECT count(*) FROM information_schema.columns
                          WHERE table_schema = 'public' AND table_name = 'msg_connections' AND column_name = 'source'),
                        (SELECT count(*) FROM pg_constraint
                          WHERE conname = 'chk_msg_connections_source' AND conrelid = 'public.msg_connections'::regclass),
                        (SELECT count(*) FROM pg_indexes
                          WHERE schemaname = 'public' AND tablename = 'msg_connections' AND indexname = 'uq_msg_connections_app_client_code'),
                        (SELECT count(*) FROM pg_indexes
                          WHERE schemaname = 'public' AND tablename = 'msg_subscriptions' AND indexname = 'uq_msg_subscriptions_app_client_code'),
                        (SELECT count(*) FROM pg_indexes
                          WHERE schemaname = 'public' AND tablename = 'msg_connections' AND indexname = 'idx_msg_connections_code_client'),
                        (SELECT count(*) FROM pg_indexes
                          WHERE schemaname = 'public' AND tablename = 'msg_subscriptions' AND indexname = 'idx_msg_subscriptions_code_client')""")) {
                rs.next();
                assertThat(rs.getInt(1)).as("V12 adds msg_connections.application_code").isEqualTo(1);
                assertThat(rs.getInt(2)).as("V12 adds msg_connections.source").isEqualTo(1);
                assertThat(rs.getInt(3)).as("V12 adds chk_msg_connections_source").isEqualTo(1);
                assertThat(rs.getInt(4)).as("V12 adds uq_msg_connections_app_client_code").isEqualTo(1);
                assertThat(rs.getInt(5)).as("V12 adds uq_msg_subscriptions_app_client_code").isEqualTo(1);
                assertThat(rs.getInt(6)).as("V12 drops the old idx_msg_connections_code_client").isEqualTo(0);
                assertThat(rs.getInt(7)).as("V12 drops the old idx_msg_subscriptions_code_client").isEqualTo(0);
            }
        }

        assertThat(Migrator.migrate(ds).migrationsExecuted).isZero();
    }

    /// A Go database at goose 56 (one migration behind 057) has genuine work
    /// for V14: the fixture (`go-schema.sql`) is captured at goose 57, so this
    /// test synthesises 56 by undoing 057's Up exactly as its own Down section
    /// specifies (Go mirror: `internal/migrate/sql/057_dispatch_job_descriptor_and_read_metadata.sql`)
    /// before baselining — catch-up-2026-09-22.md slice C1, T14.
    @Test
    void goDatabaseAtGoose56CompletesV14() throws Exception {
        DataSource ds = TestPg.newDatabase("go_adoption_56");
        GoSchema.load(ds);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("ALTER TABLE msg_dispatch_job_attempts DROP COLUMN IF EXISTS request_info");
            st.execute("ALTER TABLE msg_dispatch_jobs_read DROP COLUMN IF EXISTS metadata");
            st.execute("ALTER TABLE msg_dispatch_jobs_read DROP COLUMN IF EXISTS descriptor");
            st.execute("ALTER TABLE msg_dispatch_jobs DROP COLUMN IF EXISTS descriptor");

            st.execute("INSERT INTO public.goose_db_version (version_id, is_applied) VALUES (0, true)");
            for (int v = 1; v <= 56; v++) {
                if (v == 23 || v == 50) {
                    continue;
                }
                st.execute("INSERT INTO public.goose_db_version (version_id, is_applied) VALUES (" + v + ", true)");
            }
        }

        MigrateResult result = Migrator.migrate(ds);
        assertThat(result.success).isTrue();
        assertThat(result.migrations).extracting(m -> m.version)
                .as("V14 is among the applied migrations")
                .contains("14");

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("""
                    SELECT
                        (SELECT count(*) FROM information_schema.columns
                          WHERE table_schema = 'public' AND table_name = 'msg_dispatch_jobs' AND column_name = 'descriptor'),
                        (SELECT count(*) FROM information_schema.columns
                          WHERE table_schema = 'public' AND table_name = 'msg_dispatch_jobs_read' AND column_name = 'descriptor'),
                        (SELECT count(*) FROM information_schema.columns
                          WHERE table_schema = 'public' AND table_name = 'msg_dispatch_jobs_read' AND column_name = 'metadata'),
                        (SELECT count(*) FROM information_schema.columns
                          WHERE table_schema = 'public' AND table_name = 'msg_dispatch_job_attempts' AND column_name = 'request_info')""")) {
                rs.next();
                assertThat(rs.getInt(1)).as("V14 adds msg_dispatch_jobs.descriptor").isEqualTo(1);
                assertThat(rs.getInt(2)).as("V14 adds msg_dispatch_jobs_read.descriptor").isEqualTo(1);
                assertThat(rs.getInt(3)).as("V14 adds msg_dispatch_jobs_read.metadata").isEqualTo(1);
                assertThat(rs.getInt(4)).as("V14 adds msg_dispatch_job_attempts.request_info").isEqualTo(1);
            }
        }

        assertThat(Migrator.migrate(ds).migrationsExecuted).isZero();
    }
}
