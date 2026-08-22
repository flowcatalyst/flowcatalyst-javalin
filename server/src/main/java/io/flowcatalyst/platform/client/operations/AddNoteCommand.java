package io.flowcatalyst.platform.client.operations;

/// The input DTO for [AddNote] (audit `operation` = `AddNoteCommand`).
public record AddNoteCommand(String clientId, String category, String text) {
}
