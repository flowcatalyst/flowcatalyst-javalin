package io.flowcatalyst.platform.client.operations;

/// The input DTO for [DeactivateClient] (audit `operation` = `DeactivateCommand`).
/// `reason` is optional.
public record DeactivateCommand(String id, String reason) {
}
