package io.flowcatalyst.platform.application.operations;

/// The input DTO for [ActivateApplication] (audit `operation` = `ActivateCommand`).
public record ActivateCommand(String id) {
}
