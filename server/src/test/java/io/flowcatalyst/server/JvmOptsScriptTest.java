package io.flowcatalyst.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/// docs/spec/jvm-memory.md §1: `docker/jvm-opts.sh` derives `-Xmx` and
/// `-XX:MaxDirectMemorySize` from the cgroup memory limit. This runs the
/// real script (not a reimplementation of its arithmetic) against the
/// `FC_JVM_MEMORY_LIMIT_FILE` test seam, so a change to the shell arithmetic
/// is caught here rather than only by eye.
///
/// §4: `FC_JVM_METASPACE_PERCENT` additionally derives `-XX:MaxMetaspaceSize`
/// and subtracts it (and the existing direct-memory carve-out) from `-Xmx`.
/// The UNSET tests below pin the golden stdout captured from the script
/// BEFORE this variable existed (`git show` of the pre-slice script, run
/// against each limit) — a regression here means the fc-server image's
/// behaviour changed, which must never happen.
class JvmOptsScriptTest {

    private static final Path SCRIPT = Path.of("..", "docker", "jvm-opts.sh");

    private static final long MIB = 1024L * 1024;
    private static final long GIB = 1024 * MIB;

    record Result(String stdout, String stderr, int exitCode) {}

    private Result run(Path limitFile, Map<String, String> extraEnv) throws IOException, InterruptedException {
        assertThat(Files.exists(SCRIPT))
                .as("docker/jvm-opts.sh not found at %s (cwd %s) -- is this test running from the server module?",
                        SCRIPT.toAbsolutePath(), Path.of("").toAbsolutePath())
                .isTrue();

        ProcessBuilder pb = new ProcessBuilder("sh", SCRIPT.toString());
        Map<String, String> env = pb.environment();
        // A clean slate: none of these should leak in from the runner's shell.
        env.remove("JAVA_TOOL_OPTIONS");
        env.remove("FC_JVM_MEMORY_LIMIT_FILE");
        env.remove("FC_JVM_METASPACE_PERCENT");
        if (limitFile != null) {
            env.put("FC_JVM_MEMORY_LIMIT_FILE", limitFile.toString());
        }
        env.putAll(extraEnv);

        Process process = pb.start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        return new Result(stdout, stderr, exit);
    }

    private Path fileWith(Path dir, String content) throws IOException {
        Path f = dir.resolve("limit-" + System.nanoTime());
        Files.writeString(f, content);
        return f;
    }

    @Test
    void mib512(@TempDir Path dir) throws Exception {
        Result r = run(fileWith(dir, String.valueOf(512 * MIB)), Map.of());
        assertThat(r.stdout()).isEqualTo("-Xmx320m -XX:MaxDirectMemorySize=96m\n");
        assertThat(r.exitCode()).isZero();
    }

    @Test
    void gib1(@TempDir Path dir) throws Exception {
        Result r = run(fileWith(dir, String.valueOf(GIB)), Map.of());
        assertThat(r.stdout()).isEqualTo("-Xmx832m -XX:MaxDirectMemorySize=96m\n");
        assertThat(r.exitCode()).isZero();
    }

    @Test
    void gib2(@TempDir Path dir) throws Exception {
        Result r = run(fileWith(dir, String.valueOf(2 * GIB)), Map.of());
        assertThat(r.stdout()).isEqualTo("-Xmx1740m -XX:MaxDirectMemorySize=153m\n");
        assertThat(r.exitCode()).isZero();
    }

    @Test
    void gib4(@TempDir Path dir) throws Exception {
        Result r = run(fileWith(dir, String.valueOf(4 * GIB)), Map.of());
        assertThat(r.stdout()).isEqualTo("-Xmx3481m -XX:MaxDirectMemorySize=307m\n");
        assertThat(r.exitCode()).isZero();
    }

    @Test
    void gib8(@TempDir Path dir) throws Exception {
        Result r = run(fileWith(dir, String.valueOf(8 * GIB)), Map.of());
        assertThat(r.stdout()).isEqualTo("-Xmx6963m -XX:MaxDirectMemorySize=614m\n");
        assertThat(r.exitCode()).isZero();
    }

    @Test
    void gib256(@TempDir Path dir) throws Exception {
        Result r = run(fileWith(dir, String.valueOf(256 * GIB)), Map.of());
        assertThat(r.stdout()).isEqualTo("-Xmx222822m -XX:MaxDirectMemorySize=19660m\n");
        assertThat(r.exitCode()).isZero();
    }

    @Test
    void cgroupV2UnlimitedSentinelEmitsNoFence(@TempDir Path dir) throws Exception {
        Result r = run(fileWith(dir, "max"), Map.of());
        assertThat(r.stdout()).isEmpty();
        assertThat(r.exitCode()).isZero();
        assertThat(r.stderr()).startsWith("jvm-opts:");
    }

    @Test
    void cgroupV1UnlimitedSentinelEmitsNoFence(@TempDir Path dir) throws Exception {
        Result r = run(fileWith(dir, "9223372036854771712"), Map.of());
        assertThat(r.stdout()).isEmpty();
        assertThat(r.exitCode()).isZero();
        assertThat(r.stderr()).startsWith("jvm-opts:");
    }

    @Test
    void missingLimitFileEmitsNoFence(@TempDir Path dir) throws Exception {
        Path missing = dir.resolve("does-not-exist");
        Result r = run(missing, Map.of());
        assertThat(r.stdout()).isEmpty();
        assertThat(r.exitCode()).isZero();
        assertThat(r.stderr()).startsWith("jvm-opts:");
    }

    @Test
    void mib128IsFlooredAt64WithAWarning(@TempDir Path dir) throws Exception {
        Result r = run(fileWith(dir, String.valueOf(128 * MIB)), Map.of());
        assertThat(r.stdout()).isEqualTo("-Xmx64m -XX:MaxDirectMemorySize=96m\n");
        assertThat(r.exitCode()).isZero();
        assertThat(r.stderr()).startsWith("jvm-opts:").containsIgnoringCase("floor");
    }

    @Test
    void javaToolOptionsXmxStepsAside(@TempDir Path dir) throws Exception {
        Result r = run(fileWith(dir, String.valueOf(512 * MIB)), Map.of("JAVA_TOOL_OPTIONS", "-Xmx1g"));
        assertThat(r.stdout()).isEmpty();
        assertThat(r.exitCode()).isZero();
        assertThat(r.stderr()).startsWith("jvm-opts:");
    }

    @Test
    void javaToolOptionsMaxRamPercentageStepsAside(@TempDir Path dir) throws Exception {
        Result r = run(fileWith(dir, String.valueOf(512 * MIB)), Map.of("JAVA_TOOL_OPTIONS", "-XX:MaxRAMPercentage=50"));
        assertThat(r.stdout()).isEmpty();
        assertThat(r.exitCode()).isZero();
        assertThat(r.stderr()).startsWith("jvm-opts:");
    }

    @Test
    void javaToolOptionsMaxDirectMemorySizeStepsAside(@TempDir Path dir) throws Exception {
        Result r = run(fileWith(dir, String.valueOf(512 * MIB)), Map.of("JAVA_TOOL_OPTIONS", "-XX:MaxDirectMemorySize=64m"));
        assertThat(r.stdout()).isEmpty();
        assertThat(r.exitCode()).isZero();
        assertThat(r.stderr()).startsWith("jvm-opts:");
    }

    // ── FC_JVM_METASPACE_PERCENT ────────────────────────────────────────
    //
    // Table over the required limits × percents, exact flags asserted. Two
    // rows (512 MiB/50, 512 MiB/70, 1 GiB/70) hit the pre-existing 64 MiB
    // heap floor -- exactly the "heap floor wins, metaspace shrinks" rule
    // docs/spec/jvm-memory.md §4 documents; every other row is un-floored.
    // Values captured by running the (hand-verified for 512 MiB/1 GiB) script
    // once and pinning its output -- same practice as the UNSET golden tests
    // above.

    private static final Pattern XMX = Pattern.compile("-Xmx(\\d+)m");
    private static final Pattern DIRECT = Pattern.compile("-XX:MaxDirectMemorySize=(\\d+)m");
    private static final Pattern META = Pattern.compile("-XX:MaxMetaspaceSize=(\\d+)m");

    @ParameterizedTest(name = "{0} bytes @ {1}% -> {2}")
    @CsvSource({
            // limitBytes,      percent, expected stdout
            "536870912,   10, '-Xmx172m -XX:MaxDirectMemorySize=96m -XX:MaxMetaspaceSize=51m'",
            "536870912,   25, '-Xmx96m -XX:MaxDirectMemorySize=96m -XX:MaxMetaspaceSize=128m'",
            "536870912,   50, '-Xmx64m -XX:MaxDirectMemorySize=96m -XX:MaxMetaspaceSize=160m'",
            "536870912,   70, '-Xmx64m -XX:MaxDirectMemorySize=96m -XX:MaxMetaspaceSize=160m'",
            "1073741824,  10, '-Xmx633m -XX:MaxDirectMemorySize=96m -XX:MaxMetaspaceSize=102m'",
            "1073741824,  25, '-Xmx480m -XX:MaxDirectMemorySize=96m -XX:MaxMetaspaceSize=256m'",
            "1073741824,  50, '-Xmx224m -XX:MaxDirectMemorySize=96m -XX:MaxMetaspaceSize=512m'",
            "1073741824,  70, '-Xmx64m -XX:MaxDirectMemorySize=96m -XX:MaxMetaspaceSize=672m'",
            "2147483648,  10, '-Xmx1382m -XX:MaxDirectMemorySize=153m -XX:MaxMetaspaceSize=204m'",
            "2147483648,  25, '-Xmx1075m -XX:MaxDirectMemorySize=153m -XX:MaxMetaspaceSize=512m'",
            "2147483648,  50, '-Xmx563m -XX:MaxDirectMemorySize=153m -XX:MaxMetaspaceSize=1024m'",
            "2147483648,  70, '-Xmx153m -XX:MaxDirectMemorySize=153m -XX:MaxMetaspaceSize=1433m'",
            "4294967296,  10, '-Xmx2764m -XX:MaxDirectMemorySize=307m -XX:MaxMetaspaceSize=409m'",
            "4294967296,  25, '-Xmx2150m -XX:MaxDirectMemorySize=307m -XX:MaxMetaspaceSize=1024m'",
            "4294967296,  50, '-Xmx1126m -XX:MaxDirectMemorySize=307m -XX:MaxMetaspaceSize=2048m'",
            "4294967296,  70, '-Xmx307m -XX:MaxDirectMemorySize=307m -XX:MaxMetaspaceSize=2867m'",
            "8589934592,  10, '-Xmx5529m -XX:MaxDirectMemorySize=614m -XX:MaxMetaspaceSize=819m'",
            "8589934592,  25, '-Xmx4300m -XX:MaxDirectMemorySize=614m -XX:MaxMetaspaceSize=2048m'",
            "8589934592,  50, '-Xmx2252m -XX:MaxDirectMemorySize=614m -XX:MaxMetaspaceSize=4096m'",
            "8589934592,  70, '-Xmx614m -XX:MaxDirectMemorySize=614m -XX:MaxMetaspaceSize=5734m'",
    })
    void metaspacePercentMatrix(long limitBytes, int percent, String expected, @TempDir Path dir) throws Exception {
        Result r = run(fileWith(dir, String.valueOf(limitBytes)), Map.of("FC_JVM_METASPACE_PERCENT", String.valueOf(percent)));
        assertThat(r.exitCode()).isZero();
        assertThat(r.stdout()).isEqualTo(expected + "\n");

        // The sum invariant this whole slice exists to guarantee: heap +
        // direct + metaspace + a reserve computed the SAME way §1 always has
        // (max(192 MiB, 15% of the limit)) never exceeds the container limit.
        // Mutant: forgetting to subtract metaspace from the heap makes this
        // fail at every row above 512 MiB/10% (it only "accidentally" holds
        // at the smallest, most-floored rows).
        long reserve = Math.max(192L * MIB, limitBytes * 15 / 100);
        long xmxBytes = groupAsLong(XMX, r.stdout()) * MIB;
        long directBytes = groupAsLong(DIRECT, r.stdout()) * MIB;
        long metaBytes = groupAsLong(META, r.stdout()) * MIB;
        assertThat(xmxBytes + directBytes + metaBytes + reserve)
                .as("heap(%d) + direct(%d) + metaspace(%d) + reserve(%d) must not exceed the %d byte limit",
                        xmxBytes, directBytes, metaBytes, reserve, limitBytes)
                .isLessThanOrEqualTo(limitBytes);
    }

    @Test
    void metaspacePercentUnsetEmitsNoMetaspaceFlagAndIsByteIdenticalToUnset(@TempDir Path dir) throws Exception {
        // FC_JVM_METASPACE_PERCENT simply never set (not even to ""): the
        // fc-server image's own path. Re-asserts the gib4 golden value with
        // the var deliberately absent from the extra env map too, i.e. this
        // is the exact invocation shape fc-server's entrypoint uses.
        Result r = run(fileWith(dir, String.valueOf(4 * GIB)), Map.of());
        assertThat(r.stdout()).isEqualTo("-Xmx3481m -XX:MaxDirectMemorySize=307m\n");
        assertThat(r.stdout()).doesNotContain("MaxMetaspaceSize");
    }

    @ParameterizedTest
    @ValueSource(strings = {"9", "71", "abc", "50%", "-5", "10.5"})
    void invalidMetaspacePercentFailsTheScript(String value, @TempDir Path dir) throws Exception {
        Result r = run(fileWith(dir, String.valueOf(2 * GIB)), Map.of("FC_JVM_METASPACE_PERCENT", value));
        assertThat(r.exitCode()).as("mutant: accept an out-of-range or non-integer value").isNotZero();
        assertThat(r.stdout()).isEmpty();
        assertThat(r.stderr())
                .as("the error must name the variable and the valid range")
                .startsWith("jvm-opts:")
                .contains("FC_JVM_METASPACE_PERCENT")
                .contains("10")
                .contains("70");
    }

    @Test
    void invalidMetaspacePercentEmptyStringFailsTheScript(@TempDir Path dir) throws Exception {
        // Set to "" (not merely absent) -- ProcessBuilder's environment map
        // can hold an empty value distinctly from an unset key.
        Result r = run(fileWith(dir, String.valueOf(2 * GIB)), Map.of("FC_JVM_METASPACE_PERCENT", ""));
        assertThat(r.exitCode()).as("mutant: treat '' the same as unset").isNotZero();
        assertThat(r.stdout()).isEmpty();
        assertThat(r.stderr()).startsWith("jvm-opts:").contains("FC_JVM_METASPACE_PERCENT");
    }

    private static long groupAsLong(Pattern p, String s) {
        Matcher m = p.matcher(s);
        assertThat(m.find()).as("pattern %s not found in '%s'", p, s).isTrue();
        return Long.parseLong(m.group(1));
    }
}
