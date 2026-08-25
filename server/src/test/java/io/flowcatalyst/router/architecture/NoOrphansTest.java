package io.flowcatalyst.router.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Fails the build on production code that **nothing in production calls**.
///
/// This exists because the same defect shipped three times in one day, and no
/// other kind of test can see it:
///
/// - `InFlightTracker.markRetrying` — documented, four tests, no caller. Two
///   guards that read the attempt count were unreachable, so the stall
///   detector reported every retrying message as stalled.
/// - `LifecycleLoops` — a whole subsystem with twenty tests, wired to nothing.
///   The stall detector never ran, broker stats never refreshed, and neither
///   did the reaper that recovers ownership when a backend stops redelivering.
/// - `FC_NOTIFY_WEBHOOK_URL` — parsed into `Env`, asserted by two tests,
///   consumed by nobody. Every operator warning went to a store and stopped.
///
/// Each looked finished. Coverage was green on all three, because **tests are
/// callers**: a method with tests and no production caller has exactly the
/// coverage profile of a method that works. Only asking "who calls this?"
/// finds it, and nobody asks that of code they just wrote.
///
/// The allow-list below is the point of the test as much as the check is:
/// something unreferenced is either a defect or a decision, and this forces it
/// to be written down as one of the two.
class NoOrphansTest {

    private static final Path MAIN = Path.of("src/main/java/io/flowcatalyst");
    private static final Path ROUTER = MAIN.resolve("router");

    /// Types deliberately not referenced from production, and why.
    private static final Map<String, String> ALLOWED = new LinkedHashMap<>(Map.of(
            "Mediating", "a record returned by Pool.mediating(); referenced through var",
            "QueueMetrics", "a record crossing the Consumer boundary; referenced through Optional",
            "Acknowledger", "narrowing interface — referenced as a type parameter only",
            "RouterEvent", "package-private JFR base class; extended, never named",
            "Warnings", "referenced through its NO_OP and tee factories"
    ));

    @Test
    @DisplayName("every production type in the router is referenced from production code")
    void noUnreferencedTypes() throws IOException {
        var sources = sources(ROUTER);
        var allMain = sources(MAIN);

        var orphans = new ArrayList<String>();
        for (var file : sources) {
            var type = file.getFileName().toString().replace(".java", "");
            if (ALLOWED.containsKey(type)) {
                continue;
            }
            // Everything EXCEPT the type's own file. Counting self-references
            // is what made the first version of this test decorative: a class
            // names itself a dozen times, so any threshold that tries to
            // subtract "its own declaration" passes for an orphan too. Caught
            // by unwiring LifecycleLoops and watching this test not care.
            var elsewhere = concatenated(allMain.stream().filter(f -> !f.equals(file)).toList());
            if (!Pattern.compile("\\b" + Pattern.quote(type) + "\\b").matcher(elsewhere).find()) {
                orphans.add(type + " (" + file + ")");
            }
        }

        assertThat(orphans)
                .as("""
                        Production types nothing in production references.

                        Each is either a defect — built, tested, and never wired, \
                        which is how LifecycleLoops shipped with the reaper switched \
                        off — or a deliberate exception, in which case add it to \
                        ALLOWED with the reason.""")
                .isEmpty();
    }

    @Test
    @DisplayName("the allow-list does not outlive what it excuses")
    void allowListStaysHonest() throws IOException {
        // An allow-list entry for a type that no longer exists is a stale
        // excuse, and stale excuses are how an allow-list quietly becomes a
        // way to turn the check off.
        var present = sources(ROUTER).stream()
                .map(f -> f.getFileName().toString().replace(".java", ""))
                .collect(java.util.stream.Collectors.toSet());

        assertThat(present).as("every ALLOWED entry names a type that still exists")
                .containsAll(Set.copyOf(ALLOWED.keySet()));
    }

    private static List<Path> sources(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static String concatenated(List<Path> files) throws IOException {
        var joined = new StringBuilder();
        for (var file : files) {
            joined.append(Files.readString(file)).append('\n');
        }
        return joined.toString();
    }
}
