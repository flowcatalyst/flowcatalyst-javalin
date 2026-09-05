package io.flowcatalyst.platform.scheduler.jobs;

import io.flowcatalyst.db.generated.tables.MsgScheduledJobs;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobInstanceRepository;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static io.flowcatalyst.db.generated.Tables.MSG_SCHEDULED_JOBS;
import static org.assertj.core.api.Assertions.assertThat;

/// [JobPoller] against a real embedded Postgres (`docs/spec/scheduled-job-scheduler.md`
/// §2): every job row is inserted directly via jOOQ (the poller is
/// data-plane infrastructure, not a use case, so there is no
/// operation/envelope path to exercise here) with a **once-a-year** cron
/// (`0 0 0 1 1 *`) and a `createdAt` far in the past. `latestSlotInWindow`'s
/// skip-missed semantics then makes the answer deterministic regardless of
/// when this test runs: the latest slot in `(after, now]` is always
/// `<this year>-01-01T00:00:00Z`, and firing it advances `lastFiredAt` to
/// exactly that instant, so a second tick — whose window starts there — has
/// no new slot until next New Year's.
class JobPollerTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final MsgScheduledJobs T = MSG_SCHEDULED_JOBS;

    private static final ScheduledJobRepository JOBS = new ScheduledJobRepository(DS);
    private static final ScheduledJobInstanceRepository INSTANCES = new ScheduledJobInstanceRepository(DS);

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    private static final List<String> JOB_IDS = new ArrayList<>();

    /// Always Jan 1 of the current year, 00:00:00 UTC — the one slot
    /// `0 0 0 1 1 *` can ever fire between a job created years ago and "now".
    private static Instant thisYearJanFirst() {
        int year = Instant.now().atZone(ZoneOffset.UTC).getYear();
        return Instant.parse(year + "-01-01T00:00:00Z");
    }

    private static String insertJob(String status) {
        String id = EntityType.SCHEDULED_JOB.generate();
        DB.insertInto(T)
                .set(T.ID, id)
                .set(T.CODE, "poller-" + RUN + "-" + JOB_IDS.size())
                .set(T.NAME, "poller test job")
                .set(T.STATUS, status)
                .set(T.CRONS, new String[]{"0 0 0 1 1 *"})
                .set(T.TIMEZONE, "UTC")
                .set(T.CREATED_AT, OffsetDateTime.parse("2000-01-01T00:00:00Z"))
                .set(T.UPDATED_AT, OffsetDateTime.parse("2000-01-01T00:00:00Z"))
                .execute();
        JOB_IDS.add(id);
        return id;
    }

    private static int instanceCount(String jobId) {
        var f = ScheduledJobInstanceRepository.ListFilter.forJob(jobId, null);
        return INSTANCES.list(f, 100, 0).size();
    }

    @AfterAll
    static void cleanup() {
        DB.deleteFrom(io.flowcatalyst.db.generated.Tables.MSG_SCHEDULED_JOB_INSTANCES)
                .where(io.flowcatalyst.db.generated.Tables.MSG_SCHEDULED_JOB_INSTANCES.SCHEDULED_JOB_ID.in(JOB_IDS)).execute();
        DB.deleteFrom(T).where(T.ID.in(JOB_IDS)).execute();
    }

    @Test
    void firesTheLatestSlotOnceAndAdvancesLastFiredAtToExactlyThatSlot() {
        String jobId = insertJob("ACTIVE");
        Instant expectedSlot = thisYearJanFirst();

        new JobPoller(JOBS, INSTANCES, () -> true).pollOnce();

        var f = ScheduledJobInstanceRepository.ListFilter.forJob(jobId, null);
        var rows = INSTANCES.list(f, 100, 0);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.scheduledFor()).isEqualTo(expectedSlot);
            assertThat(row.triggerKind()).isEqualTo(io.flowcatalyst.platform.scheduledjob.TriggerKind.CRON);
        });
        assertThat(JOBS.findById(jobId).orElseThrow().lastFiredAt()).isEqualTo(expectedSlot);
    }

    @Test
    void aSecondTickInTheSameWindowFiresNothing_aCounterThatMustNotMove() {
        String jobId = insertJob("ACTIVE");
        JobPoller poller = new JobPoller(JOBS, INSTANCES, () -> true);

        poller.pollOnce();
        int afterFirstTick = instanceCount(jobId);
        assertThat(afterFirstTick).isEqualTo(1);

        poller.pollOnce();
        int afterSecondTick = instanceCount(jobId);

        assertThat(afterSecondTick).as("the slot already fired; a second tick must not create another instance")
                .isEqualTo(afterFirstTick);
    }

    @Test
    void aPausedJobNeverFires() {
        String jobId = insertJob("PAUSED");

        new JobPoller(JOBS, INSTANCES, () -> true).pollOnce();

        assertThat(instanceCount(jobId)).isZero();
    }

    @Test
    void afterFallsBackToCreatedAtForANeverFiredJob() {
        // insertJob leaves last_fired_at NULL, so this is exactly the
        // never-fired case — the same fixture as the first test, asserted
        // from the other direction: the slot found is bounded below by
        // createdAt (2000-01-01), not some other default.
        String jobId = insertJob("ACTIVE");

        new JobPoller(JOBS, INSTANCES, () -> true).pollOnce();

        assertThat(JOBS.findById(jobId).orElseThrow().lastFiredAt()).isEqualTo(thisYearJanFirst());
    }

    @Test
    void aNonLeaderPollerInsertsNothing() {
        String jobId = insertJob("ACTIVE");

        new JobPoller(JOBS, INSTANCES, () -> false).pollOnce();

        assertThat(instanceCount(jobId)).isZero();
        assertThat(JOBS.findById(jobId).orElseThrow().lastFiredAt()).isNull();
    }
}
