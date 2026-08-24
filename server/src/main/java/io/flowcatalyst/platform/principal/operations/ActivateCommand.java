package io.flowcatalyst.platform.principal.operations;

/// The input DTO for [ActivateUser] (audit `operation` = `ActivateCommand`).
public record ActivateCommand(String id) {
}
