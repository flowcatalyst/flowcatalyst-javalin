package io.flowcatalyst.platform.shared.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import javax.sql.DataSource;

import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.flowcatalyst.testpg.TestPg;

class MigratorTest {

    static final List<String> PARTITIONED_PARENTS = List.of(
            "msg_events",
            "msg_events_read",
            "msg_dispatch_jobs",
            "msg_dispatch_jobs_read",
            "msg_dispatch_job_attempts",
            "msg_scheduled_job_instances",
            "msg_scheduled_job_instance_logs");

    static DataSource ds;
    static MigrateResult first;

    @BeforeAll
    static void migrateFreshDatabase() {
        ds = TestPg.newDatabase("migrator_test");
        first = Migrator.migrate(ds);
    }

    @Test
    void freshDatabaseAppliesV1() {
        assertThat(first.success).isTrue();
        assertThat(first.migrationsExecuted).isEqualTo(19);
        assertThat(first.migrations).extracting(m -> m.version)
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19");

        MigrationInfo[] applied = Migrator.flyway(ds).info().applied();
        assertThat(applied).hasSize(19);
        assertThat(applied[applied.length - 1].getVersion().getVersion()).isEqualTo("19");
        assertThat(applied[applied.length - 1].getState()).isEqualTo(MigrationState.SUCCESS);
        assertThat(Migrator.flyway(ds).info().pending()).isEmpty();
    }

    @Test
    void secondMigrateIsNoOp() {
        MigrateResult second = Migrator.migrate(ds);
        assertThat(second.success).isTrue();
        assertThat(second.migrationsExecuted).isZero();
        assertThat(Migrator.flyway(ds).info().applied()).hasSize(19);
    }

    @Test
    void partitionedParentsExistWithInitialPartitions() throws SQLException {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            for (String parent : PARTITIONED_PARENTS) {
                try (ResultSet rs = st.executeQuery("""
                        SELECT p.relkind::text, count(i.inhrelid)
                        FROM pg_class p
                        LEFT JOIN pg_inherits i ON i.inhparent = p.oid
                        WHERE p.relname = '%s' AND p.relnamespace = 'public'::regnamespace
                        GROUP BY p.relkind""".formatted(parent))) {
                    assertThat(rs.next()).as("parent %s exists", parent).isTrue();
                    assertThat(rs.getString(1)).as("%s is partitioned", parent).isEqualTo("p");
                    assertThat(rs.getInt(2)).as("%s child partitions", parent).isGreaterThanOrEqualTo(5);
                }
                // Every partition follows Go's <parent>_YYYY_MM naming and every
                // parent index is valid (partitions were created after the indexes).
                try (ResultSet rs = st.executeQuery("""
                        SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
                        WHERE i.inhparent = 'public.%s'::regclass""".formatted(parent))) {
                    while (rs.next()) {
                        assertThat(rs.getString(1)).matches(parent + "_\\d{4}_\\d{2}");
                    }
                }
                try (ResultSet rs = st.executeQuery("""
                        SELECT count(*) FROM pg_index x JOIN pg_class ic ON ic.oid = x.indexrelid
                        WHERE x.indrelid = 'public.%s'::regclass AND NOT x.indisvalid""".formatted(parent))) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("invalid indexes on %s", parent).isZero();
                }
            }
        }
    }

    @Test
    void baselineDoesNotCreateGooseTable() throws SQLException {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT to_regclass('public.goose_db_version') IS NULL")) {
            rs.next();
            assertThat(rs.getBoolean(1)).isTrue();
        }
    }
}
