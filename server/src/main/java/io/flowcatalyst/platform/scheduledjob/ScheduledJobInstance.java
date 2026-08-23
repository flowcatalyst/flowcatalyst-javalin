package io.flowcatalyst.platform.scheduledjob;

import com.fasterxml.jackson.databind.JsonNode;
import io.flowcatalyst.platform.shared.tsid.EntityType;

import java.time.Instant;
import java.util.Objects;

/// One firing of a [ScheduledJob] (spec §6.1) — the firing-history
/// projection, not an aggregate: rows are inserted directly by the poller
/// and by [ScheduledJob#fireNow], and advanced by the dispatcher and the
/// completion callback, all outside the use-case envelope.
///
/// @param id               `sji_…` TSID
/// @param scheduledJobId   the job fired
/// @param clientId         the job's client at fire time; `null` = platform-scoped
/// @param jobCode          the job's code at fire time
/// @param triggerKind      `CRON` | `MANUAL` | `BACKFILL`
/// @param scheduledFor     the cron slot; `null` for a manual fire
/// @param firedAt          when the row was created
/// @param deliveredAt      stamped on a 2xx from the target
/// @param completedAt      stamped by the completion callback
/// @param status           see [InstanceStatus]
/// @param deliveryAttempts dispatcher attempts so far
/// @param deliveryError    last delivery failure, if any
/// @param completionStatus the consumer's outcome (`SUCCESS` / `FAILURE` / free text)
/// @param completionResult the consumer's result payload
/// @param correlationId    carried into the firing webhook
/// @param createdAt        row creation
public record ScheduledJobInstance(
        String id,
        String scheduledJobId,
        String clientId,
        String jobCode,
        TriggerKind triggerKind,
        Instant scheduledFor,
        Instant firedAt,
        Instant deliveredAt,
        Instant completedAt,
        InstanceStatus status,
        int deliveryAttempts,
        String deliveryError,
        String completionStatus,
        JsonNode completionResult,
        String correlationId,
        Instant createdAt) {

    public ScheduledJobInstance {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(scheduledJobId, "scheduledJobId");
        Objects.requireNonNull(jobCode, "jobCode");
        Objects.requireNonNull(triggerKind, "triggerKind");
        Objects.requireNonNull(firedAt, "firedAt");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /// A `MANUAL`, `QUEUED` firing of `job` created now, picked up by the
    /// dispatcher on its next tick (spec §6.1). Only [ScheduledJob#fireNow]
    /// builds one, after the job's own checks.
    static ScheduledJobInstance manual(ScheduledJob job, String correlationId) {
        Instant now = Instant.now();
        return new ScheduledJobInstance(EntityType.SCHEDULED_JOB_INSTANCE.generate(), job.id(), job.clientId(), job.code(),
                TriggerKind.MANUAL, null, now, null, null, InstanceStatus.QUEUED, 0, null, null, null, correlationId, now);
    }

    public boolean isPlatformScoped() {
        return clientId == null;
    }
}
