package io.flowcatalyst.platform.serviceaccount.operations;

/// The audit `operation` of `POST /api/service-accounts/{id}/token` (owner
/// ruling 2026-09-06 #15): the record's simple name is the audit column, the
/// account id its only content — the token is never written anywhere.
public record MintServiceAccountTokenCommand(String serviceAccountId) {
}
