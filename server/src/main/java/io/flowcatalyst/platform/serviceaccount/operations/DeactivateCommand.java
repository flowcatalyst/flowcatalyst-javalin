package io.flowcatalyst.platform.serviceaccount.operations;

/// The input DTO for [DeactivateServiceAccount] (audit `operation` = `DeactivateCommand`).
public record DeactivateCommand(String id) {
}
