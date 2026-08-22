package io.flowcatalyst.fcdev;

import com.zaxxer.hikari.HikariDataSource;
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

    public StartCommand(DevEnv env, DevPaths paths, StartOptions opts) {
        this.env = env;
        this.paths = paths;
        this.opts = opts;
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
            LOG.warn("could not write pid file — `fcdev stop` won't find this instance path={} err={}", pidFile, e.toString());
        }

        EmbeddedPg pg = null;
        HikariDataSource pool = null;
        try {
            // ── embedded Postgres ─────────────────────────────────────────
            String databaseUrl = opts.databaseUrl();
            if (databaseUrl.isEmpty()) {
                if (!opts.embeddedDb()) {
                    throw new IllegalStateException("no --database-url given and --embedded-db=false; nothing to connect to");
                }
                Path dataPath = Path.of(opts.embeddedDbPath());
                if (opts.embeddedDbReset()) {
                    LOG.warn("wiping embedded Postgres data directory path={}", dataPath);
                    EmbeddedPg.deleteTree(dataPath);
                }
                EmbeddedPg.assertCompatible(dataPath);
                pg = EmbeddedPg.start(dataPath, opts.embeddedDbPort(), paths.embeddedPgCacheDir());
                databaseUrl = pg.url();
                LOG.info("embedded postgres started port={} path={} version=PG{}", pg.port(), dataPath, EmbeddedPg.pinnedMajor());
            }

            // ── connect + migrate + seed ──────────────────────────────────
            pool = Database.newPool(databaseUrl, Math.max(4, Runtime.getRuntime().availableProcessors()));
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
            Optional<Frontend> spa = Frontend.embedded();
            if (spa.isPresent()) {
                LOG.info("embedded Vue SPA available");
            } else {
                LOG.warn("frontend not embedded — this flowcatalyst-server build carries no SPA; API only");
            }
            Server.Running running = new Server(serverEnv, pool, spa, PrometheusRegistry.defaultRegistry).start();
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
        LOG.info("subsystem configuration api_port={} embedded_db={} embedded_db_port={} scheduler={} scheduled_job={} stream={} outbox={} router={} mcp={}",
                opts.apiPort(), opts.embeddedDb(), opts.embeddedDbPort(), opts.scheduler(), opts.scheduledJob(),
                opts.stream(), opts.outbox(), opts.router(), opts.mcp());
    }

    /// `devEnvCfg`: start from the (extended) environment so explicit `FC_*`
    /// overrides win, then apply the dev-friendly defaults: platform on, the
    /// `X-FC-Test-Principal` escape hatch on, subsystem toggles from the
    /// flags, the embedded Postgres broker unless `FC_DEFAULT_BROKER` is set.
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
                .setDefault("FC_DEFAULT_BROKER", "postgres");
        return Env.load(dev.toMap());
    }

    /// A running dev monolith. [#close()] is the Go deferred-cleanup chain in
    /// order: server drains, pool closes, embedded Postgres stops, PID file
    /// removed if still ours — each step runs even when an earlier one
    /// fails. Idempotent: the shutdown hook and `call()` may both invoke it.
    public static final class Started implements AutoCloseable {
        private final Server.Running running;
        private final HikariDataSource pool;
        private final EmbeddedPg pg;
        private final Path pidFile;
        private final long pid;
        // Guards the once-only teardown; set by whichever of the hook / call() gets there first.
        private final AtomicBoolean closed = new AtomicBoolean();

        Started(Server.Running running, HikariDataSource pool, EmbeddedPg pg, Path pidFile, long pid) {
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
