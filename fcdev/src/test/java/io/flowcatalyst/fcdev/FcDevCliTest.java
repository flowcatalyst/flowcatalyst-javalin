package io.flowcatalyst.fcdev;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// The command surface, without a database.
class FcDevCliTest {

    @TempDir
    Path dir;

    record Run(int exit, String out, String err) {
    }

    /// Every command prints through picocli's out/err writers, so the test
    /// captures them there — no `System.setOut` swapping.
    private static Run run(Map<String, String> env, String... args) {
        var out = new StringWriter();
        var err = new StringWriter();
        CommandLine cl = FcDev.commandLine(DevEnv.of(env));
        cl.setOut(new PrintWriter(out, true));
        cl.setErr(new PrintWriter(err, true));
        int exit = cl.execute(args);
        return new Run(exit, out.toString(), err.toString());
    }

    @Test
    void versionSubcommandAndFlagAgree() {
        var sub = run(Map.of(), "version");
        var flag = run(Map.of(), "--version");
        assertThat(sub.exit()).isZero();
        assertThat(sub.out().strip()).isEqualTo("fcdev " + Version.current() + Version.vcsSuffix());
        assertThat(flag.exit()).isZero();
        assertThat(flag.out().strip()).isEqualTo(sub.out().strip());
    }

    @Test
    void helpListsEveryGoSubcommand() {
        var r = run(Map.of(), "--help");
        assertThat(r.exit()).isZero();
        for (var name : new String[]{"start", "stop", "init", "fresh", "mcp", "outbox", "db", "upgrade", "version"}) {
            assertThat(r.out()).contains("  " + name);
        }
        for (var flag : new String[]{"--api-port", "--metrics-port", "--embedded-db", "--embedded-db-port", "--embedded-db-path",
                "--embedded-db-reset", "--database-url", "--scheduler", "--scheduled-job", "--stream", "--outbox", "--router", "--mcp", "--pid-file"}) {
            assertThat(r.out()).as(flag).contains(flag);
        }
        assertThat(run(Map.of(), "start", "--help").out()).contains("--pid-file");
        assertThat(run(Map.of(), "db", "upgrade", "--help").out()).contains("--no-backup").contains("--yes");
        assertThat(run(Map.of(), "outbox", "create-table", "--help").out()).contains("--db-type");
    }

    @Test
    void stopWithoutAPidFileIsNotAnError() {
        var pid = dir.resolve("fcdev.pid");
        var r = run(Map.of("FC_DEV_PID_FILE", pid.toString()), "stop");
        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("No running fcdev instance found (no pid file at " + pid + ")");
    }

    @Test
    void stopCleansAStalePidFile() throws Exception {
        var pid = dir.resolve("fcdev.pid");
        Files.writeString(pid, (Integer.MAX_VALUE - 11L) + "\n");
        var r = run(Map.of(), "stop", "--pid-file", pid.toString(), "--timeout", "1s");
        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("not alive); removed stale pid file");
        assertThat(pid).doesNotExist();
    }

    @Test
    void freshRefusesWithoutYes() {
        var r = run(Map.of(), "fresh");
        assertThat(r.exit()).isEqualTo(1);
    }

    @Test
    void notYetPortedCommandsKeepTheirFlagsAndExitTwo() {
        assertThat(run(Map.of(), "init", "--yes", "--admin-email", "a@b.c", "--code", "orders", "--name", "Orders").exit()).isEqualTo(2);
        assertThat(run(Map.of(), "mcp", "--http", "127.0.0.1:8090").exit()).isEqualTo(2);
        assertThat(run(Map.of(), "outbox", "--source-db-url", "postgresql://x@y/z", "--batch-size", "10").exit()).isEqualTo(2);
        assertThat(run(Map.of(), "outbox", "create-table", "--db-type", "pg", "--db-url", "postgresql://x@y/z").exit()).isEqualTo(2);
        assertThat(run(Map.of(), "upgrade", "--check").exit()).isEqualTo(2);
        assertThat(run(Map.of(), "upgrade").err()).contains("not yet ported");
    }

    @Test
    void dbUpgradeWithoutAClusterIsANoOp() {
        var r = run(Map.of(), "db", "upgrade", "--embedded-db-path", dir.resolve("embedded-pg").toString(), "--yes");
        assertThat(r.exit()).isZero();
    }

    @Test
    void unknownOptionIsAUsageError() {
        assertThat(run(Map.of(), "--bogus").exit()).isEqualTo(2);
    }
}
