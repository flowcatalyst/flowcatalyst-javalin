package io.flowcatalyst.platform.eventtype.operations;

import tools.jackson.databind.JsonNode;

/// One entry in a [SyncEventTypesCommand] batch.
public record SyncEventTypeInput(String code, String name, String description, JsonNode schema) {
}
