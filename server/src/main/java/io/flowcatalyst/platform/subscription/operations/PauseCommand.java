package io.flowcatalyst.platform.subscription.operations;

/// The input DTO for [PauseSubscription] (audit `operation` = `PauseCommand`).
public record PauseCommand(String id) {
}
