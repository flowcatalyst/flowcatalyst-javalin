package io.flowcatalyst.platform.scheduledjob.operations;

/// The input DTO for [ResumeScheduledJob] (audit `operation` = `ResumeCommand`).
public record ResumeCommand(String id) {
}
