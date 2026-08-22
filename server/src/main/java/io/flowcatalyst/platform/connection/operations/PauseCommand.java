package io.flowcatalyst.platform.connection.operations;

/// The input DTO for [PauseConnection] (audit `operation` = `PauseCommand`;
/// spec §7, open question 7).
public record PauseCommand(String id) {
}
