package io.flowcatalyst.platform.scheduler;

import io.flowcatalyst.platform.dispatchjob.DispatchJobFixture;
import io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.Seed;
import io.flowcatalyst.platform.dispatchjob.DispatchJobLifecycle;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository;
import io.flowcatalyst.platform.dispatchjob.DispatchJobStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.DB;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.RUN;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.code;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.queueRow;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.seedQueued;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.seedWriteRow;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.seedWriteRowOnly;
import static io.flowcatalyst.platform.scheduler.SchedulerFixture.DATA_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;

/// The leader's queue housekeeping ([QueueMaintenance]): leader-only, the thresholds the owner ruled
/// (stale claims 5 min, stale `QUEUED` 15 min, reconcile every 60 s), the metrics, and the schedule.
class QueueMaintenanceTest {

    private static final DispatchJobLifecycle LIFECYCLE = new DispatchJobLifecycle(DATA_SOURCE);
    private static final DispatchJobRepository REPO = new DispatchJobRepository(DATA_SOURCE);

    private QueueMaintenance maintenance;

    @AfterEach
    void cleanUp() {
        if (maintenance != null) maintenance.close();
        DispatchJobFixture.setStatusWhere("COMPLETED", "PENDING", "QUEUED", "PROCESSING");
    }

    private QueueMaintenance maintenance(AtomicBoolean leader, List<String> inFlight, SchedulerMetrics metrics,
                                         QueueMaintenance.Timing timing) {
        maintenance = new QueueMaintenance(LIFECYCLE, () -> inFlight, leader::get, metrics, timing);
        return maintenance;
    }

    private static Object claimedAt(String id) {
        return queueRow(id).get("claimed_at");
    }

    @Test
    void theDefaultsAreTheOwnersRulings() {
        var t = QueueMaintenance.Timing.DEFAULTS;
        assertThat(t.sweepInterval()).isEqualTo(Duration.ofSeconds(60));
        assertThat(t.backlogInterval()).isEqualTo(Duration.ofSeconds(15));
        assertThat(t.staleClaimAfter()).isEqualTo(Duration.ofMinutes(5));
        assertThat(t.staleQueuedAfter()).as("owner ruling 2026-10-04").isEqualTo(Duration.ofMinutes(15));
        assertThat(t.reconcileJobAge()).isEqualTo(Duration.ofSeconds(60));
        assertThat(t.reconcileClaimAge()).isEqualTo(Duration.ofMinutes(5));
        assertThat(t.reconcileMaxRows()).isEqualTo(5_000);
    }

    /// A claim older than 5 minutes that this process does not hold is released; one it holds is not.
    @Test
    void theSweepReleasesOldClaimsThisProcessDoesNotHold() {
        String mine = seedQueued(Seed.of(code("mt1")).withMessageGroup("mt-" + RUN).withSequence(1));
        String dead = seedQueued(Seed.of(code("mt2")).withMessageGroup("mt-" + RUN).withSequence(2));
        String fresh = seedQueued(Seed.of(code("mt3")).withMessageGroup("mt-" + RUN).withSequence(3));
        DB.execute("UPDATE msg_dispatch_queue SET claimed_at = now() - interval '6 minutes' WHERE job_id = ANY(?::text[])",
                (Object) new String[] {mine, dead});
        var metrics = new SchedulerMetrics(1);

        int released = maintenance(new AtomicBoolean(true), List.of(mine), metrics, QueueMaintenance.Timing.DEFAULTS)
                .releaseStaleClaims();

        assertThat(released).isEqualTo(1);
        assertThat(claimedAt(dead)).isNull();
        assertThat(claimedAt(mine)).as("in this process's in-flight set").isNotNull();
        assertThat(claimedAt(fresh)).as("a claim of a moment ago").isNotNull();
        assertThat(metrics.staleClaimsReleased.sum()).isEqualTo(1);
    }

    /// A job still QUEUED 15 minutes after its last update goes back to PENDING; one at 14 minutes does not.
    /// Mutant: the 5-minute threshold the 2026-09-22 ruling removed.
    @Test
    void theSweepReturnsJobsStaleInQueuedFor15MinutesToPending() {
        String g = "mt-sq-" + RUN;
        String stale = seedWriteRowOnly(Seed.of(code("sq1")).withMessageGroup(g).withStatus("QUEUED")
                .withUpdatedAt(Instant.now().minus(Duration.ofMinutes(16))));
        String young = seedWriteRowOnly(Seed.of(code("sq2")).withMessageGroup(g).withStatus("QUEUED")
                .withUpdatedAt(Instant.now().minus(Duration.ofMinutes(14))));
        String processing = seedWriteRowOnly(Seed.of(code("sq3")).withMessageGroup(g).withStatus("PROCESSING")
                .withUpdatedAt(Instant.now().minus(Duration.ofMinutes(60))));
        var metrics = new SchedulerMetrics(1);

        int recovered = maintenance(new AtomicBoolean(true), List.of(), metrics, QueueMaintenance.Timing.DEFAULTS)
                .recoverStaleQueued();

        assertThat(recovered).isEqualTo(1);
        assertThat(REPO.findById(stale).orElseThrow().status()).isEqualTo(DispatchJobStatus.PENDING);
        assertThat(queueRow(stale)).as("claimable again").isNotNull();
        assertThat(REPO.findById(young).orElseThrow().status()).isEqualTo(DispatchJobStatus.QUEUED);
        assertThat(REPO.findById(processing).orElseThrow().status()).as("no PROCESSING sweep here").isEqualTo(DispatchJobStatus.PROCESSING);
        assertThat(metrics.staleQueuedRecovered.sum()).isEqualTo(1);
    }

    @Test
    void reconcileCountsEachRepairInItsMetric() {
        String missing = seedWriteRowOnly(Seed.of(code("rm1")).withMessageGroup("mt-rc-" + RUN)
                .withUpdatedAt(Instant.now().minusSeconds(3600)));
        String orphan = seedWriteRow(Seed.of(code("rm2")).withMessageGroup("mt-rc-" + RUN).withSequence(2));
        DB.execute("UPDATE msg_dispatch_jobs SET status = 'COMPLETED' WHERE id = ?", orphan);
        String stale = seedWriteRow(Seed.of(code("rm3")).withMessageGroup("mt-rc-" + RUN).withSequence(3));
        DB.execute("UPDATE msg_dispatch_jobs SET sequence = 8, updated_at = now() WHERE id = ?", stale);
        var metrics = new SchedulerMetrics(1);

        var r = maintenance(new AtomicBoolean(true), List.of(), metrics,
                new QueueMaintenance.Timing(Duration.ofSeconds(60), Duration.ofSeconds(15), Duration.ofMinutes(5),
                        Duration.ofMinutes(15), Duration.ofSeconds(30), Duration.ZERO, 5_000)).reconcile();

        assertThat(r.inserted()).isEqualTo(1);
        assertThat(r.deleted()).isEqualTo(1);
        assertThat(r.refreshed()).isEqualTo(1);
        assertThat(metrics.reconcileInserted.sum()).isEqualTo(1);
        assertThat(metrics.reconcileDeleted.sum()).isEqualTo(1);
        assertThat(metrics.reconcileRefreshed.sum()).isEqualTo(1);
        assertThat(queueRow(missing)).isNotNull();
        assertThat(queueRow(orphan)).isNull();
    }

    /// The three sweeps run only on the leader. Mutant: no leader check.
    @Test
    void aStandbyRunsNoSweep() {
        String dead = seedQueued(Seed.of(code("sb1")).withMessageGroup("mt-sb-" + RUN));
        DB.execute("UPDATE msg_dispatch_queue SET claimed_at = now() - interval '1 hour' WHERE job_id = ?", dead);
        String stale = seedWriteRowOnly(Seed.of(code("sb2")).withMessageGroup("mt-sb-" + RUN).withStatus("QUEUED")
                .withUpdatedAt(Instant.now().minus(Duration.ofHours(1))));
        var leader = new AtomicBoolean(false);
        var metrics = new SchedulerMetrics(1);
        var m = maintenance(leader, List.of(), metrics, QueueMaintenance.Timing.DEFAULTS);

        m.sweepAll();
        assertThat(claimedAt(dead)).isNotNull();
        assertThat(REPO.findById(stale).orElseThrow().status()).isEqualTo(DispatchJobStatus.QUEUED);

        leader.set(true);
        m.sweepAll();
        assertThat(claimedAt(dead)).isNull();
        assertThat(REPO.findById(stale).orElseThrow().status()).isEqualTo(DispatchJobStatus.PENDING);
    }

    /// The backlog gauge: depth and age of the oldest waiting row, zero from a standby.
    @Test
    void theBacklogGaugeReportsDepthAndOldestWaitAndIsZeroOnAStandby() {
        String oldest = seedWriteRow(Seed.of(code("bg1")).withMessageGroup("mt-bg-" + RUN));
        seedWriteRow(Seed.of(code("bg2")).withMessageGroup("mt-bg-" + RUN).withSequence(2));
        DB.execute("UPDATE msg_dispatch_queue SET enqueued_at = now() - interval '2 minutes' WHERE job_id = ?", oldest);
        var leader = new AtomicBoolean(true);
        var metrics = new SchedulerMetrics(1);
        var m = maintenance(leader, List.of(), metrics, QueueMaintenance.Timing.DEFAULTS);

        m.sampleBacklog();
        var text = gauge(metrics, "fc_dispatch_queue_backlog_jobs");
        assertThat(text).isEqualTo(2.0);
        assertThat(gauge(metrics, "fc_dispatch_queue_oldest_waiting_seconds")).isBetween(110.0, 200.0);

        leader.set(false);
        m.sampleBacklog();
        assertThat(gauge(metrics, "fc_dispatch_queue_backlog_jobs")).isZero();
    }

    private static double gauge(SchedulerMetrics metrics, String name) {
        for (var snapshot : metrics.collector().collect()) {
            if (snapshot.getMetadata().getName().equals(name)) {
                return ((io.prometheus.metrics.model.snapshots.GaugeSnapshot) snapshot).getDataPoints().getFirst().getValue();
            }
        }
        throw new AssertionError("no metric " + name);
    }

    /// The schedule really runs the sweeps (short intervals), and `close()` stops it.
    @Test
    void theScheduleRunsTheSweepsAndCloseStopsThem() throws Exception {
        String dead = seedQueued(Seed.of(code("sch1")).withMessageGroup("mt-sch-" + RUN));
        DB.execute("UPDATE msg_dispatch_queue SET claimed_at = now() - interval '1 hour' WHERE job_id = ?", dead);
        var metrics = new SchedulerMetrics(1);
        var timing = new QueueMaintenance.Timing(Duration.ofMillis(50), Duration.ofMillis(50), Duration.ofMinutes(5),
                Duration.ofMinutes(15), Duration.ofSeconds(60), Duration.ofMinutes(5), 5_000);
        maintenance(new AtomicBoolean(true), List.of(), metrics, timing).start();

        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (claimedAt(dead) != null && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(claimedAt(dead)).as("the periodic sweep released the stale claim").isNull();
        while (gauge(metrics, "fc_dispatch_queue_backlog_jobs") < 1 && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(gauge(metrics, "fc_dispatch_queue_backlog_jobs")).as("and sampled the backlog").isGreaterThanOrEqualTo(1);

        maintenance.close();
        DB.execute("UPDATE msg_dispatch_queue SET claimed_at = now() - interval '1 hour' WHERE job_id = ?", dead);
        Thread.sleep(300);
        assertThat(claimedAt(dead)).as("closed: nothing runs any more").isNotNull();
    }
}
