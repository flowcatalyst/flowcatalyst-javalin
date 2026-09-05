package io.flowcatalyst.platform.scheduler.jobs;

import io.flowcatalyst.db.generated.tables.MsgScheduledJobs;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static io.flowcatalyst.db.generated.Tables.MSG_SCHEDULED_JOBS;
import static org.assertj.core.api.Assertions.assertThat;

/// [ScheduledJobScheduler]'s lifecycle and leader gate (`docs/spec/scheduled-job-scheduler.md`
/// §1): [io.flowcatalyst.stream.StreamProcessor]'s owner-thread/[java.util.concurrent.StructuredTaskScope]
/// shutdown shape, and the fail-closed leader contract [io.flowcatalyst.server.Server#leaderGate]
/// establishes — a `BooleanSupplier` that always answers `false` (what an
/// election-start failure produces there) must make every tick of both
/// loops a no-op.
class ScheduledJobSchedulerTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final MsgScheduledJobs T = MSG_SCHEDULED_JOBS;
    private static final ScheduledJobRepository JOBS = new ScheduledJobRepository(DS);

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    private static final List<String> JOB_IDS = new ArrayList<>();

    /// Short enough that "within one interval" is a fast test, long enough
    /// that the loop reliably enters its sleep before the test asserts on it.
    private static final ScheduledJobScheduler.Settings FAST = new ScheduledJobScheduler.Settings(
            Duration.ofMillis(50), Duration.ofMillis(50), 32, Duration.ofSeconds(5), Optional.empty());

    private static String insertActiveJobWithAFirableSlot() {
        String id = EntityType.SCHEDULED_JOB.generate();
        DB.insertInto(T)
                .set(T.ID, id)
                .set(T.CODE, "scheduler-" + RUN + "-" + JOB_IDS.size())
                .set(T.NAME, "scheduler test job")
                .set(T.STATUS, "ACTIVE")
                // Once-a-year cron + a createdAt far in the past: deterministically
                // has exactly one firable slot between createdAt and "now", whenever
                // this test runs (same trick as JobPollerTest).
                .set(T.CRONS, new String[]{"0 0 0 1 1 *"})
                .set(T.TIMEZONE, "UTC")
                .set(T.CREATED_AT, OffsetDateTime.parse("2000-01-01T00:00:00Z"))
                .set(T.UPDATED_AT, OffsetDateTime.parse("2000-01-01T00:00:00Z"))
                .execute();
        JOB_IDS.add(id);
        return id;
    }

    @AfterAll
    static void cleanup() {
        DB.deleteFrom(io.flowcatalyst.db.generated.Tables.MSG_SCHEDULED_JOB_INSTANCES)
                .where(io.flowcatalyst.db.generated.Tables.MSG_SCHEDULED_JOB_INSTANCES.SCHEDULED_JOB_ID.in(JOB_IDS)).execute();
        DB.deleteFrom(T).where(T.ID.in(JOB_IDS)).execute();
    }

    @Test
    @DisplayName("start, then close, complete promptly — close does not wait out a full poll/dispatch interval")
    void startAndCloseCompleteWithinOneInterval() throws InterruptedException {
        var scheduler = ScheduledJobScheduler.start(DS, FAST, () -> true);
        Thread.sleep(150); // let both loops actually enter their sleep at least once

        long startNanos = System.nanoTime();
        scheduler.close();
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertThat(elapsedMs).as("close() must interrupt, not wait out the sleep").isLessThan(5_000);
    }

    @Test
    @DisplayName("the leader gate failing closed (an election-start failure, Server.leaderGate's own contract) means the poller does no work")
    void leaderGateFailingClosedMeansNoWorkHappens() throws InterruptedException {
        String jobId = insertActiveJobWithAFirableSlot();

        var scheduler = ScheduledJobScheduler.start(DS, FAST, () -> false);
        try {
            Thread.sleep(250); // several poll intervals' worth of ticks
        } finally {
            scheduler.close();
        }

        assertThat(JOBS.findById(jobId).orElseThrow().lastFiredAt())
                .as("a leader gate that never grants leadership must never let the poller fire a slot")
                .isNull();
    }
}
