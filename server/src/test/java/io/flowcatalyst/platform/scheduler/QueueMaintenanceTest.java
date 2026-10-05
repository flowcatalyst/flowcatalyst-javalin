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
import java.util.concurrent.atomic.AtomicBoolean;

import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.DB;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.RUN;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.code;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.seedWriteRow;
import static io.flowcatalyst.platform.scheduler.SchedulerFixture.DATA_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;

/// The leader's housekeeping ([QueueMaintenance]): leader-only, the owner-ruled threshold (stale `QUEUED`
/// 15 minutes), the backlog gauge (30 s, saturating count, oldest wait), the metrics and the schedule.
class QueueMaintenanceTest {

    private static final DispatchJobLifecycle LIFECYCLE = new DispatchJobLifecycle(DATA_SOURCE);
    private static final DispatchJobRepository REPO = new DispatchJobRepository(DATA_SOURCE);

    private QueueMaintenance maintenance;

    @AfterEach
    void cleanUp() {
        if (maintenance != null) maintenance.close();
        DispatchJobFixture.setStatusWhere("COMPLETED", "PENDING", "QUEUED", "PROCESSING");
    }

    private QueueMaintenance maintenance(AtomicBoolean leader, SchedulerMetrics metrics, QueueMaintenance.Timing timing) {
        maintenance = new QueueMaintenance(LIFECYCLE, REPO, leader::get, metrics, timing);
        return maintenance;
    }

    @Test
    void theDefaultsAreTheOwnersRulings() {
        var t = QueueMaintenance.Timing.DEFAULTS;
        assertThat(t.sweepInterval()).isEqualTo(Duration.ofSeconds(60));
        assertThat(t.backlogInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(t.staleQueuedAfter()).as("owner ruling 2026-10-04").isEqualTo(Duration.ofMinutes(15));
    }

    /// A job still QUEUED 15 minutes after its last update goes back to PENDING; one at 14 minutes does not.
    @Test
    void theSweepReturnsJobsStaleInQueuedFor15MinutesToPending() {
        String g = "mt-sq-" + RUN;
        String stale = seedWriteRow(Seed.of(code("sq1")).withMessageGroup(g).withStatus("QUEUED")
                .withUpdatedAt(Instant.now().minus(Duration.ofMinutes(16))));
        String young = seedWriteRow(Seed.of(code("sq2")).withMessageGroup(g).withStatus("QUEUED")
                .withUpdatedAt(Instant.now().minus(Duration.ofMinutes(14))));
        String processing = seedWriteRow(Seed.of(code("sq3")).withMessageGroup(g).withStatus("PROCESSING")
                .withUpdatedAt(Instant.now().minus(Duration.ofMinutes(60))));
        var metrics = new SchedulerMetrics(1);

        int recovered = maintenance(new AtomicBoolean(true), metrics, QueueMaintenance.Timing.DEFAULTS).recoverStaleQueued();

        assertThat(recovered).isEqualTo(1);
        assertThat(REPO.findById(stale).orElseThrow().status()).isEqualTo(DispatchJobStatus.PENDING);
        assertThat(REPO.findById(young).orElseThrow().status()).isEqualTo(DispatchJobStatus.QUEUED);
        assertThat(REPO.findById(processing).orElseThrow().status()).as("no PROCESSING sweep here").isEqualTo(DispatchJobStatus.PROCESSING);
        assertThat(metrics.staleQueuedRecovered.sum()).isEqualTo(1);
    }

    /// The sweep runs only on the leader.
    @Test
    void aStandbyRunsNoSweep() {
        String stale = seedWriteRow(Seed.of(code("sb2")).withMessageGroup("mt-sb-" + RUN).withStatus("QUEUED")
                .withUpdatedAt(Instant.now().minus(Duration.ofHours(1))));
        var leader = new AtomicBoolean(false);
        var m = maintenance(leader, new SchedulerMetrics(1), QueueMaintenance.Timing.DEFAULTS);

        m.sweepAll();
        assertThat(REPO.findById(stale).orElseThrow().status()).isEqualTo(DispatchJobStatus.QUEUED);

        leader.set(true);
        m.sweepAll();
        assertThat(REPO.findById(stale).orElseThrow().status()).isEqualTo(DispatchJobStatus.PENDING);
    }

    /// The backlog gauge: the PENDING count and the age of the first due job in claim order, zero from a standby.
    @Test
    void theBacklogGaugeReportsCountAndOldestWaitAndIsZeroOnAStandby() {
        seedWriteRow(Seed.of(code("bg1")).withMessageGroup("mt-bg-" + RUN).withSequence(1).withCreatedAt(Instant.now().minusSeconds(120)));
        seedWriteRow(Seed.of(code("bg2")).withMessageGroup("mt-bg-" + RUN).withSequence(2));
        seedWriteRow(Seed.of(code("bg3")).withMessageGroup("mt-bg-" + RUN).withSequence(3).withScheduledFor(Instant.now().plusSeconds(600)));
        var leader = new AtomicBoolean(true);
        var metrics = new SchedulerMetrics(1);
        var m = maintenance(leader, metrics, QueueMaintenance.Timing.DEFAULTS);

        m.sampleBacklog();
        assertThat(gauge(metrics, "fc_dispatch_queue_backlog_jobs")).as("every PENDING job, due or not").isEqualTo(3.0);
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
        String stale = seedWriteRow(Seed.of(code("sch1")).withMessageGroup("mt-sch-" + RUN).withStatus("QUEUED")
                .withUpdatedAt(Instant.now().minus(Duration.ofHours(1))));
        var metrics = new SchedulerMetrics(1);
        var timing = new QueueMaintenance.Timing(Duration.ofMillis(50), Duration.ofMillis(50), Duration.ofMinutes(15));
        maintenance(new AtomicBoolean(true), metrics, timing).start();

        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (REPO.findById(stale).orElseThrow().status() != DispatchJobStatus.PENDING && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(REPO.findById(stale).orElseThrow().status()).as("the periodic sweep recovered it").isEqualTo(DispatchJobStatus.PENDING);
        while (gauge(metrics, "fc_dispatch_queue_backlog_jobs") < 1 && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(gauge(metrics, "fc_dispatch_queue_backlog_jobs")).as("and sampled the backlog").isGreaterThanOrEqualTo(1);

        maintenance.close();
        DB.execute("UPDATE msg_dispatch_jobs SET status = 'QUEUED', updated_at = now() - interval '1 hour' WHERE id = ?", stale);
        Thread.sleep(300);
        assertThat(REPO.findById(stale).orElseThrow().status()).as("closed: nothing runs any more").isEqualTo(DispatchJobStatus.QUEUED);
    }
}
