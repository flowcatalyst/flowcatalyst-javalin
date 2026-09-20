package io.flowcatalyst.fnhost.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/// docs/spec/jvm-memory.md §4: `/ready`'s `memory` object reads the live
/// JVM state `docker/jvm-opts.sh` fenced this process into. Two things must
/// hold independent of each other: a bounded pool/option reports a real
/// positive number, and an UNBOUNDED one is OMITTED — never `-1` (mutant:
/// report -1).
class FnMemorySnapshotTest {

    // ── heapMaxBytes: always present ────────────────────────────────────

    @Test
    void heapMaxBytesIsAlwaysPositive() {
        FnMemorySnapshot.Info info = FnMemorySnapshot.capture((Path) null);
        assertThat(info.heapMaxBytes()).isPositive();
    }

    // ── metaspaceMaxBytes / directMaxBytes: unbounded -> omitted, in-process ──
    //
    // This test's own JVM (maven-surefire's forked process, pom.xml's
    // argLine is just --enable-preview) sets neither -XX:MaxMetaspaceSize
    // nor -XX:MaxDirectMemorySize, so both are genuinely unbounded here.
    // Mutant: report -1 instead of omitting -- `isNull()` catches it
    // immediately (a mutant returning -1 fails this, not the isPositive
    // tests below, which is why both directions are needed).

    @Test
    void metaspaceMaxBytesIsOmittedWhenUnbounded() {
        FnMemorySnapshot.Info info = FnMemorySnapshot.capture((Path) null);
        assertThat(info.metaspaceMaxBytes()).as("mutant: report -1 instead of omitting").isNull();
    }

    @Test
    void directMaxBytesIsOmittedWhenUnbounded() {
        FnMemorySnapshot.Info info = FnMemorySnapshot.capture((Path) null);
        assertThat(info.directMaxBytes()).as("mutant: report -1 instead of omitting").isNull();
    }

    // ── metaspaceMaxBytes / directMaxBytes: bounded -> a real positive number ──
    //
    // Neither -XX:MaxMetaspaceSize nor -XX:MaxDirectMemorySize can be applied
    // to the already-running test JVM, so this forks one with them set and
    // reads FnMemorySnapshotForkDriver's stdout.

    @Test
    void metaspaceAndDirectMaxBytesArePositiveWhenBounded(@TempDir Path tmp) throws Exception {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("java.class.path");
        long metaspaceFlagBytes = 64L * 1024 * 1024;
        long directFlagBytes = 48L * 1024 * 1024;

        ProcessBuilder pb = new ProcessBuilder(javaBin,
                "--enable-preview",
                "-XX:MaxMetaspaceSize=64m",
                "-XX:MaxDirectMemorySize=48m",
                "-cp", classpath,
                FnMemorySnapshotForkDriver.class.getName());
        pb.redirectErrorStream(true);
        Path out = tmp.resolve("fork.out");
        pb.redirectOutput(out.toFile());
        Process process = pb.start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        assertThat(finished).as("the forked driver must return").isTrue();
        assertThat(process.exitValue()).as("the forked driver must exit cleanly").isEqualTo(0);

        List<String> lines = Files.readAllLines(out);
        String dump = String.join("\n", lines);
        long heapMaxBytes = longField(lines, "heapMaxBytes", dump);
        long metaspaceMaxBytes = longField(lines, "metaspaceMaxBytes", dump);
        long directMaxBytes = longField(lines, "directMaxBytes", dump);

        assertThat(heapMaxBytes).as("dump:\n" + dump).isPositive();
        assertThat(metaspaceMaxBytes)
                .as("mutant: omit/report -1 instead of the real bounded value. dump:\n" + dump)
                .isPositive()
                // HotSpot rounds MaxMetaspaceSize to a metaspace-chunk boundary; assert it
                // landed near the requested 64 MiB, not merely "some positive number".
                .isBetween(metaspaceFlagBytes - 8 * 1024 * 1024, metaspaceFlagBytes + 8 * 1024 * 1024);
        assertThat(directMaxBytes)
                .as("mutant: omit/report -1 instead of the real bounded value. dump:\n" + dump)
                .isEqualTo(directFlagBytes);
    }

    // ── limitBytes: the cgroup-file test seam, `capture(Path)` ──────────

    @Test
    void limitBytesIsPresentWhenTheFileHoldsANumber(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("limit");
        Files.writeString(file, String.valueOf(4L * 1024 * 1024 * 1024));
        FnMemorySnapshot.Info info = FnMemorySnapshot.capture(file);
        assertThat(info.limitBytes()).isEqualTo(4L * 1024 * 1024 * 1024);
    }

    @Test
    void limitBytesIsOmittedWhenTheFileIsMissing(@TempDir Path dir) {
        FnMemorySnapshot.Info info = FnMemorySnapshot.capture(dir.resolve("does-not-exist"));
        assertThat(info.limitBytes()).isNull();
    }

    @Test
    void limitBytesIsOmittedWhenNoFileIsGiven() {
        FnMemorySnapshot.Info info = FnMemorySnapshot.capture((Path) null);
        assertThat(info.limitBytes()).isNull();
    }

    @Test
    void limitBytesIsOmittedForTheCgroupV2UnlimitedSentinel(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("limit");
        Files.writeString(file, "max");
        FnMemorySnapshot.Info info = FnMemorySnapshot.capture(file);
        assertThat(info.limitBytes()).isNull();
    }

    @Test
    void limitBytesIsOmittedForTheCgroupV1UnlimitedSentinel(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("limit");
        Files.writeString(file, "9223372036854771712");
        FnMemorySnapshot.Info info = FnMemorySnapshot.capture(file);
        assertThat(info.limitBytes()).isNull();
    }

    private static long longField(List<String> lines, String key, String dump) {
        String prefix = key + "=";
        String value = lines.stream().filter(l -> l.startsWith(prefix)).map(l -> l.substring(prefix.length()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing " + key + " in forked output:\n" + dump));
        return Long.parseLong(value);
    }
}
