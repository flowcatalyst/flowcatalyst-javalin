package io.flowcatalyst.platform.application.operations;

/// [ProvisionServiceAccount]'s command (spec `application.md` §10). The
/// route carries no body — `applicationId` comes from the path.
public record ProvisionServiceAccountCommand(String applicationId) {
}
