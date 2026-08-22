package io.flowcatalyst.platform.eventtype.operations;

/// The input DTO for [DeleteEventType] (audit `operation` = `DeleteCommand`).
public record DeleteCommand(String id) {
}
