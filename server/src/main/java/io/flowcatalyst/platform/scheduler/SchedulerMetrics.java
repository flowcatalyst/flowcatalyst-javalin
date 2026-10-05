package io.flowcatalyst.platform.scheduler;

import io.prometheus.metrics.model.registry.MultiCollector;
import io.prometheus.metrics.model.snapshots.CounterSnapshot;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.Labels;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;

import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/// The dispatch scheduler's Prometheus series (`fc_scheduler_*`, the same
/// names the Go scheduler exports where the meaning is the same): job counters
/// the poller and the lanes bump, two gauges read live from the lanes, and
/// duration sums with their counts (a rate of sum over count is the mean —
/// the registry here has no histogram dependency to spend).
///
/// Process-global in effect, like every Prometheus counter; one instance per
/// scheduler.
public final class SchedulerMetrics {

    final LongAdder claimed = new LongAdder();
    final LongAdder submitted = new LongAdder();
    final LongAdder published = new LongAdder();
    final LongAdder unpublished = new LongAdder();
    final LongAdder skippedHeld = new LongAdder();
    final LongAdder skippedDoomed = new LongAdder();
    final LongAdder droppedPoisoned = new LongAdder();
    final LongAdder markNotUpdated = new LongAdder();
    final LongAdder fullBatchClaims = new LongAdder();
    final LongAdder claimsRestored = new LongAdder();
    final LongAdder restoreErrors = new LongAdder();
    final LongAdder alreadyInFlight = new LongAdder();
    final LongAdder leaderStartRestored = new LongAdder();
    final LongAdder staleQueuedRecovered = new LongAdder();
    final LongAdder reconcileInserted = new LongAdder();
    final LongAdder reconcileDeleted = new LongAdder();
    final LongAdder reconcileRefreshed = new LongAdder();
    final LongAdder maintenanceErrors = new LongAdder();
    private volatile LongSupplier heldGroups = () -> 0;
    private volatile long backlogDepth;
    private volatile double backlogOldestAgeSeconds;
    final LongAdder pollErrors = new LongAdder();
    final LongAdder claimNanos = new LongAdder();
    final LongAdder claimCount = new LongAdder();
    private final LongAdder[] lanePublishNanos;
    private final LongAdder[] lanePublishCount;

    private volatile LongSupplier bufferInUse = () -> 0;
    private volatile LongSupplier inFlight = () -> 0;

    SchedulerMetrics(int lanes) {
        lanePublishNanos = new LongAdder[lanes];
        lanePublishCount = new LongAdder[lanes];
        for (int i = 0; i < lanes; i++) {
            lanePublishNanos[i] = new LongAdder();
            lanePublishCount[i] = new LongAdder();
        }
    }

    void heldGroups(LongSupplier heldGroups) {
        this.heldGroups = heldGroups;
    }

    void gauges(LongSupplier bufferInUse, LongSupplier inFlight) {
        this.bufferInUse = bufferInUse;
        this.inFlight = inFlight;
    }

    /// The last sample of the queue backlog (the leader samples it every 15 s).
    void backlog(long depth, double oldestAgeSeconds) {
        this.backlogDepth = depth;
        this.backlogOldestAgeSeconds = oldestAgeSeconds;
    }

    void lanePublish(int lane, long nanos) {
        lanePublishNanos[lane].add(nanos);
        lanePublishCount[lane].increment();
    }

    /// Jobs the lanes dropped because their group was poisoned (an earlier job
    /// of the group was not published) — each is re-claimed later.
    public long skippedDoomed() {
        return skippedDoomed.sum();
    }

    public long droppedPoisoned() {
        return droppedPoisoned.sum();
    }

    /// Jobs a lane published whose `QUEUED` update matched no `PENDING` row
    /// because the job had already moved on.
    public long markNotUpdated() {
        return markNotUpdated.sum();
    }

    /// Claim queries run so far.
    public long claims() {
        return claimCount.sum();
    }

    public long pollErrors() {
        return pollErrors.sum();
    }

    public long publishedTotal() {
        return published.sum();
    }

    public long unpublishedTotal() {
        return unpublished.sum();
    }

    public MultiCollector collector() {
        return () -> {
            var b = MetricSnapshots.builder();
            counter(b, "fc_scheduler_jobs_claimed_total", "Dispatch jobs returned by the claim query.", claimed);
            counter(b, "fc_scheduler_jobs_submitted_total",
                    "Claimed dispatch jobs handed to a lane (claimed less those held back).", submitted);
            counter(b, "fc_scheduler_jobs_published_total",
                    "Dispatch jobs the broker accepted.", published);
            counter(b, "fc_scheduler_jobs_unpublished_total",
                    "Dispatch jobs a lane failed to publish; left PENDING for a later claim.", unpublished);
            counter(b, "fc_scheduler_jobs_skipped_held_total",
                    "Dispatch jobs held back behind an earlier failed or backed-off job of their BLOCK_ON_ERROR group.",
                    skippedHeld);
            counter(b, "fc_scheduler_jobs_skipped_doomed_total",
                    "Claimed dispatch jobs not submitted because an in-flight job ahead of them in their group is doomed to be dropped; claimed again later.",
                    skippedDoomed);
            counter(b, "fc_scheduler_jobs_dropped_poisoned_total",
                    "Claimed dispatch jobs a lane dropped unpublished because an earlier job of their group was not published.",
                    droppedPoisoned);
            counter(b, "fc_scheduler_mark_queued_not_updated_total",
                    "Published jobs whose QUEUED update matched no PENDING row because the job had already moved on.",
                    markNotUpdated);
            counter(b, "fc_scheduler_full_batch_claims_total",
                    "Claims that filled everything asked for (a backlog deeper than one claim).", fullBatchClaims);
            counter(b, "fc_scheduler_claims_restored_total",
                    "Queue rows put back because the job was not published (failed publish, poisoned drop, doomed or hold-back withhold, failed QUEUED update, shutdown).",
                    claimsRestored);
            counter(b, "fc_scheduler_claim_restore_errors_total",
                    "Restores that failed; the reconcile sweep restores those rows.", restoreErrors);
            counter(b, "fc_scheduler_claims_already_in_flight_total",
                    "Claimed jobs this process still had in flight (a restored or refreshed row claimed again): put back, not submitted twice.",
                    alreadyInFlight);
            counter(b, "fc_scheduler_leader_start_restored_total",
                    "Queue rows restored by the new leader's start-up reconcile pass (jobs a dead claimer left PENDING with no queue row).",
                    leaderStartRestored);
            counter(b, "fc_scheduler_stale_queued_recovered_total",
                    "Jobs the leader returned from QUEUED to PENDING after the stale threshold (15 minutes).",
                    staleQueuedRecovered);
            counter(b, "fc_scheduler_queue_reconcile_inserted_total",
                    "PENDING jobs the reconcile sweep found with no queue row and gave one (a bug or an old binary writing the table).",
                    reconcileInserted);
            counter(b, "fc_scheduler_queue_reconcile_deleted_total",
                    "Queue rows the reconcile sweep deleted because their job is missing or not PENDING.",
                    reconcileDeleted);
            counter(b, "fc_scheduler_queue_reconcile_refreshed_total",
                    "Queue rows the reconcile sweep refreshed because they differed from their job.",
                    reconcileRefreshed);
            counter(b, "fc_scheduler_maintenance_errors_total",
                    "Maintenance sweeps (stale claims, reconcile, stale QUEUED, backlog sample) that ended in an error.",
                    maintenanceErrors);
            b.metricSnapshot(GaugeSnapshot.builder().name("fc_dispatch_queue_backlog_jobs")
                    .help("Jobs waiting in msg_dispatch_queue: unclaimed and due. Sampled by the leader every 15 s.")
                    .dataPoint(GaugeSnapshot.GaugeDataPointSnapshot.builder().value(backlogDepth).build())
                    .build());
            b.metricSnapshot(GaugeSnapshot.builder().name("fc_dispatch_queue_oldest_waiting_seconds")
                    .help("Age of the oldest unclaimed due job in msg_dispatch_queue (0 when none). Sampled by the leader every 15 s.")
                    .dataPoint(GaugeSnapshot.GaugeDataPointSnapshot.builder().value(backlogOldestAgeSeconds).build())
                    .build());
            counter(b, "fc_scheduler_poll_errors_total", "Polls that ended in an error.", pollErrors);
            counter(b, "fc_scheduler_claim_seconds_total", "Total time spent in the claim query.", claimNanos, 1e-9);
            counter(b, "fc_scheduler_claims_total", "Claim queries run.", claimCount);
            var laneSeconds = CounterSnapshot.builder().name("fc_scheduler_lane_publish_seconds_total")
                    .help("Total time each lane spent publishing and marking batches.");
            var laneCount = CounterSnapshot.builder().name("fc_scheduler_lane_batches_total")
                    .help("Batches each lane has published.");
            for (int i = 0; i < lanePublishNanos.length; i++) {
                var labels = Labels.of("lane", Integer.toString(i));
                laneSeconds.dataPoint(CounterSnapshot.CounterDataPointSnapshot.builder()
                        .labels(labels).value(lanePublishNanos[i].sum() * 1e-9).build());
                laneCount.dataPoint(CounterSnapshot.CounterDataPointSnapshot.builder()
                        .labels(labels).value(lanePublishCount[i].sum()).build());
            }
            b.metricSnapshot(laneSeconds.build());
            b.metricSnapshot(laneCount.build());
            b.metricSnapshot(GaugeSnapshot.builder().name("fc_scheduler_held_groups")
                    .help("Groups the poller currently remembers as held back (skipped by the claim for 5 seconds).")
                    .dataPoint(GaugeSnapshot.GaugeDataPointSnapshot.builder().value(heldGroups.getAsLong()).build())
                    .build());
            b.metricSnapshot(GaugeSnapshot.builder().name("fc_scheduler_buffer_in_use")
                    .help("Permits held: jobs claimed and not yet settled by a lane.")
                    .dataPoint(GaugeSnapshot.GaugeDataPointSnapshot.builder().value(bufferInUse.getAsLong()).build())
                    .build());
            b.metricSnapshot(GaugeSnapshot.builder().name("fc_scheduler_in_flight_jobs")
                    .help("Size of the in-flight id set the claim excludes.")
                    .dataPoint(GaugeSnapshot.GaugeDataPointSnapshot.builder().value(inFlight.getAsLong()).build())
                    .build());
            return b.build();
        };
    }

    private static void counter(MetricSnapshots.Builder b, String name, String help, LongAdder value) {
        counter(b, name, help, value, 1.0);
    }

    private static void counter(MetricSnapshots.Builder b, String name, String help, LongAdder value, double scale) {
        b.metricSnapshot(CounterSnapshot.builder().name(name).help(help)
                .dataPoint(CounterSnapshot.CounterDataPointSnapshot.builder().value(value.sum() * scale).build())
                .build());
    }
}
