package io.flowcatalyst.platform.principal.operations;

/// The input DTO for [DeleteUser] (audit `operation` = `DeleteCommand`).
public record DeleteCommand(String id) {
}
