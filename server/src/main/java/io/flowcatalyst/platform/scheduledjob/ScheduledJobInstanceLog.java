package io.flowcatalyst.platform.scheduledjob;

import com.fasterxml.jackson.databind.JsonNode;
import io.flowcatalyst.platform.shared.tsid.EntityType;

import java.time.Instant;
import java.util.Objects;

/// One log line attached to an instance (spec §6.3), written by the
/// consumer (`POST …/log`) or the dispatcher as the firing progresses.
///
/// @param id             `sjl_…` TSID
/// @param instanceId     the instance this line belongs to
/// @param scheduledJobId copied from the instance (optional on legacy rows)
/// @param clientId       copied from the instance; `null` for platform-scoped jobs
/// @param level          free text (`DEBUG | INFO | WARN | ERROR` by convention)
/// @param message        the line
/// @param metadata       optional JSON
/// @param createdAt      write time
public record ScheduledJobInstanceLog(String id, String instanceId, String scheduledJobId, String clientId,
                                      String level, String message, JsonNode metadata, Instant createdAt) {

    public ScheduledJobInstanceLog {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /// A fresh line on `instance`, stamped now.
    public static ScheduledJobInstanceLog on(ScheduledJobInstance instance, String level, String message, JsonNode metadata) {
        return new ScheduledJobInstanceLog(EntityType.SCHEDULED_JOB_INSTANCE_LOG.generate(), instance.id(),
                instance.scheduledJobId(), instance.clientId(), level, message, metadata, Instant.now());
    }
}
