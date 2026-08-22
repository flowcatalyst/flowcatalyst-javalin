package io.flowcatalyst.platform.eventtype.operations;

/// The input DTO for [DeprecateEventTypeSchema] (audit `operation` = `DeprecateSchemaCommand`).
public record DeprecateSchemaCommand(String eventTypeId, String version) {
}
