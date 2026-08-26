package io.flowcatalyst.platform.sdksync.api;

import io.flowcatalyst.platform.scheduledjob.operations.ScheduledJobEvents.ScheduledJobsSynced;

import java.util.List;

/// The scheduled-job sync's own result shape (lockfile), which is **not**
/// [SyncResultResponse]: it reports the affected job **ids** rather than
/// counts and codes, because a caller reconciling a schedule needs to know
/// which jobs moved, not how many.
///
/// All three lists are always present, empty rather than absent — a sync that
/// archived nothing and a sync that was not asked to archive must not render
/// the same.
public record SyncScheduledJobsResultResponse(String applicationCode, List<String> created, List<String> updated,
                                              List<String> archived) {

    public SyncScheduledJobsResultResponse {
        created = created == null ? List.of() : List.copyOf(created);
        updated = updated == null ? List.of() : List.copyOf(updated);
        archived = archived == null ? List.of() : List.copyOf(archived);
    }

    static SyncScheduledJobsResultResponse from(ScheduledJobsSynced e) {
        return new SyncScheduledJobsResultResponse(e.applicationCode(), e.created(), e.updated(), e.archived());
    }
}
