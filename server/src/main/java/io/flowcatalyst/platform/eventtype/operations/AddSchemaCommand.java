package io.flowcatalyst.platform.eventtype.operations;

import com.fasterxml.jackson.databind.JsonNode;

/// The input DTO for [AddSchema] (audit `operation` = `AddSchemaCommand`).
public record AddSchemaCommand(String eventTypeId, String version, JsonNode schema) {
}
