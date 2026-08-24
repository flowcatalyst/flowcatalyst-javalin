package io.flowcatalyst.platform.principal.operations;

/// The input DTO for [DeactivateUser] (audit `operation` = `DeactivateCommand`).
public record DeactivateCommand(String id) {
}
