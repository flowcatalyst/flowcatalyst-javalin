package io.flowcatalyst.platform.sdksync.api;

import io.flowcatalyst.platform.eventtype.operations.SyncEventTypeInput;
import io.flowcatalyst.platform.eventtype.operations.SyncEventTypesCommand;

import java.util.List;

/// `SyncEventTypesRequest` (lockfile): `{eventTypes[]: {code, name, description}}`.
public record SyncEventTypesRequest(List<Input> eventTypes) {

    public record Input(String code, String name, String description) {
        SyncEventTypeInput toInput() {
            return new SyncEventTypeInput(code, name, description, null);
        }
    }

    SyncEventTypesCommand toCommand(String applicationCode, boolean removeUnlisted) {
        return new SyncEventTypesCommand(applicationCode,
                eventTypes == null ? List.of() : eventTypes.stream().map(Input::toInput).toList(), removeUnlisted);
    }
}
