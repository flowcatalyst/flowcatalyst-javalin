package io.flowcatalyst.platform.client.operations;

/// The input DTO for [DeleteClient] (audit `operation` = `DeleteCommand`);
/// also what the deactivate alias runs — its `reason` never reaches here
/// (spec §3, open question 2).
public record DeleteCommand(String id) {
}
