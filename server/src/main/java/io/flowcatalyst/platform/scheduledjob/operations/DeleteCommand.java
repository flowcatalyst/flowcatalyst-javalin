package io.flowcatalyst.platform.scheduledjob.operations;

/// The input DTO for [DeleteScheduledJob] (audit `operation` = `DeleteCommand`).
public record DeleteCommand(String id) {
}
