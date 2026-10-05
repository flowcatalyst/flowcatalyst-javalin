package io.flowcatalyst.platform.dispatchjob;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// The dispatch-job lifecycle's enforcement (lifecycle spec rule 7): no
/// production source file outside [DispatchJobLifecycle] inserts into or
/// writes `msg_dispatch_jobs`. Scans every module's `src/main/java` for
///
/// - SQL text: `INSERT INTO` / `UPDATE` / `DELETE FROM` / `TRUNCATE` /
///   `DROP TABLE` / `ALTER TABLE` followed by `msg_dispatch_jobs` (the
///   `_read` projection table is a different table and not matched);
/// - jOOQ: `insertInto(` / `update(` / `deleteFrom(` / `mergeInto(` of the
///   generated `MSG_DISPATCH_JOBS` table, or of any variable assigned from it,
///   or of `DSL.table("msg_dispatch_jobs")`.
///
/// Comments are ignored. Tests, migrations (`.sql`) and the generated jOOQ
/// classes are out of scope. Adding a writer anywhere else fails here: route
/// the change through the lifecycle instead.
class DispatchJobLifecycleEnforcementTest {

    /// The deliberate exceptions, each with its reason. Anything not listed
    /// here (and not the lifecycle itself) must not write the table.
    static final Map<String, String> EXCEPTIONS = Map.of(
            "io/flowcatalyst/stream/DispatchJobProjection.java",
            "the projector's `projected_at` stamp: bookkeeping, never a status",
            "io/flowcatalyst/stream/PartitionManager.java",
            "partition DDL: drops whole expired partitions (retention), not rows through the lifecycle",
            "io/flowcatalyst/fcdev/FreshCommand.java",
            "`fcdev fresh` truncates the dev database");

    private static final String LIFECYCLE = "io/flowcatalyst/platform/dispatchjob/DispatchJobLifecycle.java";

    private static final Pattern SQL_WRITE = Pattern.compile(
            "(?is)\\b(INSERT\\s+INTO|UPDATE|DELETE\\s+FROM|TRUNCATE(\\s+TABLE)?|DROP\\s+TABLE(\\s+IF\\s+EXISTS)?"
                    + "|ALTER\\s+TABLE)\\s+(ONLY\\s+)?msg_dispatch_jobs\\b");
    private static final Pattern TABLE_ALIAS = Pattern.compile(
            "(\\w+)\\s*=\\s*(?:\\w+\\.)*MSG_DISPATCH_JOBS\\s*;");
    private static final Pattern DSL_TABLE = Pattern.compile(
            "(?s)\\b(insertInto|update|deleteFrom|mergeInto)\\(\\s*(?:DSL\\.)?(?:table|name)\\(\\s*\"msg_dispatch_jobs\"");

    @Test
    void onlyTheLifecycleWritesTheDispatchJobsTable() throws IOException {
        List<Path> roots = sourceRoots();
        assertThat(roots).as("found the production source roots").isNotEmpty();

        var violations = new ArrayList<String>();
        var seenExceptions = new LinkedHashSet<String>();
        boolean sawLifecycle = false;
        for (Path root : roots) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                    String rel = root.relativize(file).toString().replace('\\', '/');
                    if (rel.startsWith("io/flowcatalyst/db/generated/")) continue;
                    if (rel.equals(LIFECYCLE)) {
                        sawLifecycle = true;
                        continue;
                    }
                    String code = stripComments(Files.readString(file));
                    String hit = writes(code);
                    if (hit == null) continue;
                    if (EXCEPTIONS.containsKey(rel)) {
                        seenExceptions.add(rel);
                        continue;
                    }
                    violations.add(rel + ": " + hit);
                }
            }
        }
        assertThat(sawLifecycle).as("DispatchJobLifecycle.java is where the scan expects it").isTrue();
        assertThat(violations)
                .as("""
                        production code outside DispatchJobLifecycle writes msg_dispatch_jobs; \
                        route the write through the lifecycle (status has ONE owner)""")
                .isEmpty();
        // A listed exception that no longer writes the table is a stale entry.
        // The DDL ones build their statements dynamically, so only require the file to exist.
        for (String exception : EXCEPTIONS.keySet()) {
            boolean exists = roots.stream().anyMatch(r -> Files.exists(r.resolve(exception)));
            assertThat(exists).as("exception %s still exists", exception).isTrue();
        }
    }

    /// The scanner itself: what it flags and what it leaves alone.
    @Test
    void theScannerFlagsWritesAndIgnoresReadsAndComments() {
        assertThat(writes("\"UPDATE msg_dispatch_jobs SET status = 'X'\"")).isNotNull();
        assertThat(writes("\"\"\"\n INSERT INTO\n   msg_dispatch_jobs (id)\n\"\"\"")).isNotNull();
        assertThat(writes("\"delete from msg_dispatch_jobs where id = ?\"")).isNotNull();
        assertThat(writes("\"TRUNCATE TABLE msg_dispatch_jobs\"")).isNotNull();
        assertThat(writes("var T = MSG_DISPATCH_JOBS;\n dsl.update(T).set(T.STATUS, \"X\")")).isNotNull();
        assertThat(writes("dsl.insertInto(Tables.MSG_DISPATCH_JOBS).set(a, b)")).as("direct use of the generated table").isNotNull();
        assertThat(writes("dsl.deleteFrom(DSL.table(\"msg_dispatch_jobs\"))")).isNotNull();
        assertThat(writes("\"INSERT INTO msg_dispatch_jobs_read (id)\"")).as("the projection table is another table").isNull();
        assertThat(writes("\"SELECT 1 FROM msg_dispatch_jobs FOR UPDATE SKIP LOCKED\"")).isNull();
        assertThat(writes("var T = MSG_DISPATCH_JOBS;\n dsl.selectFrom(T).fetch()")).isNull();
        assertThat(stripComments("// UPDATE msg_dispatch_jobs SET x\n/* INSERT INTO msg_dispatch_jobs */ int a;"))
                .doesNotContain("msg_dispatch_jobs");
    }

    /// Which write the source contains, or `null`.
    static String writes(String code) {
        Matcher sql = SQL_WRITE.matcher(code);
        if (sql.find()) return "SQL `" + sql.group().replaceAll("\\s+", " ") + "`";
        Matcher dsl = DSL_TABLE.matcher(code);
        if (dsl.find()) return "jOOQ `" + dsl.group().replaceAll("\\s+", " ") + "`";
        Set<String> names = new LinkedHashSet<>();
        names.add("MSG_DISPATCH_JOBS");
        names.add("Tables.MSG_DISPATCH_JOBS");
        Matcher alias = TABLE_ALIAS.matcher(code);
        while (alias.find()) names.add(alias.group(1));
        for (String name : names) {
            Matcher w = Pattern.compile("\\b(insertInto|update|deleteFrom|mergeInto)\\(\\s*" + Pattern.quote(name) + "\\s*[,)]")
                    .matcher(code);
            if (w.find()) return "jOOQ `" + w.group().replaceAll("\\s+", " ") + "`";
        }
        return null;
    }

    /// Line (`//`, `///`) and block comments removed. String literals holding
    /// `//` do not occur next to the table name in this code base.
    static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)^\\s*//.*$", "");
    }

    /// Every module's `src/main/java` — the tests run with the server module as the working directory.
    private static List<Path> sourceRoots() throws IOException {
        var roots = new ArrayList<Path>();
        Path here = Path.of("").toAbsolutePath();
        Path repo = Files.exists(here.resolve("src/main/java")) ? here.getParent() : here;
        try (Stream<Path> modules = Files.list(repo)) {
            for (Path module : (Iterable<Path>) modules.sorted()::iterator) {
                String name = module.getFileName().toString();
                if (name.equals("bench") || name.equals("node_modules") || name.startsWith(".")) continue;
                Path src = module.resolve("src/main/java");
                if (Files.isDirectory(src)) roots.add(src);
            }
        }
        return roots;
    }
}
