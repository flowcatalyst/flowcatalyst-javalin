package io.flowcatalyst.platform.shared.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import io.flowcatalyst.testpg.TestPg;

/// `V32__normalise_role_assignment_names.sql` and `V33__backfill_role_application_id.sql`
/// (Go F18, migrations 076 and 077): the two data fixes, each run by Flyway and then
/// twice more by hand on the same scratch database, which must change nothing.
class RoleDataFixMigrationsTest {

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

    private static String sql(String file) throws Exception {
        try (var in = RoleDataFixMigrationsTest.class.getResourceAsStream("/db/migration/" + file)) {
            assertThat(in).as(file).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /// V32: whitespace in an assignment's role name is rewritten under the normalised
    /// name only when that names an existing role; everything else is left alone.
    @Test
    void v32NormalisesOnlyUnambiguousRoleAssignmentsAndIsIdempotent() throws Exception {
        DataSource ds = TestPg.newDatabase("v32_normalise_assignments");
        flywayTo(ds, "31").migrate();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("""
                    INSERT INTO iam_roles (id, name, display_name, application_code) VALUES
                      ('rol_v32real0001', 'example:dispatcher', 'D', 'example'),
                      ('rol_v32real0002', 'example:viewer', 'V', 'example')""");
            for (String id : List.of("prn_v32aaaaaa1", "prn_v32aaaaaa2", "prn_v32aaaaaa3")) {
                st.execute("INSERT INTO iam_principals (id, type, name) VALUES ('%s', 'SERVICE', '%s')".formatted(id, id));
            }
            insert(st, "prn_v32aaaaaa1", "example: dispatcher", "ADMIN_ASSIGNED"); // fixable
            insert(st, "prn_v32aaaaaa2", "example: dispatcher", "ADMIN_ASSIGNED"); // fixable, already holds the real one
            insert(st, "prn_v32aaaaaa2", "example:dispatcher", "SDK_SYNC");
            insert(st, "prn_v32aaaaaa3", " example :viewer ", "ADMIN_ASSIGNED");   // fixable (both ends)
            insert(st, "prn_v32aaaaaa3", "example: nosuchrole", "ADMIN_ASSIGNED"); // normalised role does not exist: left
            insert(st, "prn_v32aaaaaa3", "exa mple:viewer", "ADMIN_ASSIGNED");     // whitespace inside a segment: left
        }

        flywayTo(ds, "32").migrate();
        String v32 = sql("V32__normalise_role_assignment_names.sql");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            List<String> afterFlyway = assignments(st);
            st.execute(v32);
            st.execute(v32); // idempotent
            assertThat(assignments(st)).isEqualTo(afterFlyway).containsExactly(
                    "prn_v32aaaaaa1|example:dispatcher|ADMIN_ASSIGNED",
                    "prn_v32aaaaaa2|example:dispatcher|SDK_SYNC",
                    "prn_v32aaaaaa3|exa mple:viewer|ADMIN_ASSIGNED",
                    "prn_v32aaaaaa3|example: nosuchrole|ADMIN_ASSIGNED",
                    "prn_v32aaaaaa3|example:viewer|ADMIN_ASSIGNED");
        }
    }

    /// V33: application_id is set from the application whose code matches
    /// application_code, only where it is NULL; nothing is guessed.
    @Test
    void v33BackfillsRoleApplicationIdOnlyWhereNullAndMatchedAndIsIdempotent() throws Exception {
        DataSource ds = TestPg.newDatabase("v33_backfill_role_app_id");
        flywayTo(ds, "32").migrate();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("""
                    INSERT INTO app_applications (id, code, name) VALUES
                      ('app_v33aaaaaa1', 'v33a', 'A'), ('app_v33aaaaaa2', 'platform', 'P')
                    ON CONFLICT (code) DO NOTHING""");
            st.execute("""
                    INSERT INTO iam_roles (id, application_id, name, display_name, application_code) VALUES
                      ('rol_v33r000001', NULL, 'v33a:one', 'one', 'v33a'),
                      ('rol_v33r000002', NULL, 'platform:admin', 'admin', 'platform'),
                      ('rol_v33r000003', NULL, 'ghost:one', 'ghost', 'ghost'),
                      ('rol_v33r000004', NULL, 'v33a:nocode', 'nocode', NULL),
                      ('rol_v33r000005', 'app_keepexist01', 'v33a:keep', 'keep', 'v33a')""");
        }

        flywayTo(ds, "33").migrate();
        String v33 = sql("V33__backfill_role_application_id.sql");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(v33);
            st.execute(v33); // idempotent
            String platformId = scalar(st, "SELECT id FROM app_applications WHERE code = 'platform'");
            assertThat(scalar(st, "SELECT application_id FROM iam_roles WHERE name = 'v33a:one'")).isEqualTo("app_v33aaaaaa1");
            assertThat(scalar(st, "SELECT application_id FROM iam_roles WHERE name = 'platform:admin'")).isEqualTo(platformId);
            assertThat(scalar(st, "SELECT application_id FROM iam_roles WHERE name = 'ghost:one'")).as("no such application: left NULL").isNull();
            assertThat(scalar(st, "SELECT application_id FROM iam_roles WHERE name = 'v33a:nocode'")).as("no application_code: nothing guessed").isNull();
            assertThat(scalar(st, "SELECT application_id FROM iam_roles WHERE name = 'v33a:keep'")).as("an existing id is never overwritten").isEqualTo("app_keepexist01");
        }
    }

    private static void insert(Statement st, String principal, String role, String source) throws Exception {
        st.execute("INSERT INTO iam_principal_roles (principal_id, role_name, assignment_source) VALUES ('%s', '%s', '%s')"
                .formatted(principal, role, source));
    }

    private static List<String> assignments(Statement st) throws Exception {
        var out = new ArrayList<String>();
        try (ResultSet rs = st.executeQuery("SELECT principal_id, role_name, assignment_source FROM iam_principal_roles ORDER BY 1, 2")) {
            while (rs.next()) out.add(rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3));
        }
        return out;
    }

    private static String scalar(Statement st, String query) throws Exception {
        try (ResultSet rs = st.executeQuery(query)) {
            assertThat(rs.next()).as(query).isTrue();
            return rs.getString(1);
        }
    }
}
