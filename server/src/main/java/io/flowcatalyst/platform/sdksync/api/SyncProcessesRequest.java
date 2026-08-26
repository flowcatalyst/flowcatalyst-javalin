package io.flowcatalyst.platform.sdksync.api;

import io.flowcatalyst.platform.process.operations.SyncProcessInput;
import io.flowcatalyst.platform.process.operations.SyncProcessesCommand;

import java.util.List;

/// `SyncProcessesRequest` (lockfile):
/// `{processes[]: {code, name, description, body, diagramType, tags[]}}`.
public record SyncProcessesRequest(List<Input> processes) {

    public record Input(String code, String name, String description, String body, String diagramType,
                        List<String> tags) {
        SyncProcessInput toInput() {
            return new SyncProcessInput(code, name, description, body, diagramType, tags);
        }
    }

    /// Shared with [SyncProcessesByBodyRequest], which carries the same list
    /// under a body-scoped application code.
    static SyncProcessesCommand toCommand(List<Input> processes, String applicationCode, String applicationId,
                                          boolean removeUnlisted) {
        return new SyncProcessesCommand(applicationCode, applicationId,
                processes == null ? List.of() : processes.stream().map(Input::toInput).toList(), removeUnlisted);
    }

    SyncProcessesCommand toCommand(String applicationCode, String applicationId, boolean removeUnlisted) {
        return toCommand(processes, applicationCode, applicationId, removeUnlisted);
    }
}
