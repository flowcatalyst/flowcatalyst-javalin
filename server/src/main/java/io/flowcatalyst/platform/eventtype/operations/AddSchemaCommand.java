package io.flowcatalyst.platform.eventtype.operations;

import tools.jackson.databind.JsonNode;

/// The input DTO for [AddSchema] (audit `operation` = `AddSchemaCommand`).
///
/// `mimeType` and `schemaType` are optional (bug 3): `null`/blank means
/// `JSON_SCHEMA` and that type's default MIME type. `POST
/// /api/event-types/{id}/schemas` carries neither (its body is unchanged); the
/// SPA's `/bff` route passes both.
public record AddSchemaCommand(String eventTypeId, String version, JsonNode schema, String mimeType, String schemaType) {

    /// No MIME type or schema type: a JSON Schema with the default MIME type.
    public AddSchemaCommand(String eventTypeId, String version, JsonNode schema) {
        this(eventTypeId, version, schema, null, null);
    }
}
