package io.flowcatalyst.fcdev;

import picocli.CommandLine.Option;

import java.util.Objects;

/// The `fcdev start` flag set (Go `startOpts` + `addStartFlags`), mirrored
/// onto the root command so `fcdev` and `fcdev start` accept the same
/// options. Defaults are seeded from the environment exactly as Go does —
/// `envIntDefault` / `envStrDefault` / `envBoolDefault` — then an explicit
/// flag wins:
///
/// | flag | env | default |
/// |---|---|---|
/// | `--api-port` | `FC_API_PORT` | 8080 |
/// | `--metrics-port` | `FC_METRICS_PORT` | 9090 |
/// | `--embedded-db` | `FC_EMBEDDED_DB` | true |
/// | `--embedded-db-port` | `FC_EMBEDDED_DB_PORT` | 15432 |
/// | `--embedded-db-path` | `FC_EMBEDDED_DB_PATH` | `<userDataDir>/flowcatalyst/embedded-pg` |
/// | `--embedded-db-reset` | — | false |
/// | `--embedded-db-binary` | `FC_EMBEDDED_DB_BINARY` | `""` (resolve from classpath/Maven Central) |
/// | `--database-url` | `FC_DATABASE_URL` | `""` (embedded) |
/// | `--scheduler` | `FC_SCHEDULER_ENABLED` | true |
/// | `--scheduled-job` | `FC_SCHEDULED_JOB_ENABLED` | true |
/// | `--stream` | `FC_STREAM_PROCESSOR_ENABLED` | true |
/// | `--outbox` | `FC_OUTBOX_ENABLED` | false |
/// | `--router` | `FC_ROUTER_ENABLED` | true |
/// | `--mcp` | `FC_MCP_ENABLED` | false |
/// | `--pid-file` | `FC_DEV_PID_FILE` | `<userDataDir>/flowcatalyst/fcdev.pid` |
///
/// Boolean flags take Go's `--flag=false` form (picocli `arity = "0..1"`), so
/// `fcdev --router=false` reads the same in both binaries.
public final class StartOptions {

    @Option(names = "--api-port", paramLabel = "<port>", description = "API server port (FC_API_PORT; default: ${DEFAULT-VALUE})")
    int apiPort;

    @Option(names = "--metrics-port", paramLabel = "<port>", description = "metrics server port (FC_METRICS_PORT; default: ${DEFAULT-VALUE})")
    int metricsPort;

    @Option(names = "--embedded-db", arity = "0..1", fallbackValue = "true", paramLabel = "<bool>",
            description = "start an embedded Postgres (FC_EMBEDDED_DB; default: ${DEFAULT-VALUE})")
    boolean embeddedDb;

    @Option(names = "--embedded-db-port", paramLabel = "<port>", description = "embedded Postgres port (FC_EMBEDDED_DB_PORT; default: ${DEFAULT-VALUE})")
    int embeddedDbPort;

    @Option(names = "--embedded-db-path", paramLabel = "<dir>", description = "embedded Postgres data directory (FC_EMBEDDED_DB_PATH; default: ${DEFAULT-VALUE})")
    String embeddedDbPath;

    @Option(names = "--embedded-db-reset", arity = "0..1", fallbackValue = "true", paramLabel = "<bool>",
            description = "wipe the embedded Postgres data directory before starting")
    boolean embeddedDbReset;

    @Option(names = "--embedded-db-binary", paramLabel = "<file>",
            description = "use this .txz instead of resolving one from the classpath or Maven Central (FC_EMBEDDED_DB_BINARY)")
    String embeddedDbBinary;

    @Option(names = "--database-url", paramLabel = "<url>", description = "Postgres URL (overrides --embedded-db) (FC_DATABASE_URL)")
    String databaseUrl;

    @Option(names = "--scheduler", arity = "0..1", fallbackValue = "true", paramLabel = "<bool>",
            description = "run the dispatch scheduler (FC_SCHEDULER_ENABLED; default: ${DEFAULT-VALUE})")
    boolean scheduler;

    @Option(names = "--scheduled-job", arity = "0..1", fallbackValue = "true", paramLabel = "<bool>",
            description = "run the scheduled-job cron scheduler (FC_SCHEDULED_JOB_ENABLED; default: ${DEFAULT-VALUE})")
    boolean scheduledJob;

    @Option(names = "--stream", arity = "0..1", fallbackValue = "true", paramLabel = "<bool>",
            description = "run the stream processor (FC_STREAM_PROCESSOR_ENABLED; default: ${DEFAULT-VALUE})")
    boolean stream;

    @Option(names = "--outbox", arity = "0..1", fallbackValue = "true", paramLabel = "<bool>",
            description = "run the outbox processor (FC_OUTBOX_ENABLED; default: ${DEFAULT-VALUE})")
    boolean outbox;

    @Option(names = "--router", arity = "0..1", fallbackValue = "true", paramLabel = "<bool>",
            description = "run the message router (uses the embedded Postgres broker by default) (FC_ROUTER_ENABLED; default: ${DEFAULT-VALUE})")
    boolean router;

    @Option(names = "--mcp", arity = "0..1", fallbackValue = "true", paramLabel = "<bool>",
            description = "run the MCP HTTP server (FC_MCP_ENABLED; default: ${DEFAULT-VALUE})")
    boolean mcp;

    @Option(names = "--pid-file", paramLabel = "<file>", description = "PID file written while running; used by `fcdev stop` (FC_DEV_PID_FILE; default: ${DEFAULT-VALUE})")
    String pidFile;

    /// Seeds from the process environment.
    public StartOptions() {
        this(DevEnv.system());
    }

    /// Seeds from `env` ([EnvFactory] uses this).
    public StartOptions(DevEnv env) {
        this(env, DevPaths.resolve(env.vars()));
    }

    /// Seed the defaults from `env` (Go `addStartFlags`).
    public StartOptions(DevEnv env, DevPaths paths) {
        Objects.requireNonNull(env, "env");
        Objects.requireNonNull(paths, "paths");
        apiPort = env.integer("FC_API_PORT", 8080);
        metricsPort = env.integer("FC_METRICS_PORT", 9090);
        embeddedDb = env.bool("FC_EMBEDDED_DB", true);
        embeddedDbPort = env.integer("FC_EMBEDDED_DB_PORT", EmbeddedPg.DEFAULT_PORT);
        embeddedDbPath = env.str("FC_EMBEDDED_DB_PATH", paths.defaultEmbeddedPath().toString());
        embeddedDbReset = false;
        embeddedDbBinary = env.str("FC_EMBEDDED_DB_BINARY", "");
        databaseUrl = env.str("FC_DATABASE_URL", "");
        scheduler = env.bool("FC_SCHEDULER_ENABLED", true);
        scheduledJob = env.bool("FC_SCHEDULED_JOB_ENABLED", true);
        stream = env.bool("FC_STREAM_PROCESSOR_ENABLED", true);
        outbox = env.bool("FC_OUTBOX_ENABLED", false);
        router = env.bool("FC_ROUTER_ENABLED", true);
        mcp = env.bool("FC_MCP_ENABLED", false);
        pidFile = env.str("FC_DEV_PID_FILE", paths.pidFilePath().toString());
    }

    public int apiPort() { return apiPort; }
    public int metricsPort() { return metricsPort; }
    public boolean embeddedDb() { return embeddedDb; }
    public int embeddedDbPort() { return embeddedDbPort; }
    public String embeddedDbPath() { return embeddedDbPath; }
    public boolean embeddedDbReset() { return embeddedDbReset; }
    public String embeddedDbBinary() { return embeddedDbBinary; }
    public String databaseUrl() { return databaseUrl; }
    public boolean scheduler() { return scheduler; }
    public boolean scheduledJob() { return scheduledJob; }
    public boolean stream() { return stream; }
    public boolean outbox() { return outbox; }
    public boolean router() { return router; }
    public boolean mcp() { return mcp; }
    public String pidFile() { return pidFile; }
}
