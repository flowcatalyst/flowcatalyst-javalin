package io.flowcatalyst.platform.client.operations;

/// The input DTO for [SuspendClient] (audit `operation` = `SuspendCommand`).
public record SuspendCommand(String id, String reason) {
}
