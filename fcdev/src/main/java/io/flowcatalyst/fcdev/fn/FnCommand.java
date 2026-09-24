package io.flowcatalyst.fcdev.fn;

import io.flowcatalyst.fcdev.DevEnv;
import io.flowcatalyst.fcdev.DevPaths;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;
import picocli.CommandLine.Spec;

import java.util.Objects;
import java.util.concurrent.Callable;

/// `fcdev fn` (`docs/spec/function-developer-surface.md` §2): the developer
/// surface for functions — publish, promote, deploy, status, versions,
/// retire, config, secret, invoke, watch. [FnClient] is the only class that
/// talks HTTP; every subcommand here builds a command, calls it, and prints
/// the result in [#output] mode.
///
/// The four global options (`--platform-url`, `--client-id`,
/// `--client-secret`, `--output`) are declared here with
/// [ScopeType#INHERIT] so every subcommand — including the nested `config`/
/// `secret` ones two levels down — accepts them on ITS OWN command line
/// (picocli parses an inherited option wherever it appears in the
/// invocation). Reading the resolved value back still requires walking up to
/// THIS instance (picocli populates the option on the object that declared
/// it, not on each subcommand), which is what {@link #of(CommandSpec)} is
/// for.
@Command(name = "fn", description = "Manage functions: init, publish, promote, deploy, status, versions, retire, config, secret, invoke, watch, domain, alias",
        sortOptions = false,
        subcommands = {
                PublishCommand.class,
                PromoteCommand.class,
                DeployCommand.class,
                StatusCommand.class,
                VersionsCommand.class,
                RetireCommand.class,
                ConfigCommand.class,
                SecretCommand.class,
                InvokeCommand.class,
                WatchCommand.class,
                DomainCommand.class,
                AliasCommand.class,
                InitCommand.class,
                CommandLine.HelpCommand.class})
public final class FnCommand implements Callable<Integer> {

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "show this help and exit")
    boolean help;

    @Option(names = "--platform-url", paramLabel = "<url>", scope = ScopeType.INHERIT,
            description = "platform base URL (FLOWCATALYST_PLATFORM_URL; else fn-cli.json's, from `fcdev start`)")
    String platformUrl;

    @Option(names = "--client-id", paramLabel = "<id>", scope = ScopeType.INHERIT,
            description = "OAuth client id (FLOWCATALYST_CLIENT_ID; else fn-cli.json)")
    String clientId;

    @Option(names = "--client-secret", paramLabel = "<secret>", scope = ScopeType.INHERIT,
            description = "OAuth client secret (FLOWCATALYST_CLIENT_SECRET; else fn-cli.json)")
    String clientSecret;

    @Option(names = "--output", paramLabel = "<mode>", scope = ScopeType.INHERIT, defaultValue = "text",
            converter = OutputMode.Converter.class,
            description = "output mode: text|json (default: ${DEFAULT-VALUE})")
    OutputMode output;

    @Spec
    CommandSpec spec;

    private final DevEnv env;
    private final DevPaths paths;

    public FnCommand() {
        this(DevEnv.system());
    }

    public FnCommand(DevEnv env) {
        this.env = Objects.requireNonNull(env, "env");
        this.paths = DevPaths.resolve(env.vars());
    }

    /// A bare `fcdev fn` prints its help (Go cobra's convention for a
    /// command that only groups subcommands, same as [io.flowcatalyst.fcdev.DbCommand]).
    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getOut());
        return 0;
    }

    public OutputMode output() {
        return output == null ? OutputMode.TEXT : output;
    }

    public DevEnv env() {
        return env;
    }

    public DevPaths paths() {
        return paths;
    }

    /// Resolves credentials for this invocation (flags → env → `fn-cli.json`
    /// → [FnCredentials.MissingException]).
    public FnCredentials credentials() {
        return FnCredentials.resolve(platformUrl, clientId, clientSecret, env, paths);
    }

    /// A ready-to-use [FnClient] for the resolved credentials.
    public FnClient client() {
        FnCredentials creds = credentials();
        return new FnClient(creds.platformUrl(), creds.clientId(), creds.clientSecret());
    }

    /// An [FnClient] for `fn invoke`'s host calls: the resolved credentials
    /// when available (so a versioned call's bearer token works), but never
    /// throws when they are not — an unversioned call to the function HOST
    /// needs no platform credentials at all (spec §4 E7).
    public FnClient hostClient() {
        try {
            return client();
        } catch (FnCredentials.MissingException e) {
            return FnClient.rawOnly();
        }
    }

    /// Runs `body`, mapping the two ordinary runtime failures — a platform/host
    /// HTTP error, or missing credentials — to ONE line on stderr and exit 1
    /// (`docs/spec/function-developer-surface.md` §2: "the platform's `code`
    /// and `message` printed, never a stack trace"). A [CommandLine.ParameterException]
    /// (a usage error raised from business logic, e.g. [AddressOptions]) is
    /// NOT caught here — picocli's own `execute()` recognises it specially
    /// and answers exit 2 with usage help. Anything else is a genuine bug and
    /// is left to propagate to [io.flowcatalyst.fcdev.FcDev]'s own handler.
    public static int runSafely(CommandSpec spec, java.util.concurrent.Callable<Integer> body) {
        try {
            return body.call();
        } catch (FnClientException e) {
            spec.commandLine().getErr().println(e.oneLine());
            return 1;
        } catch (FnCredentials.MissingException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 1;
        } catch (java.io.IOException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 1;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /// Finds the [FnCommand] instance at the root of `spec`'s command tree —
    /// however many `config`/`secret` levels deep `spec` itself is. Every `fn`
    /// leaf subcommand calls this once to reach the shared global options.
    public static FnCommand of(CommandSpec spec) {
        CommandLine cl = spec.commandLine();
        while (cl != null && !(cl.getCommand() instanceof FnCommand)) {
            cl = cl.getParent();
        }
        if (cl == null) {
            throw new IllegalStateException("no FnCommand in this command's ancestry: " + spec.qualifiedName());
        }
        return (FnCommand) cl.getCommand();
    }
}
