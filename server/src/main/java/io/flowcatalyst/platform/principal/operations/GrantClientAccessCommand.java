package io.flowcatalyst.platform.principal.operations;

/// The input DTO for [GrantClientAccess] (audit `operation` = `GrantClientAccessCommand`).
public record GrantClientAccessCommand(String userId, String clientId) {
}
