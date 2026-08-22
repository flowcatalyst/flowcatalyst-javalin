package io.flowcatalyst.platform.connection.operations;

/// The input DTO for [ActivateConnection] (audit `operation` =
/// `ActivateCommand`; spec §7, open question 7).
public record ActivateCommand(String id) {
}
