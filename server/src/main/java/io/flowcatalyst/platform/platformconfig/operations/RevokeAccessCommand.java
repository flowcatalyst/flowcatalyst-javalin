package io.flowcatalyst.platform.platformconfig.operations;

/// The input DTO for [RevokeAccess] (audit `operation` = `RevokeAccessCommand`).
public record RevokeAccessCommand(String id) {
}
