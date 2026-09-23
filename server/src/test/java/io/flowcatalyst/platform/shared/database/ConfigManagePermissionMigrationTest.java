package io.flowcatalyst.platform.shared.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import io.flowcatalyst.testpg.TestPg;

/// `V17__config_manage_permission.sql` (`docs/spec/config-permissions.md`
/// §A.4, spec test 4): every `iam_role_permissions` row holding the old
/// `platform:admin:config:update` code is renamed to
/// `platform:admin:config:manage` — including a custom role, not just the
/// seeded `platform:admin` — and a role that already (somehow) holds both
/// codes ends the migration with exactly one `manage` row rather than a
/// primary-key collision.
class ConfigManagePermissionMigrationTest {

    private static final String OLD = "platform:admin:config:update";
    private static final String NEW = "platform:admin:config:manage";

    /// A [Migrator]-equivalent Flyway build stopped at `target`, so a test can
    /// insert rows BEFORE V17 runs.
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

    private static void insertRole(Connection c, String id) throws Exception {
        try (Statement st = c.createStatement()) {
            st.execute("""
                    INSERT INTO iam_roles (id, name, display_name)
                    VALUES ('%s', '%s', '%s')""".formatted(id, id, id));
        }
    }

    private static void insertPermission(Connection c, String roleId, String permission) throws Exception {
        try (Statement st = c.createStatement()) {
            st.execute("INSERT INTO iam_role_permissions (role_id, permission) VALUES ('%s', '%s')"
                    .formatted(roleId, permission));
        }
    }

    private static List<String> permissionsOf(Connection c, String roleId) throws Exception {
        var out = new ArrayList<String>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT permission FROM iam_role_permissions WHERE role_id = '" + roleId + "'")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    /// The plain rename: a custom role holding only the old code holds only
    /// the new one after V17 — proves the migration is not scoped to the
    /// seeded `platform:admin` row alone.
    @Test
    void aCustomRoleHoldingTheOldCodeHoldsOnlyTheNewCodeAfterV17() throws Exception {
        DataSource ds = TestPg.newDatabase("v17_rename");
        flywayTo(ds, "16").migrate();
        String roleId = "rol_v17rename01";
        try (Connection c = ds.getConnection()) {
            insertRole(c, roleId);
            insertPermission(c, roleId, OLD);
            insertPermission(c, roleId, "platform:admin:config:view");
        }

        Migrator.migrate(ds);

        try (Connection c = ds.getConnection()) {
            List<String> perms = permissionsOf(c, roleId);
            assertThat(perms).as("mutant: rename skipped, or the wrong permission renamed")
                    .containsExactlyInAnyOrder(NEW, "platform:admin:config:view");
            assertThat(perms).as("mutant: rename left the old code behind too").doesNotContain(OLD);
        }
    }

    /// The guard: a role that already holds both codes ends up with exactly
    /// one `manage` row, not a primary-key collision and not two rows.
    /// Mutant: dropping the DELETE guard and running the UPDATE straight
    /// would throw on the (role_id, permission) primary key instead.
    @Test
    void aRoleAlreadyHoldingBothCodesEndsWithExactlyOneManageRowAndNoCollision() throws Exception {
        DataSource ds = TestPg.newDatabase("v17_dup_guard");
        flywayTo(ds, "16").migrate();
        String roleId = "rol_v17dupguard1";
        try (Connection c = ds.getConnection()) {
            insertRole(c, roleId);
            insertPermission(c, roleId, OLD);
            insertPermission(c, roleId, NEW);
        }

        Migrator.migrate(ds);

        try (Connection c = ds.getConnection()) {
            List<String> perms = permissionsOf(c, roleId);
            assertThat(perms).as("mutant: the guard removed, or the duplicate not collapsed").containsExactly(NEW);
        }
    }
}
