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
            "mail_outbox",         // docs/spec/mail-outbox.md §2, V8
            "fn_functions",        // docs/spec/function-registry.md §2, V11
            "fn_versions",         // docs/spec/function-registry.md §2, V11
            "fn_aliases",          // docs/spec/function-registry.md §2, V11
            "fn_hosts",            // docs/spec/function-registry.md §2, V11
            "fn_client_policies",  // docs/spec/function-registry.md §2, V11
            "fn_domains",          // docs/spec/function-registry.md §2, V11
            "fn_routes"            // docs/spec/function-registry.md §2, V11
    );

    /// Every constraint and index is named explicitly, starting with its table's
    /// name or `idx_<table>_` (V8, V11); a line is Java-only iff its own table
    /// column is a Java-only table, or — for a CONSTRAINT/INDEX row specifically
    /// — its object-name column (conname / indexname) starts with `<table>_` or
    /// `idx_<table>_` for one of them. Field positions, from
    /// [SchemaFingerprint#compute]: TABLE/COLUMN/SEQUENCE rows carry the
    /// table/relation name at index 1 and have no separate object-name column;
    /// CONSTRAINT and INDEX rows carry the table name at index 1 and the
    /// constraint/index name at index 2. A plain substring `contains` (the
    /// previous implementation) would also hide a Go table whose name merely
    /// *contains* a Java-only table's name, or a Go index whose name merely
    /// contains `idx_<table>_` as an interior substring rather than a prefix
    /// (§8 M17) — this splits on tab and checks exact field equality / prefix
    /// instead.
    static boolean isJavaOnly(String fingerprintLine) {
        String[] fields = fingerprintLine.split("\t", -1);
        if (fields.length < 2) {
            return false;
        }
        String kind = fields[0];
        String table = fields[1];
        boolean hasObjectName = fields.length > 2 && ("CONSTRAINT".equals(kind) || "INDEX".equals(kind));
        String objectName = hasObjectName ? fields[2] : null;
        for (String javaOnlyTable : JAVA_ONLY_TABLES) {
            if (table.equals(javaOnlyTable)) {
                return true;
            }
            if (objectName != null
                    && (objectName.startsWith(javaOnlyTable + "_") || objectName.startsWith("idx_" + javaOnlyTable + "_"))) {
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

    /// §8 M17: the filter hides exactly the declared Java-only objects — a Go
    /// table whose name merely *contains* a Java-only table's name (rather than
    /// equalling it), or a Go index whose name merely contains `idx_<table>_`
    /// as an interior substring (rather than starting with it), must NOT be
    /// hidden; the real fn_ and mail_outbox lines must be.
    @Test
    void isJavaOnlyHidesExactlyTheDeclaredObjectsNotMereSubstringMatches() {
        // A hypothetical Go table whose name merely CONTAINS "fn_hosts" —
        // must not be hidden by a table so much as substring-matching it.
        assertThat(isJavaOnly("TABLE\txfn_hosts_archive\tr\t")).isFalse();
        assertThat(isJavaOnly("COLUMN\txfn_hosts_archive\tid\t1\tcharacter varying\t17\t\tNO\t\tNO")).isFalse();
        assertThat(isJavaOnly("CONSTRAINT\txfn_hosts_archive\txfn_hosts_archive_pkey\tp\tPRIMARY KEY (id)")).isFalse();
        // A hypothetical index on some OTHER Go table whose name merely
        // contains "fn_hosts" as an interior substring, not a prefix.
        assertThat(isJavaOnly("INDEX\tother_table\tidx_other_fn_hosts_x\tCREATE INDEX ...\ttrue")).isFalse();

        // The real fn_hosts table and its named index/constraints ARE hidden.
        assertThat(isJavaOnly("TABLE\tfn_hosts\tr\t")).isTrue();
        assertThat(isJavaOnly("COLUMN\tfn_hosts\tpool\t2\tcharacter varying\t63\t\tNO\t\tNO")).isTrue();
        assertThat(isJavaOnly("CONSTRAINT\tfn_hosts\tfn_hosts_pkey\tp\tPRIMARY KEY (id)")).isTrue();
        assertThat(isJavaOnly(
                "INDEX\tfn_hosts\tidx_fn_hosts_pool_last_heartbeat\tCREATE INDEX idx_fn_hosts_pool_last_heartbeat ON public.fn_hosts USING btree (pool, last_heartbeat)\ttrue"))
                .isTrue();

        // mail_outbox keeps passing.
        assertThat(isJavaOnly("TABLE\tmail_outbox\tr\t")).isTrue();
        assertThat(isJavaOnly("CONSTRAINT\tmail_outbox\tmail_outbox_pkey\tp\tPRIMARY KEY (id)")).isTrue();
        assertThat(isJavaOnly(
                "INDEX\tmail_outbox\tidx_mail_outbox_status_next_attempt_at\tCREATE INDEX idx_mail_outbox_status_next_attempt_at ON public.mail_outbox USING btree (status, next_attempt_at)\ttrue"))
                .isTrue();
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
