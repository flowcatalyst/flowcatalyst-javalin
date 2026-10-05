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
///
/// The same scan runs for `msg_dispatch_queue` (the waiting jobs, kept exact by
/// the lifecycle — dispatch-queue spec step 2): only the lifecycle writes it,
/// plus the two named exceptions in [#QUEUE_EXCEPTIONS].
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

    /// The deliberate exceptions for `msg_dispatch_queue`, each with its reason.
    static final Map<String, String> QUEUE_EXCEPTIONS = Map.of(
            "io/flowcatalyst/stream/PartitionManager.java",
            "dropping a msg_dispatch_jobs partition deletes the queue rows of the jobs in it, in the same transaction",
            "io/flowcatalyst/fcdev/FreshCommand.java",
            "`fcdev fresh` truncates the dev database");

    private static final String LIFECYCLE = "io/flowcatalyst/platform/dispatchjob/DispatchJobLifecycle.java";

    private static Pattern sqlWrite(String table) {
        return Pattern.compile(
                "(?is)\\b(INSERT\\s+INTO|UPDATE|DELETE\\s+FROM|TRUNCATE(\\s+TABLE)?|DROP\\s+TABLE(\\s+IF\\s+EXISTS)?"
                        + "|ALTER\\s+TABLE)\\s+(ONLY\\s+)?" + table + "\\b");
    }
    /// One table the lifecycle owns: its SQL name, its generated jOOQ constant, and who else may write it.
    record Owned(String table, String constant, Map<String, String> exceptions) {
    }

    static final Owned JOBS = new Owned("msg_dispatch_jobs", "MSG_DISPATCH_JOBS", EXCEPTIONS);
    static final Owned QUEUE = new Owned("msg_dispatch_queue", "MSG_DISPATCH_QUEUE", QUEUE_EXCEPTIONS);

    @Test
    void onlyTheLifecycleWritesTheDispatchJobsTable() throws IOException {
        assertOnlyTheLifecycleWrites(JOBS, """
                production code outside DispatchJobLifecycle writes msg_dispatch_jobs; \
                route the write through the lifecycle (status has ONE owner)""");
    }

    @Test
    void onlyTheLifecycleWritesTheDispatchQueueTable() throws IOException {
        assertOnlyTheLifecycleWrites(QUEUE, """
                production code outside DispatchJobLifecycle writes msg_dispatch_queue; \
                the queue is kept exact by the lifecycle's own statements (one row per PENDING job)""");
    }

    private static void assertOnlyTheLifecycleWrites(Owned owned, String message) throws IOException {
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
                    String hit = writes(code, owned);
                    if (hit == null) continue;
                    if (owned.exceptions().containsKey(rel)) {
                        seenExceptions.add(rel);
                        continue;
                    }
                    violations.add(rel + ": " + hit);
                }
            }
        }
        assertThat(sawLifecycle).as("DispatchJobLifecycle.java is where the scan expects it").isTrue();
        assertThat(violations).as(message).isEmpty();
        // A listed exception that no longer writes the table is a stale entry.
        // The DDL ones build their statements dynamically, so only require the file to exist.
        for (String exception : owned.exceptions().keySet()) {
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

    /// The same scanner for the queue table, and the two tables are not confused.
    @Test
    void theScannerAlsoGuardsTheQueueTable() {
        assertThat(writes("\"INSERT INTO msg_dispatch_queue (job_id) VALUES (?)\"", QUEUE)).isNotNull();
        assertThat(writes("\"WITH moved AS (SELECT 1) DELETE FROM msg_dispatch_queue q USING moved m\"", QUEUE)).isNotNull();
        assertThat(writes("\"UPDATE msg_dispatch_queue SET version = now()\"", QUEUE)).isNotNull();
        assertThat(writes("\"TRUNCATE msg_dispatch_queue\"", QUEUE)).isNotNull();
        assertThat(writes("dsl.deleteFrom(MSG_DISPATCH_QUEUE).where(x)", QUEUE)).isNotNull();
        assertThat(writes("var Q = Tables.MSG_DISPATCH_QUEUE;\n dsl.insertInto(Q).set(a, b)", QUEUE)).isNotNull();
        assertThat(writes("dsl.update(DSL.table(\"msg_dispatch_queue\"))", QUEUE)).isNotNull();
        assertThat(writes("\"SELECT count(*) FROM msg_dispatch_queue WHERE x\"", QUEUE)).as("a read").isNull();
        assertThat(writes("dsl.selectFrom(MSG_DISPATCH_QUEUE).fetch()", QUEUE)).as("a read").isNull();
        assertThat(writes("\"UPDATE msg_dispatch_jobs SET status = 'X'\"", QUEUE)).as("the jobs table is the other scan").isNull();
        assertThat(writes("\"DELETE FROM msg_dispatch_queue\"", JOBS)).as("the queue table is the other scan").isNull();
    }

    static String writes(String code) {
        return writes(code, JOBS);
    }

    /// Which write to `owned` the source contains, or `null`.
    static String writes(String code, Owned owned) {
        Matcher sql = sqlWrite(owned.table()).matcher(code);
        if (sql.find()) return "SQL `" + sql.group().replaceAll("\\s+", " ") + "`";
        Matcher dsl = Pattern.compile("(?s)\\b(insertInto|update|deleteFrom|mergeInto)\\(\\s*(?:DSL\\.)?(?:table|name)\\(\\s*\""
                + owned.table() + "\"").matcher(code);
        if (dsl.find()) return "jOOQ `" + dsl.group().replaceAll("\\s+", " ") + "`";
        Set<String> names = new LinkedHashSet<>();
        names.add(owned.constant());
        names.add("Tables." + owned.constant());
        Matcher alias = Pattern.compile("(\\w+)\\s*=\\s*(?:\\w+\\.)*" + owned.constant() + "\\s*;").matcher(code);
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
