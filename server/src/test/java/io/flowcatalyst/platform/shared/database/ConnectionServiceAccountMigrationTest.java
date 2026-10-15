package io.flowcatalyst.platform.shared.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import io.flowcatalyst.testpg.TestPg;

/// `V29__connection_service_account_principal_ids.sql` (Go F17, migration 075):
/// connections an older code-first sync wrote hold the application's PRINCIPAL
/// id (`prn_…`) in `service_account_id`; the migration re-points them at the
/// service account (`sac_…`) behind it and touches nothing else.
class ConnectionServiceAccountMigrationTest {

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

    private static String serviceAccountOf(Statement st, String connectionId) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT service_account_id FROM msg_connections WHERE id = '" + connectionId + "'")) {
            rs.next();
            return rs.getString(1);
        }
    }

    @Test
    void v29RepointsPrincipalIdsAtTheirServiceAccountAndLeavesEverythingElse() throws Exception {
        DataSource ds = TestPg.newDatabase("v29_connection_sa");
        flywayTo(ds, "28").migrate();

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO iam_service_accounts (id, code, name) VALUES ('sac_v29_a', 'v29-a', 'a')");
            st.execute("""
                    INSERT INTO iam_principals (id, type, name, active, service_account_id, created_at, updated_at)
                    VALUES ('prn_v29_a', 'SERVICE', 'a', true, 'sac_v29_a', now(), now()),
                           ('prn_v29_bare', 'SERVICE', 'bare', true, NULL, now(), now())""");
            st.execute("""
                    INSERT INTO msg_connections (id, code, name, service_account_id) VALUES
                      ('con_v29_stale', 'stale', 'stale', 'prn_v29_a'),
                      ('con_v29_ok',    'ok',    'ok',    'sac_v29_a'),
                      ('con_v29_bare',  'bare',  'bare',  'prn_v29_bare'),
                      ('con_v29_other', 'other', 'other', 'sva_unrelated')""");
        }

        flywayTo(ds, "29").migrate();

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            assertThat(serviceAccountOf(st, "con_v29_stale")).as("principal id re-pointed at the account").isEqualTo("sac_v29_a");
            assertThat(serviceAccountOf(st, "con_v29_ok")).as("already correct: untouched").isEqualTo("sac_v29_a");
            assertThat(serviceAccountOf(st, "con_v29_bare")).as("principal with no account: left for the sync to refuse")
                    .isEqualTo("prn_v29_bare");
            assertThat(serviceAccountOf(st, "con_v29_other")).as("unrelated value: untouched").isEqualTo("sva_unrelated");
        }

        // Idempotent: a second run (Go's 075 on the same database) changes nothing.
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("""
                    UPDATE msg_connections c SET service_account_id = p.service_account_id
                      FROM iam_principals p WHERE c.service_account_id = p.id AND p.service_account_id IS NOT NULL""");
            assertThat(serviceAccountOf(st, "con_v29_stale")).isEqualTo("sac_v29_a");
        }
    }
}
