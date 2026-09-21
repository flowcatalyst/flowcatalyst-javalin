package io.flowcatalyst.platform.shared.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;

import io.flowcatalyst.testpg.TestPg;

/// `V12__connection_application_scope.sql` (spec `code-first-connections.md`
/// §1, tests C1/C2): moves connection/subscription code uniqueness from
/// per-client to per-application-and-client, `NULL` a real key value.
class ConnectionApplicationScopeMigrationTest {

    /// A [Migrator]-equivalent Flyway build stopped at `target`, for tests
    /// that need to insert rows BEFORE V12 runs (spec §5 build rules: migrate
    /// to V11 first, insert the colliding rows, then migrate fully).
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

    // ── C1: fresh database ──────────────────────────────────────────────────

    @Test
    void v12DropsTheOldIndexesAndCreatesTheNewOnesOnAFreshDatabase() throws Exception {
        DataSource ds = TestPg.newDatabase("v12_fresh_indexes");
        Migrator.migrate(ds);

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("""
                    SELECT
                        (SELECT count(*) FROM pg_indexes WHERE schemaname = 'public'
                          AND tablename = 'msg_connections' AND indexname = 'idx_msg_connections_code_client'),
                        (SELECT count(*) FROM pg_indexes WHERE schemaname = 'public'
                          AND tablename = 'msg_subscriptions' AND indexname = 'idx_msg_subscriptions_code_client'),
                        (SELECT count(*) FROM pg_indexes WHERE schemaname = 'public'
                          AND tablename = 'msg_connections' AND indexname = 'uq_msg_connections_app_client_code'),
                        (SELECT count(*) FROM pg_indexes WHERE schemaname = 'public'
                          AND tablename = 'msg_subscriptions' AND indexname = 'uq_msg_subscriptions_app_client_code')""")) {
                rs.next();
                assertThat(rs.getInt(1)).as("old idx_msg_connections_code_client is gone").isZero();
                assertThat(rs.getInt(2)).as("old idx_msg_subscriptions_code_client is gone").isZero();
                assertThat(rs.getInt(3)).as("uq_msg_connections_app_client_code exists").isEqualTo(1);
                assertThat(rs.getInt(4)).as("uq_msg_subscriptions_app_client_code exists").isEqualTo(1);
            }
        }
    }

    /// The behavioural pin for the new key: two connection rows with
    /// `(application_code=NULL, client_id=NULL, code='x')` did NOT collide
    /// under the old `(code, client_id)` index (Postgres treats NULLs as
    /// distinct) — they DO collide now. Kills "omit a COALESCE" (which would
    /// make the parts distinct again, so the second insert would silently
    /// succeed) as well as "keep an old index" (which would leave the
    /// non-colliding old index still enforcing uniqueness instead of this one).
    @Test
    void twoGloballySharedConnectionRowsWithTheSameCodeNowCollide() throws Exception {
        DataSource ds = TestPg.newDatabase("v12_fresh_collision_connections");
        Migrator.migrate(ds);

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("""
                    INSERT INTO msg_connections (id, code, name, service_account_id)
                    VALUES ('con_v12_a', 'x', 'first', 'svc_v12_a')""");
        }
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            Throwable thrown = catchThrowable(() -> st.execute("""
                    INSERT INTO msg_connections (id, code, name, service_account_id)
                    VALUES ('con_v12_b', 'x', 'second', 'svc_v12_b')"""));
            assertThat(thrown).as("a second (NULL application, NULL client, 'x') connection now collides")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uq_msg_connections_app_client_code");
        }
    }

    /// Same pin as above, for `msg_subscriptions`.
    @Test
    void twoGloballySharedSubscriptionRowsWithTheSameCodeNowCollide() throws Exception {
        DataSource ds = TestPg.newDatabase("v12_fresh_collision_subscriptions");
        Migrator.migrate(ds);

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("""
                    INSERT INTO msg_subscriptions (id, code, name, target)
                    VALUES ('sub_v12_a', 'x', 'first', 'https://example.test/a')""");
        }
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            Throwable thrown = catchThrowable(() -> st.execute("""
                    INSERT INTO msg_subscriptions (id, code, name, target)
                    VALUES ('sub_v12_b', 'x', 'second', 'https://example.test/b')"""));
            assertThat(thrown).as("a second (NULL application, NULL client, 'x') subscription now collides")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uq_msg_subscriptions_app_client_code");
        }
    }

    // ── C2: a colliding database refuses V12 ────────────────────────────────

    /// Kills "drop the connections pre-check": with only `msg_connections`
    /// colliding, V12 must still refuse — if the connections pre-check were
    /// removed, migration would run straight through the (non-colliding)
    /// subscriptions pre-check and succeed.
    @Test
    void v12RefusesAColldingConnectionsTableNamingTheGroup() throws Exception {
        DataSource ds = TestPg.newDatabase("v12_colliding_connections");
        flywayTo(ds, "11").migrate();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            // The OLD idx_msg_connections_code_client (code, client_id) index does
            // not reject two rows that are both platform-wide (client_id NULL) —
            // Postgres treats NULLs as distinct in a unique index. That is exactly
            // the pre-existing gap 056/V12 must catch before touching the indexes.
            st.execute("""
                    INSERT INTO msg_connections (id, code, name, service_account_id)
                    VALUES ('con_v12c_a', 'dup-conn', 'first', 'svc_v12c_a')""");
            st.execute("""
                    INSERT INTO msg_connections (id, code, name, service_account_id)
                    VALUES ('con_v12c_b', 'dup-conn', 'second', 'svc_v12c_b')""");
        }

        assertThatThrownBy(() -> Migrator.migrate(ds))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("msg_connections")
                .hasMessageContaining("collide")
                .hasMessageContaining("code='dup-conn'")
                .hasMessageContaining("count=2");

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("""
                    SELECT
                        (SELECT count(*) FROM pg_indexes WHERE schemaname = 'public'
                          AND tablename = 'msg_connections' AND indexname = 'idx_msg_connections_code_client'),
                        (SELECT count(*) FROM pg_indexes WHERE schemaname = 'public'
                          AND tablename = 'msg_subscriptions' AND indexname = 'idx_msg_subscriptions_code_client'),
                        (SELECT count(*) FROM pg_indexes WHERE schemaname = 'public'
                          AND tablename = 'msg_connections' AND indexname = 'uq_msg_connections_app_client_code'),
                        (SELECT count(*) FROM pg_indexes WHERE schemaname = 'public'
                          AND tablename = 'msg_subscriptions' AND indexname = 'uq_msg_subscriptions_app_client_code')""")) {
                rs.next();
                assertThat(rs.getInt(1)).as("the old connections index is untouched").isEqualTo(1);
                assertThat(rs.getInt(2)).as("the old subscriptions index is untouched").isEqualTo(1);
                assertThat(rs.getInt(3)).as("the new connections index was never created").isZero();
                assertThat(rs.getInt(4)).as("the new subscriptions index was never created").isZero();
            }
        }
    }

    /// Kills "drop the subscriptions pre-check": with only `msg_subscriptions`
    /// colliding (connections clean), V12 must still refuse — if the
    /// subscriptions pre-check were removed, migration would run straight
    /// through and succeed once past the (passing) connections pre-check.
    @Test
    void v12RefusesACollidingSubscriptionsTableNamingTheGroup() throws Exception {
        DataSource ds = TestPg.newDatabase("v12_colliding_subscriptions");
        flywayTo(ds, "11").migrate();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("""
                    INSERT INTO msg_subscriptions (id, code, name, target)
                    VALUES ('sub_v12c_a', 'dup-sub', 'first', 'https://example.test/a')""");
            st.execute("""
                    INSERT INTO msg_subscriptions (id, code, name, target)
                    VALUES ('sub_v12c_b', 'dup-sub', 'second', 'https://example.test/b')""");
        }

        assertThatThrownBy(() -> Migrator.migrate(ds))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("msg_subscriptions")
                .hasMessageContaining("collide")
                .hasMessageContaining("code='dup-sub'")
                .hasMessageContaining("count=2");

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("""
                    SELECT
                        (SELECT count(*) FROM pg_indexes WHERE schemaname = 'public'
                          AND tablename = 'msg_connections' AND indexname = 'idx_msg_connections_code_client'),
                        (SELECT count(*) FROM pg_indexes WHERE schemaname = 'public'
                          AND tablename = 'msg_subscriptions' AND indexname = 'idx_msg_subscriptions_code_client'),
                        (SELECT count(*) FROM pg_indexes WHERE schemaname = 'public'
                          AND tablename = 'msg_connections' AND indexname = 'uq_msg_connections_app_client_code'),
                        (SELECT count(*) FROM pg_indexes WHERE schemaname = 'public'
                          AND tablename = 'msg_subscriptions' AND indexname = 'uq_msg_subscriptions_app_client_code')""")) {
                rs.next();
                assertThat(rs.getInt(1)).as("the old connections index is untouched").isEqualTo(1);
                assertThat(rs.getInt(2)).as("the old subscriptions index is untouched").isEqualTo(1);
                assertThat(rs.getInt(3)).as("the new connections index was never created").isZero();
                assertThat(rs.getInt(4)).as("the new subscriptions index was never created").isZero();
            }
        }
    }
}
