package io.flowcatalyst.platform.serviceaccount.operations;

import io.flowcatalyst.platform.serviceaccount.WebhookCredentials;

import java.util.List;

/// The input DTO for [CreateServiceAccountWithCredentials]. The record's
/// simple name is the audit log's `operation` column, so it must stay
/// `CreateCommand` (as in Go).
///
/// @param code               required
/// @param name               required
/// @param description        optional
/// @param scope              optional free-form scope tag
/// @param clientIds          optional; `null` means none given (create starts with `[]` either way)
/// @param applicationId      optional; when present the linked principal is confined to this application
/// @param webhookCredentials accepted and validated for wire parity with Go but never used — this
///                           create path always mints its own bearer + signing secret (spec §4.1);
///                           Go's `CreateServiceAccountWithCredentials.Execute` discards
///                           `cmd.WebhookCredentials` the same way
public record CreateCommand(String code, String name, String description, String scope, List<String> clientIds,
                            String applicationId, WebhookCredentials webhookCredentials) {
}
