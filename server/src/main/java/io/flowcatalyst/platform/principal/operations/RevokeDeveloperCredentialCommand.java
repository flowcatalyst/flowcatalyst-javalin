package io.flowcatalyst.platform.principal.operations;

/// The input DTO for [RevokeDeveloperCredential] (audit `operation` = `RevokeDeveloperCredentialCommand`).
public record RevokeDeveloperCredentialCommand(String principalId) {
}
