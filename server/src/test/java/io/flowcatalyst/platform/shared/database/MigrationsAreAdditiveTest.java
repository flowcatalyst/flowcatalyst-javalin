package io.flowcatalyst.platform.shared.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/// The rollback-to-Go rule from [Migrator]'s class doc and
/// `docs/spec/cutover.md` §0: until Go is retired, every Java migration that
/// is not a mirror of a Go goose migration must be **additive and ignorable
/// by Go** — no dropped or renamed tables or columns, no retyped columns.
/// A mirrored migration (header `-- Adopted from flowcatalyst-go …`) is
/// exempt: Go itself ran it. A statement against a table in
/// [SchemaFingerprintTest#JAVA_ONLY_TABLES] is exempt too, for the SAME
/// reason that test excludes those tables from the byte-for-byte Go
/// fingerprint comparison: a table Go never reads or writes leaves the
/// rollback-to-Go story intact regardless of what happens to its columns
/// (`fn_domains.verification_token`/`verified_at`, dropped by V16, spec
/// `function-domains-no-dns.md`, is the first real instance of this).
///
/// The predicate is tested on its own so the scan over the real directory
/// (vacuous today: V2–V7 are all mirrors) is not the only thing pinning it.
class MigrationsAreAdditiveTest {

    static final String MIRROR_MARKER = "-- Adopted from flowcatalyst-go";

    /// Structural statements Go could not survive: dropping or renaming what
    /// it reads, or changing a column's type under it.
    static final Pattern DESTRUCTIVE = Pattern.compile(
            "(?is)\\b(DROP\\s+TABLE|DROP\\s+COLUMN|RENAME\\s+(TO|COLUMN)|ALTER\\s+COLUMN\\s+\\S+\\s+(SET\\s+DATA\\s+)?TYPE)\\b");

    /// The table a `ALTER TABLE <table> ...` or `DROP TABLE [IF EXISTS] <table> ...`
    /// statement names — group 1 is the table, `IF EXISTS` optional.
    static final Pattern TABLE_STATEMENT = Pattern.compile(
            "(?is)\\b(?:ALTER|DROP)\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?([\\w.]+)");

    /// Retypes reviewed as **widenings** Go survives, keyed by migration, each
    /// with the exact violation list the scan reports for it. The predicate
    /// cannot tell `varchar(17) → varchar(100)` from a real retype, so each is
    /// named here; [SchemaFingerprintTest#WIDENED_COLUMN_JAVA] /
    /// [SchemaFingerprintTest#WIDENED_COLUMN_GO] pin the before and after
    /// widths, so a later edit that narrowed or retyped the column fails
    /// there. Go reads and writes these columns as strings: a wider column
    /// only lets Go's own long values in (V18 fixed the sync `AUDIT_WRITE`
    /// 500 that both platforms hit on an application code over 17 chars).
    static final java.util.Map<String, List<String>> REVIEWED_WIDENINGS = java.util.Map.of(
            "V18__aud_logs_entity_id_width.sql", List.of("ALTER COLUMN ENTITY_ID TYPE"));

    static boolean isMirror(String sql) {
        return sql.stripLeading().startsWith(MIRROR_MARKER);
    }

    /// Destructive statements against a [SchemaFingerprintTest#JAVA_ONLY_TABLES]
    /// table are exempt (see the class doc) — checked PER STATEMENT (split on
    /// `;`), not for the file as a whole, so a migration mixing a java-only
    /// table's drop with a genuinely shared table's drop still catches the
    /// shared one.
    static List<String> violations(String sql) {
        List<String> out = new ArrayList<>();
        for (String statement : stripComments(sql).split(";")) {
            String table = tableNameOf(statement);
            if (table != null && SchemaFingerprintTest.JAVA_ONLY_TABLES.contains(table)) {
                continue;
            }
            var m = DESTRUCTIVE.matcher(statement);
            while (m.find()) {
                out.add(m.group(1).replaceAll("\\s+", " ").toUpperCase());
            }
        }
        return out;
    }

    private static String tableNameOf(String statement) {
        var m = TABLE_STATEMENT.matcher(statement);
        return m.find() ? m.group(1) : null;
    }

    private static String stripComments(String sql) {
        return sql.replaceAll("(?m)--.*$", "");
    }

    @Test
    void predicateRejectsWhatGoCannotSurvive() {
        assertThat(violations("ALTER TABLE iam_principals DROP COLUMN email;")).containsExactly("DROP COLUMN");
        assertThat(violations("alter table tnt_clients rename to tenants;")).containsExactly("RENAME TO");
        assertThat(violations("ALTER TABLE x ALTER COLUMN mode SET DATA TYPE text;")).containsExactly("ALTER COLUMN MODE SET DATA TYPE");
        assertThat(violations("DROP TABLE IF EXISTS msg_events_old;")).containsExactly("DROP TABLE");
    }

    @Test
    void predicateAcceptsAdditiveWorkAndIgnoresComments() {
        assertThat(violations("""
                -- a comment saying DROP TABLE is fine
                ALTER TABLE iam_principals ADD COLUMN IF NOT EXISTS nickname text;
                CREATE INDEX IF NOT EXISTS ix ON iam_principals (nickname);
                ALTER TABLE msg_subscriptions ALTER COLUMN mode SET DEFAULT 'NEXT_ON_ERROR';
                DROP INDEX IF EXISTS old_ix;
                """)).isEmpty();
    }

    /// A drop against a KNOWN java-only table is exempt; the identical drop
    /// against an ordinary shared table is still caught — pins that the
    /// exemption is table-specific, not a blanket "drops are fine now".
    /// Mutant: drop the exemption entirely (first assertion fails), or
    /// broaden it to every table (second assertion fails).
    @Test
    void javaOnlyTableDropsAreExemptButAnIdenticalDropOnASharedTableIsStillCaught() {
        assertThat(SchemaFingerprintTest.JAVA_ONLY_TABLES).contains("fn_domains");
        assertThat(violations("ALTER TABLE fn_domains DROP COLUMN verification_token;"))
                .as("mutant: stop exempting java-only tables")
                .isEmpty();
        assertThat(violations("ALTER TABLE iam_principals DROP COLUMN verification_token;"))
                .as("mutant: exempt every table, not just java-only ones")
                .containsExactly("DROP COLUMN");
    }

    @Test
    void mirrorMarkerIsRecognisedOnlyAsTheHeader() {
        assertThat(isMirror(MIRROR_MARKER + " internal/migrate/sql/048.sql\nALTER TABLE t DROP COLUMN c;")).isTrue();
        assertThat(isMirror("ALTER TABLE t ADD COLUMN c int;\n" + MIRROR_MARKER)).isFalse();
    }

    @Test
    void everyNonMirroredMigrationAfterV1IsAdditive() throws IOException {
        List<String> names = new ArrayList<>();
        try (InputStream in = resource("db/migration.index")) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.isBlank()) names.add(line.strip());
            }
        }
        assertThat(names).startsWith("V1__baseline.sql").hasSizeGreaterThanOrEqualTo(7);

        List<String> offenders = new ArrayList<>();
        int mirrors = 0;
        for (String name : names.subList(1, names.size())) {
            String sql;
            try (InputStream in = resource("db/migration/" + name)) {
                sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (isMirror(sql)) {
                mirrors++;
                continue;
            }
            List<String> v = violations(sql);
            if (!v.isEmpty() && !v.equals(REVIEWED_WIDENINGS.get(name))) offenders.add(name + " " + v);
        }
        assertThat(offenders)
                .as("migrations after V1 that Go could not survive (cutover.md §0); mirrors of Go migrations are exempt")
                .isEmpty();
        assertThat(mirrors).as("V2–V7 mirror goose 046–052").isGreaterThanOrEqualTo(6);
    }

    private static InputStream resource(String path) throws IOException {
        InputStream in = MigrationsAreAdditiveTest.class.getClassLoader().getResourceAsStream(path);
        if (in == null) throw new IOException("missing classpath resource " + path);
        return in;
    }
}
