package io.flowcatalyst.platform.application.operations;

/// The input DTO for [DeactivateApplication] (audit `operation` = `DeactivateCommand`).
public record DeactivateCommand(String id) {
}
