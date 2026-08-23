package io.flowcatalyst.platform.scheduledjob.operations;

import java.util.List;

/// The input DTO for [SyncScheduledJobs] (audit `operation` =
/// `SyncScheduledJobsCommand`).
///
/// @param applicationCode the `{appCode}` of the route, for provenance and the rollup subject
/// @param applicationId   that code resolved to an id by the handler (may be `null`); stamped on
///                        created rows and backfilled onto existing ones
/// @param clientId        the scope: `null` = platform-scoped jobs, else that client's
/// @param jobs            the declared definitions
/// @param archiveUnlisted archive `ACTIVE` jobs in scope that are not declared
public record SyncScheduledJobsCommand(String applicationCode, String applicationId, String clientId,
                                       List<ScheduledJobSyncEntry> jobs, boolean archiveUnlisted) {
    public SyncScheduledJobsCommand {
        jobs = jobs == null ? List.of() : List.copyOf(jobs);
    }
}
