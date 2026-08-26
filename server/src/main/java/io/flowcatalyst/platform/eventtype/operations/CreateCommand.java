package io.flowcatalyst.platform.eventtype.operations;

import tools.jackson.databind.JsonNode;

/// The input DTO for [CreateEventType]. The record's simple name is the audit
/// log's `operation` column, so it must stay `CreateCommand` (as in Go).
///
/// @param code        `application:subdomain:aggregate:event`
/// @param name        human-readable name
/// @param description optional
/// @param clientId    optional client scope; `null` means anchor-level
/// @param schema      optional JSON Schema for the initial `1.0` spec version
public record CreateCommand(String code, String name, String description, String clientId, JsonNode schema) {
}
