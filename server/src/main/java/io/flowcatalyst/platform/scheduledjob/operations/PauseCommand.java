package io.flowcatalyst.platform.scheduledjob.operations;

/// The input DTO for [PauseScheduledJob] (audit `operation` = `PauseCommand`).
public record PauseCommand(String id) {
}
