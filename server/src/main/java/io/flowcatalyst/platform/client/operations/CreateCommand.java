package io.flowcatalyst.platform.client.operations;

import io.flowcatalyst.platform.client.ClientIdentifier;

/// The input DTO for [CreateClient]. The record's simple name is the audit
/// log's `operation` column, so it must stay `CreateCommand`.
///
/// @param name       display name (trimmed by the aggregate)
/// @param identifier raw slug (normalised by [ClientIdentifier])
public record CreateCommand(String name, String identifier) {
}
