package io.flowcatalyst.testpg;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/// The isolation is only real if the extension is actually registered: with
/// the `META-INF/services` entry or the autodetection property gone,
/// everything still passes — against ONE shared database, silently. This is
/// the test that fails instead.
class TestPgPerClassTest {

    /// Held the way the fixtures hold it: before any test method runs.
    private static final DataSource DS = TestPg.dataSource();

    @Test
    void aTestClassGetsItsOwnMigratedDatabase() throws SQLException {
        String database = currentDatabase();
        assertThat(database).as("owned by this class, not the unowned fallback")
                .startsWith("fc_test_").isNotEqualTo("fc_test_unowned").isNotEqualTo("fc_test_template");
        try (Connection c = DS.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM flyway_schema_history")) {
            rs.next();
            assertThat(rs.getInt(1)).as("cloned from the migrated template").isPositive();
        }
    }

    @Test
    void everyConnectionOfTheClassReachesTheSameDatabase() throws SQLException {
        assertThat(currentDatabase()).isEqualTo(currentDatabase());
    }

    @Nested
    class Inner {
        @Test
        void aNestedClassSharesItsOuterClasssDatabase() throws SQLException {
            assertThat(currentDatabase()).startsWith("fc_test_").isNotEqualTo("fc_test_unowned");
        }
    }

    private static String currentDatabase() throws SQLException {
        try (Connection c = DS.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT current_database()")) {
            rs.next();
            return rs.getString(1);
        }
    }
}
