package io.flowcatalyst.platform.shared.database;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import javax.sql.DataSource;

/// Computes a sortable, text fingerprint of the `public` schema: tables
/// (with relkind / partition key), columns, constraints (with definition),
/// indexes (with definition and validity) and sequences.
///
/// Dated monthly (`<parent>_YYYY_MM`) and quarterly (`<parent>_YYYY_qN`)
/// partitions, DEFAULT partitions (`<parent>_default`), and everything
/// hanging off them, plus `goose_db_version` and `flyway_schema_history`, are
/// excluded so the Java-migrated schema can be compared with the Go one
/// regardless of the month/quarter in which either was created.
public final class SchemaFingerprint {

    /// SQL fragment: true when the relation name is a dated (monthly or
    /// quarterly) partition, a table's DEFAULT partition, or a migration
    /// bookkeeping table.
    private static final String IGNORED_REL =
            "(%s ~ '_[0-9]{4}_[0-9]{2}$' OR %s ~ '_[0-9]{4}_q[0-9]$' OR %s ~ '_default$'"
                    + " OR %s ~ '^(goose_db_version|flyway_schema_history)')";

    private SchemaFingerprint() {
    }

    public static String compute(DataSource ds) throws SQLException {
        try (Connection c = ds.getConnection()) {
            return compute(c);
        }
    }

    public static String compute(Connection c) throws SQLException {
        List<String> lines = new ArrayList<>();
        // Tables, partitioned tables and sequences, plus partition key for parents.
        query(c, lines, "TABLE", """
                SELECT cl.relname, cl.relkind::text, COALESCE(pg_get_partkeydef(cl.oid), '')
                FROM pg_class cl JOIN pg_namespace n ON n.oid = cl.relnamespace
                WHERE n.nspname = 'public' AND cl.relkind IN ('r', 'p', 'S')
                  AND NOT %s""".formatted(ignored("cl.relname")));
        query(c, lines, "COLUMN", """
                SELECT table_name, column_name, ordinal_position::text, data_type,
                       COALESCE(character_maximum_length::text, ''), COALESCE(numeric_precision::text, ''),
                       is_nullable, COALESCE(column_default, ''), is_identity
                FROM information_schema.columns
                WHERE table_schema = 'public' AND NOT %s""".formatted(ignored("table_name")));
        // NOT NULL constraints (contype 'n', PG 18) are excluded: their names are
        // system-generated on partitions and they are already covered by is_nullable.
        queryConstraints(c, lines, """
                SELECT cl.relname, con.conname, con.contype::text, pg_get_constraintdef(con.oid)
                FROM pg_constraint con
                JOIN pg_class cl ON cl.oid = con.conrelid
                JOIN pg_namespace n ON n.oid = cl.relnamespace
                WHERE n.nspname = 'public' AND con.contype <> 'n'
                  AND NOT %s""".formatted(ignored("cl.relname")));
        query(c, lines, "INDEX", """
                SELECT i.tablename, i.indexname, i.indexdef, x.indisvalid::text
                FROM pg_indexes i
                JOIN pg_class ic ON ic.relname = i.indexname
                JOIN pg_namespace n ON n.oid = ic.relnamespace AND n.nspname = i.schemaname
                JOIN pg_index x ON x.indexrelid = ic.oid
                WHERE i.schemaname = 'public' AND NOT %s""".formatted(ignored("i.tablename")));
        query(c, lines, "SEQUENCE", """
                SELECT sequence_name, data_type, start_value, minimum_value, maximum_value, increment, cycle_option
                FROM information_schema.sequences
                WHERE sequence_schema = 'public' AND NOT %s""".formatted(ignored("sequence_name")));
        lines.sort(null);
        return String.join("\n", lines) + "\n";
    }

    private static String ignored(String col) {
        return IGNORED_REL.formatted(col, col, col, col);
    }

    private static void query(Connection c, List<String> out, String kind, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int cols = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder sb = new StringBuilder(kind);
                for (int i = 1; i <= cols; i++) {
                    sb.append('\t').append(rs.getString(i));
                }
                out.add(sb.toString());
            }
        }
    }

    // `pg_get_constraintdef` renders a `col IN ('A', 'B')` CHECK constraint
    // differently depending on the exact PostgreSQL point release that
    // originally parsed it: some cast the whole literal array to the
    // comparison type once (`(ARRAY['A'::t, 'B'::t])::text[]`), others cast
    // each element individually (`ARRAY[('A'::t)::text, ('B'::t)::text]`).
    // Both are the same expression; only the deparse style differs. Go's
    // captured fixture and the Java-migrated schema can legitimately be
    // built by different minor Postgres versions (Go's embedded-postgres-go
    // vs zonky's embedded-postgres for tests), so the definition text is
    // normalised to one canonical form before comparison.
    private static final Pattern CAST_SUFFIX = Pattern.compile("::text\\[\\]|::character varying|::text");
    private static final Pattern DOUBLE_WRAPPED_ARRAY = Pattern.compile("\\(\\(ARRAY(\\[[^\\]]*])\\)\\)");
    private static final Pattern PARENTHESISED_LITERAL = Pattern.compile("\\('([^']*)'\\)");

    static String normalizeConstraintDef(String def) {
        String normalized = CAST_SUFFIX.matcher(def).replaceAll("");
        normalized = DOUBLE_WRAPPED_ARRAY.matcher(normalized).replaceAll("(ARRAY$1)");
        normalized = PARENTHESISED_LITERAL.matcher(normalized).replaceAll("'$1'");
        return normalized;
    }

    private static void queryConstraints(Connection c, List<String> out, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String def = normalizeConstraintDef(rs.getString(4));
                out.add("CONSTRAINT" + '\t' + rs.getString(1) + '\t' + rs.getString(2) + '\t' + rs.getString(3)
                        + '\t' + def);
            }
        }
    }
}
