package io.flowcatalyst.server.dbsecret;

import com.zaxxer.hikari.HikariDataSource;
import io.flowcatalyst.platform.shared.database.Database;
import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/// Spec `docs/spec/db-secret.md` §3, §4, against the embedded Postgres: a
/// rotated credential pair is picked up by *new* pool connections while a
/// connection already checked out keeps working under the old role — the
/// same guarantee Go gets from pgx's `BeforeConnect` (only the physical
/// connect path reads the cached credentials; nothing reaches into an
/// established session and changes who it is authenticated as).
class DbSecretRefresherTest {

    private static final String DB_NAME = "dbsecret_refresher";

    private static DataSource DB;
    private static int PORT;

    @BeforeAll
    static void setUpDatabase() {
        DB = TestPg.newDatabase(DB_NAME);
        PORT = TestPg.instance().getPort();
    }

    private static void createRole(String role, String password) {
        try (Connection c = DB.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE ROLE " + role + " LOGIN PASSWORD '" + password + "'");
            st.execute("GRANT CONNECT ON DATABASE " + DB_NAME + " TO " + role);
        } catch (SQLException e) {
            throw new IllegalStateException("create role " + role, e);
        }
    }

    private static String currentUser(Connection c) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT current_user")) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    private static HikariDataSource poolFor(String user, String password) {
        var dsn = "postgresql://" + user + ":" + password + "@localhost:" + PORT + "/" + DB_NAME;
        return Database.newPool(dsn, 2);
    }

    /// A [SecretSource] stub the test rotates mid-run: either the JSON to
    /// return, or an exception to throw in its place — never both live at
    /// once, so a test can simulate a fetch failure without a real AWS call.
    private static final class RotatingSource implements SecretSource {
        volatile String json;
        volatile RuntimeException failure;

        @Override
        public String secretString(String arn) {
            if (failure != null) throw failure;
            return json;
        }
    }

    @Test
    void rotationAppliesOnlyToConnectionsOpenedAfterIt() throws SQLException {
        createRole("rr_role_a", "pw_a");
        createRole("rr_role_b", "pw_b");

        try (var pool = poolFor("rr_role_a", "pw_a")) {
            var source = new RotatingSource();
            source.json = "{\"username\":\"rr_role_a\",\"password\":\"pw_a\"}";
            // intervalMs = 0: no periodic tick: the test drives refreshNow() itself.
            var refresher = DbSecretRefresher.start(pool, source, "arn:test:rotation", 0);
            try {
                // A connection opened before the rotation...
                try (Connection connA = pool.getConnection()) {
                    assertThat(currentUser(connA)).isEqualTo("rr_role_a");

                    // ...rotate the secret and force the refresh...
                    source.json = "{\"username\":\"rr_role_b\",\"password\":\"pw_b\"}";
                    refresher.refreshNow();

                    // ...evict idle pooled connections so the next checkout must open a
                    // NEW physical connection (one that reads the just-applied config)
                    // rather than handing back a warm one opened under the old role.
                    pool.getHikariPoolMXBean().softEvictConnections();

                    // ...a NEW connection uses the rotated credentials...
                    try (Connection connB = pool.getConnection()) {
                        assertThat(currentUser(connB)).isEqualTo("rr_role_b");
                    }

                    // ...while connA, already established, is untouched by the rotation.
                    assertThat(currentUser(connA)).isEqualTo("rr_role_a");
                }
            } finally {
                refresher.close();
            }
        }
    }

    @Test
    void periodicScheduleAppliesRotationWithoutAManualRefresh() throws Exception {
        createRole("rr_role_e", "pw_e");
        createRole("rr_role_f", "pw_f");

        try (var pool = poolFor("rr_role_e", "pw_e")) {
            var source = new RotatingSource();
            source.json = "{\"username\":\"rr_role_e\",\"password\":\"pw_e\"}";
            // A short but real interval: this test asserts the SCHEDULE itself
            // does the fetch+apply, unlike the other tests which drive
            // refreshNow() by hand and would pass even if scheduleWithFixedDelay
            // were never wired up.
            var refresher = DbSecretRefresher.start(pool, source, "arn:test:periodic", 20);
            try {
                source.json = "{\"username\":\"rr_role_f\",\"password\":\"pw_f\"}";

                var observed = "rr_role_e";
                var deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
                while (System.nanoTime() < deadline && !observed.equals("rr_role_f")) {
                    Thread.sleep(20);
                    pool.getHikariPoolMXBean().softEvictConnections();
                    try (Connection c = pool.getConnection()) {
                        observed = currentUser(c);
                    }
                }
                assertThat(observed).isEqualTo("rr_role_f");
            } finally {
                refresher.close();
            }
        }
    }

    @Test
    void failedRefreshLeavesThePreviousCredentialsInPlace() throws SQLException {
        createRole("rr_role_c", "pw_c");
        createRole("rr_role_d", "pw_d");

        try (var pool = poolFor("rr_role_c", "pw_c")) {
            var source = new RotatingSource();
            source.json = "{\"username\":\"rr_role_c\",\"password\":\"pw_c\"}";
            var refresher = DbSecretRefresher.start(pool, source, "arn:test:refresh-failure", 0);
            try {
                source.failure = new RuntimeException("simulated Secrets Manager outage");
                refresher.refreshNow(); // must not throw, and must not apply anything

                pool.getHikariPoolMXBean().softEvictConnections();

                // A connection opened AFTER the failed refresh still uses the
                // previous (only) known-good credentials — the rotation to
                // rr_role_d never took effect.
                try (Connection conn = pool.getConnection()) {
                    assertThat(currentUser(conn)).isEqualTo("rr_role_c");
                }
            } finally {
                refresher.close();
            }
        }
    }
}
