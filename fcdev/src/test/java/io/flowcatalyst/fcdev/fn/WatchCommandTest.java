package io.flowcatalyst.fcdev.fn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/// `fn watch` — spec §4 E8: "two writes within the debounce ⇒ one deploy; a
/// failing deploy is printed and the watch continues to the next change".
/// Drives [WatchCommand#runLoop] directly against a REAL [WatchService] and
/// a real temp directory, with a tiny injected debounce (tens of ms, never
/// the real 500 ms) so every wait here is bounded. Every cycle in these
/// tests is deliberately a "no jar matched" skip (an empty directory) — a
/// fast, deterministic stand-in for a real publish+promote that still proves
/// the LOOP's cycle count and its continue-on-failure behaviour; each skip
/// prints exactly one line, so counting output lines counts cycles.
class WatchCommandTest {

    @TempDir
    Path dir;

    /// A [WatchCommand] with its `@Spec` populated by parsing a minimal,
    /// valid argument line — needed for `spec.commandLine().getOut()`.
    private WatchCommand build(StringWriter out) {
        var cl = new picocli.CommandLine(new WatchCommand());
        cl.setOut(new PrintWriter(out, true));
        cl.parseArgs(dir.toString(), "--app", "a", "--service", "s", "--name", "n");
        var cmd = (WatchCommand) cl.getCommand();
        cmd.debounce = Duration.ofMillis(20);
        return cmd;
    }

    private static long cycleCount(String out) {
        return out.lines().filter(l -> l.contains("matched 0 file")).count();
    }

    /// Mutant: no debounce (deploy on every raw event) — this test writes
    /// two files ~5ms apart, well inside the 20ms debounce window, and
    /// requires exactly one COALESCED cycle for them (plus the loop's own
    /// initial cycle). **This does NOT reliably discriminate "no debounce"
    /// on every platform** — the JDK's default `WatchService` on macOS is
    /// itself polling-based with multi-second granularity, so it often
    /// coalesces the two writes into one `take()` regardless of
    /// [WatchCommand]'s own debounce logic. Kept as an integration sanity
    /// check against the REAL watcher; {@link #twoSignalsWithinTheDebounceWindowAreOneCoalescedSignal}
    /// is the deterministic test that actually pins the debounce behaviour
    /// (see its own doc, and the final report's mutant table).
    @Test
    void twoWritesWithinTheDebounceProduceOneCoalescedCycle() throws Exception {
        var out = new StringWriter();
        WatchCommand cmd = build(out);
        cmd.maxCycles = 2; // the initial cycle + exactly one coalesced cycle

        try (WatchService ws = FileSystems.getDefault().newWatchService()) {
            dir.register(ws, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
            var latch = new CountDownLatch(1);
            Thread.ofPlatform().start(() -> {
                cmd.runLoop(null, ws, dir, "a.s.n");
                latch.countDown();
            });

            Files.writeString(dir.resolve("f1.txt"), "1");
            Thread.sleep(5);
            Files.writeString(dir.resolve("f2.txt"), "2");

            assertThat(latch.await(5, TimeUnit.SECONDS)).as("watch loop must finish within maxCycles").isTrue();
        }
        assertThat(cycleCount(out.toString()))
                .as("two writes inside the debounce window must yield exactly ONE extra cycle, not two")
                .isEqualTo(2);
    }

    /// The deterministic pin for E8's debounce claim: drives
    /// [WatchCommand#awaitDebouncedChange] directly against a [FakeWatchService]
    /// under full test control (no dependency on any real OS watcher's
    /// granularity). Two signals land ~10ms apart, well inside a 60ms
    /// debounce window; ONE call to `awaitDebouncedChange` must drain BOTH —
    /// proven by the fake service's queue being empty afterward (a mutant
    /// that returns after the FIRST signal, without coalescing, leaves the
    /// second one still queued).
    @Test
    void twoSignalsWithinTheDebounceWindowAreOneCoalescedSignal() throws Exception {
        var out = new StringWriter();
        WatchCommand cmd = build(out);
        cmd.debounce = Duration.ofMillis(60);
        var fake = new FakeWatchService();

        fake.signal();
        Thread.ofPlatform().start(() -> {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            fake.signal();
        });

        boolean changed = cmd.awaitDebouncedChange(fake);

        assertThat(changed).as("a real signal must report a change").isTrue();
        assertThat(fake.poll()).as("the SECOND signal must have been drained by the SAME debounced call, "
                + "not left for a following one (mutant: no debounce)").isNull();
    }

    /// Mutant: exit on a failing cycle. The INITIAL cycle always fails here
    /// (no jar ever matches) — if a failure stopped the loop, it would never
    /// reach `runLoop`'s wait for a change at all, and `maxCycles=2` would
    /// never be satisfied. (Two writes are used, rather than one, only
    /// because a single JDK `WatchService` implementation is polling-based
    /// with coarse — multi-second — granularity on some platforms, notably
    /// macOS; several writes maximise the chance at least one is observed
    /// inside the test's own bounded wait without depending on exact timing
    /// between them.)
    @Test
    void aFailingCycleIsPrintedAndTheWatchContinues() throws Exception {
        var out = new StringWriter();
        WatchCommand cmd = build(out);
        cmd.maxCycles = 2; // the initial (failing) cycle + one more after a change

        try (WatchService ws = FileSystems.getDefault().newWatchService()) {
            dir.register(ws, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
            var latch = new CountDownLatch(1);
            Thread.ofPlatform().start(() -> {
                cmd.runLoop(null, ws, dir, "a.s.n");
                latch.countDown();
            });
            for (int i = 0; i < 5; i++) {
                Files.writeString(dir.resolve("f" + i + ".txt"), Integer.toString(i));
                Thread.sleep(50);
            }

            assertThat(latch.await(30, TimeUnit.SECONDS)).as("watch loop must reach maxCycles despite the initial cycle failing").isTrue();
        }
        assertThat(cycleCount(out.toString())).as("both cycles must have run, and both failed").isEqualTo(2);
    }

    @Test
    void interruptStopsTheLoop() throws Exception {
        var out = new StringWriter();
        WatchCommand cmd = build(out);

        try (WatchService ws = FileSystems.getDefault().newWatchService()) {
            dir.register(ws, StandardWatchEventKinds.ENTRY_CREATE);
            var stopped = new CountDownLatch(1);
            Thread loopThread = Thread.ofPlatform().start(() -> {
                cmd.runLoop(null, ws, dir, "a.s.n");
                stopped.countDown();
            });
            Thread.sleep(50); // let it settle into ws.take()
            loopThread.interrupt();
            assertThat(stopped.await(5, TimeUnit.SECONDS)).as("an interrupt must stop the loop promptly").isTrue();
        }
    }
}
