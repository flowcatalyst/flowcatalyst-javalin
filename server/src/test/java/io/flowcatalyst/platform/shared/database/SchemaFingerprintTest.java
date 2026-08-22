package io.flowcatalyst.platform.shared.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import io.flowcatalyst.testpg.TestPg;

/// The Java-migrated schema must be identical to the Go schema (rollback to
/// Go must stay possible). The Go side is a committed fixture generated from
/// the captured `pg_dump` (see [#regenerateFixture()]).
class SchemaFingerprintTest {

    static final String FIXTURE = "/db/go-schema-fingerprint.txt";
    static final Path FIXTURE_PATH = Path.of("src/test/resources/db/go-schema-fingerprint.txt");

    @Test
    void javaMigratedSchemaMatchesGoSchemaFingerprint() throws Exception {
        DataSource ds = TestPg.newDatabase("fingerprint_java");
        Migrator.migrate(ds);
        String actual = SchemaFingerprint.compute(ds);

        String expected;
        try (InputStream in = getClass().getResourceAsStream(FIXTURE)) {
            assertThat(in).as("fixture %s (regenerate with -Dfc.regenerateFingerprint=true)", FIXTURE).isNotNull();
            expected = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(actual.lines().toList())
                .as("schema fingerprint: Java-migrated vs Go (src/test/resources/db/go-schema-fingerprint.txt)")
                .containsExactlyElementsOf(expected.lines().toList());
    }

    /// Regenerates the fixture from `db/go-schema.sql` loaded into a fresh
    /// embedded database. Run with:
    ///
    ///     mvn -pl server -Dtest=SchemaFingerprintTest -Dfc.regenerateFingerprint=true test
    @Test
    @EnabledIfSystemProperty(named = "fc.regenerateFingerprint", matches = "true")
    void regenerateFixture() throws Exception {
        DataSource ds = TestPg.newDatabase("fingerprint_go");
        GoSchema.load(ds);
        String fingerprint = SchemaFingerprint.compute(ds);
        Files.createDirectories(FIXTURE_PATH.getParent());
        Files.writeString(FIXTURE_PATH, fingerprint, StandardCharsets.UTF_8);
        assertThat(fingerprint).isNotBlank();
    }
}
