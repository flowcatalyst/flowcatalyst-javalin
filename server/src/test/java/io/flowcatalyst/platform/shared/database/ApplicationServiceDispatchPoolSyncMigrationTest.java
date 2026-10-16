package io.flowcatalyst.platform.shared.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import io.flowcatalyst.testpg.TestPg;

/// `V30__dispatch_pool_application_owner.sql` and
/// `V31__application_service_dispatch_pool_sync.sql` (Go F19, migrations 078 and 079):
/// pools gain a nullable, un-keyed `application_id`; the built-in application-service
/// role gains the dispatch-pool sync permission and no other role does.
class ApplicationServiceDispatchPoolSyncMigrationTest {

    private static final String PERMISSION = "platform:messaging:dispatch-pool:sync";

    private static Flyway flywayTo(DataSource ds, String target) {
        return Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .table(Migrator.HISTORY_TABLE)
                .baselineOnMigrate(true)
                .baselineVersion(Migrator.BASELINE_VERSION)
                .baselineDescription("Go schema (goose 001-045)")
                .validateOnMigrate(true)
                .outOfOrder(false)
                .target(target)
                .load();
    }

    private static int grants(Statement st, String roleName) throws Exception {
        try (ResultSet rs = st.executeQuery("""
                SELECT count(*) FROM iam_role_permissions p JOIN iam_roles r ON r.id = p.role_id
                 WHERE r.name = '%s' AND p.permission = '%s'""".formatted(roleName, PERMISSION))) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void v31GrantsTheExistingApplicationServiceRoleTheSyncPermissionOnceAndNoOtherRole() throws Exception {
        DataSource ds = TestPg.newDatabase("v31_pool_sync_grant");
        flywayTo(ds, "30").migrate();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("""
                    INSERT INTO iam_roles (id, name, display_name) VALUES
                      ('rol_v31_svc', 'platform:application-service', 'App service'),
                      ('rol_v31_oth', 'platform:other', 'Other')""");
            assertThat(grants(st, "platform:application-service")).isZero();
        }

        flywayTo(ds, "31").migrate();

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            assertThat(grants(st, "platform:application-service")).isEqualTo(1);
            assertThat(grants(st, "platform:other")).as("no other role gets it").isZero();
            // Idempotent: Go's 079 (same statement) on the same database changes nothing.
            st.execute("""
                    INSERT INTO iam_role_permissions (role_id, permission)
                    SELECT r.id, '%s' FROM iam_roles r WHERE r.name = 'platform:application-service'
                    ON CONFLICT DO NOTHING""".formatted(PERMISSION));
            assertThat(grants(st, "platform:application-service")).isEqualTo(1);
        }
    }

    @Test
    void v30AddsANullableUnkeyedOwnerColumnAndItsIndexAndLeavesExistingPoolsUnowned() throws Exception {
        DataSource ds = TestPg.newDatabase("v30_pool_owner");
        flywayTo(ds, "29").migrate();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO msg_dispatch_pools (id, code, name) VALUES ('dpl_v30_old', 'v30-old', 'old')");
        }

        flywayTo(ds, "30").migrate();

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT application_id FROM msg_dispatch_pools WHERE id = 'dpl_v30_old'")) {
                rs.next();
                assertThat(rs.getString(1)).as("existing pools have no owner").isNull();
            }
            try (ResultSet rs = st.executeQuery("""
                    SELECT is_nullable, character_maximum_length FROM information_schema.columns
                     WHERE table_name = 'msg_dispatch_pools' AND column_name = 'application_id'""")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("YES");
                assertThat(rs.getInt(2)).isEqualTo(17);
            }
            try (ResultSet rs = st.executeQuery("""
                    SELECT count(*) FROM pg_indexes
                     WHERE tablename = 'msg_dispatch_pools' AND indexname = 'idx_msg_dispatch_pools_application_id'""")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
            try (ResultSet rs = st.executeQuery("""
                    SELECT count(*) FROM pg_constraint
                     WHERE conrelid = 'public.msg_dispatch_pools'::regclass AND contype = 'f'""")) {
                rs.next();
                assertThat(rs.getInt(1)).as("no foreign key").isZero();
            }
            // Idempotent: Go's 078 (same statements) on the same database is harmless.
            st.execute("ALTER TABLE msg_dispatch_pools ADD COLUMN IF NOT EXISTS application_id VARCHAR(17)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_msg_dispatch_pools_application_id ON msg_dispatch_pools (application_id)");
        }
    }
}
