package io.flowcatalyst.server;

import io.flowcatalyst.http.oauth.TokenManager;
import io.flowcatalyst.http.vertx.VertxMediationClient;
import io.flowcatalyst.router.config.RouterConfig;
import io.flowcatalyst.router.inflight.InFlightTracker;
import io.flowcatalyst.router.manager.ConsumerSupervisor;
import io.flowcatalyst.router.manager.QueueBroker;
import io.flowcatalyst.router.manager.RouterManager;
import io.flowcatalyst.router.manager.RouterServer;
import io.flowcatalyst.router.observability.Warnings;
import io.flowcatalyst.router.observability.PoolMetricsCollector;
import io.flowcatalyst.router.lifecycle.BrokerStatsCache;
import io.flowcatalyst.router.lifecycle.LifecycleLoops;
import io.flowcatalyst.router.lifecycle.StallDetector;
import io.flowcatalyst.router.observability.WarningNotifier;
import io.flowcatalyst.router.observability.WarningStore;
import io.flowcatalyst.router.policy.BreakerRegistry;
import io.flowcatalyst.router.policy.CircuitBreaker;
import io.flowcatalyst.router.pool.HttpMediator;
import io.flowcatalyst.router.pool.HttpVersion;
import io.flowcatalyst.router.pool.JdkTransport;
import io.flowcatalyst.router.pool.MediationTransport;
import io.flowcatalyst.router.pool.Pool;
import io.flowcatalyst.router.queue.Consumer;
import io.flowcatalyst.router.queue.postgres.PostgresQueue;
import io.flowcatalyst.router.settled.BlockedSiblings;
import io.flowcatalyst.router.settled.HttpSettledReporter;
import io.flowcatalyst.router.standby.LeaderElection;
import io.flowcatalyst.router.standby.LockStore;
import io.flowcatalyst.router.standby.RedisLockStore;
import io.flowcatalyst.router.traffic.AlbTraffic;
import io.flowcatalyst.router.traffic.Elbv2TargetGroup;
import io.flowcatalyst.router.traffic.Traffic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.SslOptions;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.providers.PooledConnectionProvider;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/// Composition root for the message router — the counterpart to [Platform].
///
/// Everything the router needs is built here from [Env] and handed to
/// [RouterServer], which owns the lifecycle from there. Nothing in
/// `io.flowcatalyst.router.**` reads configuration or reaches for a
/// singleton; this is the only place the two worlds meet.
public final class Router implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Router.class);

    private final RouterServer server;
    private final RouterManager manager;
    private final InFlightTracker tracker;
    private final BreakerRegistry breakers;
    private final PoolMetricsCollector mediationMetrics;
    private final WarningStore warnings;
    private final Traffic traffic;

    /// Held so shutdown stops the housekeeping threads.
    private final LifecycleLoops housekeeping;

    /// Sampled by the housekeeping loop and read by the monitoring API. Held
    /// here so both see the same readings — a second cache built for the API
    /// would answer from its own sampling schedule and disagree with the
    /// dashboard on the same page.
    private final BrokerStatsCache brokerStats;

    /// Held only so shutdown can flush it — the last notices an instance
    /// sends are the ones most likely to explain why it is going away.
    private final Warnings notifier;
    private final LeaderElection election;
    private final LeaderElection.Config electionConfig;
    private final UnifiedJedis redisClient;

    /// One metrics collector per pool, created with the pool. The pool itself
    /// does not own its metrics — the monitoring API and the Prometheus
    /// exporter both read them, and neither should reach through a pool to
    /// get there.
    /// This is the SAME map instance the pool factory writes into — never a
    /// copy. Pools are created after this object exists (the first config
    /// fetch, a reload, a synthesised `-DEFAULT-POOL`), so a snapshot taken
    /// here would be empty for ever and every pool would report zero
    /// deliveries while the queue counters moved (staging, 2026-09-14).
    private final Map<String, PoolMetricsCollector> poolMetrics;

    /// The deployed-mode mediation transport's own Vert.x instance
    /// (`docs/spec/router-h2.md` §5) — `null` in dev mode, where the
    /// mediator uses [JdkTransport] instead and there is nothing here to
    /// close.
    private final VertxMediationClient vertxMediationClient;

    /// Held only for [#startElection]'s own log line (prefix/standby/alb) —
    /// everything else [#build] needed from it was already consumed while
    /// building the fields above.
    private final Env env;

    private Router(RouterServer server, RouterManager manager, InFlightTracker tracker,
                   BreakerRegistry breakers, WarningStore warnings, Traffic traffic,
                   LeaderElection election, LeaderElection.Config electionConfig,
                   UnifiedJedis redisClient, Map<String, PoolMetricsCollector> metrics,
                   Warnings notifier, LifecycleLoops housekeeping, BrokerStatsCache brokerStats,
                   PoolMetricsCollector mediationMetrics, VertxMediationClient vertxMediationClient,
                   Env env) {
        this.server = server;
        this.env = env;
        this.mediationMetrics = mediationMetrics;
        this.manager = manager;
        this.tracker = tracker;
        this.breakers = breakers;
        this.warnings = warnings;
        this.traffic = traffic;
        this.election = election;
        this.electionConfig = electionConfig;
        this.redisClient = redisClient;
        this.notifier = notifier;
        this.housekeeping = housekeeping;
        this.brokerStats = brokerStats;
        this.poolMetrics = Objects.requireNonNull(metrics, "metrics");
        this.vertxMediationClient = vertxMediationClient;
    }

    public RouterManager manager() {
        return manager;
    }

    public InFlightTracker tracker() {
        return tracker;
    }

    public BreakerRegistry breakers() {
        return breakers;
    }

    public WarningStore warnings() {
        return warnings;
    }

    public Traffic traffic() {
        return traffic;
    }

    public BrokerStatsCache brokerStats() {
        return brokerStats;
    }

    /// The router-wide metrics of the outbound mediator: the negotiated HTTP
    /// version per delivered request (`docs/spec/router-h2.md` §3), exposed as
    /// `fc_router_mediation_http_version_total{version}`.
    public io.prometheus.metrics.model.registry.MultiCollector mediationHttpVersionCollector() {
        return () -> {
            var b = io.prometheus.metrics.model.snapshots.CounterSnapshot.builder()
                    .name("fc_router_mediation_http_version")
                    .help("Outbound mediation requests by the HTTP version the target actually spoke.");
            for (var v : new HttpVersion[] {HttpVersion.HTTP_2, HttpVersion.HTTP_1_1}) {
                b.dataPoint(io.prometheus.metrics.model.snapshots.CounterSnapshot.CounterDataPointSnapshot.builder()
                        .labels(io.prometheus.metrics.model.snapshots.Labels.of("version", v.name()))
                        .value(mediationMetrics.httpVersionCount(v)).build());
            }
            return io.prometheus.metrics.model.snapshots.MetricSnapshots.builder().metricSnapshot(b.build()).build();
        };
    }

    /// A live, read-only view: a pool created after this call is visible
    /// through the returned map (the monitoring API and the Prometheus
    /// exporter hold it for the life of the process).
    public Map<String, PoolMetricsCollector> poolMetrics() {
        return java.util.Collections.unmodifiableMap(poolMetrics);
    }

    public LeaderElection election() {
        return election;
    }

    public LeaderElection.Config electionConfig() {
        return electionConfig;
    }

    /// The running router, for `POST /config/reload` (R-33) and readiness's
    /// consumer-liveness check (R-36) — see [io.flowcatalyst.router.api.RouterApi.State#server].
    public RouterServer server() {
        return server;
    }

    /// Builds the router — everything it needs (pools, election, housekeeping
    /// loops) — but does NOT contend for leadership yet: [#startElection]
    /// does that separately, once the caller's own listeners are bound
    /// (`Server#start`; `docs/spec/router-config-auth.md` R3′ moved the
    /// config document onto the API listener, so the router must not race
    /// its own bind).
    ///
    /// `dataSource` is required only for the Postgres queue backend; a
    /// deployment consuming solely from SQS or NATS may pass null, which is
    /// why a router-only instance can skip Postgres entirely.
    public static Router build(Env env, DataSource dataSource, Clock clock) {
        var warnings = new WarningStore(clock);
        // The store is what the dashboard reads; the notifier is what reaches
        // someone who is not looking at the dashboard. Raisers get both.
        var notifier = WarningNotifier.create(env.routerNotifyWebhookUrl(), env.routerNotifyTeamsEnabledRaw(),
                Warnings.parseMinSeverity(env.routerNotifyMinSeverity()),
                Duration.ofSeconds(env.routerNotifyBatchIntervalSeconds()), clock);
        if (notifier instanceof WarningNotifier started) {
            started.start();
        }
        var warningSink = Warnings.tee(warnings, notifier);
        var tracker = new InFlightTracker(clock);
        var breakers = new BreakerRegistry(CircuitBreaker.Config.DEFAULTS, clock);
        // Router-wide, not per-pool: the mediator is one shared instance
        // built here before any pool (and its own [PoolMetricsCollector])
        // exists, so the HTTP-version counter it feeds lives on its own
        // collector rather than any individual pool's.
        var mediationMetrics = new PoolMetricsCollector(clock);
        // Owner ruling 2026-09-07 (`docs/spec/router-h2.md` §5): dev mode
        // keeps the JDK client pinned to HTTP/1.1; deployed mode gets its
        // own Vert.x instance, never the listener's — the listener may not
        // even be Vert.x — so it has something of its own to close on
        // shutdown.
        VertxMediationClient vertxMediationClient = null;
        MediationTransport transport;
        if (env.routerDevMode()) {
            transport = new JdkTransport(HttpMediator.defaultClient(true));
        } else {
            vertxMediationClient = VertxMediationClient.start();
            transport = vertxMediationClient.transport();
        }
        var mediator = new HttpMediator(transport,
                env.routerDevMode() ? HttpMediator.DEV_TIMEOUT : HttpMediator.PRODUCTION_TIMEOUT,
                breakers, clock, warningSink, mediationMetrics);

        // A-01 gate (`docs/spec/router-completion.md` §2 ruling 3): a
        // platform URL is the only thing that turns this on, everywhere —
        // one instance built here and handed to every pool the factory
        // creates, never decided per pool.
        var blockedSiblings = blockedSiblingsFor(env);

        var metrics = new ConcurrentHashMap<String, PoolMetricsCollector>();
        // The broker is resolved per message from the queue it came from, so
        // it is built before the manager and closed over by it.
        var brokerRef = new java.util.concurrent.atomic.AtomicReference<QueueBroker>();
        // R-59-shaped: 0/unset means "use the implementation's own default"
        // (`docs/spec/router-hol-deferral.md` §7), never "no horizon" —
        // [Pool]'s own constructor resolves a null/non-positive Duration the
        // same way [PoolAdmission#DEFAULT_HORIZON] already does.
        var deferralHorizon = env.routerDeferralMaxDelaySeconds() > 0
                ? Duration.ofSeconds(env.routerDeferralMaxDelaySeconds())
                : null;
        RouterManager.PoolFactory poolFactory = config -> {
            metrics.computeIfAbsent(config.code(), ignored -> new PoolMetricsCollector(clock));
            return new Pool(config, Pool.Backoffs.DEFAULT, mediator, brokerRef.get(),
                    metrics.get(config.code()), clock, warningSink, blockedSiblings, deferralHorizon);
        };

        var manager = new RouterManager(tracker, warningSink, clock, poolFactory, env.routerStrictRouting(),
                env.routerDeferralBudget());
        brokerRef.set(new QueueBroker(queueId -> manager.consumer(queueId).orElse(null), tracker, clock));

        var redisClient = redisFor(env);
        var electionConfig = electionConfig(env);
        var election = new LeaderElection(electionConfig, lockStore(env, redisClient), clock);
        var traffic = trafficFor(env, clock);

        var server = new RouterServer(manager, tracker, election,
                consumerFactory(dataSource, env), configSource(env, warningSink),
                warningSink, clock, Duration.ofSeconds(env.routerDrainTimeoutSec()));

        // Traffic follows leadership: an instance that is not leading has
        // nothing useful to serve from the router surface.
        election.onChange(change -> {
            if (change.leader()) {
                traffic.register();
            } else {
                traffic.deregister();
            }
        });

        // Housekeeping. Built and tested on 2026-08-25 and then wired to
        // nothing until 2026-08-26, which meant the stall detector never ran,
        // broker stats never refreshed, and — the one that matters most — the
        // reaper that recovers ownership of messages a backend has stopped
        // redelivering never ran either. Every "the reaper picks it up"
        // reassurance in this codebase depended on this call existing.
        var stalls = new StallDetector(tracker, warningSink,
                queueId -> manager.consumer(queueId).orElse(null),
                StallDetector.Config.REPORT_ONLY, clock);
        var brokerStats = new BrokerStatsCache(clock, warningSink);
        var housekeeping = new LifecycleLoops();
        // R-59: 0/unset means "use the implementation's own default," not
        // "never evict" (§10's config table).
        var synthPoolIdleTtl = env.routerSynthPoolIdleSecs() > 0
                ? Duration.ofSeconds(env.routerSynthPoolIdleSecs())
                : RouterManager.DEFAULT_SYNTH_POOL_IDLE_TTL;
        // config-poll (A-10) rides the same housekeeping scheduler as the
        // other periodic tasks: pollConfiguration() already no-ops when not
        // running, so this reaches a leader whether it just gained
        // leadership (which is already applying, or has already applied,
        // asynchronously — R-A/R-B) or has been leading for a while and the
        // *source's* configuration changed underneath it. pollConfiguration
        // (rather than applyConfiguration directly) is what skips this task
        // while the router-initial-apply thread is still looping, so the
        // two never race each other into a double fetch (R-B).
        var housekeepingTasks = new ArrayList<>(LifecycleLoops.standard(stalls, tracker, warningSink,
                () -> brokerStats.refresh(manager.queueMetricSources()), warnings::cleanup,
                () -> manager.evictIdleSynthesisedPools(synthPoolIdleTtl),
                manager::closeDrainedPools, manager::retireLingeringConsumers));
        housekeepingTasks.add(new LifecycleLoops.Task("config-poll",
                RouterServer.parseConfigPollInterval(env.routerConfigIntervalRaw()),
                server::pollConfiguration));
        // R-26 (`docs/spec/router-completion.md` §2 ruling 5): the stall
        // watchdog was built and tested but never wired until this task
        // exists — a consumer that stopped polling without ever failing
        // would otherwise sit silently stalled for the life of the process.
        housekeepingTasks.add(new LifecycleLoops.Task("consumer-supervisor", ConsumerSupervisor.STALL_THRESHOLD,
                server::restartStalledLoops));
        housekeeping.start(housekeepingTasks);

        return new Router(server, manager, tracker, breakers, warnings, traffic, election, electionConfig,
                redisClient, metrics, notifier, housekeeping, brokerStats, mediationMetrics, vertxMediationClient,
                env);
    }

    /// Contends for leadership and, on gaining it, kicks off the first
    /// configuration apply (R-A: on the router's own virtual thread, not this
    /// caller's). Must be called after every listener the router's own
    /// configuration source might fetch from is already bound — see
    /// [#build] and `Server#start`.
    public void startElection() {
        server.start();
        LOG.atInfo().setMessage("router started")
                .addKeyValue("leader", server.leader())
                .addKeyValue("prefix", env.routerHttpPrefix())
                .addKeyValue("standby", env.standbyEnabled())
                .addKeyValue("alb", env.albEnabled())
                .log();
    }

    /// The A-01 gate: [BlockedSiblings.Settle] iff a platform base URL is
    /// configured, [BlockedSiblings.Release] otherwise — the
    /// router-specification §0 MUST, and the one place in the whole binary
    /// that decides it.
    private static BlockedSiblings blockedSiblingsFor(Env env) {
        if (env.routerPlatformUrl().isBlank()) {
            LOG.info("BLOCK_ON_ERROR siblings: released to the broker (no FC_ROUTER_PLATFORM_URL)");
            return new BlockedSiblings.Release();
        }
        LOG.atInfo().setMessage("BLOCK_ON_ERROR siblings: settled")
                .addKeyValue("platform_url", env.routerPlatformUrl())
                .log();
        return new BlockedSiblings.Settle(new HttpSettledReporter(env.routerPlatformUrl()));
    }

    /// Builds the standby [LeaderElection.Config] from [Env] (§3 of
    /// `docs/spec/router-env.md`): lock key, lock TTL, heartbeat and instance
    /// id each resolve through their own `FC_STANDBY_*`/`FLOWCATALYST_STANDBY_*`
    /// precedence chain. [LeaderElection.Config]'s own constructor already
    /// refuses `heartbeat >= lockTtl`, but that generic message names neither
    /// variable — pre-checked here so a misconfigured pair fails with the two
    /// env values in the message instead of a stack trace deep in wiring.
    ///
    /// Package-private so [io.flowcatalyst.server.RouterElectionConfigTest]
    /// can assert precedence and the clear-failure message directly, the same
    /// shape as [#configSource].
    static LeaderElection.Config electionConfig(Env env) {
        if (!env.standbyEnabled()) {
            return LeaderElection.Config.disabled();
        }
        var heartbeat = Duration.ofSeconds(env.standbyHeartbeatSeconds());
        var lockTtl = Duration.ofSeconds(env.standbyLockTtlSeconds());
        if (heartbeat.compareTo(lockTtl) >= 0) {
            throw new IllegalStateException(
                    "FC_STANDBY_HEARTBEAT_SECONDS (" + env.standbyHeartbeatSeconds() + "s) must be shorter than "
                            + "FC_STANDBY_LOCK_TTL_SECONDS (" + env.standbyLockTtlSeconds() + "s) — "
                            + "standby leader election cannot start");
        }
        var instanceId = env.standbyInstanceId().isBlank()
                ? UUID.randomUUID().toString()
                : env.standbyInstanceId();
        return new LeaderElection.Config(true, env.standbyLockKey(), instanceId, lockTtl, heartbeat);
    }

    /// Builds the Redis client for leader election.
    ///
    /// Suppressed rather than avoided: Jedis 8 removed `JedisPooled`, and
    /// every non-deprecated `UnifiedJedis` constructor is `protected`. The
    /// deprecated public one is the only way to build a pooled client from
    /// outside the library, so this is a suppression with nowhere else to go
    /// — not a lint being waved past.
    @SuppressWarnings("deprecation")
    private static UnifiedJedis redisFor(Env env) {
        if (!env.standbyEnabled() || env.standbyRedisUrl().isBlank()) {
            return null;
        }
        // Jedis 8 removed JedisPooled; a UnifiedJedis over a pooled provider
        // is the replacement, and pools internally so there is no resource to
        // borrow and return per call.
        var uri = java.net.URI.create(env.standbyRedisUrl());
        int port = uri.getPort() > 0 ? uri.getPort() : 6379;
        var config = DefaultJedisClientConfig.builder()
                .sslOptions("rediss".equalsIgnoreCase(uri.getScheme())
                        ? SslOptions.builder().build()
                        : null)
                .build();
        // Retry bounds chosen against the election's own timings, not left to
        // the client's defaults: a heartbeat is 10s and the lock TTL 30s, so
        // the client must give up well inside a heartbeat — an election that
        // blocks is an election that cannot demote. Three attempts absorbs a
        // dropped packet without letting one slow round-trip cost leadership.
        var provider = new PooledConnectionProvider(new HostAndPort(uri.getHost(), port), config);
        // Retry bounds chosen against the election's own timings rather than
        // left to the client's defaults: a heartbeat is 10s and the lock TTL
        // 30s, so the client must give up well inside a heartbeat — an
        // election that blocks is an election that cannot demote. Three
        // attempts absorbs a dropped packet without one slow round-trip
        // costing leadership.
        return new UnifiedJedis(provider, 3, Duration.ofSeconds(3));
    }

    private static LockStore lockStore(Env env, UnifiedJedis client) {
        if (client != null) {
            return new RedisLockStore(client);
        }
        // Standby disabled: LeaderElection never touches the store, so a
        // throwing stub is safer than a no-op that could silently grant
        // leadership if the disabled path ever regressed.
        return new LockStore() {
            @Override
            public boolean acquire(String key, String value, Duration ttl) {
                throw new IllegalStateException("standby is disabled; no lock store is configured");
            }

            @Override
            public boolean refresh(String key, String value, Duration ttl) {
                throw new IllegalStateException("standby is disabled; no lock store is configured");
            }

            @Override
            public void release(String key, String value) {
            }

            @Override
            public void ping() {
                throw new IllegalStateException("standby is disabled; no lock store is configured");
            }
        };
    }

    private static Traffic trafficFor(Env env, Clock clock) {
        if (!env.albEnabled() || env.albTargetGroupArn().isBlank() || env.albInstanceIp().isBlank()) {
            if (env.albEnabled()) {
                LOG.warn("ALB enabled but target group ARN or instance IP is missing; traffic management disabled");
            }
            return Traffic.DISABLED;
        }
        var drainTimeout = env.albDeregDelaySec() > 0
                ? Duration.ofSeconds(env.albDeregDelaySec())
                : AlbTraffic.DEFAULT_DRAIN_TIMEOUT;
        return new AlbTraffic(
                new AlbTraffic.Config(env.albInstanceIp(), env.albPort(), drainTimeout),
                Elbv2TargetGroup.create(env.albTargetGroupArn(), env.albRegion()),
                clock);
    }

    /// Builds a consumer for a configured queue, choosing the backend by URI
    /// scheme (`docs/spec/router.md` §7.1).
    ///
    /// `dataSource` may be null: a deployment consuming solely from SQS or
    /// NATS needs no database, which is what lets a router-only instance skip
    /// Postgres entirely — and, since a `postgres://…` queue now opens its
    /// own pool from its own URI (`QueueFactory#createPostgres`), a router
    /// consuming Postgres queues from `FLOWCATALYST_CONFIG_URL` needs no
    /// platform pool either.
    ///
    /// `dataSource`'s own connection string is [#defaultQueueUri] — so
    /// [QueueFactory] can recognise a config-URL-supplied queue's URI as
    /// "already [dataSource]'s database" (dev mode: the platform's own
    /// served router-config document names every Postgres-backed queue with
    /// [io.flowcatalyst.server.Env#databaseUrl] verbatim,
    /// `docs/spec/deployed-dispatch.md` §3) and reuse it instead of opening a
    /// redundant second pool next to it.
    private static RouterManager.ConsumerFactory consumerFactory(DataSource dataSource, Env env) {
        return new io.flowcatalyst.router.queue.QueueFactory(
                dataSource, dataSource == null ? null : defaultQueueUri(env));
    }

    /// Where the router's configuration comes from.
    ///
    /// With a config URL the router polls it (§8.1) — dev and prod alike
    /// (R4, `docs/go-mirror/2026-09-12-dispatch-rulings.md`): `fcdev` points
    /// its own config URL at its own platform, whose served document lists
    /// Postgres-backed queues instead of SQS ones
    /// (`docs/spec/deployed-dispatch.md` §3 "Dev mode"). Without one, the
    /// router starts with no queues and no pools, full stop — `FC_DEFAULT_BROKER`
    /// no longer changes this. **This removes the fixed single-queue branch
    /// entirely** (R4's ruling, "not just for dev — one code path, as
    /// intended"): a bare `fc-server` run with `FC_DEFAULT_BROKER=postgres`
    /// and no config URL is an accepted consequence, and now stops consuming
    /// anything.
    static RouterServer.ConfigSource configSource(Env env, Warnings warnings) {
        if (env.routerConfigUrl().isBlank()) {
            LOG.info("router has no config URL (R4): no queues will start");
            return RouterServer.ConfigSource.fixed(RouterConfig.EMPTY);
        }
        LOG.atInfo().setMessage("router configuration source selected")
                .addKeyValue("url", env.routerConfigUrl())
                .log();
        return io.flowcatalyst.router.config.http.HttpConfigSource.create(env.routerConfigUrl(), warnings,
                tokenManagerFor(env), env.routerPlatformUrl().isBlank() ? null : env.routerPlatformUrl());
    }

    /// The router's client-credentials auth for its own platform
    /// (`docs/spec/router-config-auth.md` §2): `FC_ROUTER_CLIENT_ID` /
    /// `FC_ROUTER_CLIENT_SECRET` set together or not at all, and only
    /// alongside [Env#routerPlatformUrl] — the credential is minted at
    /// `{routerPlatformUrl}/oauth/token`, so a credential with no platform
    /// URL to mint against is a configuration error, refused loudly here
    /// rather than silently fetching unauthenticated.
    private static TokenManager tokenManagerFor(Env env) {
        boolean hasId = !env.routerClientId().isBlank();
        boolean hasSecret = !env.routerClientSecret().isBlank();
        if (!hasId && !hasSecret) {
            return null;
        }
        if (hasId != hasSecret) {
            throw new IllegalStateException("FC_ROUTER_CLIENT_ID and FC_ROUTER_CLIENT_SECRET must be set together "
                    + "(or not at all) — router config source cannot start");
        }
        if (env.routerPlatformUrl().isBlank()) {
            throw new IllegalStateException("FC_ROUTER_CLIENT_ID/FC_ROUTER_CLIENT_SECRET are set but "
                    + "FC_ROUTER_PLATFORM_URL is not — the router has no platform to mint a token against");
        }
        return new TokenManager(env.routerPlatformUrl(), env.routerClientId(), env.routerClientSecret());
    }

    /// Go falls back to a local Postgres when the default broker is on but
    /// no database URL was given (`server/run.go:350-352`); [#consumerFactory]
    /// keeps naming a real database URL on that same fallback so a
    /// config-URL-supplied Postgres queue that happens to carry no host of
    /// its own (a bare `postgres://` sentinel) can still be recognised as
    /// "the platform's own database" rather than dereferenced literally.
    private static final String DEFAULT_BROKER_FALLBACK_URL = "postgresql://postgres@localhost:5432/flowcatalyst";

    static String defaultQueueUri(Env env) {
        String url = env.databaseUrl().isBlank() ? DEFAULT_BROKER_FALLBACK_URL : env.databaseUrl();
        return url.replaceFirst("^postgresql://", "postgres://");
    }

    @Override
    public void close() {
        // Housekeeping first: a reaper or stall sweep firing while the
        // consumers are draining would report the drain as a stall.
        housekeeping.close();
        server.close();
        // Deregister first and let the balancer drain, THEN release the
        // client that did it.
        traffic.deregister();
        traffic.close();
        if (notifier instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                LOG.warn("flushing the warning notifier failed", e);
            }
        }
        if (redisClient != null) {
            redisClient.close();
        }
        // Only set in production mode (`docs/spec/router-h2.md` §5) — dev
        // mode's JdkTransport owns nothing of its own to close. After
        // server.close() above, which has already drained the consumers
        // that would otherwise still be calling into it.
        if (vertxMediationClient != null) {
            vertxMediationClient.close();
        }
        LOG.info("router stopped");
    }
}
