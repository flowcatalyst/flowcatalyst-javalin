package io.flowcatalyst.platform.shared.database;

import io.flowcatalyst.testpg.TestPg;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.File;
import java.net.URL;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/// The index-backed migration path a native image runs on. Two things must
/// hold or the binary boots a fresh database into an empty schema:
/// the index names every file in `db/migration`, and Flyway configured
/// through it actually applies them.
class IndexedMigrationsTest {

    @Test
    void indexListsExactlyTheMigrationDirectory() throws Exception {
        URL dir = getClass().getClassLoader().getResource("db/migration");
        assertThat(dir).as("db/migration on the classpath as a directory").isNotNull();
        String[] onDisk = new File(dir.toURI()).list((d, n) -> n.endsWith(".sql"));
        assertThat(onDisk).isNotEmpty();
        assertThat(IndexedMigrations.load().names())
                .containsExactlyInAnyOrder(onDisk);
    }

    @Test
    void indexedFlywayMigratesAFreshDatabase() {
        DataSource ds = TestPg.newDatabase("indexed_migrations_test");
        MigrateResult result = Migrator.flyway(ds, true).migrate();
        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(1);
        assertThat(result.migrations).extracting(m -> m.version).containsExactly("1");
        // The scanning configuration agrees the database is now current.
        assertThat(Migrator.flyway(ds, false).info().pending()).isEmpty();
    }

    @Test
    void resourcesAreFilteredByFlywaysPrefixAndSuffix() {
        var index = IndexedMigrations.load();
        assertThat(index.getResources("V", new String[]{".sql"})).extracting(r -> r.getFilename())
                .containsExactlyElementsOf(index.names());
        assertThat(index.getResources("R", new String[]{".sql"})).isEmpty();
        assertThat(index.getResource("db/migration/V1__baseline.sql")).isNotNull();
        assertThat(index.getResource("V9__nope.sql")).isNull();
        assertThat(Arrays.asList("V1__baseline.sql")).isSubsetOf(index.names());
    }
}
