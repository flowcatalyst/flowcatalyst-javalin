package io.flowcatalyst.platform.subscription.operations;

/// The input DTO for [DeleteSubscription] (audit `operation` = `DeleteCommand`).
public record DeleteCommand(String id) {
}
