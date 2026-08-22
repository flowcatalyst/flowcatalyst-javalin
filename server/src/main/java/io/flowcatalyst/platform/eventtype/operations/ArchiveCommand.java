package io.flowcatalyst.platform.eventtype.operations;

/// The input DTO for [ArchiveEventType] (audit `operation` = `ArchiveCommand`).
public record ArchiveCommand(String id) {
}
