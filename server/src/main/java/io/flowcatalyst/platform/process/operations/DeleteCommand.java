package io.flowcatalyst.platform.process.operations;

/// The input DTO for [DeleteProcess] (audit `operation` = `DeleteCommand`).
public record DeleteCommand(String id) {
}
