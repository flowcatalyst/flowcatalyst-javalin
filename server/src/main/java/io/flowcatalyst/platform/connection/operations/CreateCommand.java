package io.flowcatalyst.platform.connection.operations;

/// The input DTO for [CreateConnection]. The record's simple name is the
/// audit log's `operation` column, so it must stay `CreateCommand`.
///
/// @param code             raw code; normalised (trimmed, lower-cased) by the operation
/// @param name             human-readable name; trimmed
/// @param description      optional
/// @param serviceAccountId the owning service account (required, not validated — spec §1)
/// @param externalId       optional external reference
/// @param clientId         optional client scope; `null` means platform-wide
public record CreateCommand(String code, String name, String description, String serviceAccountId,
                            String externalId, String clientId) {
}
