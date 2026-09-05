package io.flowcatalyst.platform.scheduledjob;

import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static io.flowcatalyst.db.generated.Tables.MSG_SCHEDULED_JOBS;
import static io.flowcatalyst.db.generated.Tables.MSG_SCHEDULED_JOB_INSTANCES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// X-06: a corrupt `status` (job or instance) or `trigger_kind` column
/// fails the read loudly instead of defaulting silently (spec §1, §6.1).
/// [#cleanup] deletes everything this class inserts — belt-and-braces
/// against `findActive()` or any other unfiltered read elsewhere (`TestPg`
/// never truncates between tests).
class ScheduledJobRepositoryTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final ScheduledJobRepository jobs = new ScheduledJobRepository(DS);
    private static final ScheduledJobInstanceRepository instances = new ScheduledJobInstanceRepository(DS);

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final List<String> JOB_IDS = new ArrayList<>();
    private static final List<String> INSTANCE_IDS = new ArrayList<>();

    private static String insertJob(String status) {
        String id = EntityType.SCHEDULED_JOB.generate();
        DB.insertInto(MSG_SCHEDULED_JOBS)
                .set(MSG_SCHEDULED_JOBS.ID, id)
                .set(MSG_SCHEDULED_JOBS.CODE, "corrupt-" + id + "-" + RUN)
                .set(MSG_SCHEDULED_JOBS.NAME, "corrupt")
                .set(MSG_SCHEDULED_JOBS.STATUS, status)
                .set(MSG_SCHEDULED_JOBS.CRONS, new String[]{"* * * * *"})
                .execute();
        JOB_IDS.add(id);
        return id;
    }

    private static String insertInstance(String scheduledJobId, String triggerKind, String status) {
        String id = "sji_" + UUID.randomUUID().toString().replace("-", "").substring(0, 13);
        DB.insertInto(MSG_SCHEDULED_JOB_INSTANCES)
                .set(MSG_SCHEDULED_JOB_INSTANCES.ID, id)
                .set(MSG_SCHEDULED_JOB_INSTANCES.SCHEDULED_JOB_ID, scheduledJobId)
                .set(MSG_SCHEDULED_JOB_INSTANCES.JOB_CODE, "corrupt." + RUN)
                .set(MSG_SCHEDULED_JOB_INSTANCES.TRIGGER_KIND, triggerKind)
                .set(MSG_SCHEDULED_JOB_INSTANCES.STATUS, status)
                .execute();
        INSTANCE_IDS.add(id);
        return id;
    }

    @AfterAll
    static void cleanup() {
        DB.deleteFrom(MSG_SCHEDULED_JOB_INSTANCES).where(MSG_SCHEDULED_JOB_INSTANCES.ID.in(INSTANCE_IDS)).execute();
        DB.deleteFrom(MSG_SCHEDULED_JOBS).where(MSG_SCHEDULED_JOBS.ID.in(JOB_IDS)).execute();
    }

    /// `chk_msg_scheduled_jobs_status` (migration 051) now blocks a fresh
    /// write of an unrecognised status, so the constraint is dropped for
    /// the seed insert AND the assertions, and the row is deleted again
    /// before restoring — otherwise restoring it would itself fail by
    /// re-validating against the row we just inserted
    /// (io.flowcatalyst.testpg.TestPg, ported from Go's
    /// testpg.WithConstraintDropped).
    @Test
    void jobFindByIdRejectsAnUnrecognisedStatusInsteadOfDefaultingToActive() {
        TestPg.withConstraintDropped(DS, "msg_scheduled_jobs", "chk_msg_scheduled_jobs_status", () -> {
            String id = insertJob("DELETED");
            try {
                assertThatThrownBy(() -> jobs.findById(id))
                        .isInstanceOf(CorruptScheduledJobException.class)
                        .satisfies(e -> assertThat(((CorruptScheduledJobException) e).rowId()).isEqualTo(id));
            } finally {
                DB.deleteFrom(MSG_SCHEDULED_JOBS).where(MSG_SCHEDULED_JOBS.ID.eq(id)).execute();
            }
        });
    }

    @Test
    void instanceFindByIdRejectsAnUnrecognisedTriggerKind() {
        String jobId = insertJob("ACTIVE");
        TestPg.withConstraintDropped(DS, "msg_scheduled_job_instances", "chk_msg_scheduled_job_instances_trigger_kind", () -> {
            String instanceId = insertInstance(jobId, "WEBHOOK", "QUEUED");
            try {
                assertThatThrownBy(() -> instances.findById(instanceId))
                        .isInstanceOf(CorruptScheduledJobException.class)
                        .satisfies(e -> assertThat(((CorruptScheduledJobException) e).rowId()).isEqualTo(instanceId));
            } finally {
                DB.deleteFrom(MSG_SCHEDULED_JOB_INSTANCES).where(MSG_SCHEDULED_JOB_INSTANCES.ID.eq(instanceId)).execute();
            }
        });
    }

    @Test
    void instanceFindByIdRejectsAnUnrecognisedStatus() {
        String jobId = insertJob("ACTIVE");
        TestPg.withConstraintDropped(DS, "msg_scheduled_job_instances", "chk_msg_scheduled_job_instances_status", () -> {
            String instanceId = insertInstance(jobId, "CRON", "ABANDONED");
            try {
                assertThatThrownBy(() -> instances.findById(instanceId))
                        .isInstanceOf(CorruptScheduledJobException.class)
                        .satisfies(e -> assertThat(((CorruptScheduledJobException) e).rowId()).isEqualTo(instanceId));
            } finally {
                DB.deleteFrom(MSG_SCHEDULED_JOB_INSTANCES).where(MSG_SCHEDULED_JOB_INSTANCES.ID.eq(instanceId)).execute();
            }
        });
    }

    @Test
    void aCorruptInstanceFailsTheWholeListReadNotJustThatRow() {
        String jobId = insertJob("ACTIVE");
        String good = insertInstance(jobId, "CRON", "QUEUED");
        TestPg.withConstraintDropped(DS, "msg_scheduled_job_instances", "chk_msg_scheduled_job_instances_status", () -> {
            String corrupt = insertInstance(jobId, "CRON", "NOT_A_STATUS");
            try {
                var filter = ScheduledJobInstanceRepository.ListFilter.forJob(jobId, null);
                assertThatThrownBy(() -> instances.list(filter, 50, 0))
                        .isInstanceOf(CorruptScheduledJobException.class)
                        .satisfies(e -> assertThat(((CorruptScheduledJobException) e).rowId()).isEqualTo(corrupt));
                assertThat(instances.findById(good)).isPresent();
            } finally {
                DB.deleteFrom(MSG_SCHEDULED_JOB_INSTANCES).where(MSG_SCHEDULED_JOB_INSTANCES.ID.eq(corrupt)).execute();
            }
        });
    }
}
