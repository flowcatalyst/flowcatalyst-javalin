package io.flowcatalyst.platform.scheduledjob.operations;

import tools.jackson.databind.JsonNode;

import java.util.List;

/// One job definition in a [SyncScheduledJobsCommand] batch (spec §8).
/// `timezone` `null`/blank and `deliveryMaxAttempts` `null` take the
/// aggregate's defaults.
public record ScheduledJobSyncEntry(String code, String name, String description, List<String> crons, String timezone,
                                    JsonNode payload, boolean concurrent, boolean tracksCompletion,
                                    Integer timeoutSeconds, Integer deliveryMaxAttempts, String targetUrl) {
    public ScheduledJobSyncEntry {
        crons = crons == null ? List.of() : List.copyOf(crons);
    }
}
