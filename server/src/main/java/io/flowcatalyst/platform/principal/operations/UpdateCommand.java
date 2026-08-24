package io.flowcatalyst.platform.principal.operations;

/// The input DTO for [UpdateUser] (audit `operation` = `UpdateCommand`).
/// `null` = untouched; `email` is an identity assertion, not a change (spec §4).
public record UpdateCommand(String id, String name, Boolean active, String email) {
}
