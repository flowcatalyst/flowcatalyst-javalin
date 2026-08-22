package io.flowcatalyst.platform.application.operations;

/// The input DTO for [DeleteApplication] (audit `operation` = `DeleteCommand`).
public record DeleteCommand(String id) {
}
