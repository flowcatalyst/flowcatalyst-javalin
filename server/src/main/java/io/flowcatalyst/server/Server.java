package io.flowcatalyst.server;

import io.flowcatalyst.platform.scheduler.DispatchPublisher;
import io.flowcatalyst.platform.scheduler.DispatchScheduler;
import io.flowcatalyst.platform.scheduler.NoopPublisher;
import io.flowcatalyst.platform.scheduler.PostgresQueuePublisher;
import io.flowcatalyst.platform.scheduler.jobs.ScheduledJobScheduler;
import io.flowcatalyst.platform.dispatchjob.DispatchJobReaper;
import io.flowcatalyst.platform.purger.Purger;
import io.flowcatalyst.http.RouteRegistry;
import io.flowcatalyst.platform.shared.database.GatedDataSource;
import java.time.Instant;
import io.flowcatalyst.platform.loginattempt.LoginAttemptRepository;
import io.flowcatalyst.platform.auth.ratelimit.RateLimit;
import io.flowcatalyst.platform.auth.login.AuthAlarms;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.http.javalin.JavalinJsonMapper;
import io.flowcatalyst.mcp.McpConfig;
import io.flowcatalyst.mcp.McpServer;
import io.flowcatalyst.mcp.PlatformClient;
import io.flowcatalyst.mcp.TokenManager;
import io.flowcatalyst.outbox.HttpDispatcher;
import io.flowcatalyst.outbox.OutboxAdminApi;
import io.flowcatalyst.outbox.OutboxProcessor;
import io.flowcatalyst.outbox.PostgresOutboxRepository;
import io.flowcatalyst.router.queue.postgres.PostgresQueue;
import io.flowcatalyst.router.standby.LeaderElection;
import io.flowcatalyst.router.standby.RedisLockStore;
import io.flowcatalyst.stream.StreamProcessor;
import io.javalin.Javalin;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.SslOptions;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.providers.PooledConnectionProvider;

import javax.sql.DataSource;
import java.net.URI;
import java.time.Duration;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;

/// The single orchestrator fc-server and fcdev both call (Go `server.Run`):
///
///   - build the API app and wire the platform aggregates ([Mode.Platform])
///   - mount the router HTTP surface under `routerHttpPrefix` (when `routerEnabled`)
///   - spawn the background subsystems (scheduler, stream, outbox, router engine, mcp, purger)
///   - bind the API + metrics (+ optional MCP) listeners
///   - on [#stop()] (SIGTERM / fcdev stop): stop accepting, drain, stop subsystems
///
/// @param mode what this instance is: the platform API over a database, a
///             database-backed worker, or a router/MCP-only node — the pool
///             travels inside the mode, so "platform enabled but no pool" is
///             not a state this record can be in. The entry points derive it
///             from `FC_PLATFORM_ENABLED` and the other toggles; the record
///             refuses an [Env] that disagrees with it.
/// @param spa  the embedded SPA, served for anything no API route claims
///             ([Spa.Embedded]) or nothing at all ([Spa.None] — a router-only
///             instance must not serve the dashboard)
public record Server(Env env, Mode mode, Spa spa, PrometheusRegistry registry) {

    private static final Logger LOG = LoggerFactory.getLogger(Server.class);
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(30);

    public Server {
        Objects.requireNonNull(env, "env");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(spa, "spa");
        Objects.requireNonNull(registry, "registry");
        if (env.platformEnabled() != (mode instanceof Mode.Platform)) {
            throw new IllegalArgumentException("FC_PLATFORM_ENABLED=" + env.platformEnabled()
                    + " but the server mode is " + mode.getClass().getSimpleName());
        }
    }

    /// What the instance runs, and therefore whether it owns a database pool.
    public sealed interface Mode permits Mode.Platform, Mode.Worker, Mode.RouterOnly {

        /// The platform API is served from `pool`; any enabled DB-backed
        /// background subsystem shares it.
        record Platform(DataSource pool) implements Mode {
            public Platform {
                Objects.requireNonNull(pool, "pool");
            }
        }

        /// DB-backed background subsystems (scheduler, stream, outbox …)
        /// without the platform API — the worker tier.
        record Worker(DataSource pool) implements Mode {
            public Worker {
                Objects.requireNonNull(pool, "pool");
            }
        }

        /// No database: the router and/or MCP surfaces only.
        enum RouterOnly implements Mode {
            INSTANCE
        }

        static Mode routerOnly() {
            return RouterOnly.INSTANCE;
        }
    }

    /// Whether the embedded Vue SPA is mounted as the catch-all.
    public sealed interface Spa permits Spa.Embedded, Spa.None {

        /// Serve `frontend` for every `GET` no API route claims.
        record Embedded(Frontend frontend) implements Spa {
            public Embedded {
                Objects.requireNonNull(frontend, "frontend");
            }
        }

        /// No SPA: unknown paths get the 404 envelope.
        enum None implements Spa {
            INSTANCE
        }

        static Spa none() {
            return None.INSTANCE;
        }
    }

    /// A started server: both listeners bound, subsystems running.
    public static final class Running {
        private final Javalin api;
        private final Metrics.Running metrics;
        private final Router router;
        private final DispatchScheduler scheduler;
        private final AutoCloseable schedulerLeaderResource;
        private final DispatchJobReaper dispatchJobReaper;
        private final OutboxProcessor outboxProcessor;
        private final Javalin outboxAdminApi;
        private final AutoCloseable outboxLeaderResource;
        private final StreamProcessor streamProcessor;
        private final AutoCloseable streamLeaderResource;
        private final ScheduledJobScheduler scheduledJobScheduler;
        private final AutoCloseable scheduledJobLeaderResource;
        private final Purger purger;
        private final McpServer.Running mcp;
        private final CountDownLatch stopped = new CountDownLatch(1);

        private Running(Javalin api, Metrics.Running metrics, Router router, DispatchJobReaper dispatchJobReaper,
                         DispatchScheduler scheduler, AutoCloseable schedulerLeaderResource,
                         OutboxProcessor outboxProcessor, Javalin outboxAdminApi, AutoCloseable outboxLeaderResource,
                         StreamProcessor streamProcessor, AutoCloseable streamLeaderResource,
                         ScheduledJobScheduler scheduledJobScheduler, AutoCloseable scheduledJobLeaderResource,
                         Purger purger, McpServer.Running mcp) {
            this.api = api;
            this.metrics = metrics;
            this.router = router;
            this.dispatchJobReaper = dispatchJobReaper;
            this.scheduler = scheduler;
            this.schedulerLeaderResource = schedulerLeaderResource;
            this.outboxProcessor = outboxProcessor;
            this.outboxAdminApi = outboxAdminApi;
            this.outboxLeaderResource = outboxLeaderResource;
            this.streamProcessor = streamProcessor;
            this.streamLeaderResource = streamLeaderResource;
            this.scheduledJobScheduler = scheduledJobScheduler;
            this.scheduledJobLeaderResource = scheduledJobLeaderResource;
            this.purger = purger;
            this.mcp = mcp;
        }

        /// `-1` when `FC_MCP_ENABLED` is off.
        public int mcpPort() {
            return mcp == null ? -1 : mcp.port();
        }

        public int apiPort() {
            return api.port();
        }

        public int metricsPort() {
            return metrics.port();
        }

        /// Graceful stop: listeners first (Jetty drains in-flight requests
        /// within the grace period), then background subsystems.
        public void stop() {
            try {
                // Listeners first so Jetty drains in-flight HTTP requests,
                // then the router — which has its own drain and must not be
                // torn down while the API is still accepting calls that
                // inspect it.
                api.stop();
                metrics.stop();
                if (router != null) {
                    router.close();
                }
                if (dispatchJobReaper != null) {
                    dispatchJobReaper.close();
                }
                if (scheduler != null) {
                    scheduler.close();
                }
                if (schedulerLeaderResource != null) {
                    try {
                        schedulerLeaderResource.close();
                    } catch (Exception e) {
                        LOG.warn("closing the scheduler's leader election failed", e);
                    }
                }
                // The admin listener first (stop accepting operator control),
                // then the processor's background loops/drainers, then its
                // leader election — same order as the scheduler above, and
                // before the pool itself closes (Main/StartCommand close the
                // pool after Running#stop returns).
                if (outboxAdminApi != null) {
                    outboxAdminApi.stop();
                }
                if (outboxProcessor != null) {
                    outboxProcessor.close();
                }
                if (outboxLeaderResource != null) {
                    try {
                        outboxLeaderResource.close();
                    } catch (Exception e) {
                        LOG.warn("closing the outbox's leader election failed", e);
                    }
                }
                // Stream before the pool closes: StreamProcessor#close interrupts and
                // joins every projector, so no claim/insert is still in flight against
                // the pool by the time the caller tears it down.
                if (streamProcessor != null) {
                    streamProcessor.close();
                }
                if (streamLeaderResource != null) {
                    try {
                        streamLeaderResource.close();
                    } catch (Exception e) {
                        LOG.warn("closing the stream processor's leader election failed", e);
                    }
                }
                // Scheduled-job scheduler and purger before the pool closes, same
                // reasoning as the stream processor above: both interrupt-and-join
                // their loops so no query is still in flight against the pool.
                if (scheduledJobScheduler != null) {
                    scheduledJobScheduler.close();
                }
                if (scheduledJobLeaderResource != null) {
                    try {
                        scheduledJobLeaderResource.close();
                    } catch (Exception e) {
                        LOG.warn("closing the scheduled-job scheduler's leader election failed", e);
                    }
                }
                if (purger != null) {
                    purger.close();
                }
                // MCP last: its own listener + session store, independent of every
                // subsystem above (no database, no leader election).
                if (mcp != null) {
                    mcp.stop();
                }
                LOG.info("server stopped");
            } finally {
                stopped.countDown();
            }
        }

        /// Blocks until [#stop()] completes.
        public void awaitStop() throws InterruptedException {
            stopped.await();
        }

        /// Test-only visibility hook (dispatch-seam spec §7 audit item 1):
        /// whether the dispatch-job reaper's executor has shut down.
        boolean dispatchJobReaperClosed() {
            return dispatchJobReaper != null && dispatchJobReaper.isClosed();
        }
    }

    public Running start() {
        DataSource dbPool = mode instanceof Mode.Platform(var pool) ? pool
                : mode instanceof Mode.Worker(var pool) ? pool : null;

        // The router is started BEFORE the listeners bind, so a readiness
        // probe never sees a server that is accepting traffic while its
        // router is still deciding whether it holds leadership.
        Router router = env.routerEnabled() ? Router.start(env, dbPool, Clock.systemUTC()) : null;

        var built = buildApiAndReaper(router);
        Javalin api = built.app();

        // ── background subsystems ───────────────────────────────────────────
        McpServer.Running mcp = null;
        if (env.mcpEnabled()) {
            // No database needed (`docs/spec/mcp.md` §1) — it must work on the
            // router-only/MCP-only path Main already has, so this reads
            // straight from Env rather than dbPool.
            var mcpConfig = McpConfig.resolve(env.mcpPlatformUrl(), env.mcpClientId(), env.mcpClientSecret(),
                    env.apiPort());
            var tokenManager = mcpConfig.hasCredentials()
                    ? new TokenManager(mcpConfig.baseUrl(), mcpConfig.clientId(), mcpConfig.clientSecret())
                    : null;
            var auth = PlatformClient.AuthMode.resolve(mcpConfig, tokenManager, env.mcpPlatformAuthToken());
            var platformClient = new PlatformClient(mcpConfig.baseUrl(), auth);
            mcp = McpServer.start(platformClient, env.mcpBind(), env.mcpPort(), Version.current());
        }

        DispatchScheduler scheduler = null;
        AutoCloseable schedulerLeaderResource = null;
        if (env.schedulerEnabled()) {
            if (dbPool == null) {
                LOG.warn("scheduler enabled but no database pool is available; ignoring FC_SCHEDULER_ENABLED");
            } else {
                var leaderGate = leaderGate(env, "scheduler");
                scheduler = DispatchScheduler.start(env.appKey(), env.dispatchProcessingEndpoint(), dbPool,
                        schedulerPublisher(env, dbPool), leaderGate.isLeader());
                if (scheduler == null) {
                    // Fail-closed (no FLOWCATALYST_APP_KEY): DispatchScheduler.start already
                    // logged the ERROR; release the leader election we just started for nothing.
                    closeQuietly(leaderGate.resource());
                } else {
                    schedulerLeaderResource = leaderGate.resource();
                }
            }
        }

        OutboxProcessor outboxProcessor = null;
        Javalin outboxAdminApi = null;
        AutoCloseable outboxLeaderResource = null;
        if (env.outboxEnabled()) {
            if (dbPool == null) {
                LOG.warn("outbox enabled but no database pool is available; ignoring FC_OUTBOX_ENABLED");
            } else if (env.outboxPlatformUrl().isBlank()) {
                LOG.warn("outbox enabled but FC_OUTBOX_PLATFORM_URL (or an alias) is not set; "
                        + "ignoring FC_OUTBOX_ENABLED");
            } else {
                var leaderGate = leaderGate(env, "outbox");
                var repository = new PostgresOutboxRepository(dbPool);
                repository.initSchema();
                var dispatcher = new HttpDispatcher(HttpDispatcher.defaultClient(), env.outboxPlatformUrl(),
                        OutboxProcessor.Config.DEFAULT_HTTP_TIMEOUT, null, env.outboxPlatformAuthToken());
                var config = outboxConfig(env);
                outboxProcessor = new OutboxProcessor(repository, dispatcher, config, leaderGate.isLeader());
                outboxProcessor.start();
                outboxLeaderResource = leaderGate.resource();
                LOG.info("outbox processor started platform_url={} poll_interval={} admin_port={}",
                        env.outboxPlatformUrl(), config.pollInterval(), env.outboxAdminPort());
                if (env.outboxAdminPort() > 0) {
                    outboxAdminApi = OutboxAdminApi.start(outboxProcessor, env.outboxAdminPort());
                    LOG.info("outbox admin api listening addr=127.0.0.1:{}", env.outboxAdminPort());
                }
            }
        }

        StreamProcessor streamProcessor = null;
        AutoCloseable streamLeaderResource = null;
        if (env.streamEnabled()) {
            if (dbPool == null) {
                LOG.warn("stream processor enabled but no database pool is available; ignoring "
                        + "FC_STREAM_PROCESSOR_ENABLED");
            } else {
                var leaderGate = leaderGate(env, "stream");
                streamProcessor = StreamProcessor.start(dbPool, StreamProcessor.Settings.fromEnv(env),
                        leaderGate.isLeader());
                streamLeaderResource = leaderGate.resource();
            }
        }

        ScheduledJobScheduler scheduledJobScheduler = null;
        AutoCloseable scheduledJobLeaderResource = null;
        if (env.scheduledJobEnabled()) {
            if (dbPool == null) {
                LOG.warn("scheduled-job scheduler enabled but no database pool is available; ignoring "
                        + "FC_SCHEDULED_JOB_ENABLED");
            } else {
                var leaderGate = leaderGate(env, "scheduled-job");
                scheduledJobScheduler = ScheduledJobScheduler.start(dbPool,
                        ScheduledJobScheduler.Settings.fromEnv(env), leaderGate.isLeader());
                scheduledJobLeaderResource = leaderGate.resource();
            }
        }

        // Not leader-gated (purger spec §4): every instance purges, whenever a
        // pool exists at all — the statements are idempotent/`IF EXISTS`, so a
        // duplicate pass from a second instance is harmless.
        Purger purger = dbPool != null ? Purger.start(dbPool, RateLimit.Policies.fromEnv(EnvReader.system())) : null;
        registry.register(AuthAlarms.collector());
        switch (mode) {
            case Mode.Platform(var pool) when pool instanceof GatedDataSource g -> registry.register(g.collector());
            case Mode.Worker(var pool) when pool instanceof GatedDataSource g -> registry.register(g.collector());
            default -> { }
        }

        // ── listeners ───────────────────────────────────────────────────────
        var metrics = new Metrics(env, registry).start();
        LOG.info("metrics server listening addr=:{}", env.metricsPort());
        api.start(env.apiPort());
        LOG.info("api server listening addr=:{}", env.apiPort());
        return new Running(api, metrics, router, built.dispatchJobReaper(), scheduler, schedulerLeaderResource,
                outboxProcessor, outboxAdminApi, outboxLeaderResource,
                streamProcessor, streamLeaderResource, scheduledJobScheduler, scheduledJobLeaderResource, purger, mcp);
    }

    /// [Env]'s outbox fields, with the library defaults ([OutboxProcessor.Config#defaults])
    /// filling in every `0`/unset knob (spec §4: `FC_OUTBOX_BATCH_SIZE` etc.
    /// default to "0 (library default …)").
    private static OutboxProcessor.Config outboxConfig(Env env) {
        var d = OutboxProcessor.Config.defaults();
        return new OutboxProcessor.Config(
                env.outboxBatchSize() > 0 ? env.outboxBatchSize() : d.batchSize(),
                env.outboxMaxInFlight() > 0 ? env.outboxMaxInFlight() : d.maxInFlight(),
                env.outboxPollIntervalMs() > 0 ? Duration.ofMillis(env.outboxPollIntervalMs()) : d.pollInterval(),
                env.outboxMaxConcurrentGroups() > 0 ? env.outboxMaxConcurrentGroups() : d.maxConcurrentGroups(),
                env.outboxBlockOnError(),
                d.maxRetries(),
                d.recoveryInterval(),
                d.recoveryThreshold(),
                d.httpTimeout());
    }

    /// The scheduler's [DispatchPublisher]: the built-in Postgres broker —
    /// the SAME queue [Router]'s default-broker consumer drains (`FC_DEFAULT_BROKER=postgres`
    /// plus a usable database URL) — or a loud-WARN [NoopPublisher] otherwise
    /// (dispatch-seam spec §11: "explicitly called out as unsafe for production").
    private static DispatchPublisher schedulerPublisher(Env env, DataSource pool) {
        if ("postgres".equals(env.defaultBroker()) && !env.databaseUrl().isBlank()) {
            String queueName = defaultQueueUri(env);
            PostgresQueue.initSchema(pool);
            LOG.info("scheduler: dispatch jobs published to the built-in postgres broker queue={}", queueName);
            return new PostgresQueuePublisher(pool, queueName);
        }
        LOG.warn("scheduler running with a NOOP publisher: dispatch jobs will be claimed but NOT delivered; "
                + "set FC_DEFAULT_BROKER=postgres (with a database URL) or wire a real publisher "
                + "before enabling FC_SCHEDULER_ENABLED in production");
        return new NoopPublisher();
    }

    /// Mirrors [Router#defaultQueueUri] (private there): the scheduler MUST
    /// publish into the exact queue name the default-broker router consumes
    /// from, so this one-line derivation from `FC_DATABASE_URL` is
    /// deliberately duplicated rather than exposed across a new dependency
    /// edge between the two composition roots.
    private static String defaultQueueUri(Env env) {
        return env.databaseUrl().replaceFirst("^postgresql://", "postgres://");
    }

    /// `isLeader`: `() -> true` when standby is disabled. `resource`: what
    /// [#start] must close on shutdown — a no-op when standby is disabled,
    /// the Redis client + [LeaderElection] otherwise.
    private record LeaderGate(BooleanSupplier isLeader, AutoCloseable resource) {
    }

    /// Mirrors Go's `newLeaderGate(ctx, cfg, subsystem)`
    /// (`internal/server/subsystems.go:152-186`): a dedicated Redis election
    /// on a `subsystem`-suffixed lock key, independent of the router's own
    /// election and of every other subsystem's — a router leader, a
    /// scheduler leader and a stream-processor leader may all be different
    /// instances (dispatch-seam spec §12; stream spec §1 "its own election").
    /// Go builds a fresh `standby.New` per subsystem the same way, never
    /// sharing one election object across subsystems even when they share a
    /// Redis server.
    private static LeaderGate leaderGate(Env env, String subsystem) {
        if (!env.standbyEnabled()) {
            return new LeaderGate(() -> true, () -> { });
        }
        UnifiedJedis client = redisForSubsystem(env);
        var config = LeaderElection.Config.of(env.standbyLockKey() + ":" + subsystem);
        var election = new LeaderElection(config, new RedisLockStore(client), Clock.systemUTC());
        election.start();
        AutoCloseable resource = () -> {
            election.close();
            client.close();
        };
        return new LeaderGate(election::isLeader, resource);
    }

    /// A trimmed copy of [Router]'s own `redisFor`: each subsystem's election
    /// needs its own connection (never the router's, and never another
    /// subsystem's — see [#leaderGate]), and `Router` exposes no accessor for
    /// its internal Redis client.
    @SuppressWarnings("deprecation")
    private static UnifiedJedis redisForSubsystem(Env env) {
        var uri = URI.create(env.standbyRedisUrl());
        int port = uri.getPort() > 0 ? uri.getPort() : 6379;
        var config = DefaultJedisClientConfig.builder()
                .sslOptions("rediss".equalsIgnoreCase(uri.getScheme()) ? SslOptions.builder().build() : null)
                .build();
        var provider = new PooledConnectionProvider(new HostAndPort(uri.getHost(), port), config);
        return new UnifiedJedis(provider, 3, Duration.ofSeconds(3));
    }



    /// A trimmed copy of [Router]'s own `redisFor` — the outbox processor's
    /// election needs its own connection, never the scheduler's or the
    /// router's (see [#outboxLeader]).
    @SuppressWarnings("deprecation")
    private static UnifiedJedis redisForOutbox(Env env) {
        var uri = URI.create(env.standbyRedisUrl());
        int port = uri.getPort() > 0 ? uri.getPort() : 6379;
        var config = DefaultJedisClientConfig.builder()
                .sslOptions("rediss".equalsIgnoreCase(uri.getScheme()) ? SslOptions.builder().build() : null)
                .build();
        var provider = new PooledConnectionProvider(new HostAndPort(uri.getHost(), port), config);
        return new UnifiedJedis(provider, 3, Duration.ofSeconds(3));
    }

    private static void closeQuietly(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception e) {
            LOG.warn("closing an unused leader election failed", e);
        }
    }

    /// The fully wired (not yet started) API app plus the seam [RouteRegistry]
    /// it was built through — exposed so the contract tests can enumerate the
    /// registered routes without binding a port.
    ApiAndReaper buildApi() {
        return buildApiAndReaper(null);
    }

    /// The dispatch-job reaper [Platform#register] starts is a background
    /// resource, not a route — it has to escape the `Javalin.create` lambda
    /// below by some path other than the `Javalin` it returns, hence this
    /// pair rather than a bare `Javalin`. `registry` is the seam
    /// [RouteRegistry] [io.flowcatalyst.http.javalin.JavalinAdapter#install]
    /// hands back, so [io.flowcatalyst.server.LockfileCoverageTest] can
    /// enumerate registrations without walking Javalin internals.
    record ApiAndReaper(Javalin app, RouteRegistry registry, DispatchJobReaper dispatchJobReaper) {
    }

    ApiAndReaper buildApiAndReaper(Router router) {
        DispatchJobReaper[] reaperHolder = new DispatchJobReaper[1];
        RouteRegistry[] registryHolder = new RouteRegistry[1];
        Javalin api = Javalin.create(cfg -> {
            cfg.startup.showJavalinBanner = false;
            cfg.concurrency.useVirtualThreads = true;
            cfg.jsonMapper(new JavalinJsonMapper());
            cfg.jetty.modifyServer(server -> server.setStopTimeout(SHUTDOWN_GRACE.toMillis()));
            // h2c on the plain API port, plus TLS+ALPN (h2) and HTTP/3 when
            // configured (docs/spec/http-transport.md §1). Adding a connector
            // here is what makes Javalin skip the default one it would
            // otherwise build from cfg.jetty.host/port (Listeners' javadoc).
            io.flowcatalyst.server.transport.Listeners.install(cfg.jetty, env);

            // Installs the seam adapter: the 404/405 envelope (formerly
            // cfg.http.prefer405over404 = false, set here directly) and the
            // bodiless-response rule (formerly ResponseDefaults, now deleted)
            // both live in the adapter (docs/spec/http-seam.md §2).
            var routes = io.flowcatalyst.http.javalin.JavalinAdapter.install(cfg);
            registryHolder[0] = routes;

            routes.get("/health", health(mode)::handle);

            switch (mode) {
                case Mode.Platform(var pool) -> reaperHolder[0] = new Platform(env, pool, loadSigningKeys()).register(routes);
                case Mode.Worker _, Mode.RouterOnly _ -> {
                    // no platform API on this instance
                }
            }
            if (router != null) {
                // Guard first, then routes, then the dashboard — the adapter
                // resolves `before` filters by path match regardless of
                // registration order, but reading top-to-bottom as
                // "guard, routes, page" is worth the ordering.
                //
                // TODO(port): the /metrics alias under the router prefix.
                io.flowcatalyst.router.api.auth.BasicAuthFilter.register(routes,
                        new io.flowcatalyst.router.api.auth.BasicAuthFilter(
                                env.routerAuthMode(), env.routerAuthUser(), env.routerAuthPass(),
                                env.routerHttpPrefix()));
                io.flowcatalyst.router.api.RouterApi.register(routes,
                        new io.flowcatalyst.router.api.RouterApi.State(
                                router.manager(), router.tracker(), router.warnings(), router.breakers(),
                                router.election(), router.electionConfig(), Version.current(),
                                env.routerHttpPrefix(), null, router.poolMetrics(),
                                router.traffic(), router.brokerStats(), router.server()));
                io.flowcatalyst.router.api.dashboard.DashboardHandler.register(
                        routes, env.routerHttpPrefix());
            }
            switch (spa) {
                case Spa.Embedded(var frontend) -> frontend.register(routes);
                case Spa.None _ -> {
                    // unknown paths get the 404 envelope
                }
            }
        });
        return new ApiAndReaper(api, registryHolder[0], reaperHolder[0]);
    }

    private SigningKeys loadSigningKeys() {
        var signingKeys = SigningKeys.load(env);
        if (signingKeys.ephemeral()) {
            LOG.warn("no JWT signing key configured — using an EPHEMERAL RSA key; tokens will not survive a restart");
        }
        return signingKeys;
    }

    /// Readiness (ruling C-Q23): a platform instance is not ready while the
    /// login-attempt partitions the backoff store writes into are missing.
    private static Health health(Mode mode) {
        return switch (mode) {
            case Mode.Platform(var pool) -> {
                // Probes take from the gate's reserved lane so readiness stays truthful
                // when the ordinary permits are all held (admission.md §1).
                var attempts = new LoginAttemptRepository(pool instanceof GatedDataSource g ? g.forProbes() : pool);
                yield new Health(List.of(new Health.Check("loginAttemptPartitions", () -> {
                    var missing = attempts.missingQuarterlyPartitions(Instant.now());
                    return missing.isEmpty() ? "" : "missing partitions: " + String.join(", ", missing);
                })));
            }
            case Mode.Worker _, Mode.RouterOnly _ -> Health.noChecks();
        };
    }
}
