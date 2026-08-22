package io.flowcatalyst.platform.client.operations;

/// The input DTO for [UpdateClient] (audit `operation` = `UpdateCommand`).
///
/// @param id   the client
/// @param name new display name; `null` = unchanged
public record UpdateCommand(String id, String name) {
}
