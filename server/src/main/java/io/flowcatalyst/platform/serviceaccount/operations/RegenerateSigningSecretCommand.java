package io.flowcatalyst.platform.serviceaccount.operations;

/// The input DTO for [RegenerateSigningSecret] (audit `operation` =
/// `RegenerateSigningSecretCommand`). See [RegenerateAuthTokenCommand] for why
/// the disclosure sink is not a field here.
public record RegenerateSigningSecretCommand(String serviceAccountId) {
}
