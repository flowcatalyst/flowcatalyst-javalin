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
/// @param allApplications    `true` grants the linked principal every application; absent or `false`
///                           leaves it with **no** application access until some is assigned (Go
///                           `a8ff165`). Cannot be combined with `applicationId`; only a caller that
///                           itself holds all-applications access may ask for it (checked at the handler)
/// @param webhookCredentials accepted and validated for wire parity with Go but never used — this
///                           create path always mints its own bearer + signing secret (spec §4.1);
///                           Go's `CreateServiceAccountWithCredentials.Execute` discards
///                           `cmd.WebhookCredentials` the same way
public record CreateCommand(String code, String name, String description, String scope, List<String> clientIds,
                            String applicationId, Boolean allApplications, WebhookCredentials webhookCredentials) {
}
