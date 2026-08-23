package io.flowcatalyst.platform.scheduledjob.operations;

/// The input DTO for [ArchiveScheduledJob] (audit `operation` = `ArchiveCommand`).
public record ArchiveCommand(String id) {
}
