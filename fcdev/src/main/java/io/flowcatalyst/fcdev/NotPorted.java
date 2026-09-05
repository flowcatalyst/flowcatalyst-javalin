package io.flowcatalyst.fcdev;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

/// The Go subcommands that keep their flag surface here but are not yet
/// ported. Each prints `fcdev <name>: not yet ported` on stderr and exits 2
/// (so a script notices). Flags are accepted and ignored; see `docs/fcdev.md`.
///
/// `mcp`, `outbox` (+ `outbox create-table`) and `upgrade` have real
/// implementations now — [McpCommand], [OutboxCommand] (+
/// [OutboxCommand.CreateTable]) and [UpgradeCommand]. Only `init` remains
/// stubbed here.
public final class NotPorted {

    /// The exit code every stub returns.
    static final int EXIT_NOT_PORTED = 2;

    private NotPorted() {
    }

    static int notYetPorted(CommandSpec spec, String name, String hint) {
        var err = spec.commandLine().getErr();
        err.println("fcdev " + name + ": not yet ported in the Java fcdev" + (hint.isEmpty() ? "" : " — " + hint));
        err.flush();
        return EXIT_NOT_PORTED;
    }

    /// `fcdev init` (Go `init.go`): bootstrap admin user + default tenant + `.env`.
    @Command(name = "init", description = "Bootstrap a fresh local environment (admin user + default tenant + .env) [not yet ported]",
            mixinStandardHelpOptions = true, sortOptions = false)
    public static final class Init implements Callable<Integer> {
        @Spec CommandSpec spec;
        @Option(names = "--database-url", paramLabel = "<url>", description = "Postgres URL (defaults to local embedded) (FC_DATABASE_URL)") String databaseUrl;
        @Option(names = "--yes", description = "non-interactive — fail if any required value is missing from flags") boolean yes;
        @Option(names = "--root", paramLabel = "<dir>", description = "project root for the .env write (default: .)") String root = ".";
        @Option(names = "--admin-email", paramLabel = "<email>", description = "anchor admin email (FC_BOOTSTRAP_ADMIN_EMAIL)") String adminEmail;
        @Option(names = "--admin-password", paramLabel = "<password>", description = "anchor admin password (FC_BOOTSTRAP_ADMIN_PASSWORD)") String adminPassword;
        @Option(names = "--code", paramLabel = "<code>", description = "application code (URL-safe slug, e.g. \"orders\")") String code;
        @Option(names = "--name", paramLabel = "<name>", description = "application name") String name;
        @Option(names = "--app-type", paramLabel = "<type>", description = "application type: APPLICATION or INTEGRATION (default: APPLICATION)") String appType = "APPLICATION";
        @Option(names = "--description", paramLabel = "<text>", description = "application description (optional)") String description;
        @Option(names = "--default-base-url", paramLabel = "<url>", description = "application's deployed base URL (optional)") String defaultBaseUrl;
        @Option(names = "--client-identifier", paramLabel = "<id>", description = "default client identifier (default: default)") String clientIdentifier = "default";
        @Option(names = "--client-name", paramLabel = "<name>", description = "default client display name (default: Default Client)") String clientName = "Default Client";
        @Option(names = "--api-base-url", paramLabel = "<url>", description = "API base URL written to FLOWCATALYST_BASE_URL (default: http://localhost:8080)") String apiBaseUrl = "http://localhost:8080";

        @Override
        public Integer call() {
            return notYetPorted(spec, "init", "use the Go fcdev, or sign in with the bootstrap admin fcdev start creates");
        }
    }
}
