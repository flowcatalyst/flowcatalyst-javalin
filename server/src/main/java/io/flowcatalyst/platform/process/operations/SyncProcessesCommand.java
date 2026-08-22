package io.flowcatalyst.platform.process.operations;

import java.util.List;

/// The input DTO for [SyncProcesses] (audit `operation` = `SyncProcessesCommand`).
/// `applicationId` is resolved by the caller from `applicationCode`; the
/// use case authorizes against it. `removeUnlisted` removes the
/// application's sync-managed (`API` / `CODE`) processes absent from
/// `processes`; `UI` rows are never touched (spec §7).
public record SyncProcessesCommand(String applicationCode, String applicationId, List<SyncProcessInput> processes,
                                   boolean removeUnlisted) {

    public SyncProcessesCommand {
        processes = processes == null ? List.of() : List.copyOf(processes);
    }
}
