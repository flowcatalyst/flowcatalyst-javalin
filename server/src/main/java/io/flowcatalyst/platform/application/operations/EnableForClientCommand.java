package io.flowcatalyst.platform.application.operations;

/// The input DTO for [EnableApplicationForClient] (audit `operation` = `EnableForClientCommand`).
public record EnableForClientCommand(String applicationId, String clientId) {
}
