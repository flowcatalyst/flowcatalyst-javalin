package io.flowcatalyst.platform.eventtype.operations;

/// The input DTO for [UpdateEventType] (audit `operation` = `UpdateCommand`).
public record UpdateCommand(String id, String name, String description) {
}
