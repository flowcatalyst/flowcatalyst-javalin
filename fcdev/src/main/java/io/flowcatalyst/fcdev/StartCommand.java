package io.flowcatalyst.fcdev;

import io.flowcatalyst.platform.shared.database.GatedDataSource;
import io.flowcatalyst.platform.shared.database.Database;
import io.flowcatalyst.server.Env;
import io.flowcatalyst.server.Frontend;
import io.flowcatalyst.server.Server;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/// `fcdev start` (Go `start.go`): the dev monolith.
///
///  1. banner
///  2. PID file (so `fcdev stop` can find us; removed on exit if still ours)
///  3. embedded Postgres — unless `--database-url`; `--embedded-db-reset` wipes first;
///     a cluster of another major is refused (→ `fcdev db upgrade`)
///  4. connect + migrate
///  5. dev defaults: bootstrap admin, persistent JWT signing key, persistent app key
///  6. seed
///  7. local MCP credentials (non-fatal)
///  8. `Env` for the shared `Server`: platform on, test headers on, toggles from
///     the flags, `FC_DEFAULT_BROKER=postgres` unless set, the DB URL
///  9. `Server.start()` with the embedded SPA; SIGINT/SIGTERM → stop server,
///     close pool, stop Postgres, remove PID file
public final class StartCommand implements Callable<Integer> {

    private static final Logger LOG = LoggerFactory.getLogger(StartCommand.class);

    /// `fcdev start`: the subcommand form.
    @Command(name = "start", description = "Run the dev monolith (identical to invoking fcdev with no subcommand)",
            mixinStandardHelpOptions = true, sortOptions = false)
    public static final class Sub implements Callable<Integer> {
        @Mixin
        final StartOptions opts;
        private final DevEnv env;
        private final DevPaths paths;

        public Sub() {
            this(DevEnv.system());
        }

        public Sub(DevEnv env) {
            this.env = env;
            this.paths = DevPaths.resolve(env.vars());
            this.opts = new StartOptions(env, paths);
        }

        @Override
        public Integer call() throws IOException, InterruptedException {
            return new StartCommand(env, paths, opts).call();
        }
    }

    private final DevEnv env;
    private final DevPaths paths;
    private final StartOptions opts;
    private final PrometheusRegistry registry;

    public StartCommand(DevEnv env, DevPaths paths, StartOptions opts) {
        this(env, paths, opts, PrometheusRegistry.defaultRegistry);
    }

    /// Test seam: a caller-supplied registry. `Server#start` registers
    /// process-global collectors (`AuthAlarms.collector()`, a `GatedDataSource`'s,
    /// the router's mediation-HTTP-version one…) that `Server.Running#stop`
    /// never deregisters, so a SECOND `StartCommand`-booted `Server` sharing
    /// the JVM-wide [PrometheusRegistry#defaultRegistry] with a first one
    /// still running in the same Surefire fork collides on registration. A
    /// dedicated `new PrometheusRegistry()` per test gives each boot its own.
    StartCommand(DevEnv env, DevPaths paths, StartOptions opts, PrometheusRegistry registry) {
        this.env = env;
        this.paths = paths;
        this.opts = opts;
        this.registry = registry;
    }

    /// `runStart`: start, block until a shutdown signal, tear down.
    ///
    /// The shutdown hook is the normal exit path (Ctrl-C, `fcdev stop`); the
    /// try-with-resources covers the programmatic one (an interrupted
    /// `awaitStop`), and [Started#close()] is idempotent so both may run.
    @Override
    public Integer call() throws IOException, InterruptedException {
        Started started = launch();
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("fcdev-shutdown").unstarted(() -> {
            LOG.info("shutdown signal received");
            started.close();
        }));
        try (started) {
            started.awaitStop();
        }
        return 0;
    }

    /// Everything up to (and including) `Server.start()`; returns the running
    /// instance so tests — and `call()` — can stop it. Throws on any failure
    /// after cleaning up what it had started.
    public Started launch() throws IOException {
        banner();

        // ── PID file ─────────────────────────────────────────────────────
        Path pidFile = Path.of(opts.pidFile());
        long pid = ProcessHandle.current().pid();
        boolean ownsPid = false;
        try {
            PidFile.write(pidFile, pid);
            ownsPid = true;
        } catch (IOException e) {
            LOG.atWarn().setMessage("could not write pid file — `fcdev stop` won't find this instance")
                    .addKeyValue("path", pidFile)
                    .setCause(e)
                    .log();
        }

        EmbeddedPg pg = null;
        GatedDataSource pool = null;
        try {
            // ── embedded Postgres ─────────────────────────────────────────
            String databaseUrl = opts.databaseUrl();
            if (databaseUrl.isEmpty()) {
                if (!opts.embeddedDb()) {
                    throw new IllegalStateException("no --database-url given and --embedded-db=false; nothing to connect to");
                }
                Path dataPath = Path.of(opts.embeddedDbPath());
                if (opts.embeddedDbReset()) {
                    LOG.atWarn().setMessage("wiping embedded Postgres data directory")
                            .addKeyValue("path", dataPath)
                            .log();
                    EmbeddedPg.deleteTree(dataPath);
                }
                EmbeddedPg.assertCompatible(dataPath);
                Path embeddedDbBinary = opts.embeddedDbBinary().isEmpty() ? null : Path.of(opts.embeddedDbBinary());
                pg = EmbeddedPg.start(dataPath, opts.embeddedDbPort(), paths.embeddedPgCacheDir(), embeddedDbBinary);
                databaseUrl = pg.url();
                LOG.atInfo().setMessage("embedded postgres started")
                        .addKeyValue("port", pg.port())
                        .addKeyValue("path", dataPath)
                        .addKeyValue("version", "PG" + EmbeddedPg.pinnedMajor())
                        .log();
            }

            // ── connect + migrate + seed ──────────────────────────────────
            pool = Database.newPool(databaseUrl);
            LOG.info("postgres connected");
            DevBootstrap.migrate(pool);

            var dev = env.mutable();
            DevBootstrap.seedAdminDefaults(dev);
            Path embeddedDbPath = Path.of(opts.embeddedDbPath());
            DevBootstrap.ensureSigningKey(dev, embeddedDbPath);
            DevBootstrap.ensureAppKey(dev, embeddedDbPath);
            DevBootstrap.seed(pool, dev.freeze());

            String mcpBaseUrl = "http://localhost:" + opts.apiPort();
            DevBootstrap.bootstrapMcpCredentials(pool, mcpBaseUrl, paths);

            // ── the shared server ─────────────────────────────────────────
            Env serverEnv = devEnv(dev, opts, databaseUrl);
            Server.Spa spa = Frontend.embeddedOrNone();
            switch (spa) {
                case Server.Spa.Embedded _ -> LOG.info("embedded Vue SPA available");
                case Server.Spa.None _ -> LOG.warn("frontend not embedded — this flowcatalyst-server build carries no SPA; API only");
            }
            Server.Running running = new Server(serverEnv, new Server.Mode.Platform(pool), spa, registry).start();
            return new Started(running, pool, pg, ownsPid ? pidFile : null, pid);
        } catch (IOException | RuntimeException e) {
            if (pool != null) pool.close();
            if (pg != null) pg.close();
            if (ownsPid) PidFile.removeIfOwned(pidFile, pid);
            throw e;
        }
    }

    /// `banner`: the startup summary.
    private void banner() {
        LOG.info("=== FlowCatalyst Dev Monolith ===");
        LOG.atInfo().setMessage("subsystem configuration")
                .addKeyValue("api_port", opts.apiPort())
                .addKeyValue("embedded_db", opts.embeddedDb())
                .addKeyValue("embedded_db_port", opts.embeddedDbPort())
                .addKeyValue("scheduler", opts.scheduler())
                .addKeyValue("scheduled_job", opts.scheduledJob())
                .addKeyValue("stream", opts.stream())
                .addKeyValue("outbox", opts.outbox())
                .addKeyValue("router", opts.router())
                .addKeyValue("mcp", opts.mcp())
                .log();
    }

    /// `devEnvCfg`: start from the (extended) environment so explicit `FC_*`
    /// overrides win, then apply the dev-friendly defaults: platform on, the
    /// `X-FC-Test-Principal` escape hatch on, subsystem toggles from the
    /// flags, the embedded Postgres broker unless `FC_DEFAULT_BROKER` is set.
    ///
    /// **`FLOWCATALYST_CONFIG_URL` defaults to fcdev's own served
    /// router-config document** (`docs/spec/deployed-dispatch.md` §3, R4,
    /// `docs/go-mirror/2026-09-12-dispatch-rulings.md`): dev and prod now run
    /// one code path — both learn their queues and pools from a served
    /// document, differing only in the queue *type* it names (Postgres here,
    /// SQS in prod). R3 put that document on the platform's INTERNAL
    /// listener (`FC_METRICS_PORT`), not the API one, so the URL is built off
    /// `opts.metricsPort()`, not `opts.apiPort()`. `setDefault`, not `set`, so
    /// an operator who has already pointed `FLOWCATALYST_CONFIG_URL`
    /// elsewhere (Integral, say) is never overridden.
    static Env devEnv(DevEnv.Mutable dev, StartOptions opts, String databaseUrl) {
        dev.set("FC_DATABASE_URL", databaseUrl)
                .set("FC_API_PORT", Integer.toString(opts.apiPort()))
                .set("FC_METRICS_PORT", Integer.toString(opts.metricsPort()))
                .set("FC_PLATFORM_ENABLED", "true")
                .set("FC_AUTH_ALLOW_TEST_HEADERS", "true")
                .set("FC_SCHEDULER_ENABLED", Boolean.toString(opts.scheduler()))
                .set("FC_SCHEDULED_JOB_ENABLED", Boolean.toString(opts.scheduledJob()))
                .set("FC_STREAM_PROCESSOR_ENABLED", Boolean.toString(opts.stream()))
                .set("FC_OUTBOX_ENABLED", Boolean.toString(opts.outbox()))
                .set("FC_ROUTER_ENABLED", Boolean.toString(opts.router()))
                .set("FC_MCP_ENABLED", Boolean.toString(opts.mcp()))
                // fcdev is the local-development binary by definition, so the
                // dev-only behaviour it gates is on unless explicitly refused:
                // with no SMTP host the mail transport logs the message body,
                // which is how a developer reads the login/2FA PIN. fc-server
                // still defaults this off, so a deployment that merely forgot
                // its SMTP settings never writes live PINs to its log.
                // setDefault, not set: FLOWCATALYST_DEV_MODE=false still wins.
                .setDefault("FLOWCATALYST_DEV_MODE", "true")
                .setDefault("FC_DEFAULT_BROKER", "postgres");
        // `--metrics-port 0` (an ephemeral port picked at bind time, e.g.
        // StartIntegrationTest) is not knowable here: Env is built and handed
        // to Server BEFORE the metrics listener binds, so there is no later
        // point at which the real port could be substituted in. Emitting
        // "http://localhost:0/..." would be a URL that can never work; refuse
        // to synthesise one instead; an operator who genuinely wants the
        // router to consume dispatch queues in this mode must set
        // FLOWCATALYST_CONFIG_URL explicitly once the port is known some
        // other way.
        if (opts.metricsPort() > 0) {
            dev.setDefault("FLOWCATALYST_CONFIG_URL",
                    "http://localhost:" + opts.metricsPort() + "/api/dispatch/router-config");
        } else {
            LOG.warn("--metrics-port 0 (ephemeral): fcdev cannot default FLOWCATALYST_CONFIG_URL to an address "
                    + "it does not know yet; set it explicitly if the router needs to consume dispatch queues here");
        }
        return Env.load(dev.toMap());
    }

    /// A running dev monolith. [#close()] is the Go deferred-cleanup chain in
    /// order: server drains, pool closes, embedded Postgres stops, PID file
    /// removed if still ours — each step runs even when an earlier one
    /// fails. Idempotent: the shutdown hook and `call()` may both invoke it.
    public static final class Started implements AutoCloseable {
        private final Server.Running running;
        private final GatedDataSource pool;
        private final EmbeddedPg pg;
        private final Path pidFile;
        private final long pid;
        // Guards the once-only teardown; set by whichever of the hook / call() gets there first.
        private final AtomicBoolean closed = new AtomicBoolean();

        Started(Server.Running running, GatedDataSource pool, EmbeddedPg pg, Path pidFile, long pid) {
            this.running = running;
            this.pool = pool;
            this.pg = pg;
            this.pidFile = pidFile;
            this.pid = pid;
        }

        /// The bound API port (differs from the flag when it was 0).
        public int apiPort() {
            return running.apiPort();
        }

        public int metricsPort() {
            return running.metricsPort();
        }

        /// The embedded Postgres, when one was started.
        public Optional<EmbeddedPg> embeddedPg() {
            return Optional.ofNullable(pg);
        }

        /// Blocks until [#close()] has stopped the server.
        public void awaitStop() throws InterruptedException {
            running.awaitStop();
        }

        @Override
        public void close() {
            if (closed.getAndSet(true)) return;
            try {
                running.stop();
            } finally {
                try {
                    pool.close();
                } finally {
                    try {
                        if (pg != null) pg.close();
                    } finally {
                        if (pidFile != null) PidFile.removeIfOwned(pidFile, pid);
                    }
                }
            }
        }
    }
}
