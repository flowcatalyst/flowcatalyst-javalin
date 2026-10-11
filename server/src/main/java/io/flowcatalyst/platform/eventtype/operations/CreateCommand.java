package io.flowcatalyst.platform.eventtype.operations;

import tools.jackson.databind.JsonNode;

/// The input DTO for [CreateEventType]. The record's simple name is the audit
/// log's `operation` column, so it must stay `CreateCommand` (as in Go).
///
/// An event type has no client: event types are platform-wide
/// (`msg_event_types` has no client column). The wire bodies still accept a
/// `clientId` for compatibility and ignore it, so the command does not carry
/// one; `clientScoped` is what marks events of a type as carried per client
/// (spec §5, 2026-10-11 F2).
///
/// @param code         `application:subdomain:aggregate:event`
/// @param name         human-readable name
/// @param description  optional
/// @param schema       optional JSON Schema for the initial `1.0` spec version
/// @param clientScoped events of this type are carried per client (owner ruling 2026-09-06 #7; absent on the wire ⇒ `false`)
public record CreateCommand(String code, String name, String description, JsonNode schema, boolean clientScoped) {

    public CreateCommand(String code, String name, String description, JsonNode schema) {
        this(code, name, description, schema, false);
    }
}
