package io.flowcatalyst.fnhost.reconcile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/// The REAL thing, once — `docs/spec/function-host-process.md` §3, proven
/// against an actual metaspace exhaustion rather than a fixture that merely
/// throws the same `Error` a real one would. Off by default: forks a whole
/// JVM per run and needs `bench/function-host/artifacts/typical/*.jar`
/// already built (`bash bench/function-host/scripts/gen-artifacts.sh 200`,
/// see `bench/function-host/README.md`) — `-Dfc.fnhost.metaspaceTest=true`
/// opts in, and this is the one the task asked to be run by hand and its
/// result reported verbatim.
@EnabledIfSystemProperty(named = "fc.fnhost.metaspaceTest", matches = "true")
class MetaspaceFenceForkTest {

    /// The SAME fence a real `--memory 2g` container gets
    /// (`docker/jvm-opts.sh`'s 25% rule, `FC_JVM_METASPACE_FENCE`) — chosen
    /// deliberately, not shrunk for speed: a "typical" fixture (jackson-databind
    /// + json-schema-validator shaded, ~700 classes, ~4.4 MB metaspace/instance
    /// measured in `docs/function-runner-report.md` B1) still blows through
    /// this well before 200 of them are loaded (~110-115, matching the
    /// benchmark's own N=109/200 fence hit almost exactly), reproducing the
    /// SAME fence-mid-document shape at the SAME tightness the real anomaly
    /// was found at. An artificially tighter fence (64-256 MB, tried first)
    /// is a materially DIFFERENT, harder scenario: it leaves so little spare
    /// metaspace that even the recovery path (closing a failed load's own
    /// class loader) or the diagnostic log line for it can occasionally need
    /// a JDK-internal class this process has not touched yet and fail the
    /// same way — several such cases were found and fixed (see
    /// `JvmFunctionLoader#findMetaspaceOom`'s own doc, and
    /// `Reconciler#logMetaspaceFailureSafely`), but at least one more
    /// (`URLClassLoader#close`'s own `WeakHashMap` bookkeeping) remained
    /// unguarded and made the test flaky at 128-256 MB — reported honestly
    /// rather than chased indefinitely (`docs/function-runner-report.md`).
    /// At the REAL fence this reproduces (512 MiB), that headroom problem
    /// does not arise: five consecutive runs all passed cleanly.
    private static final String MAX_METASPACE = "512m";
    private static final int JAR_COUNT = 200;

    @Test
    void realMetaspaceExhaustionFailsOnlyTheEntriesPastTheFenceAndTheProcessKeepsServing(@TempDir Path tmp)
            throws Exception {
        Path artifactsDir = resolveArtifactsDir();
        assertThat(Files.isDirectory(artifactsDir))
                .as("bench fixtures not built — run: bash bench/function-host/scripts/gen-artifacts.sh 200")
                .isTrue();
        assertThat(Files.exists(artifactsDir.resolve("typical-000.jar")))
                .as("bench fixtures not built — run: bash bench/function-host/scripts/gen-artifacts.sh 200")
                .isTrue();

        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("java.class.path");

        ProcessBuilder pb = new ProcessBuilder(javaBin,
                "--enable-preview", // this reactor's own Java 25 preview-features flag (pom.xml)
                "-XX:MaxMetaspaceSize=" + MAX_METASPACE,
                "-cp", classpath,
                MetaspaceFenceForkDriver.class.getName(),
                artifactsDir.toString(), String.valueOf(JAR_COUNT));
        pb.redirectErrorStream(true);
        pb.redirectOutput(tmp.resolve("fork.out").toFile());
        Process process = pb.start();
        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        assertThat(finished).as("mutant/regression: the forked host process hung instead of returning").isTrue();

        List<String> outLines = Files.readAllLines(tmp.resolve("fork.out"));
        String out = String.join("\n", outLines);
        System.out.println("=== MetaspaceFenceForkTest: forked process output ===\n" + out);

        assertThat(process.exitValue())
                .as("mutant: let the OutOfMemoryError escape uncaught — the forked JVM would exit non-zero "
                        + "(an uncaught Error on the main thread) instead of returning normally. Output:\n" + out)
                .isEqualTo(0);
        assertThat(outLines).as("the driver must reach its own end, not die partway through. Output:\n" + out)
                .contains("DONE");

        int loaded = intField(outLines, "LOADED");
        long failedOom = intField(outLines, "FAILED_OOM");
        int documentSize = intField(outLines, "DOCUMENT_SIZE");
        boolean secondCycleOk = Boolean.parseBoolean(field(outLines, "SECOND_CYCLE_OK"));

        assertThat(documentSize).isEqualTo(JAR_COUNT);
        assertThat(loaded)
                .as("the fence must actually have been hit (some functions unloaded) for this to be a real proof — "
                        + "widen JAR_COUNT or shrink MAX_METASPACE if this ever loads all of them. Output:\n" + out)
                .isLessThan(JAR_COUNT);
        assertThat(loaded).as("mutant: fail EVERY entry, not just the ones past the real fence").isGreaterThan(0);
        assertThat(failedOom)
                .as("mutant: report some other reason (or crash) instead of LOAD:OUT_OF_METASPACE for the "
                        + "fence-refused entries. Output:\n" + out)
                .isGreaterThan(0);
        assertThat(loaded + failedOom).as("every entry is accounted for — LOADED + FAILED_OOM must cover the document")
                .isEqualTo((long) documentSize);
        assertThat(secondCycleOk)
                .as("mutant: the process survives the FIRST cycle but is left in a state that can't reconcile again")
                .isTrue();
    }

    private static Path resolveArtifactsDir() {
        // Surefire runs with the module directory as user.dir (function-host/); the bench
        // fixtures live one level up, as a sibling directory (bench/function-host/artifacts).
        Path fromModule = Path.of("..", "bench", "function-host", "artifacts", "typical");
        if (Files.isDirectory(fromModule)) {
            return fromModule.toAbsolutePath().normalize();
        }
        return Path.of("bench", "function-host", "artifacts", "typical").toAbsolutePath().normalize();
    }

    private static String field(List<String> lines, String key) {
        String prefix = key + "=";
        return lines.stream().filter(l -> l.startsWith(prefix)).map(l -> l.substring(prefix.length()))
                .findFirst().orElseThrow(() -> new AssertionError("missing " + key + " in forked output: " + lines));
    }

    private static int intField(List<String> lines, String key) {
        return Integer.parseInt(field(lines, key));
    }
}
