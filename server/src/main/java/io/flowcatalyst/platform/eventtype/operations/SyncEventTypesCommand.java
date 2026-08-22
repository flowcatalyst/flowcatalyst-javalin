package io.flowcatalyst.platform.eventtype.operations;

import java.util.List;

/// The input DTO for [SyncEventTypes] (audit `operation` = `SyncEventTypesCommand`).
/// `removeUnlisted` removes `API`-sourced event types of the application that
/// are not in `eventTypes`; `UI` / `CODE` sourced rows are never touched by sync.
public record SyncEventTypesCommand(String applicationCode, List<SyncEventTypeInput> eventTypes, boolean removeUnlisted) {

    public SyncEventTypesCommand {
        eventTypes = eventTypes == null ? List.of() : List.copyOf(eventTypes);
    }
}
