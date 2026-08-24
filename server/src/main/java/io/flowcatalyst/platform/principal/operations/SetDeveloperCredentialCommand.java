package io.flowcatalyst.platform.principal.operations;

/// The input DTO for [SetDeveloperCredential] (audit `operation` = `SetDeveloperCredentialCommand`).
public record SetDeveloperCredentialCommand(String principalId) {
}
