package io.flowcatalyst.fcdev;

import io.flowcatalyst.platform.seed.FunctionDevBootstrap;
import io.flowcatalyst.platform.shared.database.Pools;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.server.Env;
import io.flowcatalyst.server.EnvReader;
import io.flowcatalyst.server.Frontend;
import io.flowcatalyst.server.Server;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
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
            sortOptions = false)
    public static final class Sub implements Callable<Integer> {
        @Option(names = {"-h", "--help"}, usageHelp = true, description = "show this help and exit")
        boolean help;

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
        Pools pools = null;
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
            // Four physical pools (admission.md §11.7), not one — sized from the
            // process/dev environment fcdev was started with, same as `FC_DB_POOL_SIZE`
            // would be read in production.
            pools = Pools.open(databaseUrl, new EnvReader(env.vars()));
            LOG.info("postgres connected");
            DevBootstrap.migrate(pools.api());

            var dev = env.mutable();
            DevBootstrap.seedAdminDefaults(dev);
            Path embeddedDbPath = Path.of(opts.embeddedDbPath());
            DevBootstrap.ensureSigningKey(dev, embeddedDbPath);
            DevBootstrap.ensureAppKey(dev, embeddedDbPath);
            DevBootstrap.seed(pools.api(), dev.freeze());

            String mcpBaseUrl = "http://localhost:" + opts.apiPort();
            DevBootstrap.bootstrapMcpCredentials(pools.api(), mcpBaseUrl, paths);
            // `docs/spec/router-config-auth.md` §3: runs regardless of `--router`
            // (`StartIntegrationTest` boots with `--router=false` and must stay
            // green) — the credentials, and the `FC_ROUTER_PLATFORM_URL` default,
            // are dev-environment setup, not conditional on the router being on.
            DevBootstrap.bootstrapRouterCredentials(pools.api(), dev, opts.apiPort());

            // `docs/spec/function-developer-surface.md` §1: the function clients —
            // idempotent, fresh secrets every boot, same as the router's above.
            // Skipped entirely under --no-functions (E3: no clients created).
            FunctionDevBootstrap.Result fnCreds = opts.functions()
                    ? DevBootstrap.bootstrapFunctionCredentials(pools.api(), dev)
                    : null;

            // ── the shared server ─────────────────────────────────────────
            Env serverEnv = devEnv(dev, opts, databaseUrl, paths);
            Server.Spa spa = Frontend.embeddedOrNone();
            switch (spa) {
                case Server.Spa.Embedded _ -> LOG.info("embedded Vue SPA available");
                case Server.Spa.None _ -> LOG.warn("frontend not embedded — this flowcatalyst-server build carries no SPA; API only");
            }
            Server.Running running = new Server(serverEnv, new Server.Mode.Platform(pools), spa, registry).start();

            // ── the function host ────────────────────────────────────────
            // Built AFTER Server#start, not before: the real bound API port is
            // needed for FC_FN_PLATFORM_URL (opts.apiPort() may be 0, an
            // ephemeral port picked at bind time — same reasoning as
            // #devEnv's own FLOWCATALYST_CONFIG_URL default and
            // DevBootstrap#bootstrapRouterCredentials's apiPort<=0 guard).
            FnHostLauncher.Result fnHost = null;
            Path fnCliJson = null;
            if (opts.functions() && fnCreds != null) {
                var settings = new FnHostLauncher.Settings("default",
                        "http://localhost:" + running.apiPort(),
                        fnCreds.host().clientId(), fnCreds.host().secret(),
                        opts.fnPort(), opts.fnPublicPort(), opts.fnMetricsPort(), paths.fnCacheDir(),
                        resolveHostJar(opts));
                fnHost = FnHostLauncher.launch(settings, FnHostLauncher.DEFAULT_IS_NATIVE,
                        FnHostLauncher.DEFAULT_JAVA_RESOLVER, FnHostLauncher.DEFAULT_PROCESS_STARTER);
                fnCliJson = writeFnCliCredentials(paths, running.apiPort(), opts.fnPort(), opts.fnPublicPort(),
                        fnCreds.cli());
                LOG.atInfo().setMessage("function host")
                        .addKeyValue("url", "http://127.0.0.1:" + opts.fnPort() + "/functions/…")
                        .log();
                // spec `function-public-routes.md` §5: "fcdev start also opens the public
                // listener ... banner line" — a literal `<name>.localhost` template, since no
                // real function is published yet at banner time.
                LOG.atInfo().setMessage("functions (public)  http://<name>.localhost:" + opts.fnPublicPort() + "/")
                        .log();
            }

            return new Started(running, pools, pg, ownsPid ? pidFile : null, pid, fnHost, fnCliJson);
        } catch (IOException | RuntimeException e) {
            if (pools != null) pools.close();
            if (pg != null) pg.close();
            if (ownsPid) PidFile.removeIfOwned(pidFile, pid);
            throw e;
        }
    }

    /// `--fn-host-jar` / `FC_FN_HOST_JAR`, else `fc-fnhost.jar` beside the
    /// running fcdev artifact (`UpgradeCommand#selfPath`'s own resolution) —
    /// `null` when neither is resolvable, so [FnHostLauncher] reports
    /// `Disabled` rather than fail `fcdev start`.
    private static Path resolveHostJar(StartOptions opts) {
        if (!opts.fnHostJar().isEmpty()) {
            return Path.of(opts.fnHostJar());
        }
        try {
            Path self = UpgradeCommand.selfPath();
            Path dir = self.getParent();
            return dir == null ? null : dir.resolve("fc-fnhost.jar");
        } catch (RuntimeException e) {
            return null;
        }
    }

    /// `fn-cli.json` (owner-only): the `fcdev-fn-cli` credentials, so `fcdev
    /// fn …` needs no flags locally. `publicUrl` (spec
    /// `function-public-routes.md` §5) is informational only today — no `fn`
    /// command reads it back yet, the way `hostUrl` feeds `fn invoke`'s
    /// default target (`FnCredentials#hostUrlFromFile`) — but it is written
    /// so a future `fn invoke --public`/documentation reader has it without
    /// a wire-shape change.
    private static Path writeFnCliCredentials(DevPaths paths, int apiPort, int fnPort, int fnPublicPort,
                                               FunctionDevBootstrap.Credentials cli) throws IOException {
        Path path = paths.fnCliCredentialsPath();
        var body = new FnCliCredentialsFile("http://localhost:" + apiPort, cli.clientId(), cli.secret(),
                "http://127.0.0.1:" + fnPort, "http://127.0.0.1:" + fnPublicPort);
        OwnerOnlyFile.write(path, Json.MAPPER.writeValueAsString(body));
        return path;
    }

    /// The wire shape `fn-cli.json` carries (spec §1, extended by §5).
    private record FnCliCredentialsFile(String platformUrl, String clientId, String clientSecret, String hostUrl,
                                         String publicUrl) {
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
    /// router-config document** (`docs/spec/router-config-auth.md` §3, R3′;
    /// superseding R3/R4's internal-listener placement, `docs/go-mirror/2026-09-12-dispatch-rulings.md`):
    /// dev and prod now run one code path — both learn their queues and
    /// pools from a served document, differing only in the queue *type* it
    /// names (Postgres here, SQS in prod). The document now lives on the API
    /// listener behind ordinary bearer auth, so the URL is built off
    /// `opts.apiPort()` — always known, unlike the old internal-listener
    /// `--metrics-port 0` case, so there is no "cannot default it" branch any
    /// more. `setDefault`, not `set`, so an operator who has already pointed
    /// `FLOWCATALYST_CONFIG_URL` elsewhere (Integral, say) is never
    /// overridden.
    static Env devEnv(DevEnv.Mutable dev, StartOptions opts, String databaseUrl, DevPaths paths) {
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
        // spec `function-artifact-upload.md` §2: "`fcdev start` sets it to
        // file://<state>/fn-artifacts when unset, so the dev loop needs nothing" —
        // the SAME directory the CLI's local-publish store used to write into
        // directly, now the platform's own upload/download routes own it.
        // setDefault, not set: an operator who already pointed FC_FN_ARTIFACT_STORE
        // elsewhere (a real S3 bucket, say) keeps that value.
        // Path#toUri, not "file://" + path: the default state directory on macOS is
        // `~/Library/Application Support/…`, and a space is not a URI character —
        // string concatenation produced a value the platform refused at startup.
        dev.setDefault("FC_FN_ARTIFACT_STORE", paths.fnArtifactsDir().toUri().toString());
        // `docs/spec/function-developer-surface.md` §1: the PLATFORM's own
        // function-publish settings — signature verification off (dev mode is
        // already on) and the pool-URL template pointed at fcdev's own
        // function host, no {pool} placeholder (a single-pool dev setup names
        // the host directly, PoolUrlTemplate R8). setDefault, not set: an
        // operator who already pointed either elsewhere keeps their value.
        // Skipped entirely under --no-functions (E3).
        if (opts.functions()) {
            dev.setDefault("FC_FN_SIGNATURES", "off");
            dev.setDefault("FC_FN_POOL_URL", "http://127.0.0.1:" + opts.fnPort());
        }
        // `--api-port 0` (an ephemeral port picked at bind time, e.g. the
        // integration tests) is not knowable here: Env is built and handed
        // to Server BEFORE the API listener binds. "http://localhost:0/..."
        // would be a URL that can never work, so refuse to synthesise one;
        // DevBootstrap#bootstrapRouterCredentials skips for the same reason,
        // and the router then simply runs with no queues in that mode.
        if (opts.apiPort() > 0) {
            dev.setDefault("FLOWCATALYST_CONFIG_URL",
                    "http://localhost:" + opts.apiPort() + "/api/dispatch/router-config");
        } else {
            LOG.warn("--api-port 0 (ephemeral): fcdev cannot default FLOWCATALYST_CONFIG_URL to an address "
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
        private final Pools pools;
        private final EmbeddedPg pg;
        private final Path pidFile;
        private final long pid;
        private final FnHostLauncher.Result fnHost;
        private final Path fnCliJson;
        // Guards the once-only teardown; set by whichever of the hook / call() gets there first.
        private final AtomicBoolean closed = new AtomicBoolean();

        Started(Server.Running running, Pools pools, EmbeddedPg pg, Path pidFile, long pid,
                FnHostLauncher.Result fnHost, Path fnCliJson) {
            this.running = running;
            this.pools = pools;
            this.pg = pg;
            this.pidFile = pidFile;
            this.pid = pid;
            this.fnHost = fnHost;
            this.fnCliJson = fnCliJson;
        }

        /// The function host launch result — `null` under `--no-functions`.
        /// Package-visible: this module's own tests drive it (E1/E2).
        FnHostLauncher.Result fnHost() {
            return fnHost;
        }

        /// The bound API port (differs from the flag when it was 0).
        public int apiPort() {
            return running.apiPort();
        }

        public int metricsPort() {
            return running.metricsPort();
        }

        /// The running server itself — for this module's integration tests,
        /// which watch the router adopt fcdev's own served document.
        Server.Running running() {
            return running;
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
                // The function host stops BEFORE the platform (spec §1): it
                // heartbeats `DRAINING` on the way down, which needs a
                // platform that is still up to heartbeat to.
                if (fnHost != null) {
                    FnHostLauncher.close(fnHost);
                }
            } finally {
                if (fnCliJson != null) {
                    try {
                        Files.deleteIfExists(fnCliJson);
                    } catch (IOException ignored) {
                        // best effort — a leftover credentials file is not fatal
                    }
                }
                try {
                    running.stop();
                } finally {
                    try {
                        pools.close();
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
}
