package io.flowcatalyst.fcdev;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// A boot that dies after the embedded Postgres is up must stop it — including
/// when the failure is an [Error] (the Java fcdev `NoClassDefFoundError` crash
/// left an orphaned postgres holding its port and data directory).
class StartBootFailureTest {

    @Test
    void errorAfterPostgresStartsStopsPostgres() throws Exception {
        io.flowcatalyst.server.Logging.init(Map.of("FC_LOG_LEVEL", "warn", "FC_LOG_FORMAT", "text"));
        Path root = Files.createTempDirectory("fcdev-bootfail");
        try {
            Path cache = root.resolve("cache");
            SharedPgDownloads.link(cache);
            var env = DevEnv.of(Map.of(
                    "FC_EMBEDDED_DB_PATH", root.resolve("flowcatalyst/embedded-pg").toString(),
                    "FC_DEV_PID_FILE", root.resolve("flowcatalyst/fcdev.pid").toString(),
                    "XDG_CACHE_HOME", cache.toString()));
            var sub = new StartCommand.Sub(env);
            int pgPort = TestPorts.belowEphemeralRange();
            new CommandLine(sub, new EnvFactory(env)).parseArgs("--api-port", "0", "--metrics-port", "0",
                    "--embedded-db-port", String.valueOf(pgPort),
                    "--router=false", "--stream=false", "--scheduler=false", "--scheduled-job=false");
            int[] seenUp = {0};
            var cmd = new StartCommand(env, new DevPaths(root, cache), sub.opts).afterPgStart(() -> {
                seenUp[0] = listening(pgPort) ? 1 : 2;
                throw new NoClassDefFoundError("injected");
            });
            try {
                assertThatThrownBy(cmd::launch).isInstanceOf(NoClassDefFoundError.class);
            } catch (AssertionError e) {
                Assumptions.abort("embedded PostgreSQL cannot start in this environment: " + e);
            }
            assertThat(seenUp[0]).as("postgres was up when the failure was injected").isEqualTo(1);
            assertThat(listening(pgPort)).as("postgres stopped after the failed boot").isFalse();
        } finally {
            EmbeddedPg.deleteTree(root);
        }
    }

    private static boolean listening(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("localhost", port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
