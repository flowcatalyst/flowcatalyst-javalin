package io.flowcatalyst.platform.application.operations;

/// The input DTO for [DisableApplicationForClient] (audit `operation` = `DisableForClientCommand`).
public record DisableForClientCommand(String applicationId, String clientId) {
}
