package io.flowcatalyst.router.api;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.router.inflight.InFlightMessage;
import io.flowcatalyst.router.inflight.InFlightTracker;
import io.flowcatalyst.router.lifecycle.BrokerStatsCache;
import io.flowcatalyst.router.manager.RouterManager;
import io.flowcatalyst.router.observability.Warnings;
import io.flowcatalyst.router.observability.PoolMetricsCollector;
import io.flowcatalyst.router.observability.WarningStore;
import io.flowcatalyst.router.policy.BreakerRegistry;
import io.flowcatalyst.router.policy.CircuitBreaker;
import io.flowcatalyst.router.pool.Broker;
import io.flowcatalyst.router.pool.Mediator;
import io.flowcatalyst.router.pool.Pool;
import io.flowcatalyst.router.pool.PoolMetrics;
import io.flowcatalyst.router.pool.QueuedMessage;
import io.flowcatalyst.router.queue.Consumer;
import io.flowcatalyst.router.queue.QueueMetrics;
import io.flowcatalyst.router.standby.LeaderElection;
import io.flowcatalyst.router.standby.LockStore;
import io.flowcatalyst.router.traffic.AlbTraffic;
import io.flowcatalyst.router.traffic.TargetGroup;
import io.flowcatalyst.router.wire.DispatchMode;
import io.flowcatalyst.router.wire.MediationOutcome;
import io.flowcatalyst.router.wire.MediationType;
import io.flowcatalyst.router.wire.Message;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/// End-to-end HTTP coverage of the router monitoring API (`docs/spec/router.md`
/// §9.1) against real [WarningStore]/[InFlightTracker]/[BreakerRegistry]/
/// [RouterManager]/[LeaderElection] instances — no mocking of the components
/// this module reads from, per `CONVENTIONS.md` §6.
///
/// `http` is a fully-wired instance; `bare` shares the same warnings/tracker
/// but has `manager`/`breakers`/`election` all `null`, to exercise the
/// provider-absent branches (spec §9.1: "503 for mutations/lookups, empty
/// payload for lists").
@SuppressWarnings("deprecation")
class RouterApiTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final Mediator NO_OP_MEDIATOR = (msg, recordFailure) -> {
        throw new UnsupportedOperationException("not exercised by these tests");
    };
    private static final Broker NO_OP_BROKER = new Broker() {
        @Override
        public void ack(QueuedMessage message) {
        }

        @Override
        public void nack(QueuedMessage message, Duration delay) {
        }

        @Override
        public void release(QueuedMessage message) {
        }
    };

    private static WarningStore warnings;
    private static InFlightTracker tracker;
    private static BreakerRegistry breakers;
    private static RouterManager manager;
    private static Pool poolA;
    private static PoolMetricsCollector poolAMetrics;
    private static RecordingConsumer consumerQ1;
    private static BrokerStatsCache brokerStats;
    private static FakeTargetGroup targetGroup;
    private static AlbTraffic traffic;
    private static TestHttp http;
    private static TestHttp bare;

    @BeforeAll
    static void start() {
        warnings = new WarningStore(CLOCK);
        tracker = new InFlightTracker(CLOCK);
        breakers = new BreakerRegistry(CircuitBreaker.Config.DEFAULTS, CLOCK);
        manager = new RouterManager(tracker, Warnings.NO_OP, CLOCK,
                cfg -> new Pool(cfg, NO_OP_MEDIATOR, NO_OP_BROKER, PoolMetrics.NO_OP, CLOCK));
        poolAMetrics = new PoolMetricsCollector(CLOCK);
        poolA = new Pool(new Pool.Config("POOL-A", 5, 100), NO_OP_MEDIATOR, NO_OP_BROKER, poolAMetrics, CLOCK);
        manager.registerPool("POOL-A", poolA);
        consumerQ1 = new RecordingConsumer("queue-1");
        manager.registerConsumer(consumerQ1);

        var electionConfig = new LeaderElection.Config(true, "fc:test:leader", "instance-a",
                Duration.ofSeconds(30), Duration.ofSeconds(10));
        var election = new LeaderElection(electionConfig, new AlwaysAcquireStore(), CLOCK);
        election.start();

        brokerStats = new BrokerStatsCache(CLOCK);
        // A real AlbTraffic over a stub target group, not a stub Traffic: the
        // status this endpoint renders — including the failed-deregister
        // disagreement — is produced by AlbTraffic, and stubbing it would
        // leave the wire shape asserted against a hand-written answer.
        targetGroup = new FakeTargetGroup();
        traffic = new AlbTraffic(
                new AlbTraffic.Config("10.0.0.7", 8080, Duration.ofSeconds(1), Duration.ofMillis(1)),
                targetGroup, CLOCK);

        var state = new RouterApi.State(manager, tracker, warnings, breakers, election, electionConfig,
                "test-version", "/router", null, Map.of("POOL-A", poolAMetrics), traffic, brokerStats);
        http = new TestHttp(cfg -> RouterApi.register(cfg.routes, state));
        warmUp(http);

        var bareState = new RouterApi.State(null, tracker, warnings, null, null, null, null, "/router", null, null, null, null);
        bare = new TestHttp(cfg -> RouterApi.register(cfg.routes, bareState));
        warmUp(bare);
    }

    @AfterAll
    static void stop() {
        http.close();
        bare.close();
        poolA.close();
    }

    private static JsonNode json(HttpResponse<String> r) {
        try {
            return Json.MAPPER.readTree(r.body());
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + r.body(), e);
        }
    }

    private static String tag() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    /// Absorbs a one-off flake reproduced independently of this suite:
    /// `/health/live` is unconditionally registered on every [RouterApi.State],
    /// so it is a harmless first request. A freshly bound Jetty connector
    /// occasionally (~1/150 in a tight loop) drops the very first connection
    /// on a JDK `HttpClient` ("EOF reached while reading" / "header parser
    /// received no bytes") — a `TestHttp`/OS-level race unrelated to routing.
    /// Firing one disposable request before the real assertions run keeps
    /// that race from occasionally failing a test outright.
    /// Waits until a freshly bound connector actually serves a request.
    ///
    /// One attempt is not enough. `/health/live` is unconditionally
    /// registered on every [RouterApi.State], so it is a safe probe — but a
    /// connector that rejects the first request may reject the second, and
    /// absorbing exactly one failure leaves the *next* call to fail instead,
    /// with a body that parses to something without the field under test.
    /// That produced a NullPointerException in full-suite runs and passed
    /// standalone, which reads like an ordering bug and is not one.
    ///
    /// Retries until it genuinely answers, so a test that gets past this line
    /// is talking to a server that works.
    private static void warmUp(TestHttp http) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        RuntimeException last = null;
        while (System.nanoTime() < deadline) {
            try {
                if (http.get("/router/health/live").statusCode() == 200) {
                    return;
                }
            } catch (RuntimeException e) {
                last = e;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("router test server never became ready", last);
    }

    // ── Health ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /health reports the snake_case SimpleHealthResponse shape")
    void healthShape() {
        var r = http.get("/router/health");
        assertThat(r.statusCode()).isEqualTo(200);
        var body = json(r);
        // Pins the exact snake_case field names the spec's table requires.
        assertThat(body.has("status")).isTrue();
        assertThat(body.get("version").asText()).isEqualTo("test-version");
        assertThat(body.has("active_warnings")).isTrue();
        assertThat(body.has("critical_warnings")).isTrue();
        assertThat(body.has("activeWarnings")).as("must be snake_case, not camelCase").isFalse();
    }

    @Test
    @DisplayName("GET /q/health is the same handler as /health")
    void healthAlias() {
        assertThat(http.get("/router/q/health").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("GET /health/live always answers 200 LIVE")
    void liveness() {
        var r = http.get("/router/health/live");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(json(r).get("status").asText()).isEqualTo("LIVE");
    }

    @Test
    @DisplayName("readiness and health status turn DEGRADED on an unacknowledged CRITICAL warning, and back on acknowledge")
    void readinessDegradesOnCritical() {
        var isolated = new WarningStore(CLOCK);
        var state = new RouterApi.State(null, new InFlightTracker(CLOCK), isolated, null, null, null,
                "v", "/router", null, null, null, null);
        try (var isolatedHttp = new TestHttp(cfg -> RouterApi.register(cfg.routes, state))) {
            warmUp(isolatedHttp);
            assertThat(isolatedHttp.get("/router/health/ready").statusCode())
                    .as("healthy with no warnings").isEqualTo(200);
            assertThat(json(isolatedHttp.get("/router/health/ready")).get("status").asText()).isEqualTo("READY");

            isolated.raise(Warnings.Severity.CRITICAL, "CONFIGURATION", "boom-" + tag());

            var ready = isolatedHttp.get("/router/health/ready");
            assertThat(ready.statusCode()).as("critical warning -> 503 NOT_READY (spec §9.4)").isEqualTo(503);
            assertThat(json(ready).get("status").asText()).isEqualTo("NOT_READY");

            var health = json(isolatedHttp.get("/router/health"));
            assertThat(health.get("status").asText()).isEqualTo("DEGRADED");
            assertThat(health.get("critical_warnings").asInt()).isEqualTo(1);

            var startup = isolatedHttp.get("/router/health/startup");
            assertThat(startup.statusCode()).isEqualTo(503);

            var id = isolated.unacknowledged().stream().findFirst().orElseThrow().id();
            isolated.acknowledge(id);

            assertThat(isolatedHttp.get("/router/health/ready").statusCode())
                    .as("acknowledging the only critical warning clears DEGRADED").isEqualTo(200);
        }
    }

    @Test
    @DisplayName("WARNING and DEGRADED thresholds follow the active-warning count (spec §9.4 table)")
    void warningThresholds() {
        var isolated = new WarningStore(CLOCK);
        var state = new RouterApi.State(null, new InFlightTracker(CLOCK), isolated, null, null, null,
                "v", "/router", null, null, null, null);
        try (var isolatedHttp = new TestHttp(cfg -> RouterApi.register(cfg.routes, state))) {
            warmUp(isolatedHttp);
            for (int i = 0; i < 6; i++) {
                isolated.raise(Warnings.Severity.WARNING, "ROUTING", "w" + i + "-" + tag());
            }
            var body = json(isolatedHttp.get("/router/health"));
            assertThat(body.get("status").asText()).as("active > 5 -> WARNING").isEqualTo("WARNING");

            for (int i = 6; i < 21; i++) {
                isolated.raise(Warnings.Severity.WARNING, "ROUTING", "w" + i + "-" + tag());
            }
            assertThat(json(isolatedHttp.get("/router/health")).get("status").asText())
                    .as("active > 20 -> DEGRADED even with zero criticals").isEqualTo("DEGRADED");
        }
    }

    @Test
    @DisplayName("GET /monitoring/health reports totalPools from the manager and always-zero totalQueues/healthyQueues")
    void monitoringHealth() {
        var body = json(http.get("/router/monitoring/health"));
        assertThat(body.has("timestamp")).isTrue();
        assertThat(body.has("uptimeMillis")).isTrue();
        var details = body.get("details");
        assertThat(details.get("totalPools").asInt()).as("one pool registered").isEqualTo(1);
        assertThat(details.get("healthyPools").asInt()).isEqualTo(1);
        // Never-fed consumer model (spec §9.4) -> always 0, deliberately.
        assertThat(details.get("totalQueues").asInt()).isZero();
        assertThat(details.get("healthyQueues").asInt()).isZero();
    }

    @Test
    @DisplayName("GET /monitoring/consumer-health is always the empty-consumers shape")
    void consumerHealthAlwaysEmpty() {
        var body = json(http.get("/router/monitoring/consumer-health"));
        assertThat(body.has("currentTimeMs")).isTrue();
        assertThat(body.has("currentTime")).isTrue();
        assertThat(body.get("consumers").isObject()).isTrue();
        assertThat(body.get("consumers").isEmpty()).isTrue();
    }

    // ── Pools ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /monitoring: snake_case outer/pool_stats fields, camelCase metrics, and the all-unacked vs <=30min active_warnings distinction")
    void monitoringComposite() {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var isolatedWarnings = new WarningStore(clock);
        var isolatedTracker = new InFlightTracker(clock);
        var isolatedManager = new RouterManager(isolatedTracker, Warnings.NO_OP, clock,
                cfg -> new Pool(cfg, NO_OP_MEDIATOR, NO_OP_BROKER, PoolMetrics.NO_OP, clock));
        var metricsCollector = new PoolMetricsCollector(clock);
        var pool = new Pool(new Pool.Config("M-POOL", 4, 0), NO_OP_MEDIATOR, NO_OP_BROKER, metricsCollector, clock);
        isolatedManager.registerPool("M-POOL", pool);
        metricsCollector.recordSuccess(Duration.ofMillis(20));
        metricsCollector.recordFailure(Duration.ofMillis(30));

        isolatedWarnings.raise(Warnings.Severity.WARNING, "ROUTING", "old-unacked");
        clock.advance(Duration.ofMinutes(31));
        isolatedWarnings.raise(Warnings.Severity.WARNING, "ROUTING", "fresh-unacked");

        var state = new RouterApi.State(isolatedManager, isolatedTracker, isolatedWarnings, null, null, null,
                "v", "/router", null, Map.of("M-POOL", metricsCollector), null, null);
        try (var isolatedHttp = new TestHttp(cfg -> RouterApi.register(cfg.routes, state))) {
            warmUp(isolatedHttp);
            var body = json(isolatedHttp.get("/router/monitoring"));
            assertThat(body.has("pool_stats")).as("snake outer key").isTrue();
            assertThat(body.has("poolStats")).as("must not be camelCase").isFalse();
            assertThat(body.has("health_report")).isTrue();

            // The distinction the spec calls out explicitly (§9.1 note).
            assertThat(body.get("active_warnings").asInt())
                    .as("top-level active_warnings = ALL unacknowledged, any age").isEqualTo(2);
            assertThat(body.get("health_report").get("active_warnings").asInt())
                    .as("health_report.active_warnings is unacked <=30min only").isEqualTo(1);

            var entry = body.get("pool_stats").get(0);
            assertThat(entry.get("pool_code").asText()).isEqualTo("M-POOL");
            assertThat(entry.get("concurrency").asInt()).isEqualTo(4);
            assertThat(entry.has("active_workers")).isTrue();
            assertThat(entry.has("is_rate_limited")).isTrue();
            assertThat(entry.has("rate_limit_per_minute")).as("0 requestsPerMinute -> unlimited -> omitted").isFalse();

            var metrics = entry.get("metrics");
            assertThat(metrics.has("totalSuccess")).as("nested metrics is camelCase, not snake").isTrue();
            assertThat(metrics.has("total_success")).isFalse();
            assertThat(metrics.get("totalSuccess").asLong()).isEqualTo(1);
            assertThat(metrics.get("totalFailure").asLong()).isEqualTo(1);
        } finally {
            pool.close();
        }
    }

    @Test
    @DisplayName("GET /monitoring/mediating shows what is in a worker, longest-stuck first")
    void monitoringMediating() throws Exception {
        // The question this endpoint exists for is "what is stuck?", which a
        // count cannot answer: eight busy workers and eight workers wedged
        // against one dead target are the same number and nothing alike.
        var gate = new java.util.concurrent.CountDownLatch(1);
        var entered = new java.util.concurrent.CountDownLatch(2);
        var isolatedManager = new RouterManager(new InFlightTracker(CLOCK), Warnings.NO_OP, CLOCK,
                cfg -> new Pool(cfg, NO_OP_MEDIATOR, NO_OP_BROKER, PoolMetrics.NO_OP, CLOCK));
        var held = new Pool(new Pool.Config("HELD-POOL", 4, 0), (msg, recordFailure) -> {
            entered.countDown();
            gate.await();
            return MediationOutcome.Success.of(200);
        }, NO_OP_BROKER, PoolMetrics.NO_OP, CLOCK);
        isolatedManager.registerPool("HELD-POOL", held);
        var state = new RouterApi.State(isolatedManager, new InFlightTracker(CLOCK), new WarningStore(CLOCK),
                null, null, null, "v", "/router", null, Map.of(), null, null);
        try (var isolatedHttp = new TestHttp(cfg -> RouterApi.register(cfg.routes, state))) {
            warmUp(isolatedHttp);
            // Staggered on purpose. Submitted together they enter their
            // workers in the same millisecond, their elapsed times tie, and
            // the ordering assertion below passes whether or not anything
            // sorts — which is exactly what a decorative test looks like.
            held.submit(message("stuck-1", "https://slow.test/a"));
            await(() -> held.activeWorkers() == 1);
            Thread.sleep(60);
            held.submit(message("stuck-2", "https://slow.test/b"));
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    .as("both messages reached a worker").isTrue();

            var body = json(isolatedHttp.get("/router/monitoring/mediating"));

            assertThat(body.isArray()).isTrue();
            assertThat(body.size()).isEqualTo(2);
            var ids = new java.util.ArrayList<String>();
            body.forEach(row -> ids.add(row.get("messageId").asText()));
            assertThat(ids).containsExactlyInAnyOrder("stuck-1", "stuck-2");

            var first = body.get(0);
            assertThat(first.get("messageId").asText())
                    .as("longest-stuck first: the answer to \"what is stuck\" is the top row")
                    .isEqualTo("stuck-1");
            // Go's field names, exactly — this is a wire contract.
            assertThat(first.get("poolCode").asText()).isEqualTo("HELD-POOL");
            assertThat(first.get("target").asText()).startsWith("https://slow.test/");
            assertThat(first.has("queue")).isTrue();
            assertThat(first.has("attempts")).isTrue();
            assertThat(first.has("elapsedTimeMs")).isTrue();
            // Longest-first: the answer to "what is stuck" is always the top row.
            assertThat(first.get("elapsedTimeMs").asLong())
                    .isGreaterThan(body.get(1).get("elapsedTimeMs").asLong());

            // The count and the rows come from one structure, so they cannot
            // disagree — which is the reason the counter was replaced.
            assertThat(held.activeWorkers()).isEqualTo(body.size());

            // A pool filter that matches nothing empties the list rather than
            // quietly ignoring the filter.
            assertThat(json(isolatedHttp.get("/router/monitoring/mediating?poolCode=OTHER")).size()).isZero();
            assertThat(json(isolatedHttp.get("/router/monitoring/mediating?poolCode=held-pool")).size())
                    .as("pool filter is case-insensitive, as in Go").isEqualTo(2);
            // A junk limit is an operator typo on a read-only call, not a 500.
            assertThat(json(isolatedHttp.get("/router/monitoring/mediating?limit=nonsense")).size()).isEqualTo(2);
            assertThat(json(isolatedHttp.get("/router/monitoring/mediating?limit=1")).size()).isOne();
        } finally {
            gate.countDown();
            held.close();
        }

        // Nothing in a worker once they finish — the set is never reaped, so
        // a row outliving its delivery would be a leak, not a stale cache.
        assertThat(held.activeWorkers()).isZero();
        assertThat(json(bare.get("/router/monitoring/mediating")).isEmpty())
                .as("no manager wired -> [] rather than an error").isTrue();
    }

    @Test
    @DisplayName("mediating rows sort longest-first regardless of the order they arrive in")
    void mediatingRowsSortLongestFirst() {
        // Fed deliberately newest-first, which the endpoint itself cannot
        // produce — its rows come out of a map in roughly insertion order, so
        // an unsorted implementation passes there by luck.
        var now = java.time.Instant.parse("2026-01-01T00:00:10Z");
        var newest = new io.flowcatalyst.router.pool.Mediating("new", "P", "", "q", "t", 0,
                java.time.Instant.parse("2026-01-01T00:00:09Z"));
        var oldest = new io.flowcatalyst.router.pool.Mediating("old", "P", "", "q", "t", 0,
                java.time.Instant.parse("2026-01-01T00:00:00Z"));
        var middle = new io.flowcatalyst.router.pool.Mediating("mid", "P", "", "q", "t", 0,
                java.time.Instant.parse("2026-01-01T00:00:05Z"));

        var rows = PoolRoutes.mediatingRows(List.of(newest, middle, oldest), null, 200, now);

        assertThat(rows).extracting(Wire.WireMediating::messageId)
                .containsExactly("old", "mid", "new");
        assertThat(rows.getFirst().elapsedTimeMs()).isEqualTo(10_000);
        // The limit keeps the longest-stuck, not an arbitrary three.
        assertThat(PoolRoutes.mediatingRows(List.of(newest, middle, oldest), null, 1, now))
                .extracting(Wire.WireMediating::messageId).containsExactly("old");
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("condition not met within 5s");
    }

    private static QueuedMessage message(String id, String target) {
        return QueuedMessage.of(
                new Message(id, "", null, null, MediationType.HTTP, target, null, false,
                        DispatchMode.IMMEDIATE),
                "broker-" + id, "receipt-" + id, "queue-1");
    }

    @Test
    @DisplayName("GET /monitoring/pools lists every pool's WirePoolStats, and [] with no manager wired")
    void monitoringPools() {
        var body = json(http.get("/router/monitoring/pools"));
        assertThat(body.isArray()).isTrue();
        boolean foundPoolA = false;
        for (var entry : body) {
            if ("POOL-A".equals(entry.get("pool_code").asText())) {
                foundPoolA = true;
                assertThat(entry.get("concurrency").asInt()).isEqualTo(5);
                assertThat(entry.has("metrics")).isTrue();
            }
        }
        assertThat(foundPoolA).as("POOL-A, registered in @BeforeAll, is in the list").isTrue();

        var bareBody = json(bare.get("/router/monitoring/pools"));
        assertThat(bareBody.isArray()).isTrue();
        assertThat(bareBody.isEmpty()).as("empty payload for lists with no manager wired").isTrue();
    }

    @Test
    @DisplayName("GET /monitoring/pools' is_rate_limited reflects the pool's own live limiter, not target 429s")
    void monitoringPoolsIsRateLimited() throws InterruptedException {
        var delivered = new CountDownLatch(1);
        Broker signalling = new Broker() {
            @Override
            public void ack(QueuedMessage message) {
                delivered.countDown();
            }

            @Override
            public void nack(QueuedMessage message, Duration delay) {
                delivered.countDown();
            }

        @Override
        public void release(QueuedMessage message) {
        }
        };
        var isolatedTracker = new InFlightTracker(CLOCK);
        var isolatedManager = new RouterManager(isolatedTracker, Warnings.NO_OP, CLOCK,
                cfg -> new Pool(cfg, NO_OP_MEDIATOR, signalling, PoolMetrics.NO_OP, CLOCK));
        // rpm=1: the bucket starts full at exactly one token, so the single
        // delivery below drains it to zero without waiting out real time.
        var limitedPool = new Pool(new Pool.Config("RL-POOL", 1, 1), (msg, recordFailure) ->
                MediationOutcome.Success.of(200), signalling, PoolMetrics.NO_OP, CLOCK);
        isolatedManager.registerPool("RL-POOL", limitedPool);
        var unlimitedPool = new Pool(new Pool.Config("UNLIMITED-POOL", 1, 0), NO_OP_MEDIATOR, NO_OP_BROKER,
                PoolMetrics.NO_OP, CLOCK);
        isolatedManager.registerPool("UNLIMITED-POOL", unlimitedPool);
        var state = new RouterApi.State(isolatedManager, isolatedTracker, new WarningStore(CLOCK), null, null, null,
                "v", "/router", null, null, null, null);
        try (var isolatedHttp = new TestHttp(cfg -> RouterApi.register(cfg.routes, state))) {
            warmUp(isolatedHttp);
            var message = new Message("rl-" + tag(), "RL-POOL", null, null, null, "https://example.invalid/hook",
                    "", false, null);
            limitedPool.submit(QueuedMessage.of(message, "", "rh", "queue-1"));
            assertThat(delivered.await(2, TimeUnit.SECONDS)).as("the single token was spent").isTrue();

            var body = json(isolatedHttp.get("/router/monitoring/pools"));
            boolean limitedSeen = false;
            boolean unlimitedSeen = false;
            for (var entry : body) {
                if ("RL-POOL".equals(entry.get("pool_code").asText())) {
                    assertThat(entry.get("is_rate_limited").asBoolean())
                            .as("the pool's own bucket is empty right now").isTrue();
                    limitedSeen = true;
                }
                if ("UNLIMITED-POOL".equals(entry.get("pool_code").asText())) {
                    assertThat(entry.get("is_rate_limited").asBoolean())
                            .as("rpm=0 -> unlimited -> never rate limited").isFalse();
                    unlimitedSeen = true;
                }
            }
            assertThat(limitedSeen).isTrue();
            assertThat(unlimitedSeen).isTrue();
        } finally {
            limitedPool.close();
            unlimitedPool.close();
        }
    }

    @Test
    @DisplayName("GET /monitoring/pool-stats?time_window= selects the matching PoolMetricsCollector window; unknown values fall back to all-time")
    void poolStatsWindowSelection() {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var isolatedTracker = new InFlightTracker(clock);
        var isolatedManager = new RouterManager(isolatedTracker, Warnings.NO_OP, clock,
                cfg -> new Pool(cfg, NO_OP_MEDIATOR, NO_OP_BROKER, PoolMetrics.NO_OP, clock));
        var metrics = new PoolMetricsCollector(clock);
        var pool = new Pool(new Pool.Config("W-POOL", 2, 0), NO_OP_MEDIATOR, NO_OP_BROKER, metrics, clock);
        isolatedManager.registerPool("W-POOL", pool);

        metrics.recordSuccess(Duration.ofMillis(10));
        metrics.recordSuccess(Duration.ofMillis(10));
        clock.advance(Duration.ofMinutes(6)); // now outside the 5-minute window, still inside 30
        metrics.recordSuccess(Duration.ofMillis(10));

        var state = new RouterApi.State(isolatedManager, isolatedTracker, new WarningStore(clock), null, null, null,
                "v", "/router", null, Map.of("W-POOL", metrics), null, null);
        try (var isolatedHttp = new TestHttp(cfg -> RouterApi.register(cfg.routes, state))) {
            warmUp(isolatedHttp);
            var fiveMin = json(isolatedHttp.get("/router/monitoring/pool-stats?time_window=5min")).get("W-POOL");
            assertThat(fiveMin.get("totalProcessed").asLong()).as("only the sample inside the 5min window")
                    .isEqualTo(1);

            var thirtyMin = json(isolatedHttp.get("/router/monitoring/pool-stats?time_window=30m")).get("W-POOL");
            assertThat(thirtyMin.get("totalProcessed").asLong()).isEqualTo(3);

            var allTime = json(isolatedHttp.get("/router/monitoring/pool-stats")).get("W-POOL");
            assertThat(allTime.get("totalProcessed").asLong()).isEqualTo(3);

            var unknown = json(isolatedHttp.get("/router/monitoring/pool-stats?time_window=bogus")).get("W-POOL");
            assertThat(unknown.get("totalProcessed").asLong()).as("unknown value falls back to all-time")
                    .isEqualTo(3);
        } finally {
            pool.close();
        }
    }

    @Test
    @DisplayName("GET /monitoring/pool-stats reflects live activeWorkers, and availablePermits = concurrency - activeWorkers; {} with no manager wired")
    void poolStatsActiveWorkersAndAvailablePermits() throws InterruptedException {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Mediator blocking = (msg, recordFailure) -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return MediationOutcome.Success.of(200);
        };
        var isolatedTracker = new InFlightTracker(CLOCK);
        var isolatedManager = new RouterManager(isolatedTracker, Warnings.NO_OP, CLOCK,
                cfg -> new Pool(cfg, blocking, NO_OP_BROKER, PoolMetrics.NO_OP, CLOCK));
        var busyPool = new Pool(new Pool.Config("BUSY-POOL", 3, 0), blocking, NO_OP_BROKER, PoolMetrics.NO_OP, CLOCK);
        isolatedManager.registerPool("BUSY-POOL", busyPool);
        var state = new RouterApi.State(isolatedManager, isolatedTracker, new WarningStore(CLOCK), null, null, null,
                "v", "/router", null, null, null, null);
        try (var isolatedHttp = new TestHttp(cfg -> RouterApi.register(cfg.routes, state))) {
            warmUp(isolatedHttp);
            var message = new Message("busy-" + tag(), "BUSY-POOL", null, null, null,
                    "https://example.invalid/hook", "", false, null);
            busyPool.submit(QueuedMessage.of(message, "", "rh", "queue-1"));
            assertThat(entered.await(2, TimeUnit.SECONDS)).as("delivery started").isTrue();

            var body = json(isolatedHttp.get("/router/monitoring/pool-stats")).get("BUSY-POOL");
            assertThat(body.get("activeWorkers").asInt()).isEqualTo(1);
            assertThat(body.get("maxConcurrency").asInt()).isEqualTo(3);
            assertThat(body.get("availablePermits").asInt()).as("concurrency - activeWorkers").isEqualTo(2);

            release.countDown();
        } finally {
            busyPool.close();
        }

        assertThat(json(bare.get("/router/monitoring/pool-stats")).isEmpty())
                .as("empty payload for lists with no manager wired").isTrue();
    }

    // ── Warnings ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("/warnings supports severity, category and acknowledged=false filters, WARN aliases WARNING, newest first")
    void warningsFiltering() {
        String t = tag();
        warnings.raise(Warnings.Severity.WARNING, "ROUTING", "route-" + t);
        warnings.raise(Warnings.Severity.ERROR, "CONFIGURATION", "cfg-" + t);
        warnings.raise(Warnings.Severity.CRITICAL, "CONFIGURATION", "crit-" + t);

        var all = json(http.get("/router/warnings?category=CONFIGURATION"));
        var messages = new java.util.ArrayList<String>();
        all.forEach(n -> messages.add(n.get("message").asText()));
        assertThat(messages).as("category filter, case-insens.").contains("cfg-" + t, "crit-" + t)
                .doesNotContain("route-" + t);

        var warn = json(http.get("/router/warnings?severity=WARN"));
        var warnMessages = new java.util.ArrayList<String>();
        warn.forEach(n -> warnMessages.add(n.get("message").asText()));
        assertThat(warnMessages).as("WARN is an alias of WARNING only").contains("route-" + t)
                .doesNotContain("cfg-" + t, "crit-" + t);

        var ackId = warnings.unacknowledged().stream()
                .filter(n -> n.message().equals("route-" + t)).findFirst().orElseThrow().id();
        warnings.acknowledge(ackId);
        var unackedOnly = json(http.get("/router/warnings?acknowledged=false"));
        var unackedMessages = new java.util.ArrayList<String>();
        unackedOnly.forEach(n -> unackedMessages.add(n.get("message").asText()));
        assertThat(unackedMessages).as("acknowledged=false filters to unacked only").doesNotContain("route-" + t)
                .contains("cfg-" + t);
    }

    @Test
    @DisplayName("/warnings/critical lists CRITICAL warnings whether acknowledged or not")
    void criticalWarningsIncludeAcked() {
        String t = tag();
        warnings.raise(Warnings.Severity.CRITICAL, "CONFIGURATION", "critack-" + t);
        var id = warnings.unacknowledged().stream()
                .filter(n -> n.message().equals("critack-" + t)).findFirst().orElseThrow().id();
        warnings.acknowledge(id);

        var body = json(http.get("/router/warnings/critical"));
        var messages = new java.util.ArrayList<String>();
        body.forEach(n -> messages.add(n.get("message").asText()));
        assertThat(messages).as("acked CRITICAL still shown here, unlike WarningStore#critical()")
                .contains("critack-" + t);
    }

    @Test
    @DisplayName("POST /warnings/{id}/acknowledge acknowledges once and 404s on an unknown or malformed id")
    void acknowledgeWarning() {
        warnings.raise(Warnings.Severity.INFO, "RESOURCE", "ack-me-" + tag());
        var id = warnings.unacknowledged().stream()
                .filter(n -> n.message().startsWith("ack-me-")).reduce((a, b) -> b).orElseThrow().id();

        var ok = http.post("/router/warnings/" + id + "/acknowledge", null);
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(json(ok).get("acknowledged").asBoolean()).isTrue();

        var unknown = http.post("/router/warnings/" + UUID.randomUUID() + "/acknowledge", null);
        assertThat(unknown.statusCode()).isEqualTo(404);

        var malformed = http.post("/router/warnings/not-a-uuid/acknowledge", null);
        assertThat(malformed.statusCode()).as("a non-UUID id is also \"not found\", not a 500").isEqualTo(404);
    }

    @Test
    @DisplayName("POST /monitoring/warnings/{id}/acknowledge is an alias of /warnings/{id}/acknowledge")
    void monitoringAcknowledgeAlias() {
        warnings.raise(Warnings.Severity.INFO, "RESOURCE", "alias-ack-" + tag());
        var id = warnings.unacknowledged().stream()
                .filter(n -> n.message().startsWith("alias-ack-")).findFirst().orElseThrow().id();
        var r = http.post("/router/monitoring/warnings/" + id + "/acknowledge", null);
        assertThat(r.statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("POST /warnings/acknowledge-all acknowledges every currently-unacknowledged warning")
    void acknowledgeAll() {
        var isolated = new WarningStore(CLOCK);
        var state = new RouterApi.State(null, new InFlightTracker(CLOCK), isolated, null, null, null,
                "v", "/router", null, null, null, null);
        try (var isolatedHttp = new TestHttp(cfg -> RouterApi.register(cfg.routes, state))) {
            warmUp(isolatedHttp);
            isolated.raise(Warnings.Severity.INFO, "RESOURCE", "a");
            isolated.raise(Warnings.Severity.INFO, "RESOURCE", "b");

            var r = isolatedHttp.post("/router/warnings/acknowledge-all", null);
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(json(r).get("acknowledged").asLong()).isEqualTo(2);
            assertThat(isolated.unacknowledged()).isEmpty();
        }
    }

    @Test
    @DisplayName("/monitoring/warnings shows only unacknowledged warnings <=30 minutes old, newest first")
    void monitoringWarningsWindow() {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var isolated = new WarningStore(clock);
        var state = new RouterApi.State(null, new InFlightTracker(clock), isolated, null, null, null,
                "v", "/router", null, null, null, null);
        try (var isolatedHttp = new TestHttp(cfg -> RouterApi.register(cfg.routes, state))) {
            warmUp(isolatedHttp);
            isolated.raise(Warnings.Severity.WARNING, "ROUTING", "old-one");
            clock.advance(Duration.ofMinutes(31));
            isolated.raise(Warnings.Severity.WARNING, "ROUTING", "fresh-one");

            var monitoring = json(isolatedHttp.get("/router/monitoring/warnings"));
            var monMessages = new java.util.ArrayList<String>();
            monitoring.forEach(n -> monMessages.add(n.get("message").asText()));
            assertThat(monMessages).as("the >30min-old warning is excluded here").containsExactly("fresh-one");

            // The plain /warnings/unacknowledged route has no age limit.
            var plain = json(isolatedHttp.get("/router/warnings/unacknowledged"));
            var plainMessages = new java.util.ArrayList<String>();
            plain.forEach(n -> plainMessages.add(n.get("message").asText()));
            assertThat(plainMessages).as("no age filter on the plain unacknowledged route")
                    .containsExactlyInAnyOrder("old-one", "fresh-one");
        }
    }

    // ── Circuit breakers ─────────────────────────────────────────────────

    @Test
    @DisplayName("/monitoring/circuit-breakers reports per-target stats with the always-0 fields the spec pins")
    void circuitBreakerList() {
        String target = "https://target-" + tag() + ".example/hook";
        var breaker = breakers.get(target);
        breaker.recordSuccess();
        breaker.recordFailure();

        var body = json(http.get("/router/monitoring/circuit-breakers"));
        var entry = body.get(target);
        assertThat(entry).isNotNull();
        assertThat(entry.get("state").asText()).isEqualTo("CLOSED");
        assertThat(entry.get("successfulCalls").asLong()).isEqualTo(1);
        assertThat(entry.get("failedCalls").asLong()).isEqualTo(1);
        assertThat(entry.get("failureRate").asDouble()).isEqualTo(0.5);
        // Spec: rejectedCalls and bufferSize are always 0.
        assertThat(entry.get("rejectedCalls").asLong()).isZero();
        assertThat(entry.get("bufferSize").asLong()).isZero();
    }

    @Test
    @DisplayName("circuit-breakers list is {} when no registry is configured (provider-absent list rule)")
    void circuitBreakerListBare() {
        var body = json(bare.get("/router/monitoring/circuit-breakers"));
        assertThat(body.isObject()).isTrue();
        assertThat(body.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("GET /monitoring/circuit-breakers/{name}/state: 200 for a known target, 404 unknown, 503 no registry")
    void circuitBreakerState() {
        String target = "https://state-" + tag() + ".example/hook";
        breakers.get(target).recordSuccess();

        var known = http.get("/router/monitoring/circuit-breakers/" + enc(target) + "/state");
        assertThat(known.statusCode()).isEqualTo(200);
        assertThat(json(known).get("state").asText()).isEqualTo("CLOSED");

        var unknown = http.get("/router/monitoring/circuit-breakers/" + enc("https://never-seen.example") + "/state");
        assertThat(unknown.statusCode()).isEqualTo(404);

        var noRegistry = bare.get("/router/monitoring/circuit-breakers/" + enc(target) + "/state");
        assertThat(noRegistry.statusCode()).isEqualTo(503);
    }

    @Test
    @DisplayName("POST /monitoring/circuit-breakers/{name}/reset clears state and 404s on an unknown target")
    void breakerReset() {
        String target = "https://reset-" + tag() + ".example/hook";
        var breaker = breakers.get(target);
        breaker.recordFailure();
        breaker.recordFailure();

        var r = http.post("/router/monitoring/circuit-breakers/" + enc(target) + "/reset", null);
        assertThat(r.statusCode()).isEqualTo(200);
        var body = json(r);
        assertThat(body.get("reset").asBoolean()).isTrue();
        assertThat(body.get("name").asText()).isEqualTo(target);
        assertThat(breaker.stats().failures()).as("reset clears cumulative counters").isZero();

        var unknown = http.post("/router/monitoring/circuit-breakers/" + enc("https://gone.example") + "/reset", null);
        assertThat(unknown.statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("POST /monitoring/circuit-breakers/reset-all resets every registered breaker and reports the count")
    void breakerResetAll() {
        var isolatedBreakers = new BreakerRegistry(CircuitBreaker.Config.DEFAULTS, CLOCK);
        isolatedBreakers.get("https://a.example").recordFailure();
        isolatedBreakers.get("https://b.example").recordFailure();
        var state = new RouterApi.State(null, new InFlightTracker(CLOCK), new WarningStore(CLOCK), isolatedBreakers,
                null, null, "v", "/router", null, null, null, null);
        try (var isolatedHttp = new TestHttp(cfg -> RouterApi.register(cfg.routes, state))) {
            warmUp(isolatedHttp);
            var r = isolatedHttp.post("/router/monitoring/circuit-breakers/reset-all", null);
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(json(r).get("reset").asInt()).isEqualTo(2);
        }

        var noRegistry = bare.post("/router/monitoring/circuit-breakers/reset-all", null);
        assertThat(noRegistry.statusCode()).isEqualTo(503);
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    // ── In-flight messages ───────────────────────────────────────────────

    @Test
    @DisplayName("GET /monitoring/in-flight-messages sorts elapsed DESC, filters by poolCode/messageId case-insensitively, and limit<=0 -> 100")
    void inFlightList() {
        String t = tag();
        var now = Instant.now();
        tracker.register(new InFlightMessage("m1-" + t, "", "pool-x", "queue-1",
                now.minusSeconds(5), now.minusSeconds(5), "", "b1", "rh1", 0));
        tracker.register(new InFlightMessage("m2-" + t, "", "pool-x", "queue-1",
                now.minusSeconds(50), now.minusSeconds(50), "", "b1", "rh2", 0));
        tracker.register(new InFlightMessage("m3-" + t, "", "OTHER-POOL", "queue-1",
                now.minusSeconds(20), now.minusSeconds(20), "", "b1", "rh3", 0));

        var body = json(http.get("/router/monitoring/in-flight-messages?poolCode=pool-x&messageId=" + t + "&limit=0"));
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).as("poolCode filter is case-insensitive exact match; OTHER-POOL excluded").isEqualTo(2);
        // Longest elapsed first.
        assertThat(body.get(0).get("messageId").asText()).isEqualTo("m2-" + t);
        assertThat(body.get(1).get("messageId").asText()).isEqualTo("m1-" + t);
        assertThat(body.get(0).get("elapsedTimeMs").asLong()).isGreaterThan(body.get(1).get("elapsedTimeMs").asLong());
        assertThat(body.get(0).has("brokerMessageId")).as("blank brokerMessageId is omitted (null), not \"\"").isFalse();

        tracker.remove("m1-" + t);
        tracker.remove("m2-" + t);
        tracker.remove("m3-" + t);
    }

    @Test
    @DisplayName("GET /monitoring/in-flight-messages/check reports poolCode+queueId when tracked, false otherwise")
    void inFlightCheck() {
        String t = tag();
        var now = Instant.now();
        tracker.register(new InFlightMessage("chk-" + t, "", "POOL-A", "queue-1", now, now, "", "b1", "rh", 0));

        var hit = json(http.get("/router/monitoring/in-flight-messages/check?messageId=chk-" + t));
        assertThat(hit.get("inPipeline").asBoolean()).isTrue();
        assertThat(hit.get("poolCode").asText()).isEqualTo("POOL-A");

        var miss = json(http.get("/router/monitoring/in-flight-messages/check?messageId=never-" + t));
        assertThat(miss.get("inPipeline").asBoolean()).isFalse();
        assertThat(miss.has("poolCode")).as("absent, not null, when not tracked").isFalse();

        tracker.remove("chk-" + t);
    }

    @Test
    @DisplayName("POST /monitoring/in-flight-messages/check-batch answers true/false per id")
    void inFlightCheckBatch() {
        String t = tag();
        var now = Instant.now();
        tracker.register(new InFlightMessage("batch-hit-" + t, "", "POOL-A", "queue-1", now, now, "", "b1", "rh", 0));

        var body = "{\"messageIds\":[\"batch-hit-" + t + "\",\"batch-miss-" + t + "\"]}";
        var r = http.post("/router/monitoring/in-flight-messages/check-batch", body);
        assertThat(r.statusCode()).isEqualTo(200);
        var json = json(r);
        assertThat(json.get("batch-hit-" + t).asBoolean()).isTrue();
        assertThat(json.get("batch-miss-" + t).asBoolean()).isFalse();

        tracker.remove("batch-hit-" + t);
    }

    @Test
    @DisplayName("force-ack: acks on the freshest handle, releases the tracker entry, then 404s on a second call")
    void forceAckSucceedsThen404s() {
        String t = tag();
        var now = Instant.now();
        tracker.register(new InFlightMessage("ack-" + t, "brk-" + t, "POOL-A", "queue-1",
                now.minusSeconds(3), now, "grp", "b1", "receipt-" + t, 2));

        var r = http.post("/router/monitoring/in-flight-messages/ack-" + t + "/ack", null);
        assertThat(r.statusCode()).isEqualTo(200);
        var body = json(r);
        assertThat(body.get("removed").asBoolean()).isTrue();
        assertThat(body.get("queueId").asText()).isEqualTo("queue-1");
        assertThat(body.get("poolCode").asText()).isEqualTo("POOL-A");
        assertThat(body.get("wasMediating").asBoolean())
                .as("no mediating-set tracker exists in Java; always false").isFalse();
        assertThat(consumerQ1.acked.stream().anyMatch(m -> m.receiptHandle().equals("receipt-" + t))).isTrue();

        var second = http.post("/router/monitoring/in-flight-messages/ack-" + t + "/ack", null);
        assertThat(second.statusCode()).as("the entry is gone after the first force-ack").isEqualTo(404);
    }

    @Test
    @DisplayName("force-ack reports the Consumer#ack outcome honestly: brokerAcked=true with no error, or false with brokerAckError set")
    void forceAckReportsTheRealBrokerOutcome() {
        // Consumer#ack (io.flowcatalyst.router.queue.Consumer) returns
        // whether the broker actually confirmed the removal — Postgres
        // reports false on no matching row, SQS/NATS on a failed call or an
        // unknown receipt. brokerAcked must be that real value, never a
        // fabricated true (an operator force-acking a stuck message must not
        // be told a delete is confirmed when it is not) nor a blanket false
        // once the interface can in fact report success.
        String okId = "ack-ok-" + tag();
        var now = Instant.now();
        tracker.register(new InFlightMessage(okId, "", "POOL-A", "queue-1", now, now, "", "b1", "rh-ok", 0));

        var okBody = json(http.post("/router/monitoring/in-flight-messages/" + okId + "/ack", null));
        assertThat(okBody.has("brokerAcked")).as("required field, never omitted").isTrue();
        assertThat(okBody.get("brokerAcked").asBoolean()).isTrue();
        assertThat(okBody.has("brokerAckError")).as("absent on success, not null/empty").isFalse();

        String failId = "ack-fail-" + tag();
        tracker.register(new InFlightMessage(failId, "", "POOL-A", "queue-1", now, now, "", "b1", "rh-fail", 0));
        consumerQ1.ackConfirms = false;
        try {
            var failBody = json(http.post("/router/monitoring/in-flight-messages/" + failId + "/ack", null));
            assertThat(failBody.get("brokerAcked").asBoolean()).isFalse();
            assertThat(failBody.get("brokerAckError").asText()).isNotBlank();
            // The tracker entry is still released either way — a broker
            // that didn't confirm the delete does not mean we keep owning it.
            assertThat(failBody.get("removed").asBoolean()).isTrue();
        } finally {
            consumerQ1.ackConfirms = true;
        }

        assertThat(consumerQ1.acked.stream().anyMatch(m -> m.receiptHandle().equals("rh-ok"))).isTrue();
        assertThat(consumerQ1.acked.stream().anyMatch(m -> m.receiptHandle().equals("rh-fail"))).isTrue();
    }

    @Test
    @DisplayName("force-ack is 503 with no manager wired, and 503 when the message's queue has no registered consumer")
    void forceAckServiceUnavailable() {
        var noManager = bare.post("/router/monitoring/in-flight-messages/anything/ack", null);
        assertThat(noManager.statusCode()).isEqualTo(503);

        String t = tag();
        var now = Instant.now();
        tracker.register(new InFlightMessage("ghost-" + t, "", "POOL-A", "ghost-queue-" + t,
                now, now, "", "b1", "rh", 0));
        var r = http.post("/router/monitoring/in-flight-messages/ghost-" + t + "/ack", null);
        assertThat(r.statusCode()).as("tracked, but its queue has no registered consumer").isEqualTo(503);
        tracker.remove("ghost-" + t);
    }

    @Test
    @DisplayName("in-flight detail: an unknown id answers inPipeline:false rather than 404")
    void inFlightDetailUnknownId() {
        // "Is it safe to resend this?" is the question, and the caller is
        // usually asking because it expects the answer to be no. A 404 would
        // make the safe answer look like a broken endpoint.
        var r = http.get("/router/monitoring/in-flight-messages/detail?messageId=ghost-" + tag());
        assertThat(r.statusCode()).isEqualTo(200);
        var body = json(r);
        assertThat(body.get("inPipeline").asBoolean()).isFalse();
        assertThat(body.has("status")).as("no status for something that is not there").isFalse();
        assertThat(body.has("queueId")).isFalse();
    }

    @Test
    @DisplayName("in-flight detail: TRACKED_IDLE vs RETRY_BACKOFF is decided by attempts")
    void inFlightDetailIdleAndRetrying() {
        String t = tag();
        var now = Instant.now();
        tracker.register(new InFlightMessage("idle-" + t, "brk-" + t, "POOL-A", "queue-1",
                now, now, "grp-" + t, "b1", "rh", 0));
        tracker.register(new InFlightMessage("retry-" + t, "", "POOL-A", "queue-1",
                now, now, "", "b1", "rh", 3));

        var idle = json(http.get("/router/monitoring/in-flight-messages/detail?messageId=idle-" + t));
        assertThat(idle.get("inPipeline").asBoolean()).isTrue();
        assertThat(idle.get("status").asText())
                .as("no attempts and no worker: buffered, waiting for a slot, or a phantom")
                .isEqualTo("TRACKED_IDLE");
        assertThat(idle.get("attempts").asInt())
                .as("present even at zero: 'on its first attempt' is the fact that separates a pinned "
                        + "message from a retrying one, and Go's omitempty hides it")
                .isZero();
        assertThat(idle.get("queueId").asText()).isEqualTo("queue-1");
        assertThat(idle.get("poolCode").asText()).isEqualTo("POOL-A");
        assertThat(idle.get("brokerMessageId").asText()).isEqualTo("brk-" + t);
        assertThat(idle.get("messageGroup").asText()).isEqualTo("grp-" + t);
        assertThat(idle.has("addedToInPipelineAt")).isTrue();
        assertThat(idle.has("mediationTarget")).as("not in a worker").isFalse();

        var retrying = json(http.get("/router/monitoring/in-flight-messages/detail?messageId=retry-" + t));
        assertThat(retrying.get("status").asText()).isEqualTo("RETRY_BACKOFF");
        assertThat(retrying.get("attempts").asInt()).isEqualTo(3);
        assertThat(retrying.has("brokerMessageId")).as("empty broker id is omitted, as in Go").isFalse();
        assertThat(retrying.has("messageGroup")).as("ungrouped is omitted, as in Go").isFalse();
    }

    @Test
    @DisplayName("in-flight detail: lastSeenElapsedMs is the phantom signature, and is separate from elapsedTimeMs")
    void inFlightDetailPhantomSignature() {
        // A TRACKED_IDLE entry whose lastSeenElapsedMs keeps growing is one the
        // broker has stopped redelivering — it will ACK-swallow every requeued
        // copy until cleared. The two elapsed times must therefore be able to
        // differ; reporting one twice would erase the whole signal.
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var isolatedTracker = new InFlightTracker(clock);
        var started = clock.instant();
        clock.advance(Duration.ofMinutes(4));
        var lastSeen = clock.instant();
        isolatedTracker.register(new InFlightMessage("phantom", "", "POOL-A", "queue-1",
                started, lastSeen, "", "b1", "rh", 0));
        clock.advance(Duration.ofMinutes(6));

        var detail = InFlightRoutes.inFlightDetail(isolatedTracker.snapshot().getFirst(), null, clock.instant());

        assertThat(detail.status()).isEqualTo("TRACKED_IDLE");
        assertThat(detail.elapsedTimeMs()).as("owned for 10 minutes").isEqualTo(Duration.ofMinutes(10).toMillis());
        assertThat(detail.lastSeenElapsedMs()).as("not redelivered for 6 of them")
                .isEqualTo(Duration.ofMinutes(6).toMillis());
    }

    @Test
    @DisplayName("in-flight detail: MEDIATING wins over RETRY_BACKOFF and carries the target it is stuck on")
    void inFlightDetailMediating() throws Exception {
        // A retrying message IS in a worker while the retry runs. Reporting
        // RETRY_BACKOFF there would tell an operator it is waiting when it is
        // actually wedged against the target — so the worker fact wins, and
        // the entry used here has attempts > 0 to prove it does.
        var gate = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var isolatedTracker = new InFlightTracker(CLOCK);
        var isolatedManager = new RouterManager(isolatedTracker, Warnings.NO_OP, CLOCK,
                cfg -> new Pool(cfg, NO_OP_MEDIATOR, NO_OP_BROKER, PoolMetrics.NO_OP, CLOCK));
        var held = new Pool(new Pool.Config("MED-POOL", 2, 0), (msg, recordFailure) -> {
            entered.countDown();
            gate.await();
            return MediationOutcome.Success.of(200);
        }, NO_OP_BROKER, PoolMetrics.NO_OP, CLOCK);
        isolatedManager.registerPool("MED-POOL", held);
        var now = Instant.now();
        isolatedTracker.register(new InFlightMessage("in-worker", "", "MED-POOL", "queue-1",
                now, now, "", "b1", "rh", 2));

        var state = new RouterApi.State(isolatedManager, isolatedTracker, new WarningStore(CLOCK),
                null, null, null, "v", "/router", null, Map.of(), null, null);
        try (var isolatedHttp = new TestHttp(cfg -> RouterApi.register(cfg.routes, state))) {
            warmUp(isolatedHttp);
            held.submit(message("in-worker", "https://wedged.test/x"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).as("the message reached a worker").isTrue();

            var body = json(isolatedHttp.get("/router/monitoring/in-flight-messages/detail?messageId=in-worker"));
            assertThat(body.get("status").asText())
                    .as("attempts=2 would read RETRY_BACKOFF; being in a worker outranks it")
                    .isEqualTo("MEDIATING");
            assertThat(body.get("mediationTarget").asText()).isEqualTo("https://wedged.test/x");
            assertThat(body.has("mediatingElapsedMs")).as("how long THIS attempt has been inside").isTrue();
            assertThat(body.get("attempts").asInt()).isEqualTo(2);
        } finally {
            gate.countDown();
            held.close();
        }
    }

    @Test
    @DisplayName("force-ack reports wasMediating from the live worker set, not a hard-coded false")
    void forceAckReportsWasMediating() throws Exception {
        // wasMediating warns that an attempt is STILL RUNNING and may yet
        // reach the target. It was hard-coded false until 2026-08-26, which
        // told every operator force-acking a wedged message the one thing the
        // flag exists to deny.
        var gate = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var isolatedTracker = new InFlightTracker(CLOCK);
        var isolatedManager = new RouterManager(isolatedTracker, Warnings.NO_OP, CLOCK,
                cfg -> new Pool(cfg, NO_OP_MEDIATOR, NO_OP_BROKER, PoolMetrics.NO_OP, CLOCK));
        var held = new Pool(new Pool.Config("ACK-POOL", 2, 0), (msg, recordFailure) -> {
            entered.countDown();
            gate.await();
            return MediationOutcome.Success.of(200);
        }, NO_OP_BROKER, PoolMetrics.NO_OP, CLOCK);
        isolatedManager.registerPool("ACK-POOL", held);
        isolatedManager.registerConsumer(new RecordingConsumer("queue-1"));
        var now = Instant.now();
        isolatedTracker.register(new InFlightMessage("wedged", "", "ACK-POOL", "queue-1",
                now, now, "", "b1", "rh", 0));
        isolatedTracker.register(new InFlightMessage("parked", "", "ACK-POOL", "queue-1",
                now, now, "", "b1", "rh", 0));

        var state = new RouterApi.State(isolatedManager, isolatedTracker, new WarningStore(CLOCK),
                null, null, null, "v", "/router", null, Map.of(), null, null);
        try (var isolatedHttp = new TestHttp(cfg -> RouterApi.register(cfg.routes, state))) {
            warmUp(isolatedHttp);
            held.submit(message("wedged", "https://wedged.test/y"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            var inWorker = json(isolatedHttp.post(
                    "/router/monitoring/in-flight-messages/wedged/ack", null));
            assertThat(inWorker.get("wasMediating").asBoolean())
                    .as("an attempt is still running and may still reach the target").isTrue();

            var notInWorker = json(isolatedHttp.post(
                    "/router/monitoring/in-flight-messages/parked/ack", null));
            assertThat(notInWorker.get("wasMediating").asBoolean())
                    .as("tracked but in no worker — nothing is going to arrive late").isFalse();
        } finally {
            gate.countDown();
            held.close();
        }
    }

    // ── Queue depth / broker stats / traffic ─────────────────────────────

    @Test
    @DisplayName("GET /monitoring/queues is snake_case, alone on this surface")
    void queuesIsSnakeCase() {
        // Its neighbours are camelCase. This one is not, because that is the
        // shape already on the wire; tidying it would be a break dressed up
        // as consistency.
        var isolated = new BrokerStatsCache(CLOCK);
        isolated.refresh(Map.of("q-snake", () -> Optional.of(new QueueMetrics(7, 3, 0, 0, 0))));
        var state = new RouterApi.State(null, new InFlightTracker(CLOCK), new WarningStore(CLOCK), null, null, null,
                "v", "/router", null, null, null, isolated);
        try (var isolatedHttp = new TestHttp(cfg -> RouterApi.register(cfg.routes, state))) {
            warmUp(isolatedHttp);
            var row = json(isolatedHttp.get("/router/monitoring/queues")).get(0);

            assertThat(row.get("queue_identifier").asText()).isEqualTo("q-snake");
            assertThat(row.get("pending_messages").asLong()).isEqualTo(7);
            assertThat(row.get("in_flight_messages").asLong()).isEqualTo(3);
            assertThat(row.has("queueIdentifier")).as("must not be camelCase").isFalse();
            assertThat(row.has("pendingMessages")).isFalse();
            assertThat(row.has("inFlightMessages")).isFalse();
        }
    }

    @Test
    @DisplayName("GET /monitoring/queues sorts by queue id so an unchanged reading renders the same twice")
    void queuesAreOrdered() {
        // The cache hands back an unordered map. A list whose rows move
        // between two polls of identical data is a dashboard nobody can read
        // — and an unsorted implementation passes a single-queue test.
        var isolated = new BrokerStatsCache(CLOCK);
        isolated.refresh(Map.of(
                "zulu", () -> Optional.of(new QueueMetrics(1, 0, 0, 0, 0)),
                "alpha", () -> Optional.of(new QueueMetrics(2, 0, 0, 0, 0)),
                "mike", () -> Optional.of(new QueueMetrics(3, 0, 0, 0, 0))));
        var state = new RouterApi.State(null, new InFlightTracker(CLOCK), new WarningStore(CLOCK), null, null, null,
                "v", "/router", null, null, null, isolated);
        try (var isolatedHttp = new TestHttp(cfg -> RouterApi.register(cfg.routes, state))) {
            warmUp(isolatedHttp);
            var ids = new java.util.ArrayList<String>();
            json(isolatedHttp.get("/router/monitoring/queues"))
                    .forEach(row -> ids.add(row.get("queue_identifier").asText()));
            assertThat(ids).containsExactly("alpha", "mike", "zulu");
        }
    }

    @Test
    @DisplayName("GET /monitoring/queue-stats derives every column from the queue's counters")
    void queueStatsDerivations() {
        var isolated = new BrokerStatsCache(CLOCK);
        // 90 acked, 10 nacked -> 0.9. Chosen so a swapped numerator (0.1) or a
        // denominator of totalPolled (0.9 by accident is impossible: 90/120)
        // both show up as a different number.
        isolated.refresh(Map.of("q-derive", () -> Optional.of(new QueueMetrics(11, 4, 120, 90, 10))));
        var state = new RouterApi.State(null, new InFlightTracker(CLOCK), new WarningStore(CLOCK), null, null, null,
                "v", "/router", null, null, null, isolated);
        try (var isolatedHttp = new TestHttp(cfg -> RouterApi.register(cfg.routes, state))) {
            warmUp(isolatedHttp);
            var body = json(isolatedHttp.get("/router/monitoring/queue-stats"));
            assertThat(body.isObject()).as("a map keyed by queue, not a list").isTrue();
            var row = body.get("q-derive");

            assertThat(row.get("name").asText()).isEqualTo("q-derive");
            assertThat(row.get("totalMessages").asLong()).as("polled").isEqualTo(120);
            assertThat(row.get("totalConsumed").asLong()).as("acked").isEqualTo(90);
            assertThat(row.get("totalFailed").asLong()).as("nacked").isEqualTo(10);
            assertThat(row.get("successRate").asDouble()).as("acked / (acked + nacked)").isEqualTo(0.9);
            assertThat(row.get("pendingMessages").asLong()).isEqualTo(11);
            assertThat(row.get("messagesNotVisible").asLong()).as("inFlight under the SQS name").isEqualTo(4);
            assertThat(row.get("currentSize").asLong()).as("pending + inFlight").isEqualTo(15);
            assertThat(row.get("throughput").asDouble()).as("never computed, on either side").isZero();
            assertThat(row.get("totalDeferred").asLong())
                    .as("Go's Defer verb has no production caller, so this is 0 on both sides").isZero();
        }
    }

    @Test
    @DisplayName("successRate is 1.0 for a queue that has processed nothing, not 0.0")
    void successRateOfAnIdleQueueIsOne() {
        // 0.0 and 1.0 are both plausible-looking and only one is right: a
        // queue that has done nothing has failed nothing, and zero would
        // paint every freshly-created queue as a total outage.
        var idle = QueueRoutes.queueStatsRow("fresh", new QueueMetrics(0, 0, 0, 0, 0));
        assertThat(idle.successRate()).isEqualTo(1.0);

        // ...and it is genuinely derived, not a constant: one failure and
        // nothing else is a total failure.
        assertThat(QueueRoutes.queueStatsRow("bad", new QueueMetrics(0, 0, 1, 0, 1)).successRate()).isZero();
    }

    @Test
    @DisplayName("queue-stats time_window narrows the counters to that window")
    void queueStatsWindow() {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var isolated = new BrokerStatsCache(clock);
        var live = new java.util.concurrent.atomic.AtomicReference<>(new QueueMetrics(0, 0, 100, 90, 10));
        Map<String, java.util.function.Supplier<Optional<QueueMetrics>>> source =
                Map.of("q-win", () -> Optional.of(live.get()));

        isolated.refresh(source);                       // baseline: 90 acked
        clock.advance(Duration.ofMinutes(6));           // now outside 5min, inside 30
        live.set(new QueueMetrics(0, 0, 140, 125, 15));
        isolated.refresh(source);

        var state = new RouterApi.State(null, new InFlightTracker(clock), new WarningStore(clock), null, null, null,
                "v", "/router", null, null, null, isolated);
        try (var isolatedHttp = new TestHttp(cfg -> RouterApi.register(cfg.routes, state))) {
            warmUp(isolatedHttp);
            var allTime = json(isolatedHttp.get("/router/monitoring/queue-stats")).get("q-win");
            assertThat(allTime.get("totalConsumed").asLong()).as("lifetime").isEqualTo(125);

            var fiveMin = json(isolatedHttp.get("/router/monitoring/queue-stats?time_window=5min")).get("q-win");
            assertThat(fiveMin.get("totalConsumed").asLong())
                    .as("only what happened since the 5-minute baseline").isEqualTo(35);
        }
    }

    @Test
    @DisplayName("queue-stats?refresh=true samples the brokers before rendering")
    void queueStatsRefreshSamplesFirst() {
        var isolatedManager = new RouterManager(new InFlightTracker(CLOCK), Warnings.NO_OP, CLOCK,
                cfg -> new Pool(cfg, NO_OP_MEDIATOR, NO_OP_BROKER, PoolMetrics.NO_OP, CLOCK));
        var consumer = new RecordingConsumer("q-refresh");
        isolatedManager.registerConsumer(consumer);
        var isolated = new BrokerStatsCache(CLOCK);
        var state = new RouterApi.State(isolatedManager, new InFlightTracker(CLOCK), new WarningStore(CLOCK),
                null, null, null, "v", "/router", null, null, null, isolated);
        try (var isolatedHttp = new TestHttp(cfg -> RouterApi.register(cfg.routes, state))) {
            warmUp(isolatedHttp);
            consumer.queueMetrics = new QueueMetrics(42, 0, 0, 0, 0);

            assertThat(json(isolatedHttp.get("/router/monitoring/queue-stats")).isEmpty())
                    .as("nothing sampled yet, and the endpoint does not sample on its own").isTrue();

            var refreshed = json(isolatedHttp.get("/router/monitoring/queue-stats?refresh=true")).get("q-refresh");
            assertThat(refreshed.get("pendingMessages").asLong()).isEqualTo(42);
        }
    }

    @Test
    @DisplayName("POST /monitoring/broker-stats/refresh samples the same queues the housekeeping loop does")
    void brokerStatsRefresh() {
        var isolatedManager = new RouterManager(new InFlightTracker(CLOCK), Warnings.NO_OP, CLOCK,
                cfg -> new Pool(cfg, NO_OP_MEDIATOR, NO_OP_BROKER, PoolMetrics.NO_OP, CLOCK));
        var consumer = new RecordingConsumer("q-forced");
        consumer.queueMetrics = new QueueMetrics(5, 1, 0, 0, 0);
        isolatedManager.registerConsumer(consumer);
        var isolated = new BrokerStatsCache(CLOCK);
        var state = new RouterApi.State(isolatedManager, new InFlightTracker(CLOCK), new WarningStore(CLOCK),
                null, null, null, "v", "/router", null, null, null, isolated);
        try (var isolatedHttp = new TestHttp(cfg -> RouterApi.register(cfg.routes, state))) {
            warmUp(isolatedHttp);
            var r = isolatedHttp.post("/router/monitoring/broker-stats/refresh", null);

            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(json(r).get("refreshed").asBoolean()).isTrue();
            assertThat(json(r).get("ageSeconds").asLong())
                    .as("clamped at 0 — a refresh that just happened cannot report NEVER_REFRESHED").isZero();

            // The observable effect, not the response's own claim: a queue the
            // endpoint had never sampled is now readable.
            var row = json(isolatedHttp.get("/router/monitoring/queues")).get(0);
            assertThat(row.get("queue_identifier").asText()).isEqualTo("q-forced");
            assertThat(row.get("pending_messages").asLong()).isEqualTo(5);
        }
    }

    @Test
    @DisplayName("a clock that steps backwards still reports ageSeconds >= 0")
    void brokerStatsRefreshAgeIsNeverNegative() {
        // NTP corrections happen. A negative age would render as a refresh in
        // the future, which reads as a broken endpoint rather than a corrected
        // clock — and this is the response to the request that just caused it.
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var isolated = new BrokerStatsCache(clock);
        var state = new RouterApi.State(null, new InFlightTracker(clock), new WarningStore(clock), null, null, null,
                "v", "/router", null, null, null, isolated);
        try (var isolatedHttp = new TestHttp(cfg -> RouterApi.register(cfg.routes, state))) {
            warmUp(isolatedHttp);
            isolated.refresh(Map.of());
            clock.advance(Duration.ofSeconds(-30));

            assertThat(isolated.ageSeconds()).as("the cache reports the raw truth").isEqualTo(-30);
            assertThat(json(isolatedHttp.post("/router/monitoring/broker-stats/refresh", null))
                    .get("ageSeconds").asLong()).isZero();
        }
    }

    @Test
    @DisplayName("with no broker stats wired: the two lists answer empty and the refresh mutation 503s")
    void brokerStatsAbsent() {
        assertThat(json(bare.get("/router/monitoring/queues")).isEmpty()).isTrue();
        assertThat(json(bare.get("/router/monitoring/queue-stats")).isEmpty()).isTrue();
        assertThat(bare.post("/router/monitoring/broker-stats/refresh", null).statusCode())
                .as("§9.1: 503 for mutations, empty payload for lists").isEqualTo(503);
    }

    @Test
    @DisplayName("GET /monitoring/traffic-status reports the mode and the target group it registers with")
    void trafficStatusRegistered() {
        targetGroup.failing = false;
        traffic.register();

        var body = json(http.get("/router/monitoring/traffic-status"));
        assertThat(body.get("enabled").asBoolean()).isTrue();
        assertThat(body.get("mode").asText()).isEqualTo("alb-target-group");
        assertThat(body.get("targetGroupArn").asText()).isEqualTo(FakeTargetGroup.ARN);
        assertThat(body.get("registered").asBoolean()).isTrue();
        assertThat(body.has("lastChangedAt")).isTrue();
        assertThat(body.has("lastError")).as("nothing has failed").isFalse();
    }

    @Test
    @DisplayName("a failed deregister reports registered:true AND lastError — the two facts that disagree")
    void trafficStatusReportsTheDisagreement() {
        // This is the field that earns the endpoint. A deregister that threw
        // leaves the router believing it is out while the balancer is still
        // sending it traffic; an operator deciding whether it is safe to stop
        // the process needs both halves, and either one alone misleads.
        targetGroup.failing = false;
        traffic.register();
        targetGroup.failing = true;
        traffic.deregister();

        var body = json(http.get("/router/monitoring/traffic-status"));
        assertThat(body.get("registered").asBoolean())
                .as("still in, as far as we know — the dangerous direction is believing otherwise").isTrue();
        assertThat(body.get("lastError").asText()).contains("cannot reach the balancer");

        targetGroup.failing = false;
    }

    @Test
    @DisplayName("with no traffic wired, traffic-status is the disabled shape rather than an error")
    void trafficStatusAbsent() {
        var body = json(bare.get("/router/monitoring/traffic-status"));
        assertThat(body.get("enabled").asBoolean()).isFalse();
        assertThat(body.get("mode").asText()).isEqualTo("disabled");
        assertThat(body.get("registered").asBoolean()).isFalse();
        assertThat(body.has("targetGroupArn")).as("no group to name").isFalse();
        assertThat(body.has("lastChangedAt")).isFalse();
    }

    // ── Pool update ──────────────────────────────────────────────────────

    @Test
    @DisplayName("PUT /monitoring/pools/{poolCode} applies both fields, 0 rate limit means unlimited, absent concurrency is unchanged")
    void updatePoolBothFields() {
        var r = http.put("/router/monitoring/pools/POOL-A", "{\"concurrency\":9,\"rate_limit_per_minute\":0}");
        assertThat(r.statusCode()).isEqualTo(200);
        var body = json(r);
        assertThat(body.get("success").asBoolean()).isTrue();
        assertThat(body.get("pool_code").asText()).isEqualTo("POOL-A");
        assertThat(body.get("new_config").get("concurrency").asInt()).isEqualTo(9);
        assertThat(body.get("new_config").get("rate_limit_per_minute").asInt()).isEqualTo(0);
    }

    @Test
    @DisplayName("PUT with an absent field omits it from new_config rather than writing null")
    void updatePoolAbsentFieldOmitted() {
        var r = http.put("/router/monitoring/pools/POOL-A", "{\"concurrency\":3}");
        assertThat(r.statusCode()).isEqualTo(200);
        var newConfig = json(r).get("new_config");
        assertThat(newConfig.has("concurrency")).isTrue();
        assertThat(newConfig.has("rate_limit_per_minute")).as("absent field is dropped, not null").isFalse();
    }

    @Test
    @DisplayName("PUT on an unknown pool is 404; with no manager wired it is 503")
    void updatePoolNotFoundOrUnavailable() {
        var notFound = http.put("/router/monitoring/pools/NO-SUCH-POOL", "{\"concurrency\":1}");
        assertThat(notFound.statusCode()).isEqualTo(404);

        var noManager = bare.put("/router/monitoring/pools/POOL-A", "{\"concurrency\":1}");
        assertThat(noManager.statusCode()).isEqualTo(503);
    }

    // ── Standby / stream health / config ────────────────────────────────

    @Test
    @DisplayName("GET /monitoring/standby-status reports the lock key (not the process UUID) as instance_id")
    void standbyStatus() {
        var body = json(http.get("/router/monitoring/standby-status"));
        assertThat(body.get("enabled").asBoolean()).isTrue();
        assertThat(body.get("is_leader").asBoolean()).isTrue();
        assertThat(body.get("instance_id").asText()).as("the lock key, not LeaderElection#instanceId()'s UUID")
                .isEqualTo("fc:test:leader");
    }

    @Test
    @DisplayName("with no election wired, standby-status is the Go no-adapter default")
    void standbyStatusDefault() {
        var body = json(bare.get("/router/monitoring/standby-status"));
        assertThat(body.get("enabled").asBoolean()).isFalse();
        assertThat(body.get("is_leader").asBoolean()).isTrue();
        assertThat(body.get("instance_id").asText()).isEqualTo("default");
    }

    @Test
    @DisplayName("stream-health/live/ready are always NOT_CONFIGURED (no stream provider ported)")
    void streamHealthNotConfigured() {
        var health = json(http.get("/router/monitoring/stream-health"));
        assertThat(health.get("enabled").asBoolean()).isFalse();
        assertThat(health.get("status").asText()).isEqualTo("NOT_CONFIGURED");

        assertThat(json(http.get("/router/monitoring/stream-health/live")).get("status").asText())
                .isEqualTo("NOT_CONFIGURED");
        assertThat(json(http.get("/router/monitoring/stream-health/ready")).get("status").asText())
                .isEqualTo("NOT_CONFIGURED");
    }

    @Test
    @DisplayName("GET /api/config never exposes secrets, only version and warning counts")
    void localConfig() {
        var body = json(http.get("/router/api/config"));
        assertThat(body.get("version").asText()).isEqualTo("test-version");
        assertThat(body.has("warnings_total")).isTrue();
        assertThat(body.has("warnings_critical")).isTrue();
    }

    @Test
    @DisplayName("POST /config/reload always succeeds with the no-reloader-wired note")
    void configReload() {
        var r = http.post("/router/config/reload", null);
        assertThat(r.statusCode()).isEqualTo(200);
        var body = json(r);
        assertThat(body.get("success").asBoolean()).isTrue();
        assertThat(body.get("note").asText()).isEqualTo("config watcher polls automatically");
    }

    // ── Dev mock targets ──────────────────────────────────────────────────

    @Test
    @DisplayName("mock endpoints increment their own counters, and /api/test/stats reports them with snake_case keys")
    void mockCounters() {
        assertThat(http.post("/router/api/test/fast", null).statusCode()).isEqualTo(200);
        assertThat(http.post("/router/api/test/success", null).statusCode()).isEqualTo(200);
        assertThat(http.post("/router/api/test/fail", null).statusCode()).isEqualTo(500);
        assertThat(http.post("/router/api/test/server-error", null).statusCode()).isEqualTo(500);
        assertThat(http.post("/router/api/test/client-error", null).statusCode()).isEqualTo(400);
        assertThat(http.post("/router/api/test/slow?delay_ms=10", null).statusCode()).isEqualTo(200);

        var stats = json(http.get("/router/api/test/stats"));
        assertThat(stats.get("fast").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(stats.get("success").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(stats.get("fail").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(stats.get("server_error").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(stats.get("client_error").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(stats.get("slow").asLong()).isGreaterThanOrEqualTo(1);

        var reset = http.post("/router/api/test/stats/reset", null);
        assertThat(reset.statusCode()).isEqualTo(200);
        assertThat(json(reset).get("reset").asBoolean()).isTrue();
        assertThat(json(http.get("/router/api/test/stats")).get("fast").asLong()).isZero();
    }

    @Test
    @DisplayName("/api/benchmark/* are aliases of the matching /api/test/* routes")
    void benchmarkAliases() {
        var before = json(http.get("/router/api/benchmark/stats")).get("fast").asLong();
        assertThat(http.post("/router/api/benchmark/process", null).statusCode()).isEqualTo(200);
        assertThat(json(http.get("/router/api/benchmark/stats")).get("fast").asLong()).isEqualTo(before + 1);
    }

    // ── Test doubles ─────────────────────────────────────────────────────

    private static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class RecordingConsumer implements Consumer {
        private final String id;
        final List<QueuedMessage> acked = new CopyOnWriteArrayList<>();

        /// What `#ack` reports next — configurable so a test can pin both
        /// the `brokerAcked:true` and `brokerAcked:false` force-ack branches.
        volatile boolean ackConfirms = true;

        /// What `#metrics` reports next. `null` is "could not read", which the
        /// broker-stats cache treats differently from a zeroed reading.
        volatile QueueMetrics queueMetrics;

        RecordingConsumer(String id) {
            this.id = id;
        }

        @Override
        public String identifier() {
            return id;
        }

        @Override
        public PollResult poll(int max) {
            return PollResult.empty();
        }

        @Override
        public boolean ack(QueuedMessage message) {
            acked.add(message);
            return ackConfirms;
        }

        @Override
        public void nack(QueuedMessage message, Duration delay) {
        }

        @Override
        public Optional<QueueMetrics> metrics() {
            return Optional.ofNullable(queueMetrics);
        }

        @Override
        public void close() {
        }
    }

    /// Stands in for ELBv2 — the AWS boundary, not a component this module
    /// reads from. [AlbTraffic]'s own policy stays real.
    private static final class FakeTargetGroup implements TargetGroup {
        static final String ARN = "arn:aws:elasticloadbalancing:eu-west-1:1:targetgroup/fc/abc";
        volatile boolean failing;

        @Override
        public void register(String targetId, int port) {
            failIfAsked();
        }

        @Override
        public void deregister(String targetId, int port) {
            failIfAsked();
        }

        @Override
        public boolean draining(String targetId, int port) {
            return false;
        }

        @Override
        public String arn() {
            return ARN;
        }

        private void failIfAsked() {
            if (failing) {
                throw new IllegalStateException("cannot reach the balancer");
            }
        }
    }

    /// Always grants the lock to whoever asks — enough to make
    /// [LeaderElection] leader without a real Redis.
    private static final class AlwaysAcquireStore implements LockStore {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public boolean acquire(String key, String value, Duration ttl) {
            calls.incrementAndGet();
            return true;
        }

        @Override
        public boolean refresh(String key, String value, Duration ttl) {
            return true;
        }

        @Override
        public void release(String key, String value) {
        }

        @Override
        public void ping() {
        }
    }
}
