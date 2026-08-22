package io.flowcatalyst.platform.eventtype.operations;

/// The input DTO for [FinaliseEventTypeSchema] (audit `operation` = `FinaliseSchemaCommand`).
public record FinaliseSchemaCommand(String eventTypeId, String version) {
}
