package io.flowcatalyst.server;

import io.flowcatalyst.router.config.QueueConfig;
import io.flowcatalyst.router.config.RouterConfig;
import io.flowcatalyst.router.inflight.InFlightTracker;
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
import io.flowcatalyst.router.pool.Pool;
import io.flowcatalyst.router.queue.Consumer;
import io.flowcatalyst.router.queue.postgres.PostgresQueue;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final WarningStore warnings;
    private final Traffic traffic;

    /// Held so shutdown stops the housekeeping threads.
    private final LifecycleLoops housekeeping;

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
    private final Map<String, PoolMetricsCollector> poolMetrics = new ConcurrentHashMap<>();

    private Router(RouterServer server, RouterManager manager, InFlightTracker tracker,
                   BreakerRegistry breakers, WarningStore warnings, Traffic traffic,
                   LeaderElection election, LeaderElection.Config electionConfig,
                   UnifiedJedis redisClient, Map<String, PoolMetricsCollector> metrics,
                   Warnings notifier, LifecycleLoops housekeeping) {
        this.server = server;
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
        this.poolMetrics.putAll(metrics);
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

    public Map<String, PoolMetricsCollector> poolMetrics() {
        return Map.copyOf(poolMetrics);
    }

    public LeaderElection election() {
        return election;
    }

    public LeaderElection.Config electionConfig() {
        return electionConfig;
    }

    /// Builds and starts the router.
    ///
    /// `dataSource` is required only for the Postgres queue backend; a
    /// deployment consuming solely from SQS or NATS may pass null, which is
    /// why a router-only instance can skip Postgres entirely.
    public static Router start(Env env, DataSource dataSource, Clock clock) {
        var warnings = new WarningStore(clock);
        // The store is what the dashboard reads; the notifier is what reaches
        // someone who is not looking at the dashboard. Raisers get both.
        var notifier = WarningNotifier.create(env.routerNotifyWebhookUrl(),
                Warnings.Severity.WARNING, clock);
        if (notifier instanceof WarningNotifier started) {
            started.start();
        }
        var warningSink = Warnings.tee(warnings, notifier);
        var tracker = new InFlightTracker(clock);
        var breakers = new BreakerRegistry(CircuitBreaker.Config.DEFAULTS, clock);
        var mediator = new HttpMediator(HttpMediator.defaultClient(),
                env.routerDevMode() ? HttpMediator.DEV_TIMEOUT : HttpMediator.PRODUCTION_TIMEOUT,
                breakers, clock, warningSink);

        var metrics = new ConcurrentHashMap<String, PoolMetricsCollector>();
        // The broker is resolved per message from the queue it came from, so
        // it is built before the manager and closed over by it.
        var brokerRef = new java.util.concurrent.atomic.AtomicReference<QueueBroker>();
        RouterManager.PoolFactory poolFactory = config -> {
            metrics.computeIfAbsent(config.code(), ignored -> new PoolMetricsCollector(clock));
            return new Pool(config, mediator, brokerRef.get(), metrics.get(config.code()), clock);
        };

        var manager = new RouterManager(tracker, warningSink, clock, poolFactory);
        brokerRef.set(new QueueBroker(queueId -> manager.consumer(queueId).orElse(null), tracker));

        var redisClient = redisFor(env);
        var electionConfig = electionConfig(env);
        var election = new LeaderElection(electionConfig, lockStore(env, redisClient), clock);
        var traffic = trafficFor(env, clock);

        var server = new RouterServer(manager, tracker, election,
                consumerFactory(dataSource), configSource(env),
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
        var brokerStats = new BrokerStatsCache(clock);
        var housekeeping = new LifecycleLoops();
        housekeeping.start(LifecycleLoops.standard(stalls, tracker, warningSink,
                () -> brokerStats.refresh(manager.consumerNames().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                queueId -> queueId,
                                queueId -> () -> manager.consumer(queueId)
                                        .flatMap(io.flowcatalyst.router.queue.Consumer::metrics))))));

        server.start();
        LOG.info("router started leader={} prefix={} standby={} alb={}",
                server.leader(), env.routerHttpPrefix(), env.standbyEnabled(), env.albEnabled());
        return new Router(server, manager, tracker, breakers, warnings, traffic, election, electionConfig,
                redisClient, metrics, notifier, housekeeping);
    }

    private static LeaderElection.Config electionConfig(Env env) {
        return env.standbyEnabled()
                ? LeaderElection.Config.of(env.standbyLockKey())
                : LeaderElection.Config.disabled();
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
    /// Postgres entirely.
    private static RouterManager.ConsumerFactory consumerFactory(DataSource dataSource) {
        return new io.flowcatalyst.router.queue.QueueFactory(dataSource);
    }

    /// Where the router's configuration comes from.
    ///
    /// With a config URL the router polls it (§8.1). Without one it runs the
    /// **default broker**: a single Postgres queue and the fallback pool,
    /// which is what `fcdev` and single-tenant deployments use (§8.4).
    private static RouterServer.ConfigSource configSource(Env env) {
        if (!env.routerConfigUrl().isBlank()) {
            LOG.info("router configuration from {}", env.routerConfigUrl());
            return io.flowcatalyst.router.config.http.HttpConfigSource.create(env.routerConfigUrl());
        }
        var queue = QueueConfig.of(defaultQueueUri(env));
        LOG.info("router using the default broker queue={}", queue.queueName());
        return RouterServer.ConfigSource.fixed(new RouterConfig(List.of(), List.of(queue)));
    }

    private static String defaultQueueUri(Env env) {
        return env.databaseUrl().replaceFirst("^postgresql://", "postgres://");
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
        LOG.info("router stopped");
    }
}
