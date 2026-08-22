package io.flowcatalyst.platform.application.operations;

/// The input DTO for [AttachServiceAccount] (audit `operation` = `AttachServiceAccountCommand`).
///
/// @param applicationId      the application
/// @param serviceAccountId   the service account (`sac_…` service-account id; the
///                           operation resolves its principal)
/// @param serviceAccountCode copied onto the event only (not validated — spec §4)
public record AttachServiceAccountCommand(String applicationId, String serviceAccountId, String serviceAccountCode) {
}
