package io.flowcatalyst.fcdev;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

/// The Go subcommands that keep their flag surface here but are not yet
/// ported. Each prints `fcdev <name>: not yet ported` on stderr and exits 2
/// (so a script notices). Flags are accepted and ignored; see `docs/fcdev.md`.
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

    /// `fcdev mcp` (Go `mcp.go`): the FlowCatalyst MCP server.
    @Command(name = "mcp", description = "Run the FlowCatalyst MCP server (stdio by default; --http to listen) [not yet ported]",
            mixinStandardHelpOptions = true, sortOptions = false)
    public static final class Mcp implements Callable<Integer> {
        @Spec CommandSpec spec;
        @Option(names = "--http", paramLabel = "<addr>", description = "listen for streamable-HTTP MCP at this bind address (e.g. 127.0.0.1:8090); empty = stdio") String http;
        @Option(names = "--platform-url", paramLabel = "<url>", description = "override FLOWCATALYST_URL (platform base URL)") String platformUrl;
        @Option(names = "--client-id", paramLabel = "<id>", description = "override FLOWCATALYST_CLIENT_ID") String clientId;
        @Option(names = "--client-secret", paramLabel = "<secret>", description = "override FLOWCATALYST_CLIENT_SECRET") String clientSecret;

        @Override
        public Integer call() {
            return notYetPorted(spec, "mcp", "");
        }
    }

    /// `fcdev outbox` (Go `outbox.go`): standalone outbox poller, plus `create-table`.
    @Command(name = "outbox", description = "Standalone outbox poller against an external app DB → external platform [not yet ported]",
            mixinStandardHelpOptions = true, sortOptions = false, subcommands = {OutboxCreateTable.class})
    public static final class Outbox implements Callable<Integer> {
        @Spec CommandSpec spec;
        @Option(names = "--env-file", paramLabel = "<file>", scope = ScopeType.INHERIT,
                description = "load environment from this dotenv file (does not override existing env) (default: .env)") String envFile = ".env";
        @Option(names = "--source-db-url", paramLabel = "<url>", description = "external app's Postgres URL (required) (FC_OUTBOX_SOURCE_DB_URL)") String sourceDbUrl;
        @Option(names = "--target-url", paramLabel = "<url>", description = "FlowCatalyst platform URL (FC_OUTBOX_PLATFORM_URL; default: http://localhost:8080)") String targetUrl;
        @Option(names = "--auth-token", paramLabel = "<token>", description = "static bearer token for the platform (used when client-id/secret are not set) (FC_OUTBOX_PLATFORM_AUTH_TOKEN)") String authToken;
        @Option(names = "--client-id", paramLabel = "<id>", description = "OAuth client_credentials client id (env FC_OUTBOX_CLIENT_ID, falls back to FLOWCATALYST_CLIENT_ID)") String clientId;
        @Option(names = "--client-secret", paramLabel = "<secret>", description = "OAuth client_credentials client secret (env FC_OUTBOX_CLIENT_SECRET, falls back to FLOWCATALYST_CLIENT_SECRET)") String clientSecret;
        @Option(names = "--token-url", paramLabel = "<url>", description = "OAuth token endpoint (env FC_OUTBOX_TOKEN_URL, default <target-url>/oauth/token)") String tokenUrl;
        @Option(names = "--scope", paramLabel = "<scope>", description = "optional requested scope to narrow the minted token (env FC_OUTBOX_SCOPE)") String scope;
        @Option(names = "--batch-size", paramLabel = "<n>", description = "rows per poll (0 = library default) (FC_OUTBOX_BATCH_SIZE)") int batchSize;
        @Option(names = "--max-in-flight", paramLabel = "<n>", description = "outstanding HTTP requests cap (0 = library default) (FC_OUTBOX_MAX_IN_FLIGHT)") int maxInFlight;
        @Option(names = "--poll-interval-ms", paramLabel = "<ms>", description = "sleep between empty polls in ms (0 = library default) (FC_OUTBOX_POLL_INTERVAL_MS)") int pollIntervalMs;

        @Override
        public Integer call() {
            return notYetPorted(spec, "outbox", "");
        }
    }

    /// `fcdev outbox create-table` (Go `outbox_create_table.go`).
    @Command(name = "create-table", description = "Create the outbox_messages table/collection in a consumer app's DB [not yet ported]",
            mixinStandardHelpOptions = true, sortOptions = false)
    public static final class OutboxCreateTable implements Callable<Integer> {
        @Spec CommandSpec spec;
        @Option(names = "--db-type", paramLabel = "<type>", description = "target store: postgres | mysql | mongodb (FC_OUTBOX_BACKEND / FC_OUTBOX_DB_TYPE; default: postgres)") String dbType;
        @Option(names = "--db-url", paramLabel = "<url>", description = "connection string/URL (required) (FC_OUTBOX_SOURCE_DB_URL / FC_OUTBOX_DB_URL / FC_OUTBOX_MONGO_URI)") String dbUrl;
        @Option(names = "--db-name", paramLabel = "<name>", description = "MongoDB database name (mongodb only) (FC_OUTBOX_MONGO_DB; default: flowcatalyst)") String dbName;

        @Override
        public Integer call() {
            return notYetPorted(spec, "outbox create-table", "");
        }
    }

    /// `fcdev upgrade` (Go `upgrade.go`): self-update from GitHub Releases.
    @Command(name = "upgrade", description = "Update fcdev to the latest release [not yet ported]",
            mixinStandardHelpOptions = true, sortOptions = false)
    public static final class Upgrade implements Callable<Integer> {
        @Spec CommandSpec spec;
        @Option(names = "--check", description = "only report whether a newer release exists; don't install") boolean check;
        @Option(names = "--force", description = "reinstall even if already on the latest version") boolean force;

        @Override
        public Integer call() {
            return notYetPorted(spec, "upgrade", "with JBang: `jbang app install --force fcdev@<catalog>`; with the jar: download the latest release");
        }
    }
}
