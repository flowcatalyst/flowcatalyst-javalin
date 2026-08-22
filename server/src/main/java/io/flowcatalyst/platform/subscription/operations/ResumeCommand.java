package io.flowcatalyst.platform.subscription.operations;

/// The input DTO for [ResumeSubscription] (audit `operation` = `ResumeCommand`).
public record ResumeCommand(String id) {
}
