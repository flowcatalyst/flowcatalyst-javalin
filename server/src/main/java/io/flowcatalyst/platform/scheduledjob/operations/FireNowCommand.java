package io.flowcatalyst.platform.scheduledjob.operations;

/// The input DTO for [FireNow] (audit `operation` = `FireNowCommand`). The
/// optional `correlationId` is stamped on the instance and carried in the
/// firing webhook.
public record FireNowCommand(String id, String correlationId) {
}
