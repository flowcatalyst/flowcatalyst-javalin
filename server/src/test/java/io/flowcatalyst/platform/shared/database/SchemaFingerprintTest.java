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

    /// Tables that exist only on the Java side. Additive-only is the rule
    /// (`docs/database.md`; rollback to Go must stay possible), and a table Go
    /// never reads or writes leaves that rollback intact — so these are
    /// removed from the Java fingerprint before the byte-for-byte comparison
    /// instead of being pretended away. Each entry names its spec.
    static final java.util.Set<String> JAVA_ONLY_TABLES = java.util.Set.of(
            "mail_outbox"   // docs/spec/mail-outbox.md §2, V8
    );

    static boolean isJavaOnly(String fingerprintLine) {
        for (String table : JAVA_ONLY_TABLES) {
            if (fingerprintLine.contains("\t" + table + "\t") || fingerprintLine.contains("\tidx_" + table + "_")
                    || fingerprintLine.endsWith("\t" + table) || fingerprintLine.contains(table + "_pkey")
                    || fingerprintLine.contains(table + "_status_check")) {
                return true;
            }
        }
        return false;
    }

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
        var javaLines = actual.lines().filter(l -> !isJavaOnly(l)).toList();
        assertThat(javaLines)
                .as("schema fingerprint: Java-migrated vs Go (src/test/resources/db/go-schema-fingerprint.txt), Java-only tables %s removed", JAVA_ONLY_TABLES)
                .containsExactlyElementsOf(expected.lines().toList());
        assertThat(actual.lines().filter(SchemaFingerprintTest::isJavaOnly).count())
                .as("every declared Java-only table is actually in the Java schema")
                .isGreaterThan(0);
    }

    /// `normalizeConstraintDef` must canonicalise INDEX rows (a partial
    /// index's `WHERE` clause) the same way it already does CONSTRAINT rows
    /// — Postgres 18.4 vs 18.6 spell an `ANY (ARRAY[...])` predicate
    /// differently (`idx_dispatch_jobs_blocked_groups`,
    /// `idx_msg_scheduled_job_instances_active`): one casts the whole
    /// literal array once, the other casts each element. Both spellings of
    /// the same predicate must normalise to one identical string.
    @Test
    void normalizeConstraintDefCanonicalisesBothIndexArrayCastSpellings() {
        String elementWiseCast =
                "((status)::text = ANY (ARRAY[('FAILED'::character varying)::text, ('ERROR'::character varying)::text]))";
        String wholeArrayCast =
                "((status)::text = ANY ((ARRAY['FAILED'::character varying, 'ERROR'::character varying])::text[]))";

        String normalizedElementWise = SchemaFingerprint.normalizeConstraintDef(elementWiseCast);
        String normalizedWholeArray = SchemaFingerprint.normalizeConstraintDef(wholeArrayCast);

        assertThat(normalizedElementWise)
                .as("both spellings of the same predicate normalise to one canonical string")
                .isEqualTo(normalizedWholeArray);
        // Pin the actual shape too, not just their mutual equality — two
        // spellings could otherwise both be normalised into some OTHER
        // shared but wrong string and this assertion would still pass.
        assertThat(normalizedElementWise).isEqualTo("((status) = ANY (ARRAY['FAILED', 'ERROR']))");
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
