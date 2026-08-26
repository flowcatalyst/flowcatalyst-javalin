package io.flowcatalyst.platform.sdksync.api;

import io.flowcatalyst.platform.process.operations.SyncProcessesCommand;

import java.util.List;

/// `SyncProcessesByBodyRequest` (lockfile) — the Laravel-SDK alias of the
/// process sync, with the application code travelling **in the body** rather
/// than the path (spec §3).
///
/// An absent or blank `applicationCode` answers 404 `Application_NOT_FOUND`
/// rather than a validation error: `findByCode("")` finds nothing, and the
/// lookup is the first thing that sees it. Go/huma would have answered 422 on
/// the missing required field; kept as 404 and recorded in the spec as an
/// observed difference rather than silently corrected.
public record SyncProcessesByBodyRequest(String applicationCode, List<SyncProcessesRequest.Input> processes) {

    SyncProcessesCommand toCommand(String resolvedCode, String applicationId, boolean removeUnlisted) {
        return SyncProcessesRequest.toCommand(processes, resolvedCode, applicationId, removeUnlisted);
    }
}
