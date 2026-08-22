package io.flowcatalyst.fcdev;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;

/// `fcdev stop` (Go `stop.go`): locate the running `fcdev start` via its PID
/// file, ask it to exit (SIGTERM — the JVM runs its shutdown hooks, so the
/// server drains and the embedded Postgres stops cleanly), wait up to
/// `--timeout`, then force-kill. Stopping when nothing runs is not an error;
/// a stale PID file is cleaned up.
///
/// The messages are progress output (a developer watches the 20 s window),
/// so they are printed as the protocol advances rather than rendered from a
/// result value at the end.
@Command(name = "stop", description = "Stop a running fcdev instance (graceful; also stops embedded Postgres)",
        mixinStandardHelpOptions = true, sortOptions = false)
public final class StopCommand implements Callable<Integer> {

    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);
    static final Duration POLL = Duration.ofMillis(150);
    static final Duration KILL_WAIT = Duration.ofSeconds(5);

    @Option(names = "--pid-file", paramLabel = "<file>", description = "PID file written by `fcdev start` (FC_DEV_PID_FILE; default: ${DEFAULT-VALUE})")
    String pidFile;

    @Option(names = "--timeout", paramLabel = "<duration>", converter = DurationConverter.class,
            description = "how long to wait for graceful exit before SIGKILL (Go duration, e.g. 20s; default: 20s)")
    Duration timeout = DEFAULT_TIMEOUT;

    @Spec
    CommandSpec spec;

    public StopCommand() {
        this(DevEnv.system());
    }

    public StopCommand(DevEnv env) {
        this.pidFile = env.str("FC_DEV_PID_FILE", DevPaths.resolve(env.vars()).pidFilePath().toString());
    }

    @Override
    public Integer call() throws IOException, InterruptedException {
        PrintWriter out = spec.commandLine().getOut();
        Path path = Path.of(pidFile);
        var recorded = PidFile.read(path);
        if (recorded.isEmpty()) {
            out.printf("No running fcdev instance found (no pid file at %s).%n", path);
            return 0;
        }
        long pid = recorded.getAsLong();
        if (!PidFile.processAlive(pid)) {
            Files.deleteIfExists(path);
            out.printf("No running fcdev instance (pid %d not alive); removed stale pid file.%n", pid);
            return 0;
        }
        ProcessHandle proc = ProcessHandle.of(pid)
                .orElseThrow(() -> new IllegalStateException("find process " + pid + ": gone"));

        out.printf("Stopping fcdev (pid %d)…%n", pid);
        out.flush();
        if (!proc.destroy()) {               // SIGTERM on Unix
            throw new IllegalStateException("signal pid " + pid + ": not permitted");
        }
        if (waitForExit(pid, timeout)) {
            PidFile.removeIfOwned(path, pid);
            out.printf("Stopped fcdev (pid %d).%n", pid);
            return 0;
        }

        // Graceful window elapsed — force-kill. This skips start's clean shutdown,
        // so the embedded Postgres may need a moment to release its lock on next boot.
        out.printf("fcdev did not exit within %s; sending SIGKILL.%n", DurationConverter.format(timeout));
        out.flush();
        if (!proc.destroyForcibly()) {
            throw new IllegalStateException("force-kill pid " + pid + ": not permitted");
        }
        if (!waitForExit(pid, KILL_WAIT)) {
            throw new IllegalStateException("pid " + pid + " still running after SIGKILL");
        }
        PidFile.removeIfOwned(path, pid);
        out.printf("Force-stopped fcdev (pid %d).%n", pid);
        return 0;
    }

    /// `waitForExit`: poll every 150 ms until the process is gone or `timeout`
    /// elapses. `Thread.sleep` because there is nothing to wait on — the
    /// target is a foreign process, not a child. Interruption propagates.
    static boolean waitForExit(long pid, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!PidFile.processAlive(pid)) return true;
            Thread.sleep(POLL);
        }
        return !PidFile.processAlive(pid);
    }
}
