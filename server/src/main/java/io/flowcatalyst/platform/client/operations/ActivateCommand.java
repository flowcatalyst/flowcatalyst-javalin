package io.flowcatalyst.platform.client.operations;

/// The input DTO for [ActivateClient] (audit `operation` = `ActivateCommand`).
public record ActivateCommand(String id) {
}
