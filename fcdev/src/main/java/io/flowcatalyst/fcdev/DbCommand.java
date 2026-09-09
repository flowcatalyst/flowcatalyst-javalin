package io.flowcatalyst.fcdev;

import io.flowcatalyst.platform.shared.database.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.Callable;

/// `fcdev db` (Go `db.go`): embedded-database management. One subcommand,
/// `upgrade`.
@Command(name = "db", description = "Manage the embedded dev database", mixinStandardHelpOptions = true,
        subcommands = {DbCommand.Upgrade.class})
public final class DbCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        // Go prints the help for a bare `fcdev db`.
        spec.commandLine().usage(spec.commandLine().getOut());
        return 0;
    }

    /// `fcdev db upgrade`: bring the embedded data directory onto the major
    /// this fcdev embeds. Not in-place (and the bundled distribution ships no
    /// `pg_upgrade`), so: move the old cluster aside to
    /// `data.bak-pg<N>-<yyyyMMdd-HHmmss>` (or delete it with `--no-backup`),
    /// initialise a fresh cluster, re-run migrations + seed. Stop
    /// `fcdev start` first.
    @Command(name = "upgrade", description = "Re-initialise the embedded Postgres onto the major version this fcdev embeds",
            mixinStandardHelpOptions = true, sortOptions = false)
    public static final class Upgrade implements Callable<Integer> {

        private static final Logger LOG = LoggerFactory.getLogger(Upgrade.class);
        static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

        @Option(names = "--embedded-db-port", paramLabel = "<port>", description = "embedded Postgres port (FC_EMBEDDED_DB_PORT; default: ${DEFAULT-VALUE})")
        int embeddedDbPort;

        @Option(names = "--embedded-db-path", paramLabel = "<dir>", description = "embedded Postgres data directory (FC_EMBEDDED_DB_PATH; default: ${DEFAULT-VALUE})")
        String embeddedDbPath;

        @Option(names = "--no-backup", description = "delete the old data dir instead of backing it up")
        boolean noBackup;

        @Option(names = "--yes", description = "skip the confirmation prompt")
        boolean yes;

        @Spec
        CommandSpec spec;

        private final DevEnv env;
        private final DevPaths paths;
        /// Where the `Proceed? [y/N]` answer is read from; picocli has no
        /// stdin abstraction, so tests inject one here.
        private final InputStream in;

        public Upgrade() {
            this(DevEnv.system());
        }

        public Upgrade(DevEnv env) {
            this(env, System.in);
        }

        public Upgrade(DevEnv env, InputStream in) {
            this.env = env;
            this.paths = DevPaths.resolve(env.vars());
            this.in = in;
            this.embeddedDbPort = env.integer("FC_EMBEDDED_DB_PORT", EmbeddedPg.DEFAULT_PORT);
            this.embeddedDbPath = env.str("FC_EMBEDDED_DB_PATH", paths.defaultEmbeddedPath().toString());
        }

        @Override
        public Integer call() throws IOException {
            PrintWriter out = spec.commandLine().getOut();
            Path dataPath = Path.of(embeddedDbPath);
            var have = EmbeddedPg.dataMajor(dataPath);
            String target = EmbeddedPg.pinnedMajor();
            if (have.isEmpty()) {
                LOG.atInfo().setMessage("no embedded cluster yet — nothing to upgrade; 'fcdev start' will initialise it")
                        .addKeyValue("target", "PG" + target)
                        .log();
                return 0;
            }
            if (have.get().equals(target)) {
                LOG.atInfo().setMessage("embedded Postgres already on the target major — nothing to do")
                        .addKeyValue("version", "PG" + target)
                        .log();
                return 0;
            }

            Path dataDir = EmbeddedPg.clusterDir(dataPath);
            out.printf("Embedded Postgres upgrade: PG%s → PG%s%n  data dir: %s%n", have.get(), target, dataDir);
            if (noBackup) {
                out.println("  the old cluster will be DELETED (--no-backup)");
            } else {
                out.println("  the old cluster will be moved aside to a timestamped backup");
            }
            out.println("  a fresh cluster is initialised; migrations + bootstrap seed re-run");

            if (!yes && !confirm(out, "Proceed?")) {
                throw new IllegalStateException("aborted");
            }

            // 1. Move aside (or delete) the old cluster. The start-time version guard
            //    guarantees no server is running against it (a mismatch blocks `start`).
            if (noBackup) {
                EmbeddedPg.deleteTree(dataDir);
            } else {
                Path backup = backupPath(dataDir, have.get(), LocalDateTime.now());
                Files.move(dataDir, backup);
                LOG.atInfo().setMessage("old cluster backed up")
                        .addKeyValue("path", backup)
                        .log();
            }

            // 2 + 3. Fresh cluster on the target major, then migrate + seed.
            initEmbeddedAndSeed(dataPath);
            LOG.atInfo().setMessage("embedded Postgres upgraded")
                    .addKeyValue("version", "PG" + target)
                    .log();
            out.printf("Done — now on PG%s. Sign in with the bootstrap admin (%s).%n", target, DevBootstrap.DEV_ADMIN_EMAIL);
            out.flush();
            return 0;
        }

        /// `<dataDir>.bak-pg<have>-<yyyyMMdd-HHmmss>` (Go `time.Format("20060102-150405")`).
        static Path backupPath(Path dataDir, String haveMajor, LocalDateTime now) {
            return dataDir.resolveSibling(dataDir.getFileName() + ".bak-pg" + haveMajor + "-" + STAMP.format(now));
        }

        /// `initEmbeddedAndSeed`: the same first-run sequence `fcdev start` performs.
        private void initEmbeddedAndSeed(Path dataPath) throws IOException {
            try (EmbeddedPg pg = EmbeddedPg.start(dataPath, embeddedDbPort, paths.embeddedPgCacheDir());
                 var pool = Database.newPool(pg.url(), 4)) {
                DevBootstrap.migrate(pool);
                var dev = env.mutable();
                DevBootstrap.seedAdminDefaults(dev);
                DevBootstrap.seed(pool, dev.freeze());
            }
        }

        /// `confirm`: prompt on stdout, read a yes/no from stdin.
        private boolean confirm(PrintWriter out, String prompt) throws IOException {
            out.printf("%s [y/N]: ", prompt);
            out.flush();
            var line = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).readLine();
            if (line == null) return false;
            return switch (line.strip().toLowerCase(Locale.ROOT)) {
                case "y", "yes" -> true;
                default -> false;
            };
        }
    }
}
