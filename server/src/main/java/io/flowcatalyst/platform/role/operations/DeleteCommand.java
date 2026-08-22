package io.flowcatalyst.platform.role.operations;

/// The input DTO for [DeleteRole] (audit `operation` = `DeleteCommand`).
public record DeleteCommand(String id) {
}
