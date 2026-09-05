package io.flowcatalyst.platform.scheduledjob;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.db.generated.tables.MsgScheduledJobInstanceLogs;
import io.flowcatalyst.db.generated.tables.MsgScheduledJobInstances;
import io.flowcatalyst.db.generated.tables.records.MsgScheduledJobInstanceLogsRecord;
import io.flowcatalyst.db.generated.tables.records.MsgScheduledJobInstancesRecord;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static io.flowcatalyst.db.generated.Tables.MSG_SCHEDULED_JOB_INSTANCES;
import static io.flowcatalyst.db.generated.Tables.MSG_SCHEDULED_JOB_INSTANCE_LOGS;
import static io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository.fromJsonb;
import static io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository.instant;
import static io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository.toJsonb;
import static io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository.utc;

/// `msg_scheduled_job_instances` + `msg_scheduled_job_instance_logs` via
/// jOOQ (spec §6). Instances are the firing-history projection, not an
/// aggregate: every write here is a direct infrastructure write on the
/// pool — [#insert] by `FireNow` and the poller, [#writeLog] and
/// [#markComplete] by the consumer callbacks. The dispatcher's own
/// transitions (`IN_FLIGHT` / `DELIVERED` / `DELIVERY_FAILED`) belong to
/// the data-plane unit (spec §7).
public final class ScheduledJobInstanceRepository {

    private static final MsgScheduledJobInstances I = MSG_SCHEDULED_JOB_INSTANCES;
    private static final MsgScheduledJobInstanceLogs L = MSG_SCHEDULED_JOB_INSTANCE_LOGS;

    /// The log list cap (spec §6.3).
    public static final int MAX_LOGS = 500;

    /// Reads and direct writes: jOOQ acquires and releases a pooled connection per statement.
    private final DSLContext dsl;

    public ScheduledJobInstanceRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    /// Equality filters for [#list] / [#count]; `null` = no filter. The
    /// `from` / `to` window on `created_at` is half-open `[from, to)`.
    public record ListFilter(String scheduledJobId, String clientId, InstanceStatus status, TriggerKind triggerKind,
                             Instant from, Instant to) {
        /// Every instance of one job.
        public static ListFilter forJob(String scheduledJobId, InstanceStatus status) {
            return new ListFilter(scheduledJobId, null, status, null, null, null);
        }
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    public Optional<ScheduledJobInstance> findById(String id) {
        return dsl.selectFrom(I).where(I.ID.eq(id)).fetchOptional().map(ScheduledJobInstanceRepository::toEntity);
    }

    /// One page of instances, newest first (`created_at DESC, id DESC` for a stable order on ties).
    public List<ScheduledJobInstance> list(ListFilter f, int limit, int offset) {
        return List.copyOf(dsl.selectFrom(I).where(condition(f))
                .orderBy(I.CREATED_AT.desc(), I.ID.desc()).limit(limit).offset(offset)
                .fetch().map(ScheduledJobInstanceRepository::toEntity));
    }

    /// The total for [#list]'s filter, ignoring the page.
    public long count(ListFilter f) {
        return dsl.fetchCount(I, condition(f));
    }

    /// Whether the job has a non-terminal instance (spec §6.1): `QUEUED` /
    /// `IN_FLIGHT` always; `DELIVERED` only when the job tracks completion
    /// and the instance has not completed — for a job that does not,
    /// `DELIVERED` is terminal, so an old delivered instance must not keep
    /// the "currently running" badge lit forever.
    public boolean hasActiveInstance(String scheduledJobId, boolean tracksCompletion) {
        Condition active = I.STATUS.in(InstanceStatus.QUEUED.name(), InstanceStatus.IN_FLIGHT.name());
        if (tracksCompletion) {
            active = active.or(I.STATUS.eq(InstanceStatus.DELIVERED.name()).and(I.COMPLETED_AT.isNull()));
        }
        return dsl.fetchExists(I, I.SCHEDULED_JOB_ID.eq(scheduledJobId).and(active));
    }

    /// Up to `limit` log lines of one instance, oldest first; a non-positive limit means [#MAX_LOGS].
    public List<ScheduledJobInstanceLog> listLogs(String instanceId, int limit) {
        return List.copyOf(dsl.selectFrom(L).where(L.INSTANCE_ID.eq(instanceId))
                .orderBy(L.CREATED_AT.asc(), L.ID.asc()).limit(limit <= 0 ? MAX_LOGS : limit)
                .fetch().map(ScheduledJobInstanceRepository::toLog));
    }

    private static Condition condition(ListFilter f) {
        Condition where = DSL.noCondition();
        if (f.scheduledJobId() != null) where = where.and(I.SCHEDULED_JOB_ID.eq(f.scheduledJobId()));
        if (f.clientId() != null) where = where.and(I.CLIENT_ID.eq(f.clientId()));
        if (f.status() != null) where = where.and(I.STATUS.eq(f.status().name()));
        if (f.triggerKind() != null) where = where.and(I.TRIGGER_KIND.eq(f.triggerKind().name()));
        if (f.from() != null) where = where.and(I.CREATED_AT.ge(utc(f.from())));
        if (f.to() != null) where = where.and(I.CREATED_AT.lt(utc(f.to())));
        return where;
    }

    // ── Direct writes (the projection, spec §6) ────────────────────────────

    /// A fresh instance row — `FireNow`'s `MANUAL` firing or the poller's `CRON` one.
    public void insert(ScheduledJobInstance inst) {
        dsl.insertInto(I)
                .set(I.ID, inst.id())
                .set(I.SCHEDULED_JOB_ID, inst.scheduledJobId())
                .set(I.CLIENT_ID, inst.clientId())
                .set(I.JOB_CODE, inst.jobCode())
                .set(I.TRIGGER_KIND, inst.triggerKind().name())
                .set(I.SCHEDULED_FOR, utc(inst.scheduledFor()))
                .set(I.FIRED_AT, utc(inst.firedAt()))
                .set(I.DELIVERED_AT, utc(inst.deliveredAt()))
                .set(I.COMPLETED_AT, utc(inst.completedAt()))
                .set(I.STATUS, inst.status().name())
                .set(I.DELIVERY_ATTEMPTS, inst.deliveryAttempts())
                .set(I.DELIVERY_ERROR, inst.deliveryError())
                .set(I.COMPLETION_STATUS, inst.completionStatus())
                .set(I.COMPLETION_RESULT, toJsonb(inst.completionResult()))
                .set(I.CORRELATION_ID, inst.correlationId())
                .set(I.CREATED_AT, utc(inst.createdAt()))
                .execute();
    }

    /// Appends a log line.
    public void writeLog(ScheduledJobInstanceLog log) {
        dsl.insertInto(L)
                .set(L.ID, log.id())
                .set(L.INSTANCE_ID, log.instanceId())
                .set(L.SCHEDULED_JOB_ID, log.scheduledJobId())
                .set(L.CLIENT_ID, log.clientId())
                .set(L.LEVEL, log.level())
                .set(L.MESSAGE, log.message())
                .set(L.METADATA, toJsonb(log.metadata()))
                .set(L.CREATED_AT, utc(log.createdAt()))
                .execute();
    }

    /// The completion callback (spec §6.2): status, outcome, result and
    /// `completed_at = now()`; repeated calls overwrite (idempotent).
    public void markComplete(String instanceId, InstanceStatus status, String completionStatus, JsonNode completionResult) {
        dsl.update(I)
                .set(I.STATUS, status.name())
                .set(I.COMPLETION_STATUS, completionStatus)
                .set(I.COMPLETION_RESULT, toJsonb(completionResult))
                .set(I.COMPLETED_AT, utc(Instant.now()))
                .where(I.ID.eq(instanceId))
                .execute();
    }

    // ── Row ↔ entity ───────────────────────────────────────────────────────

    private static ScheduledJobInstance toEntity(MsgScheduledJobInstancesRecord r) {
        return new ScheduledJobInstance(
                r.getId(), r.getScheduledJobId(), r.getClientId(), r.getJobCode(),
                triggerKind(r.getId(), r.getTriggerKind()),
                instant(r.getScheduledFor()), r.getFiredAt().toInstant(), instant(r.getDeliveredAt()), instant(r.getCompletedAt()),
                status(r.getId(), r.getStatus()),
                r.getDeliveryAttempts(), r.getDeliveryError(), r.getCompletionStatus(), fromJsonb(r.getCompletionResult()),
                r.getCorrelationId(), r.getCreatedAt().toInstant());
    }

    private static ScheduledJobInstanceLog toLog(MsgScheduledJobInstanceLogsRecord r) {
        return new ScheduledJobInstanceLog(r.getId(), r.getInstanceId(), r.getScheduledJobId(), r.getClientId(),
                r.getLevel(), r.getMessage(), fromJsonb(r.getMetadata()), r.getCreatedAt().toInstant());
    }

    /// [TriggerKind#parse], wrapped so a corrupt stored value fails loudly
    /// with the offending row's id (X-06).
    private static TriggerKind triggerKind(String rowId, String stored) {
        try {
            return TriggerKind.parse(stored);
        } catch (TriggerKind.UnrecognisedTriggerKindException e) {
            throw new CorruptScheduledJobException("scheduled job instance", rowId, e);
        }
    }

    /// [InstanceStatus#parse], wrapped so a corrupt stored value fails
    /// loudly with the offending row's id (X-06).
    private static InstanceStatus status(String rowId, String stored) {
        try {
            return InstanceStatus.parse(stored);
        } catch (InstanceStatus.UnrecognisedInstanceStatusException e) {
            throw new CorruptScheduledJobException("scheduled job instance", rowId, e);
        }
    }
}
