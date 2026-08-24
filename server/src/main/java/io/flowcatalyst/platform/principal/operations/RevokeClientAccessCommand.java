package io.flowcatalyst.platform.principal.operations;

/// The input DTO for [RevokeClientAccess] (audit `operation` = `RevokeClientAccessCommand`).
public record RevokeClientAccessCommand(String userId, String clientId) {
}
