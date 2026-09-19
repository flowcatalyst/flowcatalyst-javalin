package io.flowcatalyst.fnhost;

import io.flowcatalyst.server.EnvReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/// [FnHostMain] (`docs/spec/function-host-process.md` §1, test P8): the
/// package-private [FnHostMain#run] seam, which `main` is the only caller of
/// [System#exit] around — every assertion here drives `run` directly so no
/// test can accidentally exit the JVM running it.
class FnHostMainTest {

    private static Map<String, String> requiredEnv(Path cacheDir) {
        Map<String, String> env = new HashMap<>();
        env.put("FC_FN_PLATFORM_URL", "http://127.0.0.1:1");
        env.put("FC_FN_CLIENT_ID", "client-1");
        env.put("FC_FN_CLIENT_SECRET", "secret-1");
        env.put("FC_FN_CACHE_DIR", cacheDir.toString());
        env.put("FC_FN_PORT", "0");
        env.put("FC_METRICS_PORT", "0");
        return env;
    }

    // ── missing variables: exit 2, one line naming ALL of them ──

    @Test
    void missingVariablesExitTwoAndNameAllOfThemInOneLine() throws Exception {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = FnHostMain.run(new EnvReader(Map.of()), new PrintStream(err, true, StandardCharsets.UTF_8), null);

        assertThat(code).as("mutant: exit 1").isEqualTo(2);
        String printed = err.toString(StandardCharsets.UTF_8);
        assertThat(printed).as("mutant: name only the first")
                .contains("FC_FN_PLATFORM_URL").contains("FC_FN_CLIENT_ID").contains("FC_FN_CLIENT_SECRET");
    }

    @Test
    void missingOnlyOneVariableNamesOnlyThatOneStillExitingTwo(@TempDir Path dir) throws Exception {
        Map<String, String> env = new HashMap<>(requiredEnv(dir));
        env.remove("FC_FN_CLIENT_SECRET");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = FnHostMain.run(new EnvReader(env), new PrintStream(err, true, StandardCharsets.UTF_8), null);

        assertThat(code).isEqualTo(2);
        String printed = err.toString(StandardCharsets.UTF_8);
        assertThat(printed).contains("FC_FN_CLIENT_SECRET")
                .as("mutant: name only the first — must not ALSO claim the ones that were set")
                .doesNotContain("FC_FN_PLATFORM_URL").doesNotContain("FC_FN_CLIENT_ID");
    }

    // ── FC_EXIT_AFTER_START: 0, and close() ran exactly once ──

    @Test
    void exitAfterStartReturnsZeroAndClosesExactlyOnce(@TempDir Path dir) throws Exception {
        Map<String, String> env = new HashMap<>(requiredEnv(dir));
        env.put("FC_EXIT_AFTER_START", "true");
        AtomicInteger closeCount = new AtomicInteger();
        int code = FnHostMain.run(new EnvReader(env), System.err, closeCount);

        assertThat(code).isEqualTo(0);
        assertThat(closeCount.get()).as("mutant: name only the first (reused here: close() called once)").isEqualTo(1);
    }

    // ── ordinary start: blocks (does not close early); an interrupted wait still closes exactly once ──

    @Test
    void ordinaryStartBlocksUntilStoppedThenClosesExactlyOnce(@TempDir Path dir) throws Exception {
        Map<String, String> env = new HashMap<>(requiredEnv(dir));
        AtomicInteger closeCount = new AtomicInteger();
        AtomicInteger exitCode = new AtomicInteger(-1);

        // A real Thread (not a pooled task) so the test can interrupt it directly: run() blocks
        // on host.awaitStop() until stopped, exactly like the real shutdown-hook path would —
        // interrupting it here stands in for that, and proves close() still runs (once) rather
        // than leaking the host's bound listeners forever (see run()'s own comment on this).
        Thread runner = new Thread(() -> {
            try {
                exitCode.set(FnHostMain.run(new EnvReader(env), System.err, closeCount));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "fnhost-main-test-runner");
        runner.start();

        // Give the host a moment to actually start (bind its listeners) before interrupting —
        // not asserted on, just avoids interrupting mid-construction.
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (closeCount.get() == 0 && exitCode.get() == -1 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(closeCount.get()).as("must still be blocked, not have closed on its own").isEqualTo(0);

        runner.interrupt();
        runner.join(java.time.Duration.ofSeconds(30).toMillis());
        assertThat(runner.isAlive()).as("the interrupted wait must actually unblock run()").isFalse();
        assertThat(exitCode.get()).isEqualTo(0);
        assertThat(closeCount.get()).as("mutant: name only the first (reused here: close() called once)")
                .isEqualTo(1);
    }
}
