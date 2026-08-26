package io.flowcatalyst.platform.sdksync.api;

import io.flowcatalyst.platform.scheduledjob.operations.ScheduledJobSyncEntry;
import io.flowcatalyst.platform.scheduledjob.operations.SyncScheduledJobsCommand;
import tools.jackson.databind.JsonNode;

import java.util.List;

/// `SyncScheduledJobsRequest` (lockfile). The odd one out on this surface in
/// two ways, both the lockfile's (spec §3):
///
///   - the prune flag is `archiveUnlisted` and travels in the **body**, not
///     as the `removeUnlisted` query parameter every other route uses;
///   - `clientId` is part of the payload. `null` means platform-scoped, which
///     only an anchor or super-admin may sync — enforced by the operation's
///     own `checkScopeAccess`, not here.
///
/// `timezone` and `deliveryMaxAttempts` are passed straight through as `null`
/// when absent so the aggregate applies its own defaults.
public record SyncScheduledJobsRequest(String clientId, List<Input> jobs, Boolean archiveUnlisted) {

    public record Input(String code, String name, String description, List<String> crons, String timezone,
                        JsonNode payload, Boolean concurrent, Boolean tracksCompletion, Integer timeoutSeconds,
                        Integer deliveryMaxAttempts, String targetUrl) {
        ScheduledJobSyncEntry toEntry() {
            return new ScheduledJobSyncEntry(code, name, description, crons, timezone, payload,
                    Boolean.TRUE.equals(concurrent), Boolean.TRUE.equals(tracksCompletion),
                    timeoutSeconds, deliveryMaxAttempts, targetUrl);
        }
    }

    SyncScheduledJobsCommand toCommand(String applicationCode, String applicationId) {
        return new SyncScheduledJobsCommand(applicationCode, applicationId, clientId,
                jobs == null ? List.of() : jobs.stream().map(Input::toEntry).toList(),
                Boolean.TRUE.equals(archiveUnlisted));
    }
}
