package io.flowcatalyst.fcdev;

import io.flowcatalyst.server.Logging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/// `fcdev` — the FlowCatalyst developer monolith (Go `cmd/fcdev`): every
/// subsystem in one process against an embedded PostgreSQL. No Docker, no
/// compose, no separate migration step.
///
/// ```
/// fcdev start        run the dev monolith (default; `fcdev` alone is the same)
/// fcdev stop         stop a running dev monolith (graceful; via its PID file)
/// fcdev fresh        truncate every FlowCatalyst table (preserves schema)
/// fcdev db upgrade   re-initialise the embedded Postgres onto the bundled major
/// fcdev version      print the version
/// fcdev init | mcp | outbox | upgrade    not yet ported (flags accepted, exit 2)
/// ```
///
/// The root command carries the `start` flag set so `fcdev --api-port 9000`
/// and `fcdev start --api-port 9000` are the same invocation.
@Command(name = "fcdev",
        description = {"FlowCatalyst Development Monolith — all components in one binary",
                "",
                "fcdev runs every FlowCatalyst subsystem in one process against an embedded Postgres database. "
                        + "Designed for local development: no Docker, no docker-compose, no separate migration step.",
                "",
                "Invoking `fcdev` with no subcommand is identical to `fcdev start`."},
        versionProvider = FcDev.VersionProvider.class,
        sortOptions = false,
        subcommands = {
                StartCommand.Sub.class,
                StopCommand.class,
                NotPorted.Init.class,
                FreshCommand.class,
                NotPorted.Mcp.class,
                NotPorted.Outbox.class,
                DbCommand.class,
                NotPorted.Upgrade.class,
                VersionCommand.class,
                CommandLine.HelpCommand.class})
public final class FcDev implements Callable<Integer> {

    private static final Logger LOG = LoggerFactory.getLogger(FcDev.class);

    @Mixin
    final StartOptions opts;

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "show this help and exit")
    boolean help;

    @Option(names = {"-v", "--version"}, versionHelp = true, description = "print the version and exit")
    boolean version;

    private final DevEnv env;
    private final DevPaths paths;

    public FcDev() {
        this(DevEnv.system());
    }

    public FcDev(DevEnv env) {
        this.env = env;
        this.paths = DevPaths.resolve(env.vars());
        this.opts = new StartOptions(env, paths);
    }

    /// Bare `fcdev` ≡ `fcdev start`.
    @Override
    public Integer call() throws Exception {
        return new StartCommand(env, paths, opts).call();
    }

    /// `fcdev --version` prints the same line as `fcdev version`.
    public static final class VersionProvider implements IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[]{Version.line()};
        }
    }

    /// The command tree, wired to `env`, ready to `execute(args)`.
    public static CommandLine commandLine(DevEnv env) {
        var cl = new CommandLine(new FcDev(env), new EnvFactory(env));
        // Runtime failures (port in use, DB unreachable) shouldn't trigger a usage dump —
        // that noise hides the real error line. Log + exit 1, as the Go main does.
        cl.setExecutionExceptionHandler((ex, _, _) -> {
            if (LOG.isDebugEnabled()) {
                LOG.error("fcdev exited with error err={}", ex.toString(), ex);
            } else {
                LOG.error("fcdev exited with error err={}", ex.getMessage() == null ? ex.toString() : ex.getMessage());
            }
            return 1;
        });
        return cl;
    }

    public static void main(String[] args) {
        var env = DevEnv.system();
        Logging.init(env.vars());
        System.exit(commandLine(env).execute(args));
    }
}
