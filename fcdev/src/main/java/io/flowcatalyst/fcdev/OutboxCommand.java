package io.flowcatalyst.fcdev;

import io.flowcatalyst.platform.shared.database.GatedDataSource;
import io.flowcatalyst.mcp.TokenManager;
import io.flowcatalyst.outbox.HttpDispatcher;
import io.flowcatalyst.outbox.OutboxProcessor;
import io.flowcatalyst.outbox.PostgresOutboxRepository;
import io.flowcatalyst.platform.shared.database.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

/// `fcdev outbox` (Go `outbox.go`): a standalone outbox poller against an
/// external consumer app's Postgres database → an external FlowCatalyst
/// platform. Unlike `fcdev start --outbox` (which drains the embedded
/// platform's own `outbox_messages`), this points [PostgresOutboxRepository]
/// — the same repository, since the table shape is the SDK's, not the
/// platform's — at a database the developer names, for the case where the
/// consumer app's DB cannot be the embedded one (e.g. it already runs its
/// own Postgres with extensions fcdev doesn't bundle).
///
/// A `--env-file` (default `.env`) is loaded before flags are resolved, so
/// the `FC_OUTBOX_*` variables can live in the app's `.env` instead of being
/// exported — an explicit environment variable always wins over the file
/// ([DotEnv]).
@Command(name = "outbox", description = "Standalone outbox poller against an external app DB → external platform",
        mixinStandardHelpOptions = true, sortOptions = false, subcommands = {OutboxCommand.CreateTable.class})
public final class OutboxCommand implements Callable<Integer> {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxCommand.class);

    @Spec
    CommandSpec spec;

    @Option(names = "--env-file", paramLabel = "<file>", scope = ScopeType.INHERIT,
            description = "load environment from this dotenv file (does not override existing env) (default: .env)")
    String envFile = ".env";

    @Option(names = "--source-db-url", paramLabel = "<url>", description = "external app's Postgres URL (required) (FC_OUTBOX_SOURCE_DB_URL)")
    String sourceDbUrl;

    @Option(names = "--target-url", paramLabel = "<url>", description = "FlowCatalyst platform URL (FC_OUTBOX_PLATFORM_URL; default: http://localhost:8080)")
    String targetUrl = "http://localhost:8080";

    @Option(names = "--auth-token", paramLabel = "<token>", description = "static bearer token for the platform (used when client-id/secret are not set) (FC_OUTBOX_PLATFORM_AUTH_TOKEN)")
    String authToken;

    @Option(names = "--client-id", paramLabel = "<id>", description = "OAuth client_credentials client id (env FC_OUTBOX_CLIENT_ID, falls back to FLOWCATALYST_CLIENT_ID)")
    String clientId;

    @Option(names = "--client-secret", paramLabel = "<secret>", description = "OAuth client_credentials client secret (env FC_OUTBOX_CLIENT_SECRET, falls back to FLOWCATALYST_CLIENT_SECRET)")
    String clientSecret;

    @Option(names = "--token-url", paramLabel = "<url>", description = "OAuth token endpoint (env FC_OUTBOX_TOKEN_URL, default <target-url>/oauth/token)")
    String tokenUrl;

    @Option(names = "--scope", paramLabel = "<scope>", description = "optional requested scope to narrow the minted token (env FC_OUTBOX_SCOPE)")
    String scope;

    @Option(names = "--batch-size", paramLabel = "<n>", description = "rows per poll (0 = library default) (FC_OUTBOX_BATCH_SIZE)")
    int batchSize;

    @Option(names = "--max-in-flight", paramLabel = "<n>", description = "outstanding HTTP requests cap (0 = library default) (FC_OUTBOX_MAX_IN_FLIGHT)")
    int maxInFlight;

    @Option(names = "--poll-interval-ms", paramLabel = "<ms>", description = "sleep between empty polls in ms (0 = library default) (FC_OUTBOX_POLL_INTERVAL_MS)")
    int pollIntervalMs;

    final DevEnv env;

    public OutboxCommand() {
        this(DevEnv.system());
    }

    public OutboxCommand(DevEnv env) {
        this.env = Objects.requireNonNull(env, "env");
    }

    @Override
    public Integer call() throws Exception {
        Started started = launch();
        return blockUntilShutdown(started);
    }

    /// Everything up to (and including) [OutboxProcessor#start()]; separated
    /// from [#call()] so [OutboxCommandTest] can drive the running processor
    /// directly instead of waiting on a shutdown signal.
    Started launch() {
        var merged = DotEnv.loadOver(env, envFile);

        String sourceUrl = resolveStr("--source-db-url", sourceDbUrl, merged, "FC_OUTBOX_SOURCE_DB_URL");
        if (sourceUrl == null || sourceUrl.isBlank()) {
            throw new IllegalArgumentException("--source-db-url (or FC_OUTBOX_SOURCE_DB_URL) is required");
        }
        String target = resolveStr("--target-url", targetUrl, merged, "FC_OUTBOX_PLATFORM_URL");
        String authTok = resolveStr("--auth-token", authToken, merged, "FC_OUTBOX_PLATFORM_AUTH_TOKEN");
        String cid = resolveStr("--client-id", clientId, merged, "FC_OUTBOX_CLIENT_ID", "FLOWCATALYST_CLIENT_ID");
        String csecret = resolveStr("--client-secret", clientSecret, merged, "FC_OUTBOX_CLIENT_SECRET", "FLOWCATALYST_CLIENT_SECRET");
        String tokUrl = resolveStr("--token-url", tokenUrl, merged, "FC_OUTBOX_TOKEN_URL");
        String scopeVal = resolveStr("--scope", scope, merged, "FC_OUTBOX_SCOPE");
        int batch = resolveInt("--batch-size", batchSize, merged, "FC_OUTBOX_BATCH_SIZE");
        int maxInFlightVal = resolveInt("--max-in-flight", maxInFlight, merged, "FC_OUTBOX_MAX_IN_FLIGHT");
        int pollMs = resolveInt("--poll-interval-ms", pollIntervalMs, merged, "FC_OUTBOX_POLL_INTERVAL_MS");

        var pool = Database.newPool(sourceUrl, Math.max(4, Runtime.getRuntime().availableProcessors()));
        try {
            var repository = new PostgresOutboxRepository(pool);
            repository.initSchema();

            String authMode = authTok != null && !authTok.isBlank() ? "static-token" : "none";
            HttpDispatcher.TokenSource tokenSource = null;
            if (cid != null && !cid.isBlank() && csecret != null && !csecret.isBlank()) {
                String tokenBase = tokUrl != null && !tokUrl.isBlank() ? stripOauthTokenSuffix(tokUrl) : target;
                var tokenManager = new TokenManager(tokenBase, cid, csecret, scopeVal);
                tokenSource = tokenManagerSource(tokenManager);
                authMode = "client_credentials";
            }

            var dispatcher = new HttpDispatcher(HttpDispatcher.defaultClient(), target,
                    OutboxProcessor.Config.DEFAULT_HTTP_TIMEOUT, tokenSource, authTok);

            var d = OutboxProcessor.Config.defaults();
            var config = new OutboxProcessor.Config(
                    batch > 0 ? batch : d.batchSize(),
                    maxInFlightVal > 0 ? maxInFlightVal : d.maxInFlight(),
                    pollMs > 0 ? Duration.ofMillis(pollMs) : d.pollInterval(),
                    d.maxConcurrentGroups(), d.blockOnError(), d.maxRetries(),
                    d.recoveryInterval(), d.recoveryThreshold(), d.httpTimeout());

            // Standalone: no leader election — this process is always "the" poller.
            var processor = new OutboxProcessor(repository, dispatcher, config, () -> true);
            processor.start();
            LOG.info("fcdev outbox started source={} target={} auth={}", maskCredentials(sourceUrl), target, authMode);
            return new Started(pool, repository, processor);
        } catch (RuntimeException e) {
            pool.close();
            throw e;
        }
    }

    private int blockUntilShutdown(Started started) throws InterruptedException {
        var latch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("fcdev-outbox-shutdown").unstarted(() -> {
            LOG.info("shutdown signal received");
            started.close();
            LOG.info("fcdev outbox stopped");
            latch.countDown();
        }));
        latch.await();
        return 0;
    }

    /// A running standalone poller — [#close()] is [OutboxProcessor#close()]
    /// then the source pool, mirroring Go's deferred `pool.Close()` +
    /// `processor.Run` returning.
    static final class Started implements AutoCloseable {
        private final GatedDataSource pool;
        private final PostgresOutboxRepository repository;
        private final OutboxProcessor processor;

        Started(GatedDataSource pool, PostgresOutboxRepository repository, OutboxProcessor processor) {
            this.pool = pool;
            this.repository = repository;
            this.processor = processor;
        }

        PostgresOutboxRepository repository() {
            return repository;
        }

        OutboxProcessor processor() {
            return processor;
        }

        @Override
        public void close() {
            try {
                processor.close();
            } finally {
                pool.close();
            }
        }
    }

    private static HttpDispatcher.TokenSource tokenManagerSource(TokenManager tokenManager) {
        return new HttpDispatcher.TokenSource() {
            @Override
            public String token() {
                return tokenManager.token();
            }

            @Override
            public void invalidate() {
                tokenManager.invalidate();
            }
        };
    }

    /// A custom `--token-url` is a full endpoint; [TokenManager] wants a
    /// base URL and appends `/oauth/token` itself, so the suffix is
    /// stripped back off when present (an endpoint that does not end in it
    /// is used as-is, matching the base-URL contract as closely as an
    /// arbitrary custom endpoint allows).
    private static String stripOauthTokenSuffix(String url) {
        String trimmed = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        String suffix = "/oauth/token";
        return trimmed.endsWith(suffix) ? trimmed.substring(0, trimmed.length() - suffix.length()) : trimmed;
    }

    /// `user:PASSWORD@host` → `user:***@host` for the started-poller log
    /// line (`docs/spec/fcdev-commands.md` §3: "the source URL with the
    /// password masked") — a JDBC/Postgres URL routinely carries the
    /// database password in plain text right in the connection string.
    static String maskCredentials(String url) {
        if (url == null) {
            return null;
        }
        return url.replaceFirst("(://[^/@:]+):[^/@]*@", "$1:***@");
    }

    // ── env/flag resolution (Go `resolveEnvFlag(Multi)`) ────────────────────

    /// The explicit flag when the user passed it on the command line, else
    /// the first non-empty of `envKeys` in `env` (post-dotenv), else the
    /// flag's own baked-in default.
    String resolveStr(String optionName, String flagValue, DevEnv env, String... envKeys) {
        if (spec.commandLine().getParseResult().hasMatchedOption(optionName)) {
            return flagValue;
        }
        for (String key : envKeys) {
            var v = env.get(key);
            if (!v.isEmpty()) {
                return v;
            }
        }
        return flagValue;
    }

    int resolveInt(String optionName, int flagValue, DevEnv env, String envKey) {
        if (spec.commandLine().getParseResult().hasMatchedOption(optionName)) {
            return flagValue;
        }
        var v = env.get(envKey);
        if (!v.isEmpty()) {
            try {
                return Integer.parseInt(v.strip());
            } catch (NumberFormatException ignored) {
                // falls through to the baked default, exactly like Go's envIntDefault
            }
        }
        return flagValue;
    }

    // ── `fcdev outbox create-table` ──────────────────────────────────────

    /// `fcdev outbox create-table` (Go `outbox_create_table.go`): provisions
    /// the SDK `outbox_messages` table (postgres, mysql) in a consumer
    /// app's database — the runnable form of the per-language SDK
    /// migrations, so any supported store gets the outbox with one command
    /// instead of hand-run DDL. Idempotent (`CREATE TABLE IF NOT EXISTS`).
    ///
    /// MongoDB (`--db-type mongodb`) is in the Go flag surface and help text
    /// but is **not implemented** here — no MongoDB driver is part of this
    /// port's dependency set (CONVENTIONS §1: no new library without
    /// raising it first), so it fails fast with a clear message rather than
    /// silently doing nothing.
    @Command(name = "create-table", description = "Create the outbox_messages table/collection in a consumer app's DB",
            mixinStandardHelpOptions = true, sortOptions = false)
    public static final class CreateTable implements Callable<Integer> {

        @Spec
        CommandSpec spec;

        @Option(names = "--db-type", paramLabel = "<type>", description = "target store: postgres | mysql (mongodb is not yet ported) (FC_OUTBOX_BACKEND / FC_OUTBOX_DB_TYPE; default: postgres)")
        String dbType = "postgres";

        @Option(names = "--db-url", paramLabel = "<url>", description = "connection string/URL (required) (FC_OUTBOX_SOURCE_DB_URL / FC_OUTBOX_DB_URL / FC_OUTBOX_MONGO_URI)")
        String dbUrl;

        @Option(names = "--db-name", paramLabel = "<name>", description = "MongoDB database name (mongodb only) (FC_OUTBOX_MONGO_DB; default: flowcatalyst)")
        String dbName = "flowcatalyst";

        final DevEnv env;

        public CreateTable() {
            this(DevEnv.system());
        }

        public CreateTable(DevEnv env) {
            this.env = Objects.requireNonNull(env, "env");
        }

        @Override
        public Integer call() throws Exception {
            // The parent `outbox` command's `--env-file` is INHERIT-scoped
            // (Go's PersistentPreRunE loads it for every subcommand too);
            // this standalone subcommand loads the same default path
            // directly rather than reaching into a parent instance.
            var merged = DotEnv.loadOver(env, ".env");
            String type = resolveStr("--db-type", dbType, merged, "FC_OUTBOX_BACKEND", "FC_OUTBOX_DB_TYPE");
            String url = resolveStr("--db-url", dbUrl, merged, "FC_OUTBOX_SOURCE_DB_URL", "FC_OUTBOX_DB_URL", "FC_OUTBOX_MONGO_URI");
            // --db-name / FC_OUTBOX_MONGO_DB is accepted (Go parity) but unused —
            // mongodb is not implemented; see the "mongodb" case below.

            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException(
                        "--db-url (or FC_OUTBOX_SOURCE_DB_URL / FC_OUTBOX_DB_URL / FC_OUTBOX_MONGO_URI) is required");
            }

            PrintWriter out = spec.commandLine().getOut();
            return switch (normalize(type)) {
                case "postgres" -> createPostgres(out, url);
                case "mysql" -> createMysql(out, url);
                case "mongodb" -> {
                    // Not a validation error (the type IS recognised) — a
                    // deliberately unimplemented backend gets its own exit
                    // code, like the NotPorted stubs (docs/spec/fcdev-commands.md §3.1).
                    spec.commandLine().getErr().println("fcdev outbox create-table: mongodb is not supported in "
                            + "the Java fcdev (Mongo outbox backend is on the backlog)");
                    yield 2;
                }
                default -> throw new IllegalArgumentException(
                        "unknown --db-type \"" + type + "\": want postgres, mysql, or mongodb");
            };
        }

        private String resolveStr(String optionName, String flagValue, DevEnv env, String... envKeys) {
            if (spec.commandLine().getParseResult().hasMatchedOption(optionName)) {
                return flagValue;
            }
            for (String key : envKeys) {
                var v = env.get(key);
                if (!v.isEmpty()) {
                    return v;
                }
            }
            return flagValue;
        }

        /// Folds the accepted aliases onto a canonical key (Go `normalizeOutboxDBType`).
        static String normalize(String t) {
            if (t == null) return "";
            return switch (t.strip().toLowerCase(Locale.ROOT)) {
                case "pg", "postgres", "postgresql" -> "postgres";
                case "mysql", "mariadb", "maria" -> "mysql";
                case "mongo", "mongodb" -> "mongodb";
                default -> "";
            };
        }

        private Integer createPostgres(PrintWriter out, String url) {
            var pool = Database.newPool(url, 1);
            try {
                new PostgresOutboxRepository(pool).initSchema();
            } finally {
                pool.close();
            }
            out.println("Created outbox_messages table + indexes (postgres).");
            return 0;
        }

        /// `CREATE TABLE IF NOT EXISTS outbox_messages` for MySQL 8.0+ —
        /// verbatim from the Go SDK's `outboxsql.CreateOutboxTableSQLMySQL`,
        /// so the schema matches every SDK migration exactly. One statement
        /// (indexes are inline `INDEX (...)` clauses), so one `execute` call
        /// covers it — no multi-statement splitting needed.
        static final String CREATE_TABLE_SQL_MYSQL = """
                CREATE TABLE IF NOT EXISTS outbox_messages (
                    id VARCHAR(26) PRIMARY KEY,
                    type VARCHAR(20) NOT NULL,
                    message_group VARCHAR(255),
                    payload LONGTEXT NOT NULL,
                    status SMALLINT NOT NULL DEFAULT 0,
                    retry_count SMALLINT NOT NULL DEFAULT 0,
                    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                    error_message TEXT,
                    client_id VARCHAR(26),
                    payload_size BIGINT,
                    headers JSON,
                    INDEX idx_outbox_messages_pending (status, message_group, created_at),
                    INDEX idx_outbox_messages_stuck (status, created_at),
                    INDEX idx_outbox_client_pending (client_id, status, created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """;

        private Integer createMysql(PrintWriter out, String rawUrl) throws SQLException {
            String jdbcUrl = MysqlJdbcUrl.toJdbcUrl(rawUrl);
            try (Connection conn = DriverManager.getConnection(jdbcUrl);
                 Statement st = conn.createStatement()) {
                st.execute(CREATE_TABLE_SQL_MYSQL);
            }
            out.println("Created outbox_messages table + indexes (mysql).");
            return 0;
        }
    }
}
