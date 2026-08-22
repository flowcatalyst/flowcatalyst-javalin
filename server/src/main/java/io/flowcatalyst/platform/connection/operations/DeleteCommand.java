package io.flowcatalyst.platform.connection.operations;

/// The input DTO for [DeleteConnection] (audit `operation` = `DeleteCommand`).
public record DeleteCommand(String id) {
}
