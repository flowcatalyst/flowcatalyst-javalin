package io.flowcatalyst.platform.scheduledjob.operations;

import java.util.List;
import java.util.Set;

/// The input DTO for [SyncScheduledJobs] (audit `operation` =
/// `SyncScheduledJobsCommand`).
///
/// @param applicationCode the `{appCode}` of the route, for provenance and the rollup subject
/// @param applicationId   that code resolved to an id by the handler (may be `null`); stamped on
///                        created rows and backfilled onto existing ones
/// @param clientId        the scope: `null` = platform-scoped jobs, else that client's
/// @param jobs            the declared definitions
/// @param archiveUnlisted archive `ACTIVE` jobs in scope that are not declared
/// @param protectedIds    scheduled-job ids a function owns
///                        (`function-invocation.md` §4.2) — read by the
///                        sdksync handler from `TriggerObjectRepository`;
///                        this operation carries no `fn_` knowledge of its
///                        own. A protected job is skipped entirely, neither
///                        reconciled by a matching declared entry nor
///                        archived by `archiveUnlisted`, and not counted in
///                        either total.
public record SyncScheduledJobsCommand(String applicationCode, String applicationId, String clientId,
                                       List<ScheduledJobSyncEntry> jobs, boolean archiveUnlisted,
                                       Set<String> protectedIds) {
    public SyncScheduledJobsCommand {
        jobs = jobs == null ? List.of() : List.copyOf(jobs);
        protectedIds = protectedIds == null ? Set.of() : Set.copyOf(protectedIds);
    }

    /// No protected ids — every caller but the sdksync handler (tests, and
    /// any future caller with nothing to protect).
    public SyncScheduledJobsCommand(String applicationCode, String applicationId, String clientId,
                                     List<ScheduledJobSyncEntry> jobs, boolean archiveUnlisted) {
        this(applicationCode, applicationId, clientId, jobs, archiveUnlisted, Set.of());
    }
}
