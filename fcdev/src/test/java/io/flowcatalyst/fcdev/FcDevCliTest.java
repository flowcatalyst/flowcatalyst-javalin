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
                "--embedded-db-reset", "--embedded-db-binary", "--database-url", "--scheduler", "--scheduled-job", "--stream", "--outbox", "--router", "--mcp", "--pid-file"}) {
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
    void initIsARealCommandNotAStub() {
        // `--yes` with no `--code`: the spec's exact error, exit 1 — never the old stub's 2.
        var r = run(Map.of(), "init", "--yes", "--database-url", "postgresql://x@127.0.0.1:1/y");
        assertThat(r.exit()).isEqualTo(1);
    }

    /// `mcp`, `outbox` (+ `create-table`) and `upgrade` are real commands
    /// now, not stubs — each case below hits a fast, synchronous failure
    /// path so this stays a quick CLI-wiring smoke test: `mcp` would
    /// otherwise block on a listener forever, `outbox`/`create-table` would
    /// otherwise attempt a real (slow, or hanging) database connection, and
    /// `upgrade` would otherwise hit the real GitHub API — none of that
    /// belongs in this test (see McpCommandTest / OutboxCommandTest /
    /// CreateTableCommandTest / UpgradeCommandTest for the real behaviour,
    /// stubbed). `FcDev.commandLine`'s exception handler logs the runtime
    /// failure via SLF4J rather than picocli's captured `err` writer, so
    /// only the exit code — 1, a real failure, never the stub's 2 — is
    /// asserted here.
    @Test
    void mcpOutboxAndUpgradeAreRealCommandsNotStubs() {
        var mcpBadBind = run(Map.of(), "mcp", "--http", "not-a-host-port");
        assertThat(mcpBadBind.exit()).isEqualTo(1);

        var outboxMissingSource = run(Map.of(), "outbox");
        assertThat(outboxMissingSource.exit()).isEqualTo(1);

        var createTableUnknownType = run(Map.of(), "outbox", "create-table", "--db-type", "bogus", "--db-url", "x://y");
        assertThat(createTableUnknownType.exit()).isEqualTo(1);

        var upgradeHelp = run(Map.of(), "upgrade", "--help");
        assertThat(upgradeHelp.exit()).isZero();
        assertThat(upgradeHelp.out()).contains("--check").contains("--force");
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
