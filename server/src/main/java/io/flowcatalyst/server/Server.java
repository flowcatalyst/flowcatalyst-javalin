package io.flowcatalyst.server;

import io.flowcatalyst.platform.dispatch.DispatchQueueSettings;
import io.flowcatalyst.platform.scheduler.DispatchPublisher;
import io.flowcatalyst.platform.scheduler.DispatchScheduler;
import io.flowcatalyst.platform.scheduler.NoopPublisher;
import io.flowcatalyst.platform.scheduler.PostgresQueuePublisher;
import io.flowcatalyst.platform.scheduler.SqsDispatchPublisher;
import io.flowcatalyst.platform.scheduler.jobs.ScheduledJobScheduler;
import io.flowcatalyst.platform.dispatchjob.DispatchJobReaper;
import io.flowcatalyst.platform.mail.MailSender;
import io.flowcatalyst.platform.mail.MailService;
import io.flowcatalyst.platform.purger.Purger;
import io.flowcatalyst.http.RouteRegistry;
import io.flowcatalyst.http.Routes;
import io.flowcatalyst.http.vertx.VertxListener;
import java.util.function.Consumer;
import io.flowcatalyst.platform.shared.database.Pools;
import java.time.Instant;
import io.flowcatalyst.platform.loginattempt.LoginAttemptRepository;
import io.flowcatalyst.platform.auth.ratelimit.RateLimit;
import io.flowcatalyst.platform.auth.login.AuthAlarms;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.mcp.McpConfig;
import io.flowcatalyst.mcp.McpServer;
import io.flowcatalyst.mcp.PlatformClient;
import io.flowcatalyst.http.oauth.TokenManager;
import io.flowcatalyst.outbox.HttpDispatcher;
import io.flowcatalyst.outbox.OutboxAdminApi;
import io.flowcatalyst.outbox.OutboxProcessor;
import io.flowcatalyst.outbox.PostgresOutboxRepository;
import io.flowcatalyst.router.queue.postgres.PostgresQueue;
import io.flowcatalyst.router.standby.LeaderElection;
import io.flowcatalyst.router.standby.RedisLockStore;
import io.flowcatalyst.stream.StreamProcessor;
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

        /// The platform API is served from `pools.api()`/`pools.bff()`/
        /// `pools.dispatch()` (by route group, `docs/spec/admission.md`
        /// §11.7); every enabled DB-backed background subsystem shares
        /// `pools.background()`.
        record Platform(Pools pools) implements Mode {
            public Platform {
                Objects.requireNonNull(pools, "pools");
            }
        }

        /// DB-backed background subsystems (scheduler, stream, outbox …)
        /// without the platform API — the worker tier. Only `pools.background()`
        /// is ever used (no request path exists in this mode), but the whole
        /// [Pools] travels together with [Platform] so [Main]/`StartCommand`
        /// open one set of four pools per process, not two.
        record Worker(Pools pools) implements Mode {
            public Worker {
                Objects.requireNonNull(pools, "pools");
            }
        }

        /// The router and/or MCP surfaces only — no platform API, no
        /// worker-tier subsystem enabled. `pool` is `null` in the ordinary
        /// case: since R4 (`docs/go-mirror/2026-09-12-dispatch-rulings.md`)
        /// removed the router's fixed single-queue branch, a router-only
        /// instance never opens its own pool through [Main] just to run the
        /// built-in Postgres broker — a config-URL-supplied `postgres://`
        /// queue opens its own pool from its own URI instead
        /// (`QueueFactory#createPostgres`). [Main] never runs platform
        /// migrations or the seeder against `pool` when it is non-null for
        /// some other reason.
        record RouterOnly(DataSource pool) implements Mode {
        }

        static Mode routerOnly() {
            return new RouterOnly(null);
        }

        static Mode routerOnly(DataSource pool) {
            return new RouterOnly(pool);
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
        private final ApiListener api;
        private final Metrics.Running metrics;
        private final Router router;
        private final DispatchScheduler scheduler;
        private final AutoCloseable schedulerLeaderResource;
        private final AutoCloseable schedulerPublisherResource;
        private final DispatchJobReaper dispatchJobReaper;
        private final MailSender mailSender;
        private final OutboxProcessor outboxProcessor;
        private final OutboxAdminApi.Running outboxAdminApi;
        private final AutoCloseable outboxLeaderResource;
        private final StreamProcessor streamProcessor;
        private final AutoCloseable streamLeaderResource;
        private final ScheduledJobScheduler scheduledJobScheduler;
        private final AutoCloseable scheduledJobLeaderResource;
        private final Purger purger;
        private final McpServer.Running mcp;
        private final CountDownLatch stopped = new CountDownLatch(1);

        private Running(ApiListener api, Metrics.Running metrics, Router router, DispatchJobReaper dispatchJobReaper,
                         MailSender mailSender, DispatchScheduler scheduler, AutoCloseable schedulerLeaderResource,
                         AutoCloseable schedulerPublisherResource,
                         OutboxProcessor outboxProcessor, OutboxAdminApi.Running outboxAdminApi, AutoCloseable outboxLeaderResource,
                         StreamProcessor streamProcessor, AutoCloseable streamLeaderResource,
                         ScheduledJobScheduler scheduledJobScheduler, AutoCloseable scheduledJobLeaderResource,
                         Purger purger, McpServer.Running mcp) {
            this.api = api;
            this.metrics = metrics;
            this.router = router;
            this.dispatchJobReaper = dispatchJobReaper;
            this.mailSender = mailSender;
            this.scheduler = scheduler;
            this.schedulerLeaderResource = schedulerLeaderResource;
            this.schedulerPublisherResource = schedulerPublisherResource;
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

        /// `-1` when no TLS listener is up (`FC_TLS_PORT`'s configured value
        /// is never the answer — this is the ACTUALLY BOUND port, same
        /// convention as [#mcpPort]).
        public int tlsPort() {
            return api.tlsPort();
        }

        public int metricsPort() {
            return metrics.port();
        }

        /// Test-only visibility hook (same reasoning as [#dispatchJobReaperClosed]),
        /// public so fcdev's own integration tests can watch the router
        /// adopt the served document: `null` when the router is disabled
        /// ([Env#routerEnabled] false).
        public Router router() {
            return router;
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
                // The mail sender stops once the API stops accepting; an enqueued row
                // waits for the next start (mail-outbox spec §2).
                if (mailSender != null) {
                    mailSender.close();
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
                // SqsDispatchPublisher owns an SqsClient (HTTP connection pool +
                // threads) that nothing else references; NoopPublisher and
                // PostgresQueuePublisher aren't AutoCloseable, so this is null for
                // both and only ever set for the SQS branch.
                if (schedulerPublisherResource != null) {
                    try {
                        schedulerPublisherResource.close();
                    } catch (Exception e) {
                        LOG.warn("closing the scheduler's publisher failed", e);
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
        // Logged once, before anything binds, so the collector and the
        // heap/direct-memory ceilings docker/jvm-opts.sh fenced are visible
        // in the task's log rather than inferred from the container size
        // (docs/spec/jvm-memory.md §2).
        JvmInfo.Summary jvm = JvmInfo.summary();
        LOG.atInfo().setMessage("jvm memory")
                .addKeyValue("collectors", jvm.collectors())
                .addKeyValue("max_heap_mib", jvm.maxHeapMiB())
                .addKeyValue("max_direct_mib", jvm.maxDirectMiB())
                .addKeyValue("processors", jvm.processors())
                .log();

        // Every background subsystem below (scheduler, outbox, stream,
        // scheduled-job scheduler, purger, mail sender — and the router's own
        // housekeeping, `Router.build`'s dataSource) runs on `pools.background()`,
        // never a request-path pool (admission.md §11.7).
        DataSource dbPool = switch (mode) {
            case Mode.Platform(var pools) -> pools.background();
            case Mode.Worker(var pools) -> pools.background();
            // RouterOnly's pool is null in the ordinary case (R4 removed the
            // fixed single-queue branch that used to need one here) — either
            // way this is exactly the pool the router (and only the router)
            // should see.
            case Mode.RouterOnly(var pool) -> pool;
        };

        // ── internal listener ───────────────────────────────────────────────
        // The internal listener binds first, before the router contends for
        // leadership. R3′ (`docs/spec/router-config-auth.md`) moved the
        // router-config document to the API listener (behind ordinary bearer
        // auth) — the internal listener carries only health/metrics now — but
        // this ordering still matters: BOTH listeners are bound before the
        // router's election starts (below, after the API listener), so the
        // router's first configuration fetch — which in fcdev goes to this
        // same process's API listener for both the token and the document —
        // can never race either bind. The internal listener binds first
        // simply because it has no dependency on the router (R3 history);
        // the router itself is only *built* here, not started.
        var metrics = new Metrics(env, registry).start();
        LOG.atInfo().setMessage("metrics server listening")
                .addKeyValue("addr", ":" + env.metricsPort())
                .log();

        Router router = null;
        try {
            router = env.routerEnabled() ? Router.build(env, dbPool, Clock.systemUTC()) : null;

            var built = buildApiAndReaper(router);

            // ── background subsystems ───────────────────────────────────────
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
            AutoCloseable schedulerPublisherResource = null;
            if (env.schedulerEnabled()) {
                if (dbPool == null) {
                    LOG.warn("scheduler enabled but no database pool is available; ignoring FC_SCHEDULER_ENABLED");
                } else {
                    var leaderGate = leaderGate(env, "scheduler");
                    // Built before DispatchScheduler.start so an SqsDispatchPublisher's
                    // resource is tracked for #stop() regardless of whether the scheduler
                    // itself ends up starting (the fail-closed branch below releases it,
                    // same as the leader election, rather than leaking it).
                    DispatchPublisher publisher = schedulerPublisher(env, dbPool);
                    if (publisher instanceof AutoCloseable closeable) {
                        schedulerPublisherResource = closeable;
                    }
                    scheduler = DispatchScheduler.start(env.appKey(), env.dispatchProcessingEndpoint(), dbPool,
                            publisher, leaderGate.isLeader());
                    if (scheduler == null) {
                        // Fail-closed (no FLOWCATALYST_APP_KEY): DispatchScheduler.start already
                        // logged the ERROR; release the leader election we just started for nothing.
                        closeQuietly(leaderGate.resource());
                        if (schedulerPublisherResource != null) {
                            closeQuietly(schedulerPublisherResource);
                            schedulerPublisherResource = null;
                        }
                    } else {
                        schedulerLeaderResource = leaderGate.resource();
                    }
                }
            }

            OutboxProcessor outboxProcessor = null;
            OutboxAdminApi.Running outboxAdminApi = null;
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
                    LOG.atInfo().setMessage("outbox processor started")
                            .addKeyValue("platform_url", env.outboxPlatformUrl())
                            .addKeyValue("poll_interval", config.pollInterval())
                            .addKeyValue("admin_port", env.outboxAdminPort())
                            .log();
                    if (env.outboxAdminPort() > 0) {
                        outboxAdminApi = OutboxAdminApi.start(outboxProcessor, env.outboxAdminPort());
                        LOG.atInfo().setMessage("outbox admin api listening")
                                .addKeyValue("addr", "127.0.0.1:" + env.outboxAdminPort())
                                .log();
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

            // Not leader-gated (purger spec §4): every Platform/Worker instance
            // purges, whenever a pool exists at all — the statements are
            // idempotent/`IF EXISTS`, so a duplicate pass from a second instance
            // is harmless. Excluded for RouterOnly: its pool (when present) is
            // the router's own Postgres broker, whose database may host nothing
            // but `queue_messages` — the purger's sweeps read platform tables
            // (auth, mail, oauth…) that a router-only deployment never migrates.
            boolean platformTablesAvailable = mode instanceof Mode.Platform || mode instanceof Mode.Worker;
            Purger purger = platformTablesAvailable
                    ? Purger.start(dbPool, RateLimit.Policies.fromEnv(EnvReader.system()))
                    : null;
            // Mail (mail-outbox spec §2): Platform.register wired Notifications/Mfa/ResetLinks
            // to the OutboxMailService; this is the other half, the background sender that
            // delivers through the SMTP-or-logging transport. Platform mode only.
            MailSender mailSender = null;
            if (mode instanceof Mode.Platform) {
                // pools.background() via `dbPool` (admission.md §11.7's BACKGROUND list
                // names the mail sender explicitly), not the API pool.
                mailSender = MailSender.start(dbPool, MailService.fromEnv(env.reader()), Clock.systemUTC(),
                        MailSender.DEFAULT_INTERVAL);
                registry.register(mailSender.collector());
            }
            registry.register(AuthAlarms.collector());
            if (router != null) registry.register(router.mediationHttpVersionCollector());
            switch (mode) {
                case Mode.Platform(var pools) -> pools.registerCollectors(registry);
                case Mode.Worker(var pools) -> pools.registerCollectors(registry);
                default -> { }
            }
            JvmMetricsRegistration.register(registry);

            // ── API listener ──────────────────────────────────────────────
            // Bound before the router's election (below): buildApiAndReaper
            // needed the router built (not started) above to mount its HTTP
            // surface, and now both listeners are up before the router ever
            // contends for leadership — the router's own configuration
            // fetch, which in fcdev goes to this same process's API
            // listener, can never race either bind.
            ApiListener api = built.starter().start(env.apiPort());
            LOG.atInfo().setMessage("api server listening")
                    .addKeyValue("addr", ":" + env.apiPort())
                    .log();

            // ── router election ──────────────────────────────────────────
            // Only now, with both listeners bound, does the router contend
            // for leadership. Leadership itself is decided synchronously by
            // this call, but (R-A) the first configuration apply may still
            // be running in the background on its own thread — that is fine
            // and expected: it is what lets this call return promptly
            // regardless of the config service's own state.
            if (router != null) {
                router.startElection();
            }
            return new Running(api, metrics, router, built.dispatchJobReaper(), mailSender, scheduler, schedulerLeaderResource,
                    schedulerPublisherResource, outboxProcessor, outboxAdminApi, outboxLeaderResource,
                    streamProcessor, streamLeaderResource, scheduledJobScheduler, scheduledJobLeaderResource, purger, mcp);
        } catch (RuntimeException e) {
            // The internal listener is already bound at this point; nothing
            // else has been returned to a caller who could stop it, so this
            // is the only place that can. The router may have been built
            // (but never started an election) if the failure happened
            // between Router.build and here — close it too rather than
            // leaking its housekeeping threads/pools.
            if (router != null) {
                router.close();
            }
            metrics.stop();
            throw e;
        }
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

    /// The scheduler's [DispatchPublisher]: [SqsDispatchPublisher] for a
    /// deployed SQS dispatch setup (`FC_DISPATCH_QUEUE_TYPE=SQS`), else
    /// [PostgresQueuePublisher] over the built-in Postgres broker — routing
    /// per (tenant, priority) onto the SAME `queue_messages` table a
    /// Postgres-backed router consumer drains (`FC_DEFAULT_BROKER=postgres`
    /// plus a usable database URL) — or a loud-WARN [NoopPublisher] otherwise
    /// (dispatch-seam spec §11: "explicitly called out as unsafe for
    /// production").
    ///
    /// **[DispatchQueueSettings#resolve] is called unconditionally, before
    /// any branch is chosen** (`docs/spec/deployed-dispatch.md` §3 "Wiring").
    /// This method runs in BOTH platform and worker mode — `Server#start`
    /// calls it whenever `FC_SCHEDULER_ENABLED` is set and a pool exists,
    /// regardless of `mode`. It is the ONLY eager resolution: the platform's
    /// served router-config document resolves the settings per request
    /// (`RouterConfigApi.State` takes a supplier) and answers 503 when they
    /// are unusable, so an API tier with `DISPATCH_QUEUE_TYPE=SQS` and no
    /// prefix still boots — nothing on it publishes (owner, 2026-09-14). A
    /// worker (where `DISPATCH_SCHEDULER_ENABLED=true` actually lives in the
    /// real deployment) with a misconfigured SQS setup — `FC_DISPATCH_QUEUE_TYPE=SQS`
    /// but no usable prefix/account/region — must NOT fall through to the
    /// NOOP publisher: resolving here, before any branch, makes
    /// [DispatchQueueSettings#resolve]'s `IllegalStateException` fire at
    /// boot on the one role that would otherwise publish to nonsense names.
    private static DispatchPublisher schedulerPublisher(Env env, DataSource pool) {
        DispatchQueueSettings settings = DispatchQueueSettings.resolve(env);
        if (settings.sqs()) {
            LOG.atInfo().setMessage("scheduler: dispatch jobs published to SQS")
                    .addKeyValue("prefix", settings.prefix())
                    .addKeyValue("region", settings.sqsRegion())
                    .log();
            return new SqsDispatchPublisher(pool, settings);
        }
        if ("postgres".equals(env.defaultBroker()) && !env.databaseUrl().isBlank()) {
            PostgresQueue.initSchema(pool);
            LOG.atInfo().setMessage("scheduler: dispatch jobs published to the built-in postgres broker, "
                            + "one row-queue per (tenant, priority)")
                    .addKeyValue("prefix", settings.prefix())
                    .log();
            return new PostgresQueuePublisher(pool, settings);
        }
        LOG.warn("scheduler running with a NOOP publisher: dispatch jobs will be claimed but NOT delivered; "
                + "set FC_DEFAULT_BROKER=postgres (with a database URL) or wire a real publisher "
                + "before enabling FC_SCHEDULER_ENABLED in production");
        return new NoopPublisher();
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
    /// resource, not a route — it has to escape the `configure` lambda below
    /// by some path other than the [RouteRegistry] it returns, hence this
    /// pair. `registry` is the seam [RouteRegistry] [VertxListener.Prepared#registry]
    /// hands back, so [io.flowcatalyst.server.LockfileCoverageTest] can
    /// enumerate registrations without walking Vert.x internals.
    /// The bound API listener (`docs/spec/vertx-listener.md`).
    interface ApiListener {
        int port();

        /// `-1` when no TLS listener is up ([VertxListener#tlsPort]).
        int tlsPort();

        void stop();
    }

    interface ApiStarter {
        ApiListener start(int port);
    }

    record ApiAndReaper(ApiStarter starter, RouteRegistry registry, DispatchJobReaper dispatchJobReaper) {
    }

    /// Where the router API verifies platform tokens (`docs/spec/router-api-auth.md`
    /// rule 1): `FC_ROUTER_PLATFORM_URL`, else this process's own platform over
    /// loopback, else blank (every protected router route then answers 401). Only
    /// the verification address: `FC_ROUTER_PLATFORM_URL`'s other meaning, settle
    /// reporting, still reads the env value as set.
    private String routerTokenPlatformUrl() {
        if (!env.routerPlatformUrl().isBlank()) {
            return env.routerPlatformUrl();
        }
        return mode instanceof Mode.Platform ? "http://127.0.0.1:" + env.apiPort() : "";
    }

    ApiAndReaper buildApiAndReaper(Router router) {
        DispatchJobReaper[] reaperHolder = new DispatchJobReaper[1];
        Consumer<Routes> configure = routes -> {

            routes.get("/health", health(mode)::handle);

            switch (mode) {
                case Mode.Platform(var pools) -> reaperHolder[0] = new Platform(env, pools, loadSigningKeys()).register(routes);
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
                // The router's API and dashboard read the router's in-memory state, never
                // the database: unbounded (Group.NO_DB).
                var routerRoutes = routes.in(io.flowcatalyst.http.Group.NO_DB);
                io.flowcatalyst.router.api.auth.RouterAuth.install(routerRoutes,
                        new io.flowcatalyst.router.api.auth.RouterAuth.Settings(
                                env.routerDevMode(), env.routerAuthMode(), env.routerAuthUser(),
                                env.routerAuthPass(), env.routerHttpPrefix(), routerTokenPlatformUrl()));
                var routerState = new io.flowcatalyst.router.api.RouterApi.State(
                        router.manager(), router.tracker(), router.warnings(), router.breakers(),
                        router.election(), router.electionConfig(), Version.current(),
                        env.routerHttpPrefix(), null, router.poolMetrics(),
                        router.traffic(), router.brokerStats(), router.server());
                io.flowcatalyst.router.api.RouterApi.register(routerRoutes, routerState);
                if (env.routerDevMode()) {
                    // docs/spec/router-api-auth.md rule 7: absent, not merely protected, elsewhere.
                    io.flowcatalyst.router.api.RouterApi.registerDevRoutes(routerRoutes, routerState);
                }
                io.flowcatalyst.router.api.dashboard.DashboardHandler.register(
                        routerRoutes, env.routerHttpPrefix());
            }
            switch (spa) {
                case Spa.Embedded(var frontend) -> frontend.register(routes);
                case Spa.None _ -> {
                    // unknown paths get the 404 envelope
                }
            }
        };
        // Per-group request-path workers, derived from the four physical pools
        // (admission.md §11.7 "Workers"). RouterOnly has no [Pools] and every route it
        // registers is NO_DB (the router's own API/dashboard, health), so it needs no
        // worker pool for any real group at all.
        var workers = switch (mode) {
            case Mode.Platform(var pools) -> io.flowcatalyst.http.RequestWorkers.derived(pools);
            case Mode.Worker(var pools) -> io.flowcatalyst.http.RequestWorkers.derived(pools);
            case Mode.RouterOnly _ -> io.flowcatalyst.http.RequestWorkers.of(java.util.Map.of());
        };
        registry.register(workers.collector());
        var options = new VertxListener.Options("0.0.0.0", env.apiPort(), true,
                java.time.Duration.ofSeconds(30), java.time.Duration.ofSeconds(130), SHUTDOWN_GRACE, workers,
                io.flowcatalyst.server.transport.Listeners.resolve(env));
        var prepared = VertxListener.prepare(options, configure);
        ApiStarter starter = port -> {
            var listener = prepared.listen();
            return new ApiListener() {
                @Override
                public int port() {
                    return listener.port();
                }

                @Override
                public int tlsPort() {
                    return listener.tlsPort();
                }

                @Override
                public void stop() {
                    listener.close();
                }
            };
        };
        return new ApiAndReaper(starter, prepared.registry(), reaperHolder[0]);
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
            case Mode.Platform(var pools) -> {
                // Probes take from the API pool's reserved lane so readiness stays
                // truthful when its ordinary permits are all held (admission.md §1, §11.3).
                var attempts = new LoginAttemptRepository(pools.api().forProbes());
                yield new Health(List.of(new Health.Check("loginAttemptPartitions", () -> {
                    var missing = attempts.missingQuarterlyPartitions(Instant.now());
                    return missing.isEmpty() ? "" : "missing partitions: " + String.join(", ", missing);
                })));
            }
            case Mode.Worker _, Mode.RouterOnly _ -> Health.noChecks();
        };
    }
}
