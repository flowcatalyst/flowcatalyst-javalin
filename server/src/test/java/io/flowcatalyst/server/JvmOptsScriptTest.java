package io.flowcatalyst.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// docs/spec/jvm-memory.md §1: `docker/jvm-opts.sh` derives `-Xmx` and
/// `-XX:MaxDirectMemorySize` from the cgroup memory limit. This runs the
/// real script (not a reimplementation of its arithmetic) against the
/// `FC_JVM_MEMORY_LIMIT_FILE` test seam, so a change to the shell arithmetic
/// is caught here rather than only by eye.
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
        // A clean slate: neither var should leak in from the runner's shell.
        env.remove("JAVA_TOOL_OPTIONS");
        env.remove("FC_JVM_MEMORY_LIMIT_FILE");
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
    void gib4(@TempDir Path dir) throws Exception {
        Result r = run(fileWith(dir, String.valueOf(4 * GIB)), Map.of());
        assertThat(r.stdout()).isEqualTo("-Xmx3481m -XX:MaxDirectMemorySize=307m\n");
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
}
