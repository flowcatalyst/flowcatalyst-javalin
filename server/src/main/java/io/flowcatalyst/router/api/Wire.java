package io.flowcatalyst.router.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.flowcatalyst.router.observability.PoolMetricsCollector;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/// Every shape this API puts on the wire, in one place — the counterpart to
/// Go's `internal/router/api/dto.go`.
///
/// Together they ARE the wire contract: field names, casing, and which fields
/// are omitted when absent. `/monitoring/queues` is snake_case while its
/// neighbours are camelCase, and that stays, because it is the shape already
/// being parsed — see [Wire.QueueMetricsView].
///
/// Kept apart from the handlers so a change to a rendered shape is visible as
/// a change to this file, rather than buried in the middle of the logic that
/// happens to build it.
public final class Wire {

    /// One row of `GET /monitoring/mediating`. Field names and order match Go.
    public record WireMediating(String messageId, String poolCode, String group, String queue,
                                String target, int attempts, long elapsedTimeMs) {
    }

    public record ProbeResponse(String status) {
    }

    public record SimpleHealthResponse(String status, String version,
                                       @JsonProperty("active_warnings") int activeWarnings,
                                       @JsonProperty("critical_warnings") int criticalWarnings) {
    }

    public record DashboardHealthResponse(String status, Instant timestamp, long uptimeMillis,
                                          DashboardHealthDetails details) {
    }

    public record DashboardHealthDetails(int totalQueues, int healthyQueues, int totalPools, int healthyPools,
                                         int activeWarnings, int criticalWarnings, int circuitBreakersOpen,
                                         String degradationReason) {
    }

    public record ConsumerHealthResponse(long currentTimeMs, Instant currentTime, Map<String, Object> consumers) {
    }

    /// `GET /monitoring`. Snake outer + nested `health_report`/`pool_stats`.
    public record MonitoringResponse(String status, String version,
                                     @JsonProperty("health_report") WireHealthReport healthReport,
                                     @JsonProperty("pool_stats") List<WirePoolStats> poolStats,
                                     @JsonProperty("active_warnings") int activeWarnings,
                                     @JsonProperty("critical_warnings") int criticalWarnings) {
    }

    public record WireHealthReport(String status, @JsonProperty("pools_healthy") int poolsHealthy,
                                   @JsonProperty("pools_unhealthy") int poolsUnhealthy,
                                   @JsonProperty("consumers_healthy") int consumersHealthy,
                                   @JsonProperty("consumers_unhealthy") int consumersUnhealthy,
                                   @JsonProperty("active_warnings") int activeWarnings,
                                   @JsonProperty("critical_warnings") int criticalWarnings, List<String> issues) {
    }

    /// One pool's stats for `GET /monitoring`/`GET /monitoring/pools`: snake
    /// outer fields, camelCase `metrics` (the real [PoolMetricsCollector.Snapshot]
    /// shape, including the Java-only `totalSuppressed`/`suppressedCount`
    /// counters `PoolMetricsCollector`'s own javadoc documents as a
    /// deliberate addition over the Go shape).
    public record WirePoolStats(@JsonProperty("pool_code") String poolCode, int concurrency,
                                @JsonProperty("active_workers") int activeWorkers,
                                @JsonProperty("queue_size") int queueSize,
                                @JsonProperty("queue_capacity") int queueCapacity,
                                @JsonProperty("message_group_count") int messageGroupCount,
                                @JsonProperty("rate_limit_per_minute") Integer rateLimitPerMinute,
                                @JsonProperty("is_rate_limited") boolean isRateLimited,
                                @JsonProperty("total_deferred") long totalDeferred,
                                PoolMetricsCollector.Snapshot metrics) {
    }

    /// `GET /monitoring/pool-stats` map value — camelCase throughout, and
    /// distinct from [WirePoolStats]: no `isRateLimited`, but `totalRateLimited`
    /// and `availablePermits` instead.
    public record DashboardPoolStats(String poolCode, long totalProcessed, long totalSucceeded, long totalFailed,
                                     long totalRateLimited, long totalDeferred, double successRate, int activeWorkers,
                                     int availablePermits, int maxConcurrency, int queueSize, int maxQueueCapacity,
                                     double averageProcessingTimeMs) {
    }

    public record WireWarning(String id, String category, String severity, String message, String source,
                              @JsonProperty("created_at") Instant createdAt, boolean acknowledged,
                              @JsonProperty("acknowledged_at") Instant acknowledgedAt) {
    }

    public record AcknowledgedResponse(boolean acknowledged) {
    }

    public record AcknowledgedCountResponse(long acknowledged) {
    }

    /// `DELETE /warnings` and `DELETE /warnings/old` (Go `CountResponse`,
    /// `dto.go:343-346`).
    public record ClearedResponse(long cleared) {
    }

    public record DashboardCircuitBreaker(String name, String state, long successfulCalls, long failedCalls,
                                          long rejectedCalls, double failureRate, long bufferedCalls,
                                          long bufferSize) {
    }

    public record CircuitBreakerStateResponse(String name, String state, long successes, long failures,
                                              int recentFailures) {
    }

    public record BreakerResetResponse(boolean reset, String name) {
    }

    public record BreakerResetAllResponse(int reset) {
    }

    public record InFlightMessageInfo(String messageId, String brokerMessageId, String queueId, String poolCode,
                                      long elapsedTimeMs, Instant addedToInPipelineAt, String messageGroup,
                                      int attempts) {
    }

    public record InFlightCheckResponse(String messageId, boolean inPipeline, String poolCode, String queueId) {
    }

    /// `GET /monitoring/in-flight-messages/detail`.
    ///
    /// **Deliberate deviation from Go**: Go marks `attempts` and the three
    /// millisecond fields `omitempty`, so a message on its first attempt
    /// reports no `attempts` field at all — indistinguishable from an
    /// endpoint that did not look. Here every field is present once
    /// `inPipeline` is true, because `attempts: 0` is the fact that separates
    /// a message pinned on its first delivery from one legitimately retrying,
    /// and that distinction is the whole point of the row. Absence is still
    /// used where it means something: a message that is not in the pipeline
    /// carries `messageId` and `inPipeline` and nothing else, and
    /// `mediationTarget`/`mediatingElapsedMs` appear only for `MEDIATING`.
    ///
    /// @param status             `MEDIATING` | `RETRY_BACKOFF` | `TRACKED_IDLE`,
    ///                           absent when not in the pipeline
    /// @param lastSeenAt         refreshed on every broker redelivery of the
    ///                           owner copy
    /// @param lastSeenElapsedMs  the phantom signature: a `TRACKED_IDLE` entry
    ///                           whose value keeps growing is one the broker
    ///                           has stopped redelivering, and it will
    ///                           ACK-swallow every requeued copy until cleared
    public record InFlightMessageDetail(String messageId, boolean inPipeline, String status,
                                        String brokerMessageId, String queueId, String poolCode,
                                        String messageGroup, Integer attempts, Long elapsedTimeMs,
                                        Instant addedToInPipelineAt, Instant lastSeenAt, Long lastSeenElapsedMs,
                                        String mediationTarget, Long mediatingElapsedMs) {

        static InFlightMessageDetail notInPipeline(String messageId) {
            return new InFlightMessageDetail(messageId, false, null, null, null, null, null,
                    null, null, null, null, null, null, null);
        }
    }

    /// `GET /monitoring/queues` — **snake_case**, alone on this surface,
    /// because that is the shape already on the wire.
    public record QueueMetricsView(@JsonProperty("queue_identifier") String queueIdentifier,
                                   @JsonProperty("pending_messages") long pendingMessages,
                                   @JsonProperty("in_flight_messages") long inFlightMessages) {
    }

    /// `GET /monitoring/queue-stats` map value — camelCase.
    ///
    /// `currentSize` is `pendingMessages + inFlightMessages`, and
    /// `messagesNotVisible` is `inFlightMessages` under the SQS name the
    /// dashboard uses; both are kept as separate fields because that is what
    /// the wire contract says, not because they are separate facts.
    public record DashboardQueueStats(String name, long totalMessages, long totalConsumed, long totalFailed,
                                      long totalDeferred, double successRate, long currentSize, double throughput,
                                      long pendingMessages, long messagesNotVisible) {
    }

    public record BrokerStatsRefreshResponse(boolean refreshed, long ageSeconds) {
    }

    /// `GET /monitoring/traffic-status`.
    ///
    /// `lastChangedAt` is an [Instant] rather than Go's hand-formatted
    /// millisecond string: every other timestamp on this surface goes through
    /// the platform's one RFC 3339 layout, and one layout across the API beats
    /// reproducing the single place Go rolled its own. Still RFC 3339, still
    /// parses.
    public record TrafficStatusResponse(boolean enabled, String mode, String targetGroupArn, boolean registered,
                                        Instant lastChangedAt, String lastError) {
    }

    public record InFlightCheckBatchRequest(List<String> messageIds) {
        public InFlightCheckBatchRequest {
            messageIds = messageIds == null ? List.of() : List.copyOf(messageIds);
        }
    }

    public record ForceAckResponse(String messageId, boolean removed, boolean brokerAcked, String brokerAckError,
                                   String queueId, String poolCode, long elapsedTimeMs, boolean wasMediating) {
    }

    public record PoolConfigUpdateRequest(Integer concurrency,
                                          @JsonProperty("rate_limit_per_minute") Integer rateLimitPerMinute) {
    }

    public record PoolConfigUpdateResponse(boolean success, @JsonProperty("pool_code") String poolCode,
                                           @JsonProperty("new_config") PoolConfigUpdateNewConfig newConfig) {
    }

    public record PoolConfigUpdateNewConfig(Integer concurrency,
                                            @JsonProperty("rate_limit_per_minute") Integer rateLimitPerMinute) {
    }

    public record StandbyStatusResponse(boolean enabled, @JsonProperty("is_leader") boolean isLeader,
                                        @JsonProperty("instance_id") String instanceId) {
    }

    public record StreamHealthResponse(boolean enabled, String status, String detail) {
    }

    public record StreamProbeResponse(String status) {
    }

    public record LocalConfigResponse(String version, @JsonProperty("warnings_total") long warningsTotal,
                                      @JsonProperty("warnings_critical") long warningsCritical) {
    }

    /// `POST /config/reload` (R-33). `reloaded` is false — with every count
    /// zero — both when this instance is not currently running the
    /// configuration-source pipeline at all, and when the source answered
    /// unavailable; a follower is refused before this is ever built (409,
    /// see [AdminRoutes]).
    public record ConfigReloadResponse(boolean reloaded, int pools, int consumersStarted, int consumersStopped,
                                       List<String> failedQueues) {

        public ConfigReloadResponse {
            failedQueues = failedQueues == null ? List.of() : List.copyOf(failedQueues);
        }

        static final ConfigReloadResponse UNAVAILABLE = new ConfigReloadResponse(false, 0, 0, 0, List.of());
    }

    public record MockOkResponse(boolean ok, String endpoint) {
    }

    public record MockStatsResponse(long fast, long slow, long faulty,
                                    @JsonProperty("faulty_success") long faultySuccess,
                                    @JsonProperty("faulty_fail") long faultyFail, long fail, long success,
                                    long pending, @JsonProperty("client_error") long clientError,
                                    @JsonProperty("server_error") long serverError) {
    }

    public record ResetResponse(boolean reset) {
    }

    /// One row of `GET /monitoring/group-flushes` (R-52, R-53): a group
    /// currently suppressed in some pool, and until when.
    public record GroupFlushView(String pool, String group, Instant suppressedUntil) {
    }

    public record GroupFlushClearResponse(boolean cleared) {
    }

    /// `POST /messages` body (§9.1). `dispatch_mode`/`mediation_type` are the
    /// raw wire strings — [MessageRoutes] parses/defaults them, never this
    /// record — because "absent" and "present but blank/unrecognised" are
    /// different outcomes only the handler needs to tell apart.
    public record PublishMessageRequest(String id, @JsonProperty("pool_code") String poolCode,
                                        @JsonProperty("mediation_type") String mediationType,
                                        @JsonProperty("mediation_target") String mediationTarget,
                                        @JsonProperty("message_group_id") String messageGroupId,
                                        @JsonProperty("high_priority") boolean highPriority,
                                        @JsonProperty("dispatch_mode") String dispatchMode,
                                        @JsonProperty("auth_token") String authToken,
                                        @JsonProperty("signing_secret") String signingSecret) {
    }

    public record PublishMessageResponse(@JsonProperty("message_id") String messageId,
                                         @JsonProperty("broker_message_id") String brokerMessageId,
                                         @JsonProperty("pool_code") String poolCode,
                                         @JsonProperty("queue_identifier") String queueIdentifier) {
    }

    /// `POST /api/seed/messages` body (§9.1). `count` is validated (1..10000)
    /// by [MessageRoutes], not here.
    public record SeedMessagesRequest(@JsonProperty("pool_code") String poolCode,
                                      @JsonProperty("mediation_target") String mediationTarget, int count) {
    }

    public record SeedMessagesResponse(@JsonProperty("pool_code") String poolCode,
                                       @JsonProperty("queue_identifier") String queueIdentifier, long published) {
    }

    /// One row of `GET /monitoring/blocked-groups` (R-04): a live message
    /// group, its buffer depth, whether a drainer currently owns it, and —
    /// when a target has flushed it — until when it is suppressed, alongside
    /// the pool settings an operator needs to judge whether the group is
    /// actually stuck or just busy.
    ///
    /// @param suppressedUntil    `null` when not currently suppressed
    /// @param rateLimitPerMinute follows [WirePoolStats]: `0` (unlimited) is
    ///                           omitted as `null`
    public record BlockedGroupView(String pool, String group, int depth, boolean draining,
                                   Instant suppressedUntil, int concurrency, Integer rateLimitPerMinute) {
    }

    private Wire() {
    }
}
