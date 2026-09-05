package io.flowcatalyst.server.dbsecret;

import com.zaxxer.hikari.HikariDataSource;
import io.flowcatalyst.platform.shared.database.Database;
import io.flowcatalyst.server.dbsecret.jfr.DbSecretRefreshEvent;
import io.flowcatalyst.testjfr.Recorded;
import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/// The `DbSecretRefresh` flight-recorder event (`docs/spec/jfr-events.md`
/// §5), read back out of a real recording — the same embedded-Postgres pool
/// fixture as `DbSecretRefresherTest`.
class DbSecretEventTest {

    private static final String DB_NAME = "dbsecret_jfr";

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

    private static HikariDataSource poolFor(String user, String password) {
        var dsn = "postgresql://" + user + ":" + password + "@localhost:" + PORT + "/" + DB_NAME;
        return Database.newPool(dsn, 2);
    }

    /// A [SecretSource] stub the test rotates mid-run: either the JSON to
    /// return, or an exception to throw in its place.
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
    @DisplayName("a successful refresh records succeeded=true with no error")
    void successfulRefreshRecordsSucceededTrue() throws Exception {
        createRole("jfr_role_a", "pw_a");

        try (var pool = poolFor("jfr_role_a", "pw_a")) {
            var source = new RotatingSource();
            source.json = "{\"username\":\"jfr_role_a\",\"password\":\"pw_a\"}";
            var refresher = DbSecretRefresher.start(pool, source, "arn:test:jfr-success", 0);
            try {
                var events = Recorded.from(DbSecretRefreshEvent.class, refresher::refreshNow);

                assertThat(events).hasSize(1);
                var event = events.getFirst();
                assertThat(event.getBoolean("succeeded")).isTrue();
                assertThat(event.getString("error")).isNull();
            } finally {
                refresher.close();
            }
        }
    }

    @Test
    @DisplayName("a failed fetch records succeeded=false with the exception text, and never any credential material")
    void failedFetchRecordsSucceededFalse() throws Exception {
        createRole("jfr_role_b", "pw_b");

        try (var pool = poolFor("jfr_role_b", "pw_b")) {
            var source = new RotatingSource();
            source.json = "{\"username\":\"jfr_role_b\",\"password\":\"pw_b\"}";
            var refresher = DbSecretRefresher.start(pool, source, "arn:test:jfr-failure", 0);
            try {
                source.failure = new RuntimeException("simulated Secrets Manager outage");

                var events = Recorded.from(DbSecretRefreshEvent.class, refresher::refreshNow);

                assertThat(events).hasSize(1);
                var event = events.getFirst();
                assertThat(event.getBoolean("succeeded")).isFalse();
                assertThat(event.getString("error")).contains("simulated Secrets Manager outage");
                assertThat(event.getString("error")).doesNotContain("pw_b");
            } finally {
                refresher.close();
            }
        }
    }
}
