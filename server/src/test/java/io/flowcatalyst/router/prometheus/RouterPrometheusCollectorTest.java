package io.flowcatalyst.router.prometheus;

import io.flowcatalyst.router.inflight.InFlightMessage;
import io.flowcatalyst.router.inflight.InFlightTracker;
import io.flowcatalyst.router.observability.PoolMetricsCollector;
import io.flowcatalyst.router.policy.BreakerRegistry;
import io.flowcatalyst.router.policy.CircuitBreaker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// `docs/spec/router.md` §9.2 (the Prometheus contract) and constant 44
/// (the histogram buckets). Every assertion here targets the **rendered
/// text**, not object state, because that is what a dashboard/alert
/// actually matches on.
class RouterPrometheusCollectorTest {

    private final TestClock clock = new TestClock(Instant.parse("2026-01-01T00:00:00Z"));

    @Test
    @DisplayName("the mediation histogram renders exactly the constant-44 boundaries as a cumulative + Inf histogram")
    void histogramBucketsMatchConstant44AndAreCumulative() {
        var metrics = new PoolMetricsCollector(clock);
        // Three delivery attempts at 3ms, 600ms and 20s — chosen so each
        // finite boundary except 1s/+Inf keeps exactly one qualifying
        // observation, and 20s exceeds every finite bound (widest is 10s).
        metrics.recordSuccess(Duration.ofMillis(3));
        metrics.recordFailure(Duration.ofMillis(600));
        metrics.recordTransient(Duration.ofSeconds(20));

        var pool = new RouterPrometheusCollector.PoolSnapshot("DEFAULT-POOL", 0, 0, 0, metrics);
        var collector = collectorWith(List.of(pool), List.of());

        var text = collector.renderText();

        // Pins: metric name, HELP, TYPE, label name `pool`, and the
        // constant-44 boundary set .005 .01 .025 .05 .1 .25 .5 1 2.5 5 10
        // rendered in ascending, cumulative "le" order with an explicit
        // +Inf terminal bucket — a renamed label or a moved boundary
        // breaks this exact block.
        assertThat(text).contains("""
                # HELP fc_mediation_duration_seconds Mediation latency in seconds.
                # TYPE fc_mediation_duration_seconds histogram
                fc_mediation_duration_seconds_bucket{pool="DEFAULT-POOL",le="0.005"} 1
                fc_mediation_duration_seconds_bucket{pool="DEFAULT-POOL",le="0.01"} 1
                fc_mediation_duration_seconds_bucket{pool="DEFAULT-POOL",le="0.025"} 1
                fc_mediation_duration_seconds_bucket{pool="DEFAULT-POOL",le="0.05"} 1
                fc_mediation_duration_seconds_bucket{pool="DEFAULT-POOL",le="0.1"} 1
                fc_mediation_duration_seconds_bucket{pool="DEFAULT-POOL",le="0.25"} 1
                fc_mediation_duration_seconds_bucket{pool="DEFAULT-POOL",le="0.5"} 1
                fc_mediation_duration_seconds_bucket{pool="DEFAULT-POOL",le="1.0"} 2
                fc_mediation_duration_seconds_bucket{pool="DEFAULT-POOL",le="2.5"} 2
                fc_mediation_duration_seconds_bucket{pool="DEFAULT-POOL",le="5.0"} 2
                fc_mediation_duration_seconds_bucket{pool="DEFAULT-POOL",le="10.0"} 2
                fc_mediation_duration_seconds_bucket{pool="DEFAULT-POOL",le="+Inf"} 3
                fc_mediation_duration_seconds_count{pool="DEFAULT-POOL"} 3
                fc_mediation_duration_seconds_sum{pool="DEFAULT-POOL"} 20.603
                """.strip());
    }

    @Test
    @DisplayName("a pool with no deliveries still renders all-zero buckets, sum 0 and count 0 (Go always emits the fixed bound array)")
    void freshPoolStillEmitsTheFullZeroHistogram() {
        var metrics = new PoolMetricsCollector(clock);
        var pool = new RouterPrometheusCollector.PoolSnapshot("EMPTY-POOL", 0, 0, 0, metrics);
        var collector = collectorWith(List.of(pool), List.of());

        var text = collector.renderText();

        assertThat(text).contains("fc_mediation_duration_seconds_bucket{pool=\"EMPTY-POOL\",le=\"0.005\"} 0");
        assertThat(text).contains("fc_mediation_duration_seconds_bucket{pool=\"EMPTY-POOL\",le=\"+Inf\"} 0");
        assertThat(text).contains("fc_mediation_duration_seconds_count{pool=\"EMPTY-POOL\"} 0");
        assertThat(text).contains("fc_mediation_duration_seconds_sum{pool=\"EMPTY-POOL\"} 0.0");
    }

    @Test
    @DisplayName("pool gauges and per-success counters carry the pool label and exact contract names")
    void poolGaugesAndProcessedCounters() {
        var metrics = new PoolMetricsCollector(clock);
        metrics.recordSuccess(Duration.ofMillis(1));
        metrics.recordFailure(Duration.ofMillis(1));
        metrics.recordFailure(Duration.ofMillis(1));
        metrics.recordRateLimited();
        metrics.recordRateLimited();

        var pool = new RouterPrometheusCollector.PoolSnapshot("DEFAULT-POOL", 5, 2, 1, metrics);
        var collector = collectorWith(List.of(pool), List.of());

        var text = collector.renderText();

        assertThat(text).contains("""
                # HELP fc_pool_queue_size Messages buffered in group queues awaiting dispatch.
                # TYPE fc_pool_queue_size gauge
                fc_pool_queue_size{pool="DEFAULT-POOL"} 5.0
                """.strip());
        assertThat(text).contains("""
                # HELP fc_pool_active_workers Currently active worker threads per pool.
                # TYPE fc_pool_active_workers gauge
                fc_pool_active_workers{pool="DEFAULT-POOL"} 2.0
                """.strip());
        assertThat(text).contains("""
                # HELP fc_pool_message_groups Distinct message groups currently holding buffered work.
                # TYPE fc_pool_message_groups gauge
                fc_pool_message_groups{pool="DEFAULT-POOL"} 1.0
                """.strip());
        // success="true"/"false" — the exact label values the contract names,
        // not booleans.
        assertThat(text).contains("""
                # HELP fc_messages_processed_total Cumulative messages processed, by success.
                # TYPE fc_messages_processed_total counter
                fc_messages_processed_total{pool="DEFAULT-POOL",success="false"} 2.0
                fc_messages_processed_total{pool="DEFAULT-POOL",success="true"} 1.0
                """.strip());
        assertThat(text).contains("""
                # HELP fc_rate_limit_exceeded_total Cumulative rate-limit events.
                # TYPE fc_rate_limit_exceeded_total counter
                fc_rate_limit_exceeded_total{pool="DEFAULT-POOL"} 2.0
                """.strip());
    }

    @Test
    @DisplayName("R-53: fc_messages_suppressed_total carries the pool's group-flush suppressed count, not the success/failure totals")
    void suppressedCounterRecordsGroupFlushSuppressions() {
        var metrics = new PoolMetricsCollector(clock);
        metrics.recordSuccess(Duration.ofMillis(1));
        metrics.recordSuppressed();
        metrics.recordSuppressed();
        metrics.recordSuppressed();

        var pool = new RouterPrometheusCollector.PoolSnapshot("DEFAULT-POOL", 0, 0, 0, metrics);
        var text = collectorWith(List.of(pool), List.of()).renderText();

        assertThat(text).contains("""
                # HELP fc_messages_suppressed_total Cumulative messages ACKed because their group was suppressed by a target flushGroup.
                # TYPE fc_messages_suppressed_total counter
                fc_messages_suppressed_total{pool="DEFAULT-POOL"} 3.0
                """.strip());
    }

    @Test
    @DisplayName("recordTransient counts toward fc_mediation_duration_seconds but not toward fc_messages_processed_total{success=\"false\"}")
    void transientIsNotYetAFailureVerdict() {
        var metrics = new PoolMetricsCollector(clock);
        metrics.recordTransient(Duration.ofMillis(1));

        var pool = new RouterPrometheusCollector.PoolSnapshot("DEFAULT-POOL", 0, 0, 0, metrics);
        var text = collectorWith(List.of(pool), List.of()).renderText();

        assertThat(text).contains("fc_messages_processed_total{pool=\"DEFAULT-POOL\",success=\"false\"} 0.0");
        assertThat(text).contains("fc_messages_processed_total{pool=\"DEFAULT-POOL\",success=\"true\"} 0.0");
        assertThat(text).contains("fc_mediation_duration_seconds_count{pool=\"DEFAULT-POOL\"} 1");
    }

    @Test
    @DisplayName("queue series trim an SQS-style URL to the trailing queue name for both the queue and consumer labels")
    void queueSeriesNormaliseTheQueueId() {
        var queue = new RouterPrometheusCollector.QueueSnapshot(
                "https://sqs.us-east-1.amazonaws.com/123456789012/my-queue",
                7, 4, 50, 40, 3, 2);
        var text = collectorWith(List.of(), List.of(queue)).renderText();

        assertThat(text).contains("""
                # HELP fc_queue_pending_messages Approximate messages waiting on the broker.
                # TYPE fc_queue_pending_messages gauge
                fc_queue_pending_messages{queue="my-queue"} 7.0
                """.strip());
        assertThat(text).contains("""
                # HELP fc_queue_in_flight_messages Approximate messages currently being processed by consumers.
                # TYPE fc_queue_in_flight_messages gauge
                fc_queue_in_flight_messages{queue="my-queue"} 4.0
                """.strip());
        assertThat(text).contains("""
                # HELP fc_consumer_messages_received_total Cumulative messages received from the broker by this consumer.
                # TYPE fc_consumer_messages_received_total counter
                fc_consumer_messages_received_total{consumer="my-queue"} 50.0
                """.strip());
        assertThat(text).contains("""
                # HELP fc_queue_messages_total Cumulative consumer ack/nack/defer outcomes.
                # TYPE fc_queue_messages_total counter
                fc_queue_messages_total{outcome="acked",queue="my-queue"} 40.0
                fc_queue_messages_total{outcome="deferred",queue="my-queue"} 2.0
                fc_queue_messages_total{outcome="nacked",queue="my-queue"} 3.0
                """.strip());
    }

    @Test
    @DisplayName("a queue id with no slash, or a trailing slash, is left unchanged rather than truncated")
    void queueIdEdgeCasesAreNotTruncated() {
        var noSlash = new RouterPrometheusCollector.QueueSnapshot("plain-queue-name", 1, 0, 0, 0, 0, 0);
        var trailingSlash = new RouterPrometheusCollector.QueueSnapshot("https://example.com/queues/", 2, 0, 0, 0, 0, 0);

        var text = collectorWith(List.of(), List.of(noSlash, trailingSlash)).renderText();

        assertThat(text).contains("fc_queue_pending_messages{queue=\"plain-queue-name\"} 1.0");
        assertThat(text).contains("fc_queue_pending_messages{queue=\"https://example.com/queues/\"} 2.0");
    }

    @Test
    @DisplayName("fc_circuit_breaker_open is 1 only for a fully OPEN breaker — a HALF_OPEN breaker reads 0")
    void breakerOpenGaugeIsExactlyOneForFullyOpenOnly() {
        // minCalls=1, failureRateThreshold=1.0: a single failure trips it.
        var config = new CircuitBreaker.Config(1.0, 1, 1, Duration.ofSeconds(5), 10);
        var breakers = new BreakerRegistry(config, clock);

        var openBreaker = breakers.get("https://example.com/open");
        openBreaker.recordFailure();
        assertThat(openBreaker.state()).isEqualTo(CircuitBreaker.State.OPEN);

        var halfOpenBreaker = breakers.get("https://example.com/half-open");
        halfOpenBreaker.recordFailure();
        clock.advance(Duration.ofSeconds(6)); // past resetTimeout
        halfOpenBreaker.allow(); // OPEN -> HALF_OPEN as a side effect
        assertThat(halfOpenBreaker.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        var text = collectorWith(breakers).renderText();

        assertThat(text).contains("""
                # HELP fc_circuit_breaker_open 1 when the breaker is OPEN, 0 otherwise.
                # TYPE fc_circuit_breaker_open gauge
                """.strip());
        assertThat(text).contains("fc_circuit_breaker_open{target=\"https://example.com/open\"} 1.0");
        assertThat(text).contains("fc_circuit_breaker_open{target=\"https://example.com/half-open\"} 0.0");
    }

    @Test
    @DisplayName("fc_circuit_breaker_calls_total carries cumulative success/failure counts keyed by the full target URL")
    void breakerCallsCounterCarriesCumulativeOutcomes() {
        var breakers = new BreakerRegistry(CircuitBreaker.Config.DEFAULTS, clock);
        // Query string included in the key, matching §13 Q12 (kept, unruled).
        var breaker = breakers.get("https://example.com/hook?tenant=a");
        breaker.recordSuccess();
        breaker.recordSuccess();
        breaker.recordFailure();

        var text = collectorWith(breakers).renderText();

        assertThat(text).contains("""
                # HELP fc_circuit_breaker_calls_total Cumulative breaker outcomes.
                # TYPE fc_circuit_breaker_calls_total counter
                fc_circuit_breaker_calls_total{outcome="failure",target="https://example.com/hook?tenant=a"} 1.0
                fc_circuit_breaker_calls_total{outcome="success",target="https://example.com/hook?tenant=a"} 2.0
                """.strip());
    }

    @Test
    @DisplayName("fc_in_pipeline_messages has no labels and equals the in-flight tracker's size")
    void inFlightGaugeHasNoLabels() {
        var tracker = new InFlightTracker(clock);
        tracker.register(inFlightMessage("m1"));
        tracker.register(inFlightMessage("m2"));
        tracker.register(inFlightMessage("m3"));

        var text = new RouterPrometheusCollector(
                RouterPrometheusCollector.PoolSource.NONE,
                RouterPrometheusCollector.QueueSource.NONE,
                new BreakerRegistry(CircuitBreaker.Config.DEFAULTS, clock),
                tracker).renderText();

        assertThat(text).contains("""
                # HELP fc_in_pipeline_messages Total in-flight messages across all pools.
                # TYPE fc_in_pipeline_messages gauge
                fc_in_pipeline_messages 3.0
                """.strip());
    }

    @Test
    @DisplayName("no pools, no queues and no breakers emit no series at all for those families — only fc_in_pipeline_messages remains")
    void emptySourcesEmitNoSeriesForThoseFamilies() {
        var text = new RouterPrometheusCollector(
                RouterPrometheusCollector.PoolSource.NONE,
                RouterPrometheusCollector.QueueSource.NONE,
                new BreakerRegistry(CircuitBreaker.Config.DEFAULTS, clock),
                new InFlightTracker(clock)).renderText();

        assertThat(text)
                .doesNotContain("fc_pool_queue_size")
                .doesNotContain("fc_pool_active_workers")
                .doesNotContain("fc_pool_message_groups")
                .doesNotContain("fc_messages_processed_total")
                .doesNotContain("fc_rate_limit_exceeded_total")
                .doesNotContain("fc_messages_suppressed_total")
                .doesNotContain("fc_mediation_duration_seconds")
                .doesNotContain("fc_queue_pending_messages")
                .doesNotContain("fc_queue_in_flight_messages")
                .doesNotContain("fc_consumer_messages_received_total")
                .doesNotContain("fc_queue_messages_total")
                .doesNotContain("fc_circuit_breaker_open")
                .doesNotContain("fc_circuit_breaker_calls_total")
                .contains("fc_in_pipeline_messages 0.0");
    }

    @Test
    @DisplayName("Q41: the acknowledged contract gap is reproduced, not silently fixed — those series/labels never appear")
    void q41AcknowledgedGapIsNotSilentlyFixed() {
        var metrics = new PoolMetricsCollector(clock);
        metrics.recordSuccess(Duration.ofMillis(1));
        var pool = new RouterPrometheusCollector.PoolSnapshot("DEFAULT-POOL", 1, 1, 1, metrics);
        var queue = new RouterPrometheusCollector.QueueSnapshot("q", 1, 1, 1, 1, 1, 1);
        var breakers = new BreakerRegistry(CircuitBreaker.Config.DEFAULTS, clock);
        breakers.get("https://example.com").recordSuccess();

        var text = new RouterPrometheusCollector(
                () -> List.of(pool), () -> List.of(queue), breakers, new InFlightTracker(clock)).renderText();

        assertThat(text)
                .doesNotContain("fc_messages_submitted_total")
                .doesNotContain("fc_messages_rejected_total")
                .doesNotContain("fc_consumer_polls_total")
                .doesNotContain("fc_consumer_errors_total")
                .doesNotContain("result=")
                .doesNotContain("flowcatalyst_broker_");
    }

    private RouterPrometheusCollector collectorWith(List<RouterPrometheusCollector.PoolSnapshot> pools,
                                                      List<RouterPrometheusCollector.QueueSnapshot> queues) {
        return new RouterPrometheusCollector(
                () -> pools, () -> queues,
                new BreakerRegistry(CircuitBreaker.Config.DEFAULTS, clock),
                new InFlightTracker(clock));
    }

    private RouterPrometheusCollector collectorWith(BreakerRegistry breakers) {
        return new RouterPrometheusCollector(
                RouterPrometheusCollector.PoolSource.NONE,
                RouterPrometheusCollector.QueueSource.NONE,
                breakers,
                new InFlightTracker(clock));
    }

    private InFlightMessage inFlightMessage(String id) {
        var now = clock.instant();
        return new InFlightMessage(id, "", "DEFAULT-POOL", "q", now, now, "", "batch-1", "handle-" + id, 0);
    }

    private static final class TestClock extends Clock {
        private Instant now;

        TestClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
