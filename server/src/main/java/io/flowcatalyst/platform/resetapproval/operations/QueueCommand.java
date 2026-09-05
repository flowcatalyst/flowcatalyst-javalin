package io.flowcatalyst.platform.resetapproval.operations;

/// `QueueResetApproval`'s command (spec §8.6). Built by
/// [io.flowcatalyst.platform.resetapproval.ResetApprovalQueue] only after it
/// has already decided a row should be created — `clientId` is never `null`
/// here (a principal without one is filtered out first).
public record QueueCommand(String principalId, String clientId) {
}
